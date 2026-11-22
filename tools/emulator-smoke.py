#!/usr/bin/env python3
"""Drive the installed Voice Trail APK through Android's public launcher and UI.

Only a disposable emulator is accepted. No instrumentation or test hooks are used.
Each case records what the screen actually showed, including environmental blocks.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

APP = "io.github.voicetrail"
REMOTE_DUMP = "/data/local/tmp/voice-trail-ui.xml"
SPEECH_ERROR = "Speech paused. Install an English offline voice"
SERVICE_OFF = "Screen access is off."
CAMERA_RESULT = ("I found ", "Possible categories in the full picture:",
                 "I couldn't identify a clear category in the full picture",
                 "This photo may contain a code or password")
CAMERA_ERROR = ("Couldn't open the back camera", "Camera permission is needed",
                "Capture failed.", "Text recognition failed", "Image description failed")


def bounds(node):
    values = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if values:
        x, y, right, bottom = map(int, values.groups())
        if right > x and bottom > y:
            return x, y, right, bottom
    return None


def button(tree, label):
    return next((n for n in tree.iter("node") if n.get("package") == APP
                 and n.get("enabled") == "true" and n.get("clickable") == "true"
                 and label in (n.get("content-desc"), n.get("text")) and bounds(n)), None)


def message(tree, *prefixes):
    for n in tree.iter("node"):
        content = n.get("text", "")
        if n.get("package") == APP and any(content.startswith(p) for p in prefixes):
            return content
    return None


class Device:
    def __init__(self, serial, directory):
        self.serial = serial
        self.out = directory
        self.out.mkdir(parents=True, exist_ok=True)
        self.last = ET.Element("hierarchy")

    def adb(self, *args, timeout=20):
        result = subprocess.run(["adb", "-s", self.serial, *map(str, args)], timeout=timeout,
                                capture_output=True, encoding="utf-8", errors="replace")
        if result.returncode:
            raise RuntimeError(f"adb {args[0]} failed ({result.returncode}): {result.stderr.strip()[:160]}")
        return result.stdout.strip()

    def ui(self):
        self.adb("shell", "rm", "-f", REMOTE_DUMP)
        self.adb("shell", "uiautomator", "dump", REMOTE_DUMP, timeout=12)
        self.last = ET.fromstring(self.adb("exec-out", "cat", REMOTE_DUMP))
        return self.last

    def wait(self, predicate, description, seconds=25):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            try:
                tree = self.ui()
            except (ET.ParseError, RuntimeError, subprocess.TimeoutExpired):
                time.sleep(0.25)
                continue
            if predicate(tree):
                return tree
            time.sleep(0.25)
        raise AssertionError(f"Timed out waiting for {description}")

    def tap_node(self, node):
        x, y, right, bottom = bounds(node)
        self.adb("shell", "input", "tap", (x + right) // 2, (y + bottom) // 2)

    def click(self, label):
        # Root is scrolled to the top after each in-app page navigation. If a large
        # font has pushed a button below the fold, scroll forward until it appears.
        for attempt in range(5):
            tree = self.wait(lambda t: any(n.get("package") == APP for n in t.iter("node")),
                             "Voice Trail controls")
            n = button(tree, label)
            if n is not None:
                self.tap_node(n)
                return
            scroll = next((bounds(q) for q in tree.iter("node") if q.get("package") == APP
                           and q.get("scrollable") == "true" and bounds(q)), None)
            if not scroll or attempt == 4:
                break
            x, y, right, bottom = scroll
            center = (x + right) // 2
            self.adb("shell", "input", "swipe", center, y + (bottom-y)*4//5,
                     center, y + (bottom-y)//5, 350)
        raise AssertionError(f"Accessible button unavailable: {label}")

    def evidence(self, name):
        # Flatten just the app's accessible labels and roles. Do not archive system
        # UI, device keyboard contents, or unrelated packages' dynamic information.
        panel = ET.Element("app-screen", package=APP)
        for n in self.last.iter("node"):
            if n.get("package") == APP:
                ET.SubElement(panel, "control", {k: n.get(k, "") for k in
                              ("class", "text", "content-desc", "enabled", "bounds")})
        ET.ElementTree(panel).write(self.out / f"{name}.xml", encoding="utf-8", xml_declaration=True)


def launcher(d):
    result = d.adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
                   "-c", "android.intent.category.LAUNCHER", "-p", APP)
    if "Error:" in result or "Exception" in result:
        raise AssertionError("Android could not launch the installed APK")
    tree = d.wait(lambda t: button(t, "Speak") is not None or button(t, "说句话") is not None, "home")
    d.evidence("01-launch")
    return "passed", "English home" if button(tree, "Speak") is not None else "Chinese home"


def languages(d):
    english = button(d.last, "Speak") is not None
    if not english:
        d.click("更多")
        d.click("语言和显示模式")
        d.click("English")
        d.wait(lambda t: button(t, "Speak") is not None, "English home")
    d.click("More")
    d.click("Language and display")
    d.click("简体中文")
    d.wait(lambda t: button(t, "说句话") is not None and button(t, "看一看") is not None, "Chinese home")
    d.evidence("02-chinese")
    d.click("更多")
    d.click("语言和显示模式")
    d.click("English")
    d.wait(lambda t: button(t, "Speak") is not None and button(t, "Look around") is not None, "English home")
    d.evidence("03-english")
    return "passed", "Saw 说句话 and 看一看, followed by Speak and Look around"


def typed(d):
    d.click("Type a command")
    editor = lambda t: next((n for n in t.iter("node") if n.get("package") == APP
                             and n.get("class") == "android.widget.EditText" and bounds(n)), None)
    tree = d.wait(lambda t: editor(t) is not None, "command editor")
    d.tap_node(editor(tree))
    d.adb("shell", "input", "text", "read%sthe%sscreen")
    d.wait(lambda t: editor(t) is not None and editor(t).get("text") == "read the screen",
           "typed words")
    d.evidence("04-typed")
    # The editor and Run are above the keyboard; never toggle a guessed IME state.
    d.click("Run")
    tree = d.wait(lambda t: message(t, SPEECH_ERROR, SERVICE_OFF),
                  "recoverable result of read command on a clean device", seconds=50)
    output = message(tree, SPEECH_ERROR, SERVICE_OFF)
    d.evidence("05-read-outcome")
    return "recovery_observed", output


def camera(d):
    # Re-launch so a stale speech error from the preceding read cannot be taken
    # as the camera result. Force-stop leaves language and permission settings intact.
    d.adb("shell", "am", "force-stop", APP)
    launcher(d)
    d.click("Look around")
    def permission(t):
        return next((n for n in t.iter("node") if
                     n.get("resource-id", "").endswith(":id/permission_allow_foreground_only_button")
                     and "permissioncontroller" in n.get("package", "") and bounds(n)), None)
    tree = d.wait(lambda t: message(t, SPEECH_ERROR) or permission(t) is not None
                  or button(t, "Take photo and describe") is not None, "camera entry or speech recovery", seconds=50)
    error = message(tree, SPEECH_ERROR)
    if error:
        d.evidence("06-look-blocked")
        return "blocked", error
    if permission(tree) is not None:
        d.tap_node(permission(tree))
    tree = d.wait(lambda t: button(t, "Take photo and describe") is not None or
                  message(t, *CAMERA_RESULT, *CAMERA_ERROR), "camera controls")
    d.evidence("06-look")
    # Work through the normal capture action. If automatic capture already finished,
    # keep its result. Otherwise the real button is the supported manual path.
    if not message(tree, *CAMERA_RESULT, *CAMERA_ERROR):
        d.click("Take photo and describe")
    tree = d.wait(lambda t: message(t, *CAMERA_RESULT, *CAMERA_ERROR),
                  "photo capture and local analysis", seconds=65)
    result = message(tree, *CAMERA_RESULT, *CAMERA_ERROR)
    d.evidence("07-photo-outcome")
    return ("blocked" if message(tree, *CAMERA_ERROR) else "passed"), result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--apk", type=Path, help="Install this APK and record its checksum")
    parser.add_argument("--require-camera", action="store_true", help="Treat a camera environment block as a failure")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Choose a disposable Android emulator serial")
    d = Device(args.serial, args.out)
    report = {"device": args.serial, "cases": []}
    failures = []
    try:
        prop = lambda k: d.adb("shell", "getprop", k)
        if prop("ro.kernel.qemu") != "1" and prop("ro.boot.qemu") != "1":
            raise RuntimeError("Emulator property missing, stopping before device changes")
        report.update(api=prop("ro.build.version.sdk"), abi=prop("ro.product.cpu.abi"))
        if args.apk:
            report["apk_sha256"] = hashlib.sha256(args.apk.read_bytes()).hexdigest()
            if "Success" not in d.adb("install", "-r", str(args.apk.resolve()), timeout=100):
                raise RuntimeError("Release APK installation failed")
        for case in (launcher, languages, typed, camera):
            try:
                status, observed = case(d)
            except (AssertionError, RuntimeError, ET.ParseError, subprocess.TimeoutExpired) as error:
                status, observed = "failed", str(error)
                d.evidence(f"{case.__name__}-failure")
            report["cases"].append({"case": case.__name__, "status": status, "observed": observed})
            if status == "failed" or (case == camera and status == "blocked" and args.require_camera):
                failures.append(case.__name__)
            if case in (launcher, languages) and status == "failed":
                break
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        report["setup_error"] = str(error)
        failures.append("setup")
    report["failures"] = failures
    result = json.dumps(report, ensure_ascii=False, indent=2)
    (args.out / "smoke.json").write_text(result + "\n", encoding="utf-8")
    print(result, flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
