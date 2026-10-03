#!/usr/bin/env python3
"""Real-IME end-to-end check of the 0.0.3-beta.1 features.

Drives the live openIME service on one device or emulator with real touch
events (`input swipe`, `input motionevent`), the debug E2E receiver for the
keyboard's own click targets, and `uiautomator dump` to read the screen. Needs
a debug build of openIME; it selects openIME as the default input method.

Covered, each in portrait and (where it matters) landscape:

  * space-bar cursor drag: the cursor moves, the bottom row is locked while the
    finger is down and free afterwards, a plain tap is still a space
  * number row: ten digit keys, they type, the keyboard keeps its height
  * emoji association after committing a word
  * voice: filler removal and punctuation-as-space through the real
    post-processing chain
  * autofill strip (needs the debug test autofill service, installed with the
    debug build): chips show in the keyboard and tapping one fills the field

Screenshots go to --out so a person can look at them. Settings and rotation are
restored afterwards, also on error.

    python3 scripts/beta3_e2e.py [--serial SERIAL] [--out DIR] [case ...]
"""

from __future__ import annotations

import argparse
import base64
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

PKG = "llc.slacker.openime"
IME = f"{PKG}/.LocalVoiceImeService"
LAB = f"{PKG}/.ImeTestLabActivity"
AUTOFILL_LAB = f"{PKG}/.AutofillTestActivity"
AUTOFILL_SERVICE = f"{PKG}/.TestAutofillService"
RECEIVER = f"{PKG}/.E2ETestReceiver"
ACTION = f"{PKG}.TEST_COMMAND"

PREFS_HEAD = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>"
PREFS_TAIL = "</map>"


@dataclass
class Node:
    cls: str
    text: str
    desc: str
    x0: int
    y0: int
    x1: int
    y1: int

    @property
    def cx(self) -> int:
        return (self.x0 + self.x1) // 2

    @property
    def cy(self) -> int:
        return (self.y0 + self.y1) // 2

    @property
    def w(self) -> int:
        return self.x1 - self.x0

    @property
    def h(self) -> int:
        return self.y1 - self.y0


class Device:
    def __init__(self, serial: str | None, out: Path):
        self.base = ["adb"] + (["-s", serial] if serial else [])
        self.out = out
        out.mkdir(parents=True, exist_ok=True)
        self.shots = 0

    def run(self, *args: str, binary: bool = False):
        result = subprocess.run(self.base + list(args), capture_output=True)
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    def shell(self, *args: str) -> str:
        return self.run("shell", *args)

    def density(self) -> float:
        match = re.search(r"(\d+)", self.shell("wm", "density"))
        return int(match.group(1)) / 160.0 if match else 2.625

    def size(self) -> tuple[int, int]:
        match = re.search(r"(\d+)x(\d+)", self.shell("wm", "size"))
        return (int(match.group(1)), int(match.group(2))) if match else (1080, 2400)

    def shot(self, name: str) -> Path:
        self.shots += 1
        path = self.out / f"{self.shots:02d}-{name}.png"
        path.write_bytes(self.run("exec-out", "screencap", "-p", binary=True))
        return path

    def dump(self) -> list[Node]:
        for _ in range(3):
            self.shell("uiautomator", "dump", "--windows", "/sdcard/beta3.xml")
            xml = self.shell("cat", "/sdcard/beta3.xml")
            start = xml.find("<?xml")
            if start < 0:
                time.sleep(0.5)
                continue
            try:
                root = ET.fromstring(xml[start:])
            except ET.ParseError:
                time.sleep(0.5)
                continue
            nodes = []
            for el in root.iter("node"):
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", el.get("bounds", ""))
                if not m:
                    continue
                nodes.append(
                    Node(el.get("class", ""), el.get("text", ""), el.get("content-desc", ""), *map(int, m.groups()))
                )
            return nodes
        return []

    def command(self, cmd: str) -> bool:
        self.run("logcat", "-c")
        self.shell("am", "broadcast", "-n", RECEIVER, "-a", ACTION, "--es", "cmd", cmd)
        time.sleep(0.6)
        log = self.run("logcat", "-d", "-s", "OpenImeE2E:I")
        return "ok=true" in log.split("cmd=")[-1] if "cmd=" in log else False

    def tap(self, x: int, y: int) -> None:
        self.shell("input", "tap", str(x), str(y))


def b64(text: str) -> str:
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def find(nodes: list[Node], desc_prefix: str) -> Node | None:
    for node in nodes:
        if node.desc.startswith(desc_prefix):
            return node
    return None


