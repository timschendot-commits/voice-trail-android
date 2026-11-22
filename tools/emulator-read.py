"""Exercise the installed Voice Trail APK against a stock Android Settings screen."""
import argparse
import json
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path


PKG = "io.github.voicetrail"
SERVICE = PKG + "/.PhoneService"
BOUNDS = r"\[(-?\d+),\s*(-?\d+)\]\[(-?\d+),\s*(-?\d+)\]"


class NoOfflineVoice(Exception):
    pass


class Device:
    def __init__(self, serial):
        self.adb = ["adb", "-s", serial]

    def shell(self, *words):
        proc = subprocess.run(self.adb + ["shell", shlex.join(words)], capture_output=True,
                              text=True, errors="replace", timeout=20)
        if proc.returncode:
            raise RuntimeError((proc.stderr or proc.stdout).strip()[:220])
        return proc.stdout.strip()

    def launch(self):
        self.shell("am", "start", "-W", "-a", "android.intent.action.MAIN",
                   "-c", "android.intent.category.LAUNCHER", "-n", PKG + "/.LauncherActivity")

    def ui(self):
        path = "/data/local/tmp/voice-trail-read-test.xml"
        for _ in range(3):
            self.shell("rm", "-f", path)
            self.shell("uiautomator", "dump", path)
            if self.shell("ls", "/data/local/tmp").splitlines().count("voice-trail-read-test.xml"):
                data = self.shell("cat", path)
                if data.startswith("<?xml"):
                    return ET.fromstring(data)
        raise RuntimeError("UI hierarchy unavailable")

    def foreground(self):
        data = self.shell("dumpsys", "activity", "activities")
        for line in data.splitlines():
            if "topResumedActivity=" in line or "mResumedActivity:" in line:
                component = re.search(r"\bu\d+ ([\w.]+)/", line)
                if component:
                    return component[1]
        return ""

    def tap(self, rect):
        x1, y1, x2, y2 = rect
        self.shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def overlay(self):
        # The overlay's actual input window stays exposed to dumpsys without
        # connecting another accessibility service during the read operation.
        data = self.shell("dumpsys", "input")
        for line in data.splitlines():
            if "Voice Trail" in line and "frame=" in line and "NOT_TOUCHABLE" not in line:
                match = re.search(r"frame=\[(-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\]", line)
                if match:
                    rect = tuple(map(int, match.groups()))
                    if rect[2] > rect[0] and rect[3] > rect[1]:
                        return rect
        data = self.shell("dumpsys", "window", "windows")
        for block in re.split(r"(?m)^\s*Window #\d+", data)[1:]:
            head = block.splitlines()[0]
            if " Voice Trail}" not in head or "isOnScreen=false" in block:
                continue
            match = re.search(r"\bframe=" + BOUNDS, block)
            if match:
                rect = tuple(map(int, match.groups()))
                if rect[2] > rect[0] and rect[3] > rect[1]:
                    return rect
        return None

    def notification(self):
        for key in self.shell("cmd", "notification", "list").splitlines():
            if f"|{PKG}|30|" in key:
                data = self.shell("cmd", "notification", "get", key)
                for message in ("Task complete.", "Task did not finish.", "Task in progress."):
                    if message in data:
                        return message
        return ""

    def restore(self, key, value):
        if value == "null":
            self.shell("settings", "delete", "secure", key)
        else:
            self.shell("settings", "put", "secure", key, value)


def button(tree, label):
    for n in tree.iter("node"):
        if n.get("package") != PKG or n.get("clickable") != "true":
            continue
        if label not in (n.get("text"), n.get("content-desc")):
            continue
        bounds = re.fullmatch(BOUNDS, n.get("bounds", ""))
        if bounds:
            return tuple(map(int, bounds.groups()))
    raise RuntimeError(f"Visible button {label!r} missing")


