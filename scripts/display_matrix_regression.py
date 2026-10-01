#!/usr/bin/env python3
"""Display-environment matrix for the live keyboard.

For each environment (orientation, system font size, dark mode, phone / tablet /
foldable / narrow windows) the debug input lab is opened, the keyboard is shown,
and the script checks that

  * the keyboard is a bottom panel, never the fullscreen "extract" mode;
  * every key and toolbar button has a size and lies inside the keyboard window.

It needs a debug build of openIME selected as the default input method on one
device or emulator. Every setting it changes is reset afterwards, also on error.
Screenshots go to --out so a human can look at them.

    python3 scripts/display_matrix_regression.py [--serial SERIAL] [--out DIR] [case ...]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

PKG = "llc.slacker.openime"
LAB = f"{PKG}/.ImeTestLabActivity"
RECEIVER = f"{PKG}/.E2ETestReceiver"
ACTION = f"{PKG}.TEST_COMMAND"

# name -> (wm size, wm density, user_rotation, font_scale, night)
CASES: dict[str, tuple] = {
    "portrait": (None, None, 0, "1.0", False),
    "landscape": (None, None, 1, "1.0", False),
    "portrait_font1.3": (None, None, 0, "1.3", False),
    "portrait_font2.0": (None, None, 0, "2.0", False),
    "landscape_font1.3": (None, None, 1, "1.3", False),
    "dark": (None, None, 0, "1.0", True),
    "small_phone_720x1280_d320": ("720x1280", "320", 0, "1.0", False),
    "narrow_540x1200_d420": ("540x1200", "420", 0, "1.0", False),
    "tablet_1600x2560_d280": ("1600x2560", "280", 0, "1.0", False),
    "tablet_landscape": ("1600x2560", "280", 1, "1.0", False),
    "foldable_inner_1840x2208_d420": ("1840x2208", "420", 0, "1.0", False),
}

KEYBOARD_ITEMS = ("key", "toolbar", "keyboard-selector", "clipboard-toolbar", "undo-toolbar", "keyboard-hide")


class Device:
    def __init__(self, serial: str | None):
        self.base = ["adb"] + (["-s", serial] if serial else [])

    def run(self, *args: str, binary: bool = False):
        result = subprocess.run(self.base + list(args), capture_output=True)
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    def shell(self, *args: str) -> str:
        return self.run("shell", *args)


def settle(seconds: float) -> None:
    time.sleep(seconds)


def reset(dev: Device) -> None:
    dev.shell("wm", "size", "reset")
    dev.shell("wm", "density", "reset")
    dev.shell("settings", "put", "system", "font_scale", "1.0")
    dev.shell("settings", "put", "system", "accelerometer_rotation", "0")
    dev.shell("settings", "put", "system", "user_rotation", "0")
    dev.shell("cmd", "uimode", "night", "no")
    settle(2)


def apply(dev: Device, case: tuple) -> None:
    size, density, rotation, font, night = case
    if size:
        dev.shell("wm", "size", size)
    if density:
        dev.shell("wm", "density", density)
    dev.shell("settings", "put", "system", "accelerometer_rotation", "0")
    dev.shell("settings", "put", "system", "user_rotation", str(rotation))
    dev.shell("settings", "put", "system", "font_scale", font)
    dev.shell("cmd", "uimode", "night", "yes" if night else "no")
    settle(2)


def edit_fields(dev: Device) -> list[tuple[int, int]]:
    dev.shell("uiautomator", "dump", "/sdcard/display_matrix.xml")
    xml = dev.shell("cat", "/sdcard/display_matrix.xml")
    pattern = r'<node[^>]*class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    centers = []
    for match in re.finditer(pattern, xml):
        x0, y0, x1, y1 = map(int, match.groups())
        if x1 > x0 and y1 > y0:
            centers.append(((x0 + x1) // 2, (y0 + y1) // 2))
    return centers


def ime_shown(dev: Device) -> bool:
    return "mInputShown=true" in dev.shell("dumpsys", "input_method")


def ensure_default_ime(dev: Device) -> None:
    service = f"{PKG}/.LocalVoiceImeService"
    if dev.shell("settings", "get", "secure", "default_input_method").strip() != service:
        dev.shell("ime", "enable", "--user", "0", service)
        dev.shell("ime", "set", "--user", "0", service)
        settle(1)


def show_ime(dev: Device) -> bool:
    ensure_default_ime(dev)
    # The lab and the IME service share one process: restart only the activity,
    # a force-stop would kill the keyboard and the system may fall back to another IME.
    # 0x10008000 = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK
    dev.shell("am", "start", "-n", LAB, "--es", "focus_id", "lab_single", "-f", "0x10008000")
    settle(3)
    if "ImeTestLabActivity" not in dev.shell("dumpsys", "activity", "activities").split("topResumedActivity=")[-1][:200]:
        return False  # something else is in front; the checks below would measure the wrong app
    for _ in range(4):
        if ime_shown(dev):
            break
        fields = edit_fields(dev)
        if len(fields) >= 2:
            # Moving the focus away and back makes the client request the IME again.
            dev.shell("input", "tap", str(fields[1][0]), str(fields[1][1]))
            settle(1)
            dev.shell("input", "tap", str(fields[0][0]), str(fields[0][1]))
        elif fields:
            dev.shell("input", "tap", str(fields[0][0]), str(fields[0][1]))
        settle(2)
    settle(1.5)
    return ime_shown(dev)


def bounds(dev: Device) -> list[tuple[str, float, float, float, float]]:
    dev.run("logcat", "-c")
    dev.shell("am", "broadcast", "-n", RECEIVER, "-a", ACTION, "--es", "cmd", "bounds")
    settle(1)
    log = dev.run("logcat", "-d", "-s", "OpenIme:I")
    items = []
    for match in re.finditer(r"tag=([^|]*)\|desc=([^|]*)\|([-0-9.eE]+),([-0-9.eE]+),([-0-9.eE]+),([-0-9.eE]+)", log):
        x, y, w, h = (float(value) for value in match.groups()[2:])
        items.append((match.group(1), x, y, w, h))
    return items


def check(dev: Device, name: str, out: Path) -> bool:
    problems: list[str] = []
    if not show_ime(dev):
        problems.append("the keyboard did not show")
    else:
        # The first inFullscreenMode= in the dump is the service's current state;
        # the ones after it are history entries.
        fullscreen = re.search(r"\binFullscreenMode=(true|false)", dev.shell("dumpsys", "input_method"))
        if fullscreen and fullscreen.group(1) == "true":
            problems.append("the keyboard is in fullscreen (extract) mode")
    items = bounds(dev)
    if not items:
        problems.append("no bounds were reported")
    for tag, x, y, w, h in items:
        if not tag.startswith(KEYBOARD_ITEMS):
            continue
        if w <= 0.005 or h <= 0.005:
            problems.append(f"{tag} has no size ({w:.3f}x{h:.3f})")
        elif x < -0.002 or y < -0.002 or x + w > 1.002 or y + h > 1.002:
            problems.append(f"{tag} leaves the window (x={x:.3f} w={w:.3f} y={y:.3f} h={h:.3f})")
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{name}.png").write_bytes(dev.run("exec-out", "screencap", "-p", binary=True))
    verdict = "FAIL" if problems else "PASS"
    detail = f" :: {'; '.join(problems[:4])}" if problems else f" ({len(items)} items)"
    print(f"{verdict} {name}{detail}")
    return not problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial")
    parser.add_argument("--out", type=Path, default=Path(".local/test-runs/display-matrix"))
    parser.add_argument("cases", nargs="*", help=f"subset of: {', '.join(CASES)}")
    args = parser.parse_args()
    unknown = [name for name in args.cases if name not in CASES]
    if unknown:
        parser.error(f"unknown case(s): {', '.join(unknown)}")

    dev = Device(args.serial)
    results: dict[str, bool] = {}
    try:
        for name, case in CASES.items():
            if args.cases and name not in args.cases:
                continue
            reset(dev)
            apply(dev, case)
            results[name] = check(dev, name, args.out)
    finally:
        reset(dev)
    failed = [name for name, ok in results.items() if not ok]
    print(f"SUMMARY {len(results) - len(failed)} passed, {len(failed)} failed" + (f": {failed}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
