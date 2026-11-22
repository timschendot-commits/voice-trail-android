"""Schedule one reminder through the released UI and observe its eventual OS notification."""
import argparse
import json
import re
import runpy
import shlex
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path


ui = runpy.run_path(str(Path(__file__).with_name("emulator-smoke.py")))
APP = ui["APP"]

def device_time(d):
    return datetime.strptime(d.adb("shell", "date", "+%Y%m%d%H%M%S%z"), "%Y%m%d%H%M%S%z")


def duration_seconds(raw):
    if raw == "0":
        return 0.0
    units = {"d":86400, "h":3600, "m":60, "s":1, "ms":.001}
    parts = re.findall(r"(\d+)(ms|d|h|m|s)", raw.lstrip("+-"))
    if not parts or "".join(n + unit for n, unit in parts) != raw.lstrip("+-"):
        return None
    return (-1 if raw.startswith("-") else 1) * sum(int(n) * units[u] for n, u in parts)


def alarm_snapshot(d):
    lines = d.adb("shell", "dumpsys", "alarm").splitlines()
    result = {"device_time": device_time(d).isoformat(), "pending": [], "app_history": []}
    for i, line in enumerate(lines):
        if APP not in line:
            continue
        # Android Alarm.dump() prints the package in an alarm header or tag,
        # followed by origWhen, policyWhenElapsed, whenElapsed and maxWhenElapsed.
        result["app_history"].append(line.strip()[:320])
        nearby = lines[i + 1:i + 10]
        start = next((j for j, s in enumerate(nearby[:4]) if " origWhen=" in s), None)
        if start is None:
            continue
        fields = []
        for s in nearby[start:]:
            if not s.strip().startswith(("type=", "policyWhenElapsed:", "whenElapsed=", "operation=")):
                break
            fields.append(s.strip())
        if fields and fields not in result["pending"]:
            result["pending"].append(fields)
    result["app_history"] = result["app_history"][:20]
    return result


def record_values(d, key):
    record = d.adb("shell", "cmd", "notification", "get", shlex.quote(key))
    primary = record.split("publicNotification=", 1)[0]
    def extra(name):
        m = re.search(r"(?m)^\s*android\." + name + r"=\w+ \((.*)\)\s*$", primary)
        return m[1] if m else None
    channel = re.search(r"effectiveNotificationChannel=NotificationChannel\{mId='([^']+)'", record)
    if not channel:
        channel = re.search(r"\bchannel(?:Id)?=['\"]?([\w.-]+)", primary)
    return {"key": key, "title": extra("title"), "body": extra("text"),
            "channel": channel[1] if channel else None,
            "fields": [s.strip()[:450] for s in record.splitlines() if
                       "android.title=" in s or "android.text=" in s or
                       "effectiveNotificationChannel=" in s or s.startswith("NotificationRecord(")][:8]}


