#!/usr/bin/env python3
"""Inspect the installed Voice Trail APK through its launcher and document picker.

This runs only on a disposable Android emulator. The images contain synthetic
label text. No application API, instrumentation hook or OCR stub is used.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


APP = "io.github.voicetrail"
PICK = ("Choose a photo or screenshot", "选择现有图片或截图")
EXPIRY = ("Find expiry date", "查有效期", "查找有效期")


class EnvironmentBlocked(Exception):
    pass


class Device:
    def __init__(self, serial, out):
        self.command = ["adb", "-s", serial] if serial else ["adb"]
        self.out = out
        self.tree = None

    def adb(self, *args, check=True):
        p = subprocess.run(self.command + list(args), capture_output=True,
                           encoding="utf-8", errors="replace", timeout=25)
        if check and p.returncode:
            raise RuntimeError(f"adb {' '.join(args[:6])} returned {p.returncode}: {(p.stderr + p.stdout)[:480]}")
        return p.stdout.strip()

    def shell(self, *args):
        return self.adb("shell", *args)

    def screen(self):
        self.adb("shell", "rm", "-f", "/data/local/tmp/voice-trail-ui.xml")
        result = self.adb("shell", "uiautomator", "dump", "/data/local/tmp/voice-trail-ui.xml", check=False)
        if "dumped" in result.lower():
            data = self.adb("exec-out", "cat", "/data/local/tmp/voice-trail-ui.xml", check=False)
            try:
                self.tree = ET.fromstring(data)
                return self.tree
            except ET.ParseError:
                pass
        return ET.Element("hierarchy")

    @staticmethod
    def label(node):
        return (node.get("text") or node.get("content-desc") or "").strip()

    def node(self, root, names, pkg=APP):
        wanted = {s.casefold() for s in names}
        for n in root.iter("node"):
            if pkg is not None and n.get("package") != pkg:
                continue
            if n.get("enabled") == "false":
                continue
            if any(n.get(k, "").strip().casefold() in wanted for k in ("text", "content-desc")):
                return n
        return None

    def tap(self, node):
        xy = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
        if len(xy) != 4 or xy[2] <= xy[0] or xy[3] <= xy[1]:
            raise RuntimeError("The selected control has no visible bounds")
        self.shell("input", "tap", str((xy[0]+xy[2])//2), str((xy[1]+xy[3])//2))

    def scroll(self, root, to_top=False):
        area = next((n for n in root.iter("node") if n.get("scrollable") == "true"), None)
        if area is None:
            return
        xy = list(map(int, re.findall(r"\d+", area.get("bounds", ""))))
        if len(xy) != 4:
            return
        x = (xy[0]+xy[2])//2
        lo = xy[1] + (xy[3]-xy[1])//6
        hi = xy[3] - (xy[3]-xy[1])//6
        start, end = (lo,hi) if to_top else (hi,lo)
        self.shell("input", "swipe", str(x), str(start), str(x), str(end), "350")

    def record(self, name, root):
        result = ET.Element("voice-trail")
        for n in root.iter("node"):
            if n.get("package") == APP and n.get("password") != "true":
                ET.SubElement(result, "view", {k: n.get(k, "") for k in
                              ("class", "text", "content-desc", "bounds")})
        ET.ElementTree(result).write(self.out / (name+".xml"), encoding="utf-8", xml_declaration=True)

    def wait(self, find, fail, timeout=25):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            root = self.screen()
            found = find(root)
            if found is not None:
                return found
            time.sleep(.35)
        raise AssertionError(fail)

    def tap_app(self, names):
        def locate(root):
            n = self.node(root, names)
            if n is None:
                self.scroll(root)
            return n
        self.tap(self.wait(locate, "Visible app control not found: "+names[0]))


def make_image(path, label):
    image = Image.new("RGB", (1280, 420), "white")
    draw = ImageDraw.Draw(image)
    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 92)
    except OSError:
        font = ImageFont.load_default(size=92)
    draw.text((68, 140), label, fill="black", font=font)
    image.save(path)


def is_picker(root):
    return any("documentsui" in n.get("package", "").lower() for n in root.iter("node"))


def choose_image(d, name):
    # Read the actual document name in Recent files or in the Downloads folder.
    # The selector is the standard ACTION_OPEN_DOCUMENT system UI.
    d.wait(lambda root: True if is_picker(root) else None, "System document picker did not open")
    opened_drawer = False
    def document(root):
        nonlocal opened_drawer
        if not is_picker(root):
            return None
        file_node = d.node(root, (name, Path(name).stem), pkg=None)
        if file_node is not None:
            return file_node
        downloads = d.node(root, ("Downloads", "下载", "下載"), pkg=None)
        if opened_drawer and downloads is not None and downloads.get("class") != "android.widget.Spinner":
            d.tap(downloads)
            opened_drawer = False
            return None
        if not opened_drawer:
            menu = d.node(root, ("Show roots", "Open navigation drawer", "Show navigation menu",
                                 "显示根目录", "显示导航菜单"), pkg=None)
            if menu is not None:
                d.tap(menu)
                opened_drawer = True
        return None
    d.tap(d.wait(document, "Could not choose the named synthetic image in the system picker: "+name))
    def returned(root):
        if is_picker(root):
            confirm = d.node(root, ("Open","打开"), pkg=None)
            if confirm is not None:
                d.tap(confirm)
            return None
        if any(n.get("package") == APP for n in root.iter("node")):
            return True
        return None
    d.wait(returned, "Android did not return the selected document to the app")


def open_vision(d):
    d.shell("am", "force-stop", APP)
    launch = d.shell("am", "start", "-a", "android.intent.action.MAIN",
                     "-c", "android.intent.category.LAUNCHER", "-n", APP + "/.LauncherActivity")
    if "Error" in launch:
        raise AssertionError("Could not launch the installed APK")
    d.tap_app(("Look around", "看一看"))
    def entered(root):
        if d.node(root, PICK) is not None:
            d.record("look-around", root)
            return True
        texts = [d.label(n) for n in root.iter("node")]
        for msg in texts:
            if ("Speech paused" in msg and "offline voice" in msg) or ("语音" in msg and "离线语音" in msg):
                d.record("offline-voice-block", root)
                raise EnvironmentBlocked(msg)
        # Camera permission is independent of reading a file. Deny it here so
        # a synthetic camera feed cannot be mistaken for the chosen still photo.
        for n in root.iter("node"):
            if "permissioncontroller" in n.get("package", "") and (
                n.get("resource-id", "").endswith(":id/permission_deny_button") or
                d.label(n).casefold() in {"don’t allow","don't allow","不允许"}):
                d.tap(n)
                break
        return None
    d.wait(entered, "Look around did not become accessible", timeout=45)


def read_fixture(d, name, expected, key, report):
    # After a long answer, the chooser may be above the current scroll position.
    d.scroll(d.screen(), to_top=True)
    d.tap_app(PICK)
    choose_image(d, name)
    def observed(root):
        labels = [d.label(n) for n in root.iter("node") if n.get("package") == APP]
        for value in labels:
            if expected in value:
                return (value,root)
        if any(n.get("package") == APP for n in root.iter("node")):
            d.scroll(root, to_top=True)
        return None
    value,root = d.wait(observed, f"The app did not recognize {expected} from {name}", timeout=65)
    report[key] = {"filename": name, "observed": value}
    d.record(key, root)
    d.tap_app(EXPIRY)
    def answer(root):
        for n in root.iter("node"):
            if n.get("package") != APP:
                continue
            v = d.label(n)
            if key == "expiry":
                if expected in v and re.search(r"(?:Line\s+1|第\s*1\s*行)",v,re.I) and (
                        "recognized label date" in v or "识读的标签日期" in v):
                    return (v,root)
            elif "can't establish the expiry" in v or "无法确定有效期" in v:
                return (v,root)
        d.scroll(root, to_top=True)
        return None
    value,root = d.wait(answer, f"Unexpected expiry decision for {name}", timeout=30)
    report[key]["expiry_answer"] = value
    d.record(key+"-answer", root)


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--out", type=Path, default=Path("emulator-vision-report"))
    cli.add_argument("--serial")
    args = cli.parse_args()
    args.out.mkdir(exist_ok=True, parents=True)
    d = Device(args.serial, args.out)
    report = {"case": "release APK local OCR through Android document picker", "status": "failed"}
    status = 1
    airplane = None
    try:
        if not any(d.shell("getprop", p) == "1" for p in ("ro.kernel.qemu","ro.boot.qemu")):
            raise EnvironmentBlocked("A disposable Android emulator is required")
        # Only this emulator's airplane mode is changed; restore it at exit.
        airplane = d.shell("settings", "get", "global", "airplane_mode_on")
        d.shell("cmd", "connectivity", "airplane-mode", "enable")
        if d.shell("settings", "get", "global", "airplane_mode_on") != "1":
            raise EnvironmentBlocked("Could not enable emulator airplane mode")
        report["airplane_mode"] = True
        fixtures = (("trail-exp.png","EXP 2031-07-28"), ("trail-mfg.png","MFG 2029-03-19"))
        for name,label in fixtures:
            path = args.out / name
            make_image(path,label)
            remote = "/sdcard/Download/"+name
            d.adb("push",str(path),remote)
            d.shell("am","broadcast","-a","android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                    "-d","file://"+remote)
        open_vision(d)
        read_fixture(d, fixtures[0][0],"2031-07-28","expiry",report)
        read_fixture(d, fixtures[1][0],"2029-03-19","production_only",report)
        report["status"] = "passed"
        status = 0
    except EnvironmentBlocked as exc:
        report.update(status="environment_blocked", reason=str(exc))
        status = 3
    except (AssertionError, RuntimeError, OSError, subprocess.SubprocessError) as exc:
        report["reason"] = str(exc)
    finally:
        if status and d.tree is not None:
            d.record("last-observed-app-ui",d.tree)
        if airplane == "0":
            d.adb("shell","cmd","connectivity","airplane-mode","disable",check=False)
        data = json.dumps(report,ensure_ascii=False,indent=2)
        (args.out / "vision.json").write_text(data+"\n",encoding="utf-8")
        print(data,flush=True)
    return status


if __name__ == "__main__":
    raise SystemExit(main())
