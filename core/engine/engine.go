// Package engine embeds dsh-go for fixed, offline Android accessibility tasks.
// The JNI boundary accepts product enums and opaque handles. Screen content,
// recording buffers, recognition transcripts and contact names stay out of it.
package engine

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"strconv"
	"sync"

	"github.com/JopenChen/dsh-go/pkg/brand"
	"github.com/JopenChen/dsh-go/pkg/goal"
	"github.com/JopenChen/dsh-go/pkg/session"
	"github.com/JopenChen/dsh-go/pkg/tools"
)

// Dispatch results are part of the Android ABI.
const (
	Denied = iota
	Allowed
	Ask
	Handoff
)

// Tool policy is specific to the current goal. Operations with irreversible
// effects are absent from the executable catalog and always go to the user.
var catalog = map[string]map[string]int{
	"read_screen":    {"read_nodes": Allowed},
	"read_code":      {"read_nodes": Allowed, "read_code": Allowed},
	"open_app":       {"read_nodes": Allowed, "launch_app": Allowed},
	"find_feature":   {"read_nodes": Allowed, "launch_app": Allowed, "find_label": Allowed, "open_contacts": Allowed},
	"wechat_text":    {"read_nodes": Allowed, "launch_app": Allowed, "find_label": Allowed, "open_contacts": Allowed, "open_chat": Allowed, "review_recipient": Ask, "prepare_text": Allowed},
	"wechat_voice":   {"read_nodes": Allowed, "launch_app": Allowed, "find_label": Allowed, "open_contacts": Allowed, "open_chat": Allowed, "review_recipient": Ask, "prepare_voice": Allowed},
	"alarm":          {"open_alarm": Allowed},
	"reminder":       {"open_reminder": Allowed},
	"vision":         {"capture_photo": Allowed, "read_photo": Allowed, "share_photo": Ask},
	"classify":       {"share_words": Ask},
	"manual_handoff": {},
}

func manualOnly(tool string) bool {
	switch tool {
	case "send_message", "enter_code", "enter_password", "pay", "transfer", "red_packet",
		"delete_data", "change_settings", "uninstall":
		return true
	}
	return false
}

type approvalRequest struct {
	id       brand.ApprovalRequestID
	tool     string
	approved bool
}

type task struct {
	kind       string
	log        *session.SessionLog
	nextStep   uint64
	callSeq    uint64
	stepOpen   bool
	terminal   bool
	pending    *approvalRequest
}

// Registry serializes event, dispatch and close, including concurrent UI and
// accessibility callbacks. Each session is memory-only and is discarded by Close.
type Registry struct {
	mu    sync.Mutex
	tasks map[string]*task
}

func New() *Registry {
	return &Registry{tasks: make(map[string]*task)}
}

func (r *Registry) Start(kind string) string {
	if _, ok := catalog[kind]; !ok {
		return ""
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.tasks) >= 32 {
		return ""
	}
	var random [16]byte
	if _, err := rand.Read(random[:]); err != nil {
		return ""
	}
	id := hex.EncodeToString(random[:])
	log := session.NewSessionLog(brand.NewSessionID(id))
	g := goal.NewGoal(id, kind, 1)
	t := &task{kind: kind, log: log, nextStep: 1}
	if !t.append(session.GoalChangeData{
		GoalID: g.ID, Description: g.Description, Phase: session.GoalPhase(g.Phase),
		MaxRounds: g.MaxRounds, Revision: g.Revision,
	}) || !t.append(session.TurnStartData{Turn: 0}) {
		return ""
	}
	r.tasks[id] = t
	return id
}

func (t *task) append(e session.EventData) bool {
	_, err := t.log.Append(e)
	return err == nil
}

func (t *task) beginStep() bool {
	if t.stepOpen || t.nextStep > 128 || !t.append(session.StepStartData{Turn: 0, Step: t.nextStep}) {
		return false
	}
	t.stepOpen = true
	return true
}

func (t *task) endStep() bool {
	if !t.stepOpen || !t.append(session.StepEndData{Turn: 0, Step: t.nextStep}) {
		return false
	}
	t.stepOpen = false
	t.nextStep++
	return true
}

func (t *task) request(tool string) bool {
	if t.pending != nil {
		return t.pending.tool == tool
	}
	id := brand.NewApprovalRequestID("approval-" + strconv.FormatUint(t.callSeq, 10))
	if !t.append(session.ApprovalRequestIDData{RequestID: id, Tool: tool}) {
		return false
	}
	t.pending = &approvalRequest{id: id, tool: tool}
	return true
}