def run(d, report, max_wait):
    if not re.fullmatch(r"emulator-\d+", d.serial) or not any(
            d.adb("shell", "getprop", key) == "1" for key in ("ro.kernel.qemu", "ro.boot.qemu")):
        raise RuntimeError("A disposable Android emulator is required")
    d.adb("shell", "pm", "grant", APP, "android.permission.POST_NOTIFICATIONS")
    d.adb("shell", "am", "force-stop", APP)
    ui["launcher"](d)
    if ui["button"](d.last, "Speak") is None:
        d.click("更多"); d.click("语言和显示模式"); d.click("English")
    d.click("Type a command")

    def editor(tree):
        return next((n for n in tree.iter("node") if n.get("package") == APP and
                     n.get("class") == "android.widget.EditText" and ui["bounds"](n)), None)

    tree = d.wait(lambda t: editor(t) is not None, "typed command entry")
    d.tap_node(editor(tree))
    # Use the emulator's clock including its UTC offset. Leave a minimum of
    # 60 seconds for the normal read-back before Android receives the alarm.
    now = device_time(d)
    due = now.replace(second=0, microsecond=0) + timedelta(minutes=2)
    label = "check the emulator timer"
    clock = due.strftime("%I:%M %p").lower()
    command = f"remind me at {clock} to {label}"
    before = set(d.adb("shell", "cmd", "notification", "list").splitlines())
    report.update(command=command, emulator_sample_at=now.isoformat(), target_at=due.isoformat())
    d.adb("shell", "input", "text", command.replace(" ", "%s"))
    d.wait(lambda t: editor(t) is not None and editor(t).get("text") == command, "exact typed reminder")
    d.click("Run")
    prefix = "One local reminder is scheduled."
    tree = d.wait(lambda t: ui["message"](t, prefix, ui["SPEECH_ERROR"], "Notifications are off."),
                  "reminder accepted or explicit recovery", seconds=45)
    report["screen_result"] = ui["message"](tree, prefix, ui["SPEECH_ERROR"], "Notifications are off.")
    if not report["screen_result"].startswith(prefix):
        raise RuntimeError(report["screen_result"])
    d.evidence("reminder-scheduled")
    d.adb("shell", "input", "keyevent", "KEYCODE_HOME")
    start = time.monotonic()
    initial = alarm_snapshot(d)
    report["alarm_snapshots"] = [initial]
    report["own_notifications"] = {}
    remaining = (due - device_time(d)).total_seconds()
    # Respect Android's actual inexact delivery window, plus a small allowance;
    # if Android does not expose it, use the supplied ceiling.
    latest = [duration_seconds(m[1]) for block in initial["pending"] for s in block
              if (m := re.search(r"\bmaxWhenElapsed=(\S+)", s))]
    known = [v for v in latest if v is not None and v >= 0]
    budget = min(max_wait, max(remaining, *known) + 75) if known else max_wait
    report["wait_budget_seconds"] = round(budget, 2)
    print("ALARM_WINDOW " + json.dumps(initial), flush=True)
    until = start + budget
    next_observation = start
    while time.monotonic() < until:
        keys = [k for k in d.adb("shell", "cmd", "notification", "list").splitlines() if f"|{APP}|" in k]
        for key in keys:
            if key in before and f"|{APP}|1|" in key:
                continue
            if key not in report["own_notifications"]:
                entry = record_values(d, key)
                report["own_notifications"][key] = entry
                print("OWN_NOTIFICATION " + json.dumps(entry), flush=True)
            entry = report["own_notifications"][key]
            if f"|{APP}|1|voicetrail://reminder/" not in key:
                continue
            if (entry["title"], entry["body"], entry["channel"]) != ("Voice Trail reminder", label, "reminders"):
                # Re-read if NotificationManager has only just posted a partial record.
                report["own_notifications"][key] = record_values(d, key)
                continue
            observed = device_time(d)
            delay = int((observed - due).total_seconds())
            if delay < 0:
                raise RuntimeError("OS notification observed before scheduled minute")
            report.update(notification_id=1, channel="reminders", title="Voice Trail reminder",
                          body=label, observed_at=observed.isoformat(), first_observed_after_due_seconds=delay)
            report["alarm_snapshots"].append(alarm_snapshot(d))
            return
        if time.monotonic() >= next_observation:
            snapshot = alarm_snapshot(d)
            report["alarm_snapshots"].append(snapshot)
            print("ALARM_POLL " + json.dumps(snapshot), flush=True)
            next_observation = time.monotonic() + 60
        time.sleep(2)
    report["alarm_snapshots"].append(alarm_snapshot(d))
    report["end_device_time"] = device_time(d).isoformat()
    raise RuntimeError("The real reminder notification was not observed within the bounded test interval")


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--out", type=Path, required=True)
    args.add_argument("--serial", default="emulator-5554")
    args.add_argument("--max-wait", type=int, default=900,
                      help="Hard ceiling in seconds after scheduling, default 15 minutes")
    options = args.parse_args()
    d = ui["Device"](options.serial, options.out)
    report = {"case": "local reminder delivery while launcher is foreground"}
    try:
        run(d, report, options.max_wait)
        report["status"] = "passed"
    except Exception as exc:
        report.update(status="failed", reason=str(exc)[:500], evidence=d.diagnostics())
    result = json.dumps(report, ensure_ascii=False, indent=2)
    (options.out / "reminder.json").write_text(result + "\n", encoding="utf-8")
    print(result, flush=True)
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
