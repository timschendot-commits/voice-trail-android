package engine

import (
	"encoding/json"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/JopenChen/dsh-go/pkg/goal"
	"github.com/JopenChen/dsh-go/pkg/session"
)

func TestReadScreenUsesUpstreamSessionAndGoal(t *testing.T) {
	r := New()
	id := r.Start("read_screen")
	if len(id) != 32 || !r.Event(id, "step_start") || r.Event(id, "step_start") {
		t.Fatal("could not start exactly one step")
	}
	if r.Dispatch(id, "read_nodes") != Allowed || r.Dispatch(id, "prepare_voice") != Denied {
		t.Fatal("wrong goal-to-tool binding")
	}
	if r.Event(id, "done") || !r.Event(id, "step_done") || !r.Event(id, "done") {
		t.Fatal("accepted incomplete step or failed to close verified task")
	}
	log := r.tasks[id].log
	g, ok := goal.FromLog(log)
	if !ok || g.Phase != goal.PhaseComplete || g.Description != "read_screen" {
		t.Fatalf("unexpected dsh-go goal projection: %+v", g)
	}
	if errs := log.Check(); len(errs) != 0 {
		t.Fatalf("unbalanced dsh-go session: %v", errs)
	}
	r.Close(id)
	r.Close(id)
	if r.Dispatch(id, "read_nodes") != Denied {
		t.Fatal("a closed session issued a permit")
	}
}

func TestOnePhotoGrantIsConsumedEvenUnderConcurrentCallbacks(t *testing.T) {
	r := New()
	id := r.Start("vision")
	defer r.Close(id)
	if r.Event(id, "approved") || r.Dispatch(id, "share_photo") != Ask {
		t.Fatal("photo sharing skipped explicit consent")
	}
	if r.Dispatch(id, "read_photo") != Denied || !r.Event(id, "approved") || r.Event(id, "approved") {
		t.Fatal("pending consent was rebound or replayed")
	}
	var granted int32
	var calls sync.WaitGroup
	for i := 0; i < 4; i++ {
		calls.Add(1)
		go func() {
			defer calls.Done()
			if r.Dispatch(id, "share_photo") == Allowed {
				atomic.AddInt32(&granted, 1)
			}
		}()
	}
	calls.Wait()
	if granted != 1 || !r.Event(id, "cancel") || r.Dispatch(id, "share_photo") != Denied {
		t.Fatal("a single spoken approval must grant exactly one dispatch")
	}
	var asks, yes, no int
	for _, e := range r.tasks[id].log.Events() {
		switch data := e.Data.(type) {
		case session.ApprovalRequestIDData:
			asks++
		case session.ApprovalDecidedData:
			if data.Allowed {
				yes++
			} else {
				no++
			}
		}
	}
	if asks != 2 || yes != 1 || no != 1 {
		t.Fatalf("unpaired approvals: asked=%d yes=%d no=%d", asks, yes, no)
	}
}

func TestManualHandoffAndPrivacy(t *testing.T) {
	r := New()
	const secret = "private-person-000000"
	if r.Start(secret) != "" {
		t.Fatal("accepted a free-text goal")
	}
	id := r.Start("manual_handoff")
	defer r.Close(id)
	if r.Event(id, secret) || r.Dispatch(id, secret) != Denied {
		t.Fatal("accepted a free-text event or tool")
	}
	if !r.Event(id, "approval_requested") || !r.Event(id, "approved") {
		t.Fatal("manual acknowledgement failed")
	}
	for _, tool := range []string{"pay", "enter_password", "enter_code", "uninstall", "change_settings", "delete_data", "send_message"} {
		if r.Dispatch(id, tool) != Handoff {
			t.Fatalf("%s should always require the actual user action", tool)
		}
	}
	if r.Event(id, "done") || !r.Event(id, "handoff") || r.Event(id, "approved") {
		t.Fatal("handoff incorrectly claimed automatic completion")
	}
	data, err := json.Marshal(r.tasks[id].log.Events())
	if err != nil || strings.Contains(string(data), secret) {
		t.Fatal("unexpected secret in event stream or unencodable audit")
	}
}

func TestInterruptedStepClosesLogAndInvalidatesApprovals(t *testing.T) {
	for _, outcome := range []string{"failed", "cancel", "handoff", "close"} {
		t.Run(outcome, func(t *testing.T) {
			r := New()
			id := r.Start("wechat_text")
			log := r.tasks[id].log
			if !r.Event(id, "step_start") || r.Dispatch(id, "review_recipient") != Ask {
				t.Fatal("missing recipient review")
			}
			if outcome == "close" {
				r.Close(id)
			} else if !r.Event(id, outcome) {
				t.Fatalf("could not record %s", outcome)
			}
			if r.Event(id, "approved") || r.Dispatch(id, "prepare_text") != Denied {
				t.Fatal("stale Android callback advanced the goal")
			}
			if errs := log.Check(); len(errs) > 0 {
				t.Fatalf("unbalanced upstream log: %v", errs)
			}
			r.Close(id)
		})
	}
}
