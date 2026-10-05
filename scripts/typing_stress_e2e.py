#!/usr/bin/env python3
"""Type more than fifty characters on every keyboard with real touches.

For each keyboard (拼音 26 键, 拼音 9 键, 笔画, 英文 26 键, 数字) and one mixed
session, this taps the keys' real screen positions (from the keyboard's own
`bounds` report) into the debug ImeTestLabActivity's multi-line field, commits
words with the space bar, and then checks:

  * the field holds at least --min characters, exactly the expected text where
    that is known (English, digits), CJK characters only for Chinese;
  * the IME and the field's app kept their processes (no crash, no restart);
  * no crash or ANR for openIME in logcat.

Needs a debug build (the E2E receiver and the lab activity) on one device or
emulator; it selects openIME as the default input method. Screenshots of each
finished field go to --out.

    python3 scripts/typing_stress_e2e.py [--serial SERIAL] [--out DIR] [--min 50] [case ...]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "llc.slacker.openime"
CLASSES = "llc.slacker.openime"  # class names stay put when the applicationId gets a suffix
IME = f"{PKG}/{CLASSES}.LocalVoiceImeService"
LAB = f"{PKG}/{CLASSES}.ImeTestLabActivity"
RECEIVER = f"{PKG}/{CLASSES}.E2ETestReceiver"
ACTION = f"{CLASSES}.TEST_COMMAND"
FIELD = "lab_multiline"
FIELD_HINT = "多行文本"
ROOT = Path(__file__).resolve().parent.parent
STROKE_TABLE = ROOT / "app" / "build" / "generated" / "assets" / "prebuildRimeData" / "stroke_table.tsv"

PINYIN_WORDS = (
    "nihao women zhongguo jintian tianqi henhao shurufa xiexie pengyou gongzuo "
    "xuexi shijian wenti dianhua shouji diannao kaixin mingtian zaijian huanying "
    "shenghuo jiating yinyue dianying chifan shuijiao lvxing kafei pingguo xiangjiao"
).split()
ENGLISH_WORDS = "the quick brown fox jumps over the lazy dog and keeps on typing every word".split()
T9 = {c: d for d, letters in {"2": "abc", "3": "def", "4": "ghi", "5": "jkl", "6": "mno",
                              "7": "pqrs", "8": "tuv", "9": "wxyz"}.items() for c in letters}


class Device:
    def __init__(self, serial: str | None, out: Path):
        self.base = ["adb"] + (["-s", serial] if serial else [])
        self.out = out
        out.mkdir(parents=True, exist_ok=True)
        self.bounds: list[tuple[str, str, float, float]] = []

    def run(self, *args: str, binary: bool = False):
        result = subprocess.run(self.base + list(args), capture_output=True)
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    def shell(self, *args: str) -> str:
        return self.run("shell", *args)

    def command(self, cmd: str) -> str:
        self.run("logcat", "-c")
        self.shell("am", "broadcast", "-n", RECEIVER, "-a", ACTION, "--es", "cmd", cmd)
        time.sleep(0.5)
        return self.run("logcat", "-d", "-s", "OpenIme:I", "OpenImeE2E:I")

    def refresh_bounds(self) -> None:
        log = self.command("bounds")
        window = re.search(r"window=(\d+),(\d+),(\d+),(\d+)", log)
        if not window:
            raise RuntimeError("keyboard bounds unavailable (is the keyboard shown?)")
        wx, wy, ww, wh = map(int, window.groups())
        self.bounds = []
        for line in log.splitlines():
            match = re.search(r"tag=([^|]*)\|desc=([^|]*)\|([-\d.E]+),([-\d.E]+),([-\d.E]+),([-\d.E]+)", line)
            if match:
                tag, desc = match.group(1), match.group(2)
                left, top, width, height = map(float, match.groups()[2:])
                self.bounds.append((tag, desc, wx + (left + width / 2) * ww, wy + (top + height / 2) * wh))

    def tap_tag(self, tag: str, index: int = 0) -> None:
        hits = [b for b in self.bounds if b[0] == tag]
        if len(hits) <= index:
            raise RuntimeError(f"no key tagged {tag!r}")
        _, _, x, y = hits[index]
        self.shell("input", "tap", str(int(x)), str(int(y)))
        time.sleep(0.09)

    def pid(self, package: str) -> str:
        return self.shell("pidof", package).strip()

    def field_text(self) -> str:
        for _ in range(3):
            self.shell("uiautomator", "dump", "/sdcard/stress.xml")
            xml = self.shell("cat", "/sdcard/stress.xml")
            start = xml.find("<?xml")
            if start < 0:
                time.sleep(0.5)
                continue
            try:
                root = ET.fromstring(xml[start:])
            except ET.ParseError:
                time.sleep(0.5)
                continue
            for el in root.iter("node"):
                if el.get("resource-id", "").endswith(f"/{FIELD}"):
                    # uiautomator reports an empty field's hint as its text.
                    text = el.get("text", "")
                    return "" if text == el.get("hint", FIELD_HINT) or text == FIELD_HINT else text
        return ""

    def field_bounds(self) -> tuple[int, int, int, int] | None:
        self.shell("uiautomator", "dump", "/sdcard/stress.xml")
        xml = self.shell("cat", "/sdcard/stress.xml")
        match = re.search(rf'resource-id="[^"]*/{FIELD}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        return tuple(map(int, match.groups())) if match else None

    def preedit(self) -> str | None:
        """The keyboard's own pre-edit line (the IME window's text field), or None."""
        self.shell("uiautomator", "dump", "--windows", "/sdcard/stress-ime.xml")
        xml = self.shell("cat", "/sdcard/stress-ime.xml")
        values = re.findall(
            rf'<node[^>]*text="([^"]*)"[^>]*resource-id="[^"]*"[^>]*class="android.widget.EditText"[^>]*package="{re.escape(PKG)}"',
            xml,
        )
        hint = re.search(r'resource-id="[^"]*/' + FIELD, xml)
        # The IME window comes first in the dump; the lab fields follow it.
        return values[0] if values and not (hint and xml.find(values[0]) > hint.start()) else None

    def shot(self, name: str) -> None:
        (self.out / f"{name}.png").write_bytes(self.run("exec-out", "screencap", "-p", binary=True))


def show_keyboard(dev: Device) -> None:
    # Use the id exactly as the system lists it (short form for the plain package).
    ime_id = next((line.strip() for line in dev.shell("ime", "list", "-a", "-s").splitlines()
                   if line.strip().startswith(f"{PKG}/")), IME)
    if dev.shell("settings", "get", "secure", "default_input_method").strip() != ime_id:
        dev.shell("ime", "enable", ime_id)
        dev.shell("ime", "set", ime_id)
        time.sleep(1)
    dev.shell("am", "start", "-n", LAB, "--es", "focus_id", FIELD, "-f", "0x10008000")
    time.sleep(2.5)
    for _ in range(6):
        if "mInputShown=true" in dev.shell("dumpsys", "input_method"):
            return
        # After a reinstall the first focus may not bring the keyboard up: tap the field.
        bounds = dev.field_bounds()
        if bounds:
            dev.shell("input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
        time.sleep(1.5)
    raise RuntimeError("keyboard did not show")


def clear_field(dev: Device) -> None:
    dev.command("clear-swipe")
    time.sleep(0.4)


def set_mode(dev: Device, mode: str) -> None:
    dev.command(f"mode:{mode}")
    time.sleep(0.6)
    dev.refresh_bounds()


def type_pinyin26(dev: Device, words: list[str]) -> None:
    for word in words:
        for letter in word:
            dev.tap_tag(f"key:{letter}")
        time.sleep(0.35)  # let librime answer before committing
        dev.tap_tag("key-space")
        time.sleep(0.15)


def type_nine_key(dev: Device, words: list[str], check_preedit: bool = False) -> list[str]:
    """Type each word's digits and commit; with [check_preedit], report any pre-edit holding digits."""
    problems = []
    for word in words:
        for letter in word.replace("v", "u"):
            dev.tap_tag(f"key-9:{T9[letter]}")
        time.sleep(0.4)
        if check_preedit:
            shown = dev.preedit()
            if shown is None or any(ch.isdigit() for ch in shown):
                problems.append(f"{word}: pre-edit {shown!r}")
        dev.tap_tag("key-space")
        time.sleep(0.15)
    return problems