def field_text(dev: Device) -> str:
    for node in dev.dump():
        if node.cls.endswith("EditText"):
            return node.text
    return ""


def write_prefs(dev: Device, booleans: dict[str, bool]) -> None:
    body = "".join(f'<boolean name="{k}" value="{"true" if v else "false"}" />' for k, v in booleans.items())
    dev.shell("am", "force-stop", PKG)
    time.sleep(1)
    encoded = base64.b64encode(f"{PREFS_HEAD}{body}{PREFS_TAIL}".encode()).decode()
    # One quoted string for the device shell: the payload is plain base64.
    dev.run(
        "shell",
        f"run-as {PKG} sh -c 'mkdir -p shared_prefs && echo {encoded} | base64 -d > shared_prefs/ime_settings.xml'",
    )


def ensure_ime(dev: Device) -> None:
    if dev.shell("settings", "get", "secure", "default_input_method").strip() != IME:
        dev.shell("ime", "enable", "--user", "0", IME)
        dev.shell("ime", "set", "--user", "0", IME)
        time.sleep(1)
    dev.shell("pm", "grant", PKG, "android.permission.RECORD_AUDIO")


def show_keyboard(dev: Device, focus: str = "lab_single") -> bool:
    ensure_ime(dev)
    dev.shell("am", "start", "-n", LAB, "--es", "focus_id", focus, "-f", "0x10008000")
    time.sleep(3)
    for _ in range(5):
        if "mInputShown=true" in dev.shell("dumpsys", "input_method"):
            break
        edits = [n for n in dev.dump() if n.cls.endswith("EditText")]
        if len(edits) >= 2:
            dev.tap(edits[1].cx, edits[1].cy)
            time.sleep(1)
            dev.tap(edits[0].cx, edits[0].cy)
        elif edits:
            dev.tap(edits[0].cx, edits[0].cy)
        time.sleep(2)
    time.sleep(1.5)
    return "mInputShown=true" in dev.shell("dumpsys", "input_method")


def set_rotation(dev: Device, rotation: int) -> None:
    dev.shell("settings", "put", "system", "accelerometer_rotation", "0")
    dev.shell("settings", "put", "system", "user_rotation", str(rotation))
    time.sleep(2.5)


def rime_ready(dev: Device) -> bool:
    dev.run("logcat", "-c")
    dev.command("state")
    return "rimeReady=true" in dev.run("logcat", "-d", "-s", "OpenImeE2E:I")


# ----------------------------------------------------------------------------- cases


def case_space_cursor(dev: Device, landscape: bool) -> list[str]:
    problems: list[str] = []
    write_prefs(dev, {"sound": False, "haptic": False})
    if not show_keyboard(dev):
        return ["the keyboard did not show"]
    dev.command("mode:ENGLISH_26")
    time.sleep(1)
    original = "hello world"
    dev.command(f"type64:{b64(original)}")
    time.sleep(1)
    before = field_text(dev)
    if before != original:
        problems.append(f"typed text is {before!r}, expected {original!r}")
    nodes = dev.dump()
    space = find(nodes, "空格")
    if not space:
        return problems + ["space key not found"]
    density = dev.density()
    start_x = space.cx
    y = space.cy
    # DOWN, then horizontal moves in ~3dp steps, like a finger.
    dev.shell("input", "motionevent", "DOWN", str(start_x), str(y))
    steps = 20
    total_dp = 18 + 12 * 4
    for i in range(1, steps + 1):
        dev.shell("input", "motionevent", "MOVE", str(int(start_x - total_dp * density * i / steps)), str(y))
    time.sleep(0.4)
    dev.shot(f"space-drag-held{'-landscape' if landscape else ''}")
    # While the finger is down the bottom row is dimmed, so the other keys have
    # no usable touch. A tap on the punctuation key must not type.
    punct = [n for n in dev.dump() if n.desc.startswith(",")]
    if punct:
        # A second finger would hit it; `input tap` is a separate gesture.
        dev.tap(punct[0].cx, punct[0].cy)
        time.sleep(0.5)
    dev.shell("input", "motionevent", "UP", str(int(start_x - total_dp * density)), str(y))
    time.sleep(0.8)
    after_drag = field_text(dev)
    if after_drag != original:
        problems.append(f"the drag changed the text to {after_drag!r}")
    dev.command("type:X")
    time.sleep(1)
    after = field_text(dev)
    if after.replace("X", "") != original or "X" not in after:
        problems.append(f"after the drag the text is {after!r}")
    else:
        index = after.index("X")
        moved = len(original) - index
        if not 2 <= moved <= 5:
            problems.append(f"cursor moved {moved} characters (expected about 4)")
    dev.shot(f"space-drag-after{'-landscape' if landscape else ''}")
    # A plain tap on space is still a space.
    nodes = dev.dump()
    space = find(nodes, "空格")
    if space:
        dev.tap(space.cx, space.cy)
        time.sleep(0.8)
        if field_text(dev) == after:
            problems.append("a tap on the space bar no longer types a space")
    return problems


