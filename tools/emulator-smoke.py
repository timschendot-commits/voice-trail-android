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
MIC_RESPONSES = ("English offline recognition is unavailable.", "Allow microphone access",
                 "Microphone permission is off.", "I didn't catch that.", "The microphone is busy.",
                 "Offline recognition stopped.", SPEECH_ERROR)
HANDOFF = "Paused. Complete this action in the original app using TalkBack."
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


def is_launcher(package):
    return "launcher" in package or package == "com.google.android.apps.nexuslauncher"


def visible_context(tree):
    labels, classes, packages = [], [], []
    for n in tree.iter("node"):
        pkg = n.get("package", "")
        if pkg and pkg not in packages:
            packages.append(pkg)
        if n.get("class") and n.get("class") not in classes:
            classes.append(n.get("class"))
        label = n.get("text") or n.get("content-desc")
        if label and label not in labels:
            labels.append(label[:120])
    return {"packages": packages[:12], "classes": classes[:12], "labels": labels[:12]}


class Device:
    def __init__(self, serial, directory):
        self.serial = serial
        self.out = directory
        self.out.mkdir(parents=True, exist_ok=True)
        self.last = ET.Element("hierarchy")
        self.dismissed = []
        self.launches = []
        self.last_ui_error = None

    def adb(self, *args, timeout=20):
        result = subprocess.run(["adb", "-s", self.serial, *map(str, args)], timeout=timeout,
                                capture_output=True, encoding="utf-8", errors="replace")
        if result.returncode:
            details = (result.stderr + "\n" + result.stdout).strip()
            raise RuntimeError(f"adb {' '.join(map(str,args[:6]))} failed ({result.returncode}): {details[:480]}")
        return result.stdout.strip()

    def ui(self):
        self.adb("shell", "rm", "-f", REMOTE_DUMP)
        self.adb("shell", "uiautomator", "dump", REMOTE_DUMP, timeout=12)
        self.last = ET.fromstring(self.adb("exec-out", "cat", REMOTE_DUMP))
        return self.last

    def diagnostics(self):
        result = {"ui": visible_context(self.last), "last_ui_error": self.last_ui_error}
        for service, markers in (("activity", ("mResumedActivity", "topResumedActivity", "ResumedActivity:")),
                                 ("window", ("mCurrentFocus", "mFocusedApp", "mShowingLockscreen", "mDreamingLockscreen"))):
            try:
                args = ("activities",) if service == "activity" else ("windows",)
                dump = self.adb("shell", "dumpsys", service, *args, timeout=8)
                result[service] = [x.strip()[:240] for x in dump.splitlines()
                                   if any(m in x for m in markers)][:8]
                if service == "window" and not result[service]:
                    dump = self.adb("shell", "dumpsys", "window", timeout=10)
                    result[service] = [x.strip()[:240] for x in dump.splitlines()
                                       if any(m in x for m in markers)][:8]
            except (RuntimeError, subprocess.TimeoutExpired) as err:
                result[service] = str(err)[:240]
        return result

    def dismiss_known_notice(self, tree):
        nodes = [n for n in tree.iter("node") if bounds(n)]
        system = lambda n: n.get("package") in {"android", "com.android.systemui"}
        notice = next((n for n in nodes if system(n) and
                       any(x in n.get("text", "").lower() for x in
                           ("built for an older version", "isn't optimized for", "full screen mode"))), None)
        if notice is not None:
            ok = next((n for n in nodes if system(n) and n.get("text", "").lower() in ("ok", "got it")
                       and n.get("clickable") == "true"), None)
            if ok is not None:
                self.dismissed.append(notice.get("text", "")[:180])
                self.tap_node(ok)
                return True
        for n in nodes:
            if is_launcher(n.get("package", "")) and n.get("text", "").lower() == "got it":
                self.dismissed.append("Launcher onboarding: " + str(visible_context(tree)["labels"][:4]))
                self.tap_node(n)
                return True
        return False

    def wait(self, predicate, description, seconds=25):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            try:
                tree = self.ui()
            except (ET.ParseError, RuntimeError, subprocess.TimeoutExpired) as err:
                self.last_ui_error = str(err)[:200]
                time.sleep(0.25)
                continue
            self.last_ui_error = None
            if self.dismiss_known_notice(tree):
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
    def home(t):
        return button(t, "Speak") is not None or button(t, "说句话") is not None
    result = d.adb("shell", "am", "start", "-a", "android.intent.action.MAIN",
                   "-c", "android.intent.category.LAUNCHER", "-n", APP + "/.LauncherActivity")
    if "Error:" in result or "Exception" in result:
        raise AssertionError("Android could not launch the installed APK")
    attempt = {"method": "public launcher intent", "am": result}
    d.launches.append(attempt)
    try:
        tree = d.wait(home, "public launcher home", seconds=12)
    except AssertionError:
        attempt["intent_state"] = d.diagnostics()
        # Use the real Android home screen and its app icon as a second independent
        # public path. Swipe only inside the bounds of an observed launcher view.
        d.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        tree = d.wait(lambda t: any(is_launcher(n.get("package", "")) and bounds(n)
                                    for n in t.iter("node")), "Android home launcher", seconds=18)
        def icon(t):
            for n in t.iter("node"):
                if not is_launcher(n.get("package", "")) or not bounds(n):
                    continue
                names = [v.strip().lower().replace(" ", "") for v in
                         [n.get("text", ""),n.get("content-desc", "")]]
                if any(s in ("voicetrail", "声迹助手") for s in names):
                    return n
            return None
        for swipe in range(4):
            target = icon(tree)
            if target is not None:
                break
            boxes = [bounds(n) for n in tree.iter("node") if is_launcher(n.get("package", "")) and bounds(n)]
            if not boxes:
                break
            x, y, right, bottom = max(boxes, key=lambda b: (b[2]-b[0])*(b[3]-b[1]))
            center = (x + right)//2
            d.adb("shell", "input", "swipe", center, y+(bottom-y)*4//5, center, y+(bottom-y)//5, 450)
            tree = d.wait(lambda t: any(is_launcher(n.get("package", "")) for n in t.iter("node")),
                          "launcher drawer")
        target = icon(tree)
        if target is None:
            raise AssertionError("VoiceTrail app icon missing from visible launcher drawer")
        attempt.update(method="Android launcher icon", icon=target.get("text") or target.get("content-desc"),
                       icon_bounds=target.get("bounds"))
        d.tap_node(target)
        tree = d.wait(home, "home after launcher icon tap", seconds=20)
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


def enter_command(d, words, evidence_name):
    # Test inputs consist only of English letters and spaces, safe for adb input.
    if not re.fullmatch("[A-Za-z ]+", words):
        raise ValueError("Unexpected test input")
    d.click("Type a command")
    editor = lambda t: next((n for n in t.iter("node") if n.get("package") == APP
                             and n.get("class") == "android.widget.EditText" and bounds(n)), None)
    tree = d.wait(lambda t: editor(t) is not None, "command editor")
    d.tap_node(editor(tree))
    d.adb("shell", "input", "text", words.replace(" ", "%s"))
    d.wait(lambda t: editor(t) is not None and editor(t).get("text") == words,
           "typed words")
    d.evidence(evidence_name)
    # The editor and Run are above the keyboard; never toggle a guessed IME state.
    d.click("Run")


def typed(d):
    enter_command(d, "read the screen", "04-typed")
    tree = d.wait(lambda t: message(t, SPEECH_ERROR, SERVICE_OFF),
                  "recoverable result of read command on a clean device", seconds=50)
    output = message(tree, SPEECH_ERROR, SERVICE_OFF)
    d.evidence("05-read-outcome")
    return "recovery_observed", output


def foreground_permission(t):
    return next((n for n in t.iter("node") if
                 n.get("resource-id", "").endswith(":id/permission_allow_foreground_only_button")
                 and "permissioncontroller" in n.get("package", "") and bounds(n)), None)


def microphone(d):
    d.adb("shell", "am", "force-stop", APP)
    launcher(d)
    d.click("Speak")
    tree = d.wait(lambda t: foreground_permission(t) is not None or
                  message(t, "Listening.", *MIC_RESPONSES), "microphone prompt or ASR state")
    if foreground_permission(tree) is not None:
        d.tap_node(foreground_permission(tree))
    tree = d.wait(lambda t: message(t, *MIC_RESPONSES), "actual no-input ASR outcome", seconds=65)
    response = message(tree, *MIC_RESPONSES)
    permissions = d.adb("shell", "dumpsys", "package", APP)
    granted = bool(re.search(r"android\.permission\.RECORD_AUDIO:\s*granted=true", permissions))
    d.evidence("08-microphone")
    return "recovery_observed", {"microphone_granted": granted, "human_speech_supplied": False,
                                 "response": response}


def sensitive_handoff(d):
    enter_command(d, "Enter the verification code", "09-handoff-command")
    # No numerical code or third-party text field is supplied to this test.
    tree = d.wait(lambda t: message(t, HANDOFF), "explicit manual handoff", seconds=50)
    if button(tree, "Speak") is None:
        raise AssertionError("Expected handoff on Voice Trail home")
    d.evidence("10-handoff-outcome")
    return "passed", message(tree, HANDOFF)


def camera(d):
    # Re-launch so a stale speech error from the preceding read cannot be taken
    # as the camera result. Force-stop leaves language and permission settings intact.
    d.adb("shell", "am", "force-stop", APP)
    launcher(d)
    d.click("Look around")
    tree = d.wait(lambda t: message(t, SPEECH_ERROR) or foreground_permission(t) is not None
                  or button(t, "Take photo and describe") is not None, "camera entry or speech recovery", seconds=50)
    error = message(tree, SPEECH_ERROR)
    if error:
        d.evidence("06-look-blocked")
        return "blocked", error
    if foreground_permission(tree) is not None:
        d.tap_node(foreground_permission(tree))
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
    parser.add_argument("--exercise-voice", action="store_true",
                        help="With a working English TTS, exercise the microphone and sensitive-command handoff")
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
        cases = [launcher, languages, typed]
        if args.exercise_voice:
            cases.extend([microphone, sensitive_handoff])
        for case in (*cases, camera):
            try:
                status, observed = case(d)
            except (AssertionError, RuntimeError, ET.ParseError, subprocess.TimeoutExpired) as error:
                status, observed = "failed", {"error": str(error), "device": d.diagnostics()}
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
    report["launches"] = d.launches
    report["dismissed_system_notices"] = d.dismissed
    result = json.dumps(report, ensure_ascii=False, indent=2)
    (args.out / "smoke.json").write_text(result + "\n", encoding="utf-8")
    print(result, flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