RAIL_TAGS = ("nine-pinyin-path-filter", "nine-pinyin-path-selected")


def check_nine_key_rail(dev: Device, sequences: list[str]) -> list[str]:
    """Tap every reading the left rail offers: each must change the pre-edit or the candidates."""
    problems = []
    for digits in sequences:
        index = 0
        while True:
            dev.tap_tag("key-retype")
            for digit in digits:
                dev.tap_tag(f"key-9:{digit}")
            time.sleep(0.9)
            dev.refresh_bounds()
            rail = [b for b in dev.bounds if b[0] in RAIL_TAGS]
            if index >= len(rail):
                break
            name = rail[index][1]
            symbols_y = next(b[3] for b in dev.bounds if b[0] == "key-symbols")
            for _ in range(4):  # scroll the rail until the reading is above the 符号 key
                hit = next((b for b in dev.bounds if b[0] in RAIL_TAGS and b[1] == name), None)
                if hit is None or hit[3] < symbols_y - 90:
                    break
                dev.shell("input", "swipe", str(int(hit[2])), str(int(symbols_y - 120)),
                          str(int(hit[2])), str(int(symbols_y - 420)), "250")
                time.sleep(0.5)
                dev.refresh_bounds()
            if hit is None or hit[3] >= symbols_y - 90:
                problems.append(f"{digits}: {name} unreachable")
                index += 1
                continue
            before = (dev.preedit(), [b[1] for b in dev.bounds if b[0] == "candidate-first-row"])
            dev.shell("input", "tap", str(int(hit[2])), str(int(hit[3])))
            time.sleep(0.9)
            dev.refresh_bounds()
            after = (dev.preedit(), [b[1] for b in dev.bounds if b[0] == "candidate-first-row"])
            if after[0] is None or any(ch.isdigit() for ch in after[0]):
                problems.append(f"{digits}: after {name} the pre-edit is {after[0]!r}")
            elif after == before and "已选择" not in name:
                problems.append(f"{digits}: tapping {name} changed nothing ({before})")
            index += 1
    dev.tap_tag("key-retype")
    return problems


