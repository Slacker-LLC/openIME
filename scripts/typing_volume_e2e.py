#!/usr/bin/env python3
"""Type a large volume of real text on every keyboard and check what comes out.

For each keyboard this drives the real keys (their screen positions come from
the keyboard's own `bounds` report) and checks the committed text against the
input list:

  random     2000 random key taps, mixed with space and backspace: the keyboard
             must stay alive and never leave a stuck composition;
  words      1000 dictionary words (word + reading from the bundled tables);
  chars      1000 single characters by frequency, so every common character is
             typed at least once;
  sentences  100 sentences of 15+ characters typed as one continuous reading;
  four       500 four-character words (the dictionaries' idioms included).

The reading is typed key by key and committed with the space bar, exactly as a
user types it. Batches of words are checked in one read; a batch that does not
match is re-typed word by word to name the words that were wrong.

Read the numbers as: how often the first candidate the space bar commits is the
word that was typed (words/chars/four), and how much of a whole sentence
conversion survives (sentences, exact match and per-character accuracy). A miss
is not always a defect: the word list is spread over the whole dictionary, so it
carries tail vocabulary that the engine's own lexicon may not have.

    python3 scripts/build_volume_data.py          # once, fills build/volume-data
    python3 scripts/typing_volume_e2e.py [--serial SERIAL] [--out DIR]
                                         [--keyboard PINYIN_26 ...]
                                         [--phases chars ...] [--quick]

Needs a debug build installed (the E2E receiver and the lab activity); it
selects openIME as the default input method and rotates nothing.
"""

from __future__ import annotations

import argparse
import base64
import difflib
import importlib.util
import json
import random
import re
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "build" / "volume-data"
STROKE_TABLE = ROOT / "app" / "build" / "generated" / "assets" / "prebuildRimeData" / "stroke_table.tsv"
FLYPY_SCHEMA = ROOT / "app" / "src" / "main" / "assets" / "rime-data" / "double_pinyin_flypy.schema.yaml"

# Apps seen taking the front of the shared emulator during a run; they are stopped
# again when the keyboard has to be brought back.
INTRUDERS = ("llc.slacker.minis.dev",)

T9 = {c: d for d, letters in {"2": "abc", "3": "def", "4": "ghi", "5": "jkl", "6": "mno",
                              "7": "pqrs", "8": "tuv", "9": "wxyz"}.items() for c in letters}
CIRCLED = "ⓆⓌⓇⓉⓎⓊⒾⓄⓅⓈⒹⒻⒼⒽⒿⓀⓁⓏⓍⒸⓋⒷⓃⓂ"
CIRCLED_KEYS = "qwrtyuiopsdfghjklzxcvbnm"

# The volume of each phase, per keyboard.
PLAN = {
    "PINYIN_26": {"random": 2000, "words": 1000, "chars": 1000, "sentences": 100, "four": 500},
    "PINYIN_9": {"random": 2000, "words": 1000, "chars": 1000, "sentences": 100, "four": 500},
    "DOUBLE_PINYIN": {"random": 2000, "words": 1000, "chars": 1000, "sentences": 100, "four": 500},
    "WUBI": {"random": 2000, "words": 500, "chars": 500, "sentences": 0, "four": 0},
    "STROKE": {"random": 2000, "words": 1000, "chars": 1000, "sentences": 100, "four": 500},
    "ENGLISH_26": {"random": 2000, "words": 1000, "sentences": 100, "four": 0},
    "DIGITS": {"random": 2000, "words": 40},
}