func (t *task) decide(yes bool) bool {
	if t.pending == nil || t.pending.approved {
		return false
	}
	if !t.append(session.ApprovalDecidedData{RequestID: t.pending.id, Allowed: yes}) {
		return false
	}
	t.pending.approved = yes
	// A standalone confirmation only acknowledges an Android handoff; it has
	// no matching device capability and cannot grant a different tool.
	if !yes || t.pending.tool == "handoff_review" {
		t.pending = nil
	}
	return true
}

func (r *Registry) Event(id, event string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	t := r.tasks[id]
	if t == nil || t.terminal {
		return false
	}
	switch event {
	case "step_start":
		return t.pending == nil && t.beginStep()
	case "step_done":
		return t.pending == nil && t.endStep()
	case "approval_requested":
		if t.pending != nil {
			return !t.pending.approved
		}
		t.callSeq++
		return t.request("handoff_review")
	case "approved":
		return t.decide(true)
	case "declined":
		return t.decide(false)
	case "done":
		if t.stepOpen || t.pending != nil || t.kind == "manual_handoff" {
			return false
		}
		return t.finish(goal.PhaseComplete, session.ReasonCompleted)
	case "cancel":
		return t.finish(goal.PhasePaused, session.ReasonInterrupted)
	case "failed":
		return t.finish(goal.PhaseBlocked, session.ReasonError)
	case "handoff":
		return t.finish(goal.PhasePaused, session.ReasonBlocked)
	default:
		return false
	}
}

func (t *task) finish(phase goal.Phase, reason session.TurnEndReason) bool {
	if t.pending != nil && !t.pending.approved && !t.decide(false) {
		return false
	}
	t.pending = nil
	if t.stepOpen && !t.endStep() {
		return false
	}
	for _, op := range goal.NewGoalToolset(t.log).Tools() {
		if op.Name == "goal_set_phase" {
			_, err := op.Execute(context.Background(), map[string]any{"phase": string(phase)})
			if err != nil || !t.append(session.TurnEndData{Turn: 0, Reason: reason}) {
				return false
			}
			t.terminal = true
			return true
		}
	}
	return false
}

func (r *Registry) Close(id string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if t := r.tasks[id]; t != nil && !t.terminal {
		t.finish(goal.PhasePaused, session.ReasonInterrupted)
	}
	delete(r.tasks, id)
}

// Dispatch authorizes one immediate platform call. The dsh-go execute stage
// issues a permit; Android performs the action and observes its outcome. An
// allowed dispatch is never the evidence that a UI action succeeded.
func (r *Registry) Dispatch(id, name string) int {
	r.mu.Lock()
	defer r.mu.Unlock()
	t := r.tasks[id]
	if t == nil || t.terminal {
		return Denied
	}
	if manualOnly(name) {
		return Handoff
	}
	policy, known := catalog[t.kind][name]
	if !known || (t.pending != nil && t.pending.tool != name) || t.callSeq >= 256 {
		return Denied
	}
	t.callSeq++
	callID := brand.NewToolCallID("call-" + strconv.FormatUint(t.callSeq, 10))
	status := Denied
	check := func(*tools.ToolCallRequest) (tools.PreToolDecision, error) {
		switch policy {
		case Allowed:
			return tools.PreAllow, nil
		case Ask:
			return tools.PreAsk, nil
		default:
			return tools.PreDeny, nil
		}
	}
	confirm := func(*tools.ToolCallRequest) (bool, error) {
		if t.pending != nil && t.pending.tool == name && t.pending.approved {
			t.pending = nil
			return true, nil
		}
		if t.request(name) {
			status = Ask
		}
		return false, nil
	}
	permit := &tools.Tool{Name: name, Execute: func(context.Context, map[string]any) (any, error) {
		return "permit_issued", nil
	}}
	request := &tools.ToolCallRequest{CallID: callID, Tool: name}
	pipeline := tools.NewPipeline().UsePre(tools.PreDecisionMiddleware(check, confirm)).WithTool(permit)
	ownStep := !t.stepOpen
	if ownStep && !t.beginStep() {
		return Denied
	}
	if !t.append(session.ToolCallData{CallID: callID, Tool: name}) {
		return Denied
	}
	result := pipeline.Run(context.Background(), request, permit)
	outcome := "permit_withheld"
	if !result.IsError && result.Value == "permit_issued" {
		status, outcome = Allowed, "permit_issued"
	}
	if !t.append(session.ToolResultData{CallID: callID, IsError: status != Allowed, Output: outcome}) {
		return Denied
	}
	if ownStep && !t.endStep() {
		return Denied
	}
	return status
}