def stroke_codes(count: int) -> list[tuple[str, str]]:
    if not STROKE_TABLE.is_file():
        raise RuntimeError(f"{STROKE_TABLE} missing; build the debug APK first")
    seen: dict[str, str] = {}
    for line in STROKE_TABLE.read_text(encoding="utf-8").splitlines():
        char, code = line.split("\t")
        if char not in seen and 2 <= len(code) <= 9:
            seen[char] = code
        if len(seen) >= count:
            break
    return list(seen.items())


def type_strokes(dev: Device, codes: list[tuple[str, str]]) -> None:
    for _, code in codes:
        for stroke in code:
            dev.tap_tag(f"key-stroke:{stroke}")
        time.sleep(0.25)
        dev.tap_tag("key-space")
        time.sleep(0.15)


def type_english(dev: Device, words: list[str]) -> None:
    for word in words:
        for letter in word:
            dev.tap_tag(f"key:{letter}")
        time.sleep(0.2)
        dev.tap_tag("key-space")
        time.sleep(0.1)


def type_digits(dev: Device, digits: str) -> None:
    for digit in digits:
        dev.tap_tag(f"key:{digit}")


def probe_preedit_stays_on_keyboard(dev: Device, keys: list[str]) -> str | None:
    """Type a pre-edit without committing: the app's field must not change."""
    before = dev.field_text()
    for tag in keys:
        dev.tap_tag(tag)
    time.sleep(0.5)
    during = dev.field_text()
    for _ in keys:
        dev.tap_tag("key-backspace")
    time.sleep(0.3)
    if during != before:
        return f"pre-edit reached the app's field: {before!r} → {during!r}"
    return None


def visible_tags(dev: Device, predicate) -> list[tuple[str, str, float, float]]:
    """Bounds entries matching [predicate] whose centre is on screen above the navigation bar."""
    height = int(re.search(r"(\d+)x(\d+)", dev.shell("wm", "size")).group(2))
    return [b for b in dev.bounds if predicate(b) and 0 < b[3] < height - 120]


def type_from_symbol_panel(dev: Device, categories: list[str], per_category: int = 2) -> str:
    """Open 符号, tap the first symbols of each category; returns what should have been typed."""
    expected = ""
    dev.tap_tag("key-symbols")
    time.sleep(1.0)
    for category in categories:
        dev.command(f"tap:{category}")
        time.sleep(0.8)
        dev.refresh_bounds()
        keys = visible_tags(dev, lambda b: b[0].startswith("key:"))[:per_category]
        for tag, desc, x, y in keys:
            dev.shell("input", "tap", str(int(x)), str(int(y)))
            time.sleep(0.25)
            expected += tag[len("key:"):]
    dev.shell("input", "keyevent", "BACK")
    time.sleep(0.6)
    return expected


def type_emoji(dev: Device, count: int = 6) -> str:
    dev.command("tap:表情")
    time.sleep(1.2)
    dev.refresh_bounds()
    cells = visible_tags(dev, lambda b: b[0] == "emoji-cell")[:count]
    expected = ""
    for _, desc, x, y in cells:
        dev.shell("input", "tap", str(int(x)), str(int(y)))
        time.sleep(0.25)
        expected += desc
    dev.shell("input", "keyevent", "BACK")
    time.sleep(0.6)
    return expected