def load_stress():
    """The existing E2E harness: its Device, keyboard bring-up and clearing."""
    spec = importlib.util.spec_from_file_location("stress", Path(__file__).resolve().parent / "typing_stress_e2e.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_tsv(path: Path) -> list[list[str]]:
    if not path.is_file():
        raise SystemExit(f"{path} missing; run scripts/build_volume_data.py first")
    return [line.split("\t") for line in path.read_text(encoding="utf-8").splitlines() if line]


class Keys:
    """Screen positions of one keyboard, and batched real taps on them."""

    def __init__(self, dev, refresh=True, on_lost=None):
        self.dev = dev
        self.positions: dict[str, tuple[int, int]] = {}
        # Called when the input view is gone: another app can come to the front
        # in the middle of a long run and take the keyboard with it.
        self.on_lost = on_lost
        self._recovering = False
        if refresh:
            self.refresh()

    def refresh(self) -> None:
        try:
            self.dev.refresh_bounds()
        except RuntimeError:
            if self.on_lost is None or self._recovering:
                raise
            self._recovering = True
            try:
                self.on_lost()
                self.dev.refresh_bounds()
            finally:
                self._recovering = False
        self.positions = {tag: (int(x), int(y)) for tag, _, x, y in self.dev.bounds}

    def has(self, tag: str) -> bool:
        return tag in self.positions

    def ensure(self, tags: list[str]) -> None:
        """Make sure every tag of [tags] is on screen, bringing the keyboard back if not.

        A keyboard that was replaced by another layout - or by another app's
        field - still answers `bounds`, it just has none of these keys, so
        missing tags are treated as a lost keyboard as well.
        """
        if not [tag for tag in tags if tag not in self.positions]:
            return
        self.refresh()
        missing = [tag for tag in tags if tag not in self.positions]
        if missing and self.on_lost is not None and not self._recovering:
            self._recovering = True
            try:
                self.on_lost()
                self.refresh()
            finally:
                self._recovering = False
            missing = [tag for tag in tags if tag not in self.positions]
        if missing:
            raise RuntimeError(f"keyboard does not offer {missing[:4]}")

    def tap(self, tags: list[str], settle: float = 0.0, gap: float = 0.06) -> None:
        """One adb call for the whole sequence, paced on the device.

        Without the pause between taps the keyboard loses taps: two touches
        inside one frame are coalesced, and the reading that comes out is a
        different one (measured on the nine-key decoder: 1 of 3 correct without
        the gap, 3 of 3 with it).
        """
        if not tags:
            return
        self.ensure(tags)
        taps = [f"input tap {self.positions[tag][0]} {self.positions[tag][1]}" for tag in tags]
        self.dev.shell(f"; sleep {gap}; ".join(taps) if gap else "; ".join(taps))
        if settle:
            time.sleep(settle)

    def tap_groups(self, groups: list[list[str]], settle: float = 0.0,
                   gap: float = 0.06, group_gap: float = 0.22) -> None:
        """Tap syllable by syllable, one adb call for the whole sequence.

        The pause between syllables is longer than the pause between the keys of
        one syllable: that is how the reading is typed, and the engine answers
        one syllable at a time.
        """
        flat = [tag for group in groups for tag in group]
        self.ensure(flat)
        parts: list[str] = []
        for index, group in enumerate(groups):
            for position, tag in enumerate(group):
                if parts:
                    parts.append(f"sleep {group_gap if position == 0 else gap}")
                elif index:
                    parts.append(f"sleep {group_gap}")
                parts.append(f"input tap {self.positions[tag][0]} {self.positions[tag][1]}")
        if not parts:
            return
        self.dev.shell("; ".join(parts))
        if settle:
            time.sleep(settle)


def read_payload(dev, command: str, marker: str, size: int = 0) -> str | None:
    log = dev.command(command)
    match = re.search(rf"{marker} (\S+)", log)
    return base64.b64decode(match.group(1)).decode("utf-8") if match else None


def editor_tail(dev, size: int = 400) -> str:
    return read_payload(dev, f"editor-tail64:{size}", "EDITOR") or ""


def candidates(dev) -> list[str]:
    payload = read_payload(dev, "candidates64", "CANDIDATES")
    return payload.split("\n") if payload else []


def flypy_keys() -> dict[str, str]:
    """Syllable to the two keys 小鹤双拼 types it with, from the scheme's own algebra."""
    text = FLYPY_SCHEMA.read_text(encoding="utf-8")
    algebra = re.search(r"^\s*algebra:\n((?:\s*-\s.*\n)+)", text, re.MULTILINE)
    if not algebra:
        raise SystemExit(f"no algebra in {FLYPY_SCHEMA}")
    rules: list[tuple[str, str]] = []
    for line in algebra.group(1).splitlines():
        rule = line.strip().removeprefix("- ").strip()
        if rule.startswith("xform/"):
            body = rule[len("xform/"):]
            pattern, replacement = body.rsplit("/", 2)[:2]
            # Rime writes back-references as $1; Python's re wants \1.
            replacement = re.sub(r"\$(\d)", r"\\\1", replacement)
            rules.append((pattern, replacement))

    def transform(syllable: str) -> str:
        value = syllable
        for pattern, replacement in rules:
            value = re.sub(pattern, replacement, value, count=1)
        return value.translate({ord(c): k for c, k in zip(CIRCLED, CIRCLED_KEYS)})

    return transform


def stroke_codes() -> dict[str, str]:
    """Character to the stroke keys that produce it (from the built stroke table)."""
    codes: dict[str, str] = {}
    for line in STROKE_TABLE.read_text(encoding="utf-8").splitlines():
        if "\t" not in line:
            continue
        char, code = line.split("\t")
        if 1 <= len(code) <= 20 and all(part in "hspnz" for part in code):
            codes.setdefault(char, code)
    return codes


class Keyboard:
    """What one keyboard needs to type a word, a reading or a sentence."""

    def __init__(self, name: str, dev, keys: Keys):
        self.name = name
        self.dev = dev
        self.keys = keys
        self.strokes = stroke_codes() if name == "STROKE" else {}
        self.flypy = flypy_keys() if name == "DOUBLE_PINYIN" else None

    # --- key sequences -----------------------------------------------------

    def reading_groups(self, pinyin: str) -> list[list[str]]:
        """The keys of one reading, grouped one syllable per group."""
        syllables = pinyin.split()
        if self.name == "PINYIN_26" or self.name == "ENGLISH_26":
            return [[f"key:{ch}" for ch in syllable] for syllable in syllables]
        if self.name == "PINYIN_9":
            return [
                [f"key-9:{T9[ch]}" for ch in syllable.replace("v", "u")]
                for syllable in syllables
            ]
        if self.name == "DOUBLE_PINYIN":
            groups: list[list[str]] = []
            for syllable in syllables:
                code = self.flypy(syllable)
                if len(code) != 2:
                    raise RuntimeError(f"double pinyin code for {syllable!r} is {code!r}")
                groups.append([f"key:{ch}" for ch in code])
            return groups
        raise RuntimeError(f"{self.name} does not type readings")

    def reading_keys(self, pinyin: str) -> list[str]:
        return [tag for group in self.reading_groups(pinyin) for tag in group]

    def word_groups(self, word: str, pinyin: str, code: str | None) -> list[list[str]]:
        """Everything to type before the committing space bar, one group per syllable."""
        if self.name == "WUBI":
            return [[f"key:{ch}" for ch in code]]
        if self.name == "STROKE":
            groups: list[list[str]] = []
            for char in word:
                strokes = self.strokes.get(char)
                if not strokes:
                    raise RuntimeError(f"no stroke code for {char!r}")
                groups.append([f"key-stroke:{part}" for part in strokes] + ["key-space"])
            return groups
        if self.name == "ENGLISH_26":
            return [[f"key:{ch}" for ch in word]]
        return self.reading_groups(pinyin)

    def word_keys(self, word: str, pinyin: str, code: str | None) -> list[str]:
        return [tag for group in self.word_groups(word, pinyin, code) for tag in group]

    def english_keys(self, text: str) -> list[str]:
        return [f"key:{ch}" if ch != " " else "key-space" for ch in text.lower()]

    # --- typing ------------------------------------------------------------

    def type_word(self, word: str, pinyin: str, code: str | None, settle: float | None = None) -> None:
        """Type one word and commit it with the space bar.

        The wait after the reading grows with its length: the space bar must
        not arrive before the engine has answered the last key, or it commits
        a half-typed reading.
        """
        groups = self.word_groups(word, pinyin, code)
        size = sum(len(group) for group in groups)
        wait = settle if settle is not None else min(1.2, 0.25 + 0.05 * size)
        if self.name == "STROKE":
            # Every character already carries its own committing space.
            self.keys.tap_groups(groups, settle=wait)
            return
        self.keys.tap_groups(groups, settle=wait)
        self.keys.tap(["key-space"], settle=0.15)

    def type_reading(self, pinyin: str, settle: float | None = None) -> None:
        groups = self.reading_groups(pinyin)
        size = sum(len(group) for group in groups)
        wait = settle if settle is not None else min(3.0, 0.3 + 0.02 * size)
        self.keys.tap_groups(groups, settle=wait)
        self.keys.tap(["key-space"], settle=0.25)

    def type_text(self, text: str, settle: float = 0.4) -> None:
        self.keys.tap(self.english_keys(text), settle=settle)
        self.keys.tap(["key-space"], settle=0.1)


def phase_random(dev, keyboard: Keyboard, taps: int, report: dict) -> None:
    """Random keys, with commits and backspaces: nothing may jam or crash."""
    keys = keyboard.keys
    alphabet = [tag for tag in keys.positions if tag.startswith("key:") and len(tag) == 5]
    if keyboard.name == "PINYIN_9":
        alphabet = [tag for tag in keys.positions if tag.startswith("key-9:")]
    elif keyboard.name == "STROKE":
        alphabet = [tag for tag in keys.positions if tag.startswith("key-stroke:")]
    if not alphabet:
        raise RuntimeError(f"{keyboard.name}: no keys to type at random")
    random.seed(20261014)
    issued = 0
    while issued < taps:
        batch = [random.choice(alphabet) for _ in range(min(10, taps - issued))]
        keys.tap(batch)
        issued += len(batch)
        if issued % 40 == 0:
            keys.tap(["key-space"], settle=0.15)
        if issued % 400 == 0:
            keys.refresh()
    keys.tap(["key-space"], settle=0.3)
    time.sleep(0.5)
    report["random"] = {"taps": issued, "text_before_clear": len(editor_tail(dev, 400))}


def phase_rows(dev, keyboard: Keyboard, rows: list[tuple[str, str, str | None]], expected_of,
               report: dict, label: str, batch: int = 25, settle: float = 0.35) -> None:
    """Type rows in batches, check each batch in one read, name the wrong ones.

    Every row is also recorded on its own in `results`, so the report can split
    the phase by input shape: the word list is two blocks (2-character words
    first, 3-character after), and one rate over both hides the step.
    """
    hits = 0
    misses: list[dict[str, str]] = []
    results: list[dict[str, object]] = []
    started = time.time()
    for start in range(0, len(rows), batch):
        chunk = rows[start:start + batch]
        keyboard.keys.refresh()
        dev.command("clear-swipe")
        time.sleep(0.3)
        for word, pinyin, code in chunk:
            keyboard.type_word(word, pinyin, code, settle=settle)
        time.sleep(0.4)
        expected = "".join(expected_of(row) for row in chunk)
        typed = editor_tail(dev, 600)
        if typed == expected:
            hits += len(chunk)
            # The batch read matched as a whole; per-row text is not observed,
            # so the result says so rather than inventing the typed text.
            for word, pinyin, code in chunk:
                results.append({
                    "expected": expected_of((word, pinyin, code)).strip(),
                    "typed": None,
                    "hit": True,
                    "rank": None,
                    "batch": True,
                })
        else:
            for word, pinyin, code in chunk:
                keyboard.keys.refresh()
                dev.command("clear-swipe")
                time.sleep(0.2)
                # A named miss is checked with the engine given all the time it
                # needs, so only a real one is reported. The candidate list at
                # that moment says whether the word was merely ranked lower.
                keyboard.keys.tap_groups(keyboard.word_groups(word, pinyin, code), settle=1.4)
                offered = candidates(dev)
                keyboard.keys.tap(["key-space"], settle=0.15)
                time.sleep(0.3)
                produced = editor_tail(dev, 60)
                expected = expected_of((word, pinyin, code))
                target = expected.strip()
                rank = offered.index(target) if target in offered else None
                if produced == expected:
                    hits += 1
                    results.append({"expected": target, "typed": produced, "hit": True, "rank": None, "batch": False})
                else:
                    if len(misses) < 60:
                        misses.append({
                            "expected": target,
                            "typed": produced,
                            "rank": -1 if rank is None else rank,
                        })
                    results.append({
                        "expected": target,
                        "typed": produced,
                        "hit": False,
                        "rank": -1 if rank is None else rank,
                        "batch": False,
                    })
        done = min(start + batch, len(rows))
        print(f"  {keyboard.name}/{label}: {done}/{len(rows)} ({hits} exact, {time.time() - started:.0f}s)",
              flush=True)
    report[label] = {
        "total": len(rows),
        "exact": hits,
        "rate": round(hits / len(rows), 4) if rows else 0.0,
        "misses": misses[:40],
        "miss_count": len(rows) - hits,
        "results": results,
    }


def phase_sentences(dev, keyboard: Keyboard, rows: list[list[str]], report: dict, limit: int) -> None:
    """Whole sentences in one reading: is the converted text the sentence?

    The nine-key engine holds at most 64 digits of one composition, so only
    sentences inside that reading length are typed; the rest of the list is
    covered by the other keyboards.
    """
    usable = [
        (sentence, pinyin) for sentence, pinyin in rows
        if keyboard.name != "PINYIN_9"
        or len([T9[ch] for syllable in pinyin.split() for ch in syllable.replace("v", "u")]) <= 60
    ][:limit]
    exact = 0
    characters = 0
    correct = 0
    misses: list[dict[str, object]] = []
    for sentence, pinyin in usable:
        keyboard.keys.refresh()
        dev.command("clear-swipe")
        time.sleep(0.25)
        keyboard.type_reading(pinyin)
        time.sleep(0.5)
        typed = editor_tail(dev, 200)
        characters += len(sentence)
        matcher = difflib.SequenceMatcher(None, sentence, typed, autojunk=False)
        correct += sum(block.size for block in matcher.get_matching_blocks())
        if typed == sentence:
            exact += 1
        elif len(misses) < 40:
            misses.append({"expected": sentence, "typed": typed})
    report["sentences"] = {
        "total": len(usable),
        "exact": exact,
        "exact_rate": round(exact / len(usable), 4) if usable else 0.0,
        "characters": characters,
        "character_accuracy": round(correct / characters, 4) if characters else 0.0,
        "misses": misses[:20],
    }


def phase_digits(dev, keyboard: Keyboard, rows: list[tuple[str, str, str | None]], report: dict,
                 batches: int) -> None:
    """Digit groups typed with no reading: the field must hold exactly them."""
    hits = 0
    misses: list[dict[str, str]] = []
    for index in range(batches):
        group = "".join(str((index * 7 + step * 3) % 10) for step in range(25))
        keyboard.keys.refresh()
        dev.command("clear-swipe")
        time.sleep(0.2)
        keyboard.keys.tap([f"key:{digit}" for digit in group], settle=0.3)
        typed = editor_tail(dev, 60)
        if typed == group:
            hits += 1
        else:
            misses.append({"expected": group, "typed": typed})
    report["words"] = {
        "total": batches,
        "exact": hits,
        "rate": round(hits / batches, 4) if batches else 0.0,
        "misses": misses[:10],
        "miss_count": batches - hits,
    }


def run_keyboard(dev, stress, name: str, plan: dict, out: Path, phases: list[str] | None = None) -> dict:
    print(f"== {name} ==", flush=True)
    report: dict[str, object] = {"keyboard": name, "plan": plan}

    def enabled(label: str) -> bool:
        return phases is None or label in phases

    def recover() -> None:
        """A long run outlives foreground changes: wake the screen and put the
        field back in front.

        Another app can come to the front of a shared emulator and take the
        keyboard with it - usually one that a second session starts by hand -
        so it is stopped first, before the field is brought back.
        """
        for attempt in range(5):
            try:
                print(f"  {name}: keyboard lost, bringing it back ({attempt + 1})", flush=True)
                for intruder in INTRUDERS:
                    dev.shell("am", "force-stop", intruder)
                dev.shell("input", "keyevent", "KEYCODE_WAKEUP")
                dev.shell("wm", "dismiss-keyguard")
                time.sleep(0.5)
                stress.show_keyboard(dev)
                stress.set_mode(dev, name)
                time.sleep(1.0)
                return
            except RuntimeError:
                time.sleep(3)
        raise RuntimeError(f"{name}: the keyboard did not come back")

    stress.show_keyboard(dev)
    stress.set_mode(dev, name)
    time.sleep(0.6)
    keyboard = Keyboard(name, dev, Keys(dev, on_lost=recover))
    keyboard.keys.refresh()

    if plan.get("random") and enabled("random"):
        phase_random(dev, keyboard, plan["random"], report)
        stress.set_mode(dev, name)
        keyboard.keys.refresh()
        print(f"  {name}/random: {report['random']}", flush=True)

    if name == "DIGITS":
        if enabled("words"):
            phase_digits(dev, keyboard, [], report, plan.get("words", 40))
    elif name == "ENGLISH_26":
        words = [(line[0], line[0], None) for line in read_tsv(DATA / "english.tsv")][: plan["words"]]
        if enabled("words"):
            phase_rows(dev, keyboard, words, lambda row: row[0] + " ", report, "words", settle=0.3)
        sentences = [[line[0]] for line in read_tsv(DATA / "en-sentences.tsv")]
        exact = 0
        misses = []
        for (sentence,) in sentences[: plan["sentences"]] if enabled("sentences") else []:
            keyboard.keys.refresh()
            dev.command("clear-swipe")
            time.sleep(0.2)
            keyboard.type_text(sentence)
            typed = editor_tail(dev, 200)
            expected = sentence + " "
            if typed == expected:
                exact += 1
            elif len(misses) < 20:
                misses.append({"expected": expected, "typed": typed})
        report["sentences"] = {
            "total": min(plan["sentences"], len(sentences)),
            "exact": exact,
            "exact_rate": round(exact / plan["sentences"], 4) if plan["sentences"] else 0.0,
            "misses": misses,
        }
    elif name == "WUBI":
        rows = [(word, "", code) for word, code in read_tsv(DATA / "wubi.tsv")][: plan["words"]]
        if enabled("words"):
            phase_rows(dev, keyboard, rows, lambda row: row[0], report, "words", settle=0.3)
        if plan.get("chars") and enabled("chars"):
            chars = [(char, "", code) for char, code in read_tsv(DATA / "chars-wubi.tsv")][: plan["chars"]]
            phase_rows(dev, keyboard, chars, lambda row: row[0], report, "chars", settle=0.3)
    elif name == "STROKE":
        # Stroke input has no reading: every character carries its own stroke
        # keys and its own committing space, so the sentences and the
        # four-character words run through the same per-word check.
        def ready(text: str) -> bool:
            return all(char in keyboard.strokes for char in text)

        words = [(word, "", None) for word, _ in read_tsv(DATA / "words.tsv") if ready(word)][: plan["words"]]
        if enabled("words"):
            phase_rows(dev, keyboard, words, lambda row: row[0], report, "words", settle=0.25)
        if plan.get("chars") and enabled("chars"):
            chars = [(char, "", None) for char, _ in read_tsv(DATA / "chars.tsv") if ready(char)][: plan["chars"]]
            phase_rows(dev, keyboard, chars, lambda row: row[0], report, "chars", settle=0.25)
        if plan.get("sentences") and enabled("sentences"):
            sentences = [(text, "", None) for text, _ in read_tsv(DATA / "sentences.tsv") if ready(text)]
            phase_rows(dev, keyboard, sentences, lambda row: row[0], report, "sentences", batch=5, settle=0.25)
        if plan.get("four") and enabled("four"):
            four = [(word, "", None) for word, _ in read_tsv(DATA / "four.tsv") if ready(word)][: plan["four"]]
            phase_rows(dev, keyboard, four, lambda row: row[0], report, "four", settle=0.25)
    else:
        words = [(word, pinyin, None) for word, pinyin in read_tsv(DATA / "words.tsv")][: plan["words"]]
        if enabled("words"):
            phase_rows(dev, keyboard, words, lambda row: row[0], report, "words")
        if plan.get("chars") and enabled("chars"):
            chars = [(char, pinyin, None) for char, pinyin in read_tsv(DATA / "chars.tsv")][: plan["chars"]]
            phase_rows(dev, keyboard, chars, lambda row: row[0], report, "chars")
        if plan.get("sentences") and enabled("sentences"):
            phase_sentences(dev, keyboard, read_tsv(DATA / "sentences.tsv"), report, plan["sentences"])
        if plan.get("four") and enabled("four"):
            four = [(word, pinyin, None) for word, pinyin in read_tsv(DATA / "four.tsv")][: plan["four"]]
            phase_rows(dev, keyboard, four, lambda row: row[0], report, "four")

    (out / f"{name}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--serial")
    parser.add_argument("--out", type=Path, default=ROOT / "build" / "typing-volume")
    parser.add_argument("--keyboard", action="append", default=None)
    parser.add_argument("--phases", action="append", default=None,
                        help="run only these phases (random, words, chars, sentences, four)")
    parser.add_argument("--quick", action="store_true", help="small counts, to check the harness itself")
    args = parser.parse_args()

    stress = load_stress()
    args.out.mkdir(parents=True, exist_ok=True)
    dev = stress.Device(args.serial, args.out)
    # The run lasts hours, which is longer than the screen timeout: keep the
    # screen on while the device charges and start from a woken device, or the
    # input method goes away with the screen and takes the run with it.
    dev.shell("svc", "power", "stayon", "true")
    dev.shell("input", "keyevent", "KEYCODE_WAKEUP")
    dev.shell("wm", "dismiss-keyguard")
    names = args.keyboard or list(PLAN)
    plan_all = {name: dict(PLAN[name]) for name in names}
    if args.quick:
        for plan in plan_all.values():
            for key in list(plan):
                plan[key] = min(plan[key], 12)

    summary = {}
    for name in names:
        plan = plan_all[name]
        started = time.time()
        pid_before = dev.pid(stress.PKG)
        summary[name] = run_keyboard(dev, stress, name, plan, args.out, args.phases)
        summary[name]["seconds"] = round(time.time() - started)
        summary[name]["process_kept"] = dev.pid(stress.PKG) == pid_before
        crashes = dev.run("logcat", "-b", "crash", "-d")
        summary[name]["crash"] = stress.PKG in crashes
        (args.out / "summary.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8",
        )
        print(f"== {name} done in {summary[name]['seconds']}s, crash={summary[name]['crash']}", flush=True)

    for name, report in summary.items():
        parts = []
        for label in ("words", "chars", "four", "sentences"):
            if label in report:
                entry = report[label]
                rate = entry.get("rate", entry.get("exact_rate"))
                parts.append(f"{label} {entry['exact']}/{entry['total']} ({rate})")
        print(f"{name}: " + ", ".join(parts) if parts else f"{name}: {report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
