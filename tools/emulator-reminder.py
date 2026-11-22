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


def run(d, report):
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
    now = datetime.strptime(d.adb("shell", "date", "+%Y%m%d%H%M%S%z"), "%Y%m%d%H%M%S%z")
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
    until = time.monotonic() + 185
    while time.monotonic() < until:
        new = set(d.adb("shell", "cmd", "notification", "list").splitlines()) - before
        for key in new:
            if f"|{APP}|1|voicetrail://reminder/" not in key:
                continue
            record = d.adb("shell", "cmd", "notification", "get", shlex.quote(key))
            if "Voice Trail reminder" not in record or label not in record or "channel=reminders" not in record:
                continue
            observed = datetime.strptime(d.adb("shell", "date", "+%Y%m%d%H%M%S%z"), "%Y%m%d%H%M%S%z")
            delay = int((observed - due).total_seconds())
            if delay < 0:
                raise RuntimeError("OS notification observed before scheduled minute")
            report.update(notification_id=1, channel="reminders", title="Voice Trail reminder",
                          body=label, observed_at=observed.isoformat(), first_observed_after_due_seconds=delay)
            return
        time.sleep(1.25)
    raise RuntimeError("The real reminder notification was not observed within the bounded test interval")


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--out", type=Path, required=True)
    args.add_argument("--serial", default="emulator-5554")
    options = args.parse_args()
    d = ui["Device"](options.serial, options.out)
    report = {"case": "local reminder delivery while launcher is foreground"}
    try:
        run(d, report)
        report["status"] = "passed"
    except Exception as exc:
        report.update(status="failed", reason=str(exc)[:500], evidence=d.diagnostics())
    result = json.dumps(report, ensure_ascii=False, indent=2)
    (options.out / "reminder.json").write_text(result + "\n", encoding="utf-8")
    print(result, flush=True)
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