def set_rotation(dev: Device, landscape: bool) -> None:
    dev.shell("settings", "put", "system", "accelerometer_rotation", "0")
    dev.shell("settings", "put", "system", "user_rotation", "1" if landscape else "0")
    time.sleep(2)


def is_cjk(text: str) -> bool:
    return all("㐀" <= ch <= "鿿" or "\U00020000" <= ch <= "\U0003134f" for ch in text)


def run_case(dev: Device, name: str, minimum: int) -> tuple[bool, str]:
    show_keyboard(dev)
    clear_field(dev)
    expected: str | None = None
    chinese = False
    probe = None
    if name == "pinyin26":
        set_mode(dev, "PINYIN_26")
        probe = probe_preedit_stays_on_keyboard(dev, [f"key:{c}" for c in "nihao"])
        type_pinyin26(dev, PINYIN_WORDS)
        chinese = True
    elif name == "pinyin9":
        set_mode(dev, "PINYIN_9")
        probe = probe_preedit_stays_on_keyboard(dev, [f"key-9:{d}" for d in "669"])
        digit_problems = type_nine_key(dev, PINYIN_WORDS, check_preedit=True)
        probe = probe or ("; ".join(digit_problems) if digit_problems else None)
        chinese = True
    elif name == "nine-rail":
        set_mode(dev, "PINYIN_9")
        problems = check_nine_key_rail(dev, ["2", "4", "6", "9", "64", "74", "646", "6464", "94664", "6442646", "644264658846649669"])
        return (not problems), ("; ".join(problems) if problems else "every rail reading responds, pre-edit always letters")
    elif name == "stroke":
        set_mode(dev, "STROKE")
        probe = probe_preedit_stays_on_keyboard(dev, [f"key-stroke:{c}" for c in "phz"])
        type_strokes(dev, stroke_codes(minimum + 6))
        chinese = True
    elif name == "english":
        set_mode(dev, "ENGLISH_26")
        words = ENGLISH_WORDS[:]
        type_english(dev, words)
        expected = " ".join(words) + " "
    elif name == "digits":
        set_mode(dev, "DIGITS")
        digits = "1234567890" * 6
        type_digits(dev, digits)
        expected = digits
    elif name == "mixed":
        set_mode(dev, "PINYIN_26")
        type_pinyin26(dev, PINYIN_WORDS[:8])
        dev.tap_tag("key-backspace")
        dev.tap_tag("key:mode")  # 中/英 → English
        time.sleep(0.6)
        dev.refresh_bounds()
        type_english(dev, ENGLISH_WORDS[:4])
        set_mode(dev, "PINYIN_9")
        type_nine_key(dev, PINYIN_WORDS[8:16])
        set_mode(dev, "STROKE")
        type_strokes(dev, stroke_codes(10))
        set_mode(dev, "DIGITS")
        type_digits(dev, "2026100512")
        set_mode(dev, "PINYIN_26")
        type_pinyin26(dev, PINYIN_WORDS[16:24])
    elif name == "symbols":
        set_mode(dev, "PINYIN_9")
        expected = type_from_symbol_panel(
            dev, ["常用", "中文", "英文", "数学", "序号", "特殊", "网络颜文字", "拼音", "日文", "注音", "制表", "单位", "编程"],
        )
        minimum = min(minimum, len(expected))
    elif name == "emoji":
        set_mode(dev, "PINYIN_26")
        expected = type_emoji(dev, 8)
        minimum = min(minimum, len(expected))
    elif name == "rail":
        set_mode(dev, "PINYIN_9")
        cells = visible_tags(dev, lambda b: b[0].startswith("punct:") and b[0] != "punct:add")[:3]
        expected = ""
        for tag, _, x, y in cells:
            dev.shell("input", "tap", str(int(x)), str(int(y)))
            time.sleep(0.25)
            expected += tag[len("punct:"):]
        dev.tap_tag("key-9:0")
        expected += "0"
        minimum = min(minimum, len(expected))
    elif name == "expand":
        set_mode(dev, "PINYIN_26")
        picked = ""
        for word in ["shi", "zhong", "hao", "de", "ren"]:
            for letter in word:
                dev.tap_tag(f"key:{letter}")
            time.sleep(0.5)
            dev.refresh_bounds()
            dev.tap_tag("candidate-expand")
            time.sleep(0.8)
            dev.refresh_bounds()
            grid = visible_tags(dev, lambda b: b[0] in ("candidate-grid", "candidate-grid-first"))
            if len(grid) < 3:
                return False, f"expanded candidates for {word}: only {len(grid)}"
            tag, desc, x, y = grid[2]
            dev.shell("input", "tap", str(int(x)), str(int(y)))
            time.sleep(0.5)
            dev.refresh_bounds()
            picked += desc.removeprefix("候选:")
        expected = picked
        minimum = min(minimum, len(expected))
    elif name == "clear":
        set_mode(dev, "PINYIN_26")
        type_pinyin26(dev, PINYIN_WORDS[:6])
        dev.refresh_bounds()
        hit = next(b for b in dev.bounds if b[0] == "key-backspace")
        dev.shell("input", "swipe", str(int(hit[2])), str(int(hit[3])), str(int(hit[2])), str(int(hit[3] - 330)), "300")
        time.sleep(1)
        text = dev.field_text()
        dev.shot(name)
        return (text == ""), f"after swipe-up clear the field holds {text!r}"
    elif name in ("landscape", "dark"):
        if name == "landscape":
            set_rotation(dev, True)
        else:
            dev.shell("cmd", "uimode", "night", "yes")
            time.sleep(1.5)
        try:
            show_keyboard(dev)
            clear_field(dev)
            set_mode(dev, "PINYIN_26")
            type_pinyin26(dev, PINYIN_WORDS[:14])
            set_mode(dev, "PINYIN_9")
            type_nine_key(dev, PINYIN_WORDS[14:28])
            time.sleep(1)
            text = dev.field_text()
            dev.shot(name)
        finally:
            if name == "landscape":
                set_rotation(dev, False)
            else:
                dev.shell("cmd", "uimode", "night", "no")
        ok = len(text) >= minimum and is_cjk(text)
        return ok, f"{len(text)} characters: {text[:40]}…"
    else:
        return False, f"unknown case {name}"
    time.sleep(1)
    text = dev.field_text()
    dev.shot(name)
    count = len(text)
    if probe:
        return False, probe
    if count < minimum:
        return False, f"{count} characters, need {minimum}: {text!r}"
    if expected is not None and text != expected:
        return False, f"text differs:\n  got      {text!r}\n  expected {expected!r}"
    if chinese and not is_cjk(text):
        return False, f"non-Chinese characters in {text!r}"
    return True, f"{count} characters: {text[:40]}…"