def await_value(get, accepted, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = get()
        if accepted(value):
            return value
        time.sleep(.4)
    return None


def app_text(tree):
    return [n.get("text", "") for n in tree.iter("node")
            if n.get("package") == PKG and n.get("class") == "android.widget.TextView" and n.get("text")]


def run(device, report):
    if (device.shell("getprop", "ro.boot.qemu") != "1" and
            device.shell("getprop", "ro.kernel.qemu") != "1"):
        raise RuntimeError("A disposable emulator is required")
    if device.shell("getprop", "ro.hardware") not in ("ranchu", "goldfish"):
        raise RuntimeError("Unexpected emulator hardware")
    device.shell("am", "force-stop", PKG)
    device.shell("pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    device.shell("pm", "grant", PKG, "android.permission.RECORD_AUDIO")
    device.launch()
    ui = device.ui()
    if any(n.get("text") == "读当前屏幕" for n in ui.iter("node")):
        device.tap(button(ui, "更多"))
        device.tap(button(device.ui(), "语言和显示模式"))
        device.tap(button(device.ui(), "English"))
        ui = device.ui()
    read_button = button(ui, "Read the screen")

    keys = ("enabled_accessibility_services", "accessibility_enabled")
    old = [device.shell("settings", "get", "secure", k) for k in keys]
    try:
        current = [] if old[0] in ("", "null") else old[0].split(":")
        if SERVICE not in current and PKG + "/" + PKG + ".PhoneService" not in current:
            current.append(SERVICE)
        device.restore(keys[0], ":".join(current))
        device.restore(keys[1], "1")
        device.shell("am", "start", "-W", "-a", "android.settings.DATE_SETTINGS")
        if not await_value(device.foreground, lambda p: p == "com.android.settings", 15):
            raise RuntimeError("Android Settings did not appear")
        source = device.ui()
        labels = list(dict.fromkeys(n.get("text", "") for n in source.iter("node")
                                    if n.get("package") == "com.android.settings" and
                                    n.get("class") == "android.widget.TextView" and
                                    re.search(r"[A-Za-z]{3}", n.get("text", ""))))
        if len(labels) < 2:
            raise RuntimeError("Insufficient readable Settings labels for comparison")
        report["source_labels"] = labels
        if device.notification():
            raise RuntimeError("A stale app notification survived the clean start")

        overlay = await_value(device.overlay, bool, 8)
        if overlay:
            device.tap(overlay)
        if overlay and await_value(device.foreground, lambda p: p == PKG, 5):
            report["entry"] = "accessibility overlay, then Read the screen button"
        else:
            device.launch()
            report["entry"] = "public launcher, then Read the screen button"
        # The button bounds were observed in the same language and orientation.
        # UiAutomation's shell dump can disconnect live accessibility services:
        # avoid asking it for a hierarchy while this operation is in progress.
        time.sleep(.6)
        device.tap(read_button)
        if not await_value(device.foreground, lambda p: p == "com.android.settings", 35):
            observed = app_text(device.ui())
            report["visible_status"] = observed
            if any("offline voice" in s.lower() for s in observed):
                raise NoOfflineVoice("The app reported that it needs an installed English offline voice")
            raise RuntimeError("Read did not return to the external app")
        report["returned_to"] = "com.android.settings"
        state = await_value(device.notification, lambda s: s in ("Task complete.", "Task did not finish."), 95)
        report["task_notification"] = state
        if state != "Task complete.":
            raise RuntimeError("Missing successful completion notification for the current task")
        if device.foreground() != "com.android.settings":
            raise RuntimeError("Foreground app changed during read")
        device.launch()
        ui = device.ui()
        readbacks = app_text(ui)
        if not any(sum(label in reply for label in labels) >= 2 for reply in readbacks):
            device.tap(button(ui, "Repeat"))
            readbacks = app_text(device.ui())
            report["result_control"] = "Repeat"
        else:
            report["result_control"] = "visible task result"
        best = max(readbacks, key=lambda s: sum(label in s for label in labels), default="")
        matched = [label for label in labels if label in best]
        report["readback_text"] = best
        report["matched_source_labels"] = matched
        if len(matched) < 2:
            raise RuntimeError("The assistant did not repeat two observed labels from the original Settings page")
    finally:
        for key, value in zip(keys, old):
            device.restore(key, value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    report = {"case": "published APK cross-app read", "method": "real Android views, app TTS and JNI; no microphone assertion"}
    try:
        run(Device(args.serial), report)
        report["status"], code = "passed", 0
    except NoOfflineVoice as exc:
        report.update(status="environment_blocked", error=str(exc))
        code = 3
    except (RuntimeError, OSError, subprocess.SubprocessError, ET.ParseError) as exc:
        report.update(status="failed", error=str(exc))
        code = 1
    text = json.dumps(report, ensure_ascii=False, indent=2)
    (args.out / "read.json").write_text(text + "\n", encoding="utf-8")
    print(text, flush=True)
    return code


if __name__ == "__main__":
    sys.exit(main())