def case_number_row(dev: Device, landscape: bool) -> list[str]:
    problems: list[str] = []
    for enabled in (False, True):
        write_prefs(dev, {"number_row": enabled, "sound": False, "haptic": False})
        if not show_keyboard(dev):
            return ["the keyboard did not show"]
        for mode in ("PINYIN_26", "ENGLISH_26"):
            dev.command(f"mode:{mode}")
            time.sleep(1.2)
            nodes = dev.dump()
            digits = [n for n in nodes if re.fullmatch(r"数字 \d", n.desc)]
            label = f"{mode}-numrow-{'on' if enabled else 'off'}{'-landscape' if landscape else ''}"
            dev.shot(label)
            if enabled and len(digits) != 10:
                problems.append(f"{mode}: number row has {len(digits)} keys, expected 10")
            if not enabled and digits:
                problems.append(f"{mode}: number row shown while the setting is off")
            width, height = dev.size()
            if landscape:
                width, height = height, width
            space = find(nodes, "空格")
            if not space:
                problems.append(f"{mode}: space key missing")
            elif space.y1 > height:
                problems.append(f"{mode}: bottom row runs off the screen ({space.y1} > {height})")
            if enabled and digits:
                five = next((n for n in digits if n.desc == "数字 5"), None)
                if five and five.h < 30 * dev.density():
                    problems.append(f"{mode}: number key only {five.h}px tall")
        if enabled:
            dev.command("mode:PINYIN_26")
            time.sleep(1)
            nodes = dev.dump()
            five = find(nodes, "数字 5")
            if five:
                dev.tap(five.cx, five.cy)
                time.sleep(0.8)
                if "5" not in field_text(dev):
                    problems.append(f"tapping the 5 key typed {field_text(dev)!r}")
    return problems


def case_emoji(dev: Device, landscape: bool) -> list[str]:
    problems: list[str] = []
    for enabled in (True, False):
        write_prefs(dev, {"emoji_association": enabled, "sound": False, "haptic": False})
        if not show_keyboard(dev):
            return ["the keyboard did not show"]
        dev.command("mode:PINYIN_26")
        time.sleep(1)
        for letter in "kaixin":
            dev.command(f"tap:{letter}")
        time.sleep(1.5)
        nodes = dev.dump()
        candidate = next((n for n in nodes if n.text == "开心" or n.desc.startswith("开心")), None)
        if not candidate:
            problems.append("candidate 开心 not offered for 'kaixin'")
            continue
        dev.tap(candidate.cx, candidate.cy)
        time.sleep(1.2)
        nodes = dev.dump()
        emoji = [n for n in nodes if n.desc.startswith("联想:") and any(ord(c) > 0x2000 for c in n.desc)]
        dev.shot(f"emoji-{'on' if enabled else 'off'}{'-landscape' if landscape else ''}")
        if enabled and not emoji:
            problems.append("no emoji association after committing 开心")
        if enabled and emoji and not emoji[0].desc.startswith("联想:😊"):
            problems.append(f"first association is {emoji[0].desc!r}, expected 😊")
        if not enabled and emoji:
            problems.append("emoji association shown while the setting is off")
        if enabled and emoji:
            dev.tap(emoji[0].cx, emoji[0].cy)
            time.sleep(1)
            if "😊" not in field_text(dev):
                problems.append(f"tapping the emoji typed {field_text(dev)!r}")
    return problems