def main() -> int:
    global PKG, IME, LAB, RECEIVER
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--serial")
    parser.add_argument("--out", type=Path, default=ROOT / "build" / "typing-stress")
    parser.add_argument("--min", type=int, default=50)
    parser.add_argument("--package", default=PKG, help="applicationId of the debug build (e.g. with a .dev suffix)")
    parser.add_argument("cases", nargs="*", default=[
        "pinyin26", "pinyin9", "nine-rail", "stroke", "english", "digits", "mixed",
        "symbols", "emoji", "rail", "expand", "clear", "landscape", "dark",
    ])
    args = parser.parse_args()
    PKG = args.package
    IME = f"{PKG}/{CLASSES}.LocalVoiceImeService"
    LAB = f"{PKG}/{CLASSES}.ImeTestLabActivity"
    RECEIVER = f"{PKG}/{CLASSES}.E2ETestReceiver"
    dev = Device(args.serial, args.out)

    show_keyboard(dev)
    time.sleep(3)  # librime ready
    ime_pid = dev.pid(PKG)
    dev.run("logcat", "-b", "crash", "-c")
    failures = 0
    for name in args.cases:
        try:
            ok, detail = run_case(dev, name, args.min)
        except RuntimeError as error:
            ok, detail = False, str(error)
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'} {name}: {detail}", flush=True)

    crashes = dev.run("logcat", "-b", "crash", "-d")
    anr = dev.run("logcat", "-d", "-s", "ActivityManager:E")
    pid_now = dev.pid(PKG)
    if PKG in crashes:
        failures += 1
        print("FAIL crash log:\n" + crashes[-2000:])
    if f"ANR in {PKG}" in anr:
        failures += 1
        print("FAIL ANR reported for openIME")
    if pid_now != ime_pid:
        failures += 1
        print(f"FAIL openIME process restarted ({ime_pid} → {pid_now})")
    else:
        print(f"PASS process stayed up (pid {pid_now})")
    print(f"{'ALL PASSED' if failures == 0 else f'{failures} FAILED'}; screenshots in {args.out}")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