def case_voice(dev: Device, landscape: bool) -> list[str]:
    problems: list[str] = []
    cases = [
        ({"voice_strip_fillers": True, "voice_punctuation_as_space": False}, "嗯我觉得呃这个不错", "我觉得这个不错"),
        ({"voice_strip_fillers": False, "voice_punctuation_as_space": False}, "嗯我觉得呃这个不错", "嗯我觉得呃这个不错"),
        ({"voice_strip_fillers": True, "voice_punctuation_as_space": True}, "嗯 你好 逗号 世界 句号", "你好 世界"),
        ({"voice_strip_fillers": True, "voice_punctuation_as_space": False}, "你好 逗号 世界", "你好，世界"),
    ]
    for prefs, spoken, expected in cases:
        write_prefs(dev, {**prefs, "sound": False, "haptic": False})
        if not show_keyboard(dev):
            return ["the keyboard did not show"]
        dev.command("mode:PINYIN_26")
        if not dev.command(f"voice-processed64:{b64(spoken)}"):
            problems.append(f"voice-processed command refused for {spoken!r}")
            continue
        time.sleep(2.5)
        got = field_text(dev).strip()
        # Natural terminal punctuation may follow in prose fields.
        if got.rstrip("。？！") != expected:
            problems.append(f"{prefs}: {spoken!r} -> {got!r}, expected {expected!r}")
    dev.shot(f"voice{'-landscape' if landscape else ''}")
    return problems


def case_autofill(dev: Device, landscape: bool) -> list[str]:
    problems: list[str] = []
    write_prefs(dev, {"sound": False, "haptic": False})
    ensure_ime(dev)
    dev.shell("settings", "put", "secure", "autofill_service", AUTOFILL_SERVICE)
    try:
        # The first request after a process start has no window token yet, so
        # warm the service up before the real attempt.
        dev.shell("am", "start", "-n", AUTOFILL_LAB, "-f", "0x10008000")
        time.sleep(4)
        dev.shell("am", "start", "-n", AUTOFILL_LAB, "-f", "0x10008000")
        time.sleep(4)
        edits = [n for n in dev.dump() if n.cls.endswith("EditText")]
        if len(edits) < 2:
            return ["autofill lab not on screen"]
        dev.tap(edits[1].cx, edits[1].cy)
        time.sleep(2)
        dev.tap(edits[0].cx, edits[0].cy)
        time.sleep(4)
        nodes = dev.dump()
        back = find(nodes, "返回工具栏")
        dev.shot(f"autofill-strip{'-landscape' if landscape else ''}")
        if not back:
            return problems + ["the autofill strip did not appear in the keyboard"]
        # Chips are remote surfaces; the first one starts right after the back control.
        chip_x = back.x1 + int(80 * dev.density())
        dev.tap(chip_x, back.cy)
        time.sleep(3)
        text = next((n.text for n in dev.dump() if n.cls.endswith("EditText") and "@" in n.text), "")
        dev.shot(f"autofill-filled{'-landscape' if landscape else ''}")
        if text != "work@openime.dev":
            problems.append(f"tapping the chip filled {text!r}")
        # After filling, the toolbar comes back.
        if not find(dev.dump(), "切换键盘"):
            problems.append("the toolbar did not come back after filling")
    finally:
        dev.shell("settings", "delete", "secure", "autofill_service")
    return problems


CASES = {
    "space_cursor": case_space_cursor,
    "number_row": case_number_row,
    "emoji": case_emoji,
    "voice": case_voice,
    "autofill": case_autofill,
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial")
    parser.add_argument("--out", type=Path, default=Path(".local/test-runs/beta3-e2e"))
    parser.add_argument("--no-landscape", action="store_true")
    parser.add_argument("cases", nargs="*", help=f"subset of: {', '.join(CASES)}")
    args = parser.parse_args()
    unknown = [name for name in args.cases if name not in CASES]
    if unknown:
        parser.error(f"unknown case(s): {', '.join(unknown)}")

    dev = Device(args.serial, args.out)
    results: dict[str, bool] = {}
    try:
        for orientation in ([False] if args.no_landscape else [False, True]):
            set_rotation(dev, 1 if orientation else 0)
            for name, case in CASES.items():
                if args.cases and name not in args.cases:
                    continue
                label = f"{name}{' (landscape)' if orientation else ''}"
                try:
                    problems = case(dev, orientation)
                except Exception as error:  # a crashed case must not hide the others
                    problems = [f"error: {error}"]
                results[label] = not problems
                print(f"{'PASS' if not problems else 'FAIL'} {label}" + (f" :: {'; '.join(problems)}" if problems else ""))
    finally:
        set_rotation(dev, 0)
        dev.shell("settings", "delete", "secure", "autofill_service")
        write_prefs(dev, {})
    failed = [name for name, ok in results.items() if not ok]
    print(f"SUMMARY {len(results) - len(failed)} passed, {len(failed)} failed" + (f": {failed}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
