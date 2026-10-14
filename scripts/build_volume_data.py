#!/usr/bin/env python3
"""Build the input lists for the volume E2E harness (scripts/typing_volume_e2e.py).

Everything comes from data already on this machine: the bundled dictionaries
(base.dict.yaml, 8105.dict.yaml, wubi86.dict.yaml, english-words.tsv) and the
Chinese/English prose in this repository's own documents. Nothing is
downloaded and nothing is committed; the output lands in build/volume-data/.

    python3 scripts/build_volume_data.py [--out DIR]

Files written (tab separated, no header):

  words.tsv          word, pinyin           1000 common two- and three-character words
  four.tsv           word, pinyin            500 four-character words (many are idioms)
  sentences.tsv      sentence, pinyin        100 Chinese sentences of 15+ characters
  wubi.tsv           word, code              500 words with their wubi86 code
  chars.tsv          char, pinyin           1000 single characters by frequency
  english.tsv        word                    1000 English completion words by rank
  en-sentences.tsv   sentence                100 English sentences of 15+ characters
"""

from __future__ import annotations

import argparse
import random
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
DICTS = ASSETS / "rime-data" / "openime_dicts"
CJK = re.compile(r"^[㐀-鿿豈-﫿]+$")
SYLLABLE = re.compile(r"^[a-z]+$")
SENTENCE_CHARS = re.compile(r"^[㐀-鿿豈-﫿，。！？、；：（）《》“”‘’…—]+$")
LATIN_SENTENCE = re.compile(r"^[A-Za-z0-9 ,.'()/\-+#%:_\[\]]+$")


# Well-known idioms the dictionaries carry; the dictionary's own reading is used.
IDIOMS = (
    "一心一意 三心二意 画蛇添足 掩耳盗铃 刻舟求剑 守株待兔 走马观花 锦上添花 半途而废 百发百中 "
    "大器晚成 一箭双雕 亡羊补牢 对牛弹琴 杯弓蛇影 井底之蛙 鹤立鸡群 瓜熟蒂落 流水不腐 户枢不蠹 "
    "塞翁失马 杞人忧天 叶公好龙 指鹿为马 望梅止渴 骑虎难下 如鱼得水 雪中送炭 锦囊妙计 抛砖引玉 "
    "破釜沉舟 卧薪尝胆 负荆请罪 完璧归赵 四面楚歌 三顾茅庐 草木皆兵 风声鹤唳 势如破竹 如火如荼 "
    "水到渠成 水滴石穿 铁杵成针 悬梁刺股 凿壁偷光 囊萤映雪 精益求精 融会贯通 举一反三 循序渐进 "
    "温故知新 学以致用 实事求是 一丝不苟 兢兢业业 任重道远 同心协力 众志成城 各抒己见 畅所欲言"
).split()


def read_dict(path: Path, per_character_syllables: bool = True) -> list[tuple[str, str, int]]:
    """Entries of a Rime dictionary; the wubi table stores one code, not syllables."""
    entries: list[tuple[str, str, int]] = []
    inside = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("..."):
            inside = True
            continue
        if not inside or not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        word, pinyin, weight = parts[0], parts[1], parts[2]
        if not CJK.match(word) or not weight.isdigit():
            continue
        syllables = pinyin.split()
        if not syllables or not all(SYLLABLE.match(s) for s in syllables):
            continue
        if per_character_syllables and len(syllables) != len(word):
            continue
        entries.append((word, pinyin, int(weight)))
    return entries


def spread(entries: list[tuple[str, str, int]], count: int, seed: int) -> list[tuple[str, str, int]]:
    """Take [count] entries spread over the list instead of one weight band."""
    if len(entries) <= count:
        return entries
    step = len(entries) / count
    return [entries[int(i * step)] for i in range(count)]


def chinese_sentences(limit: int, minimum: int = 15) -> list[str]:
    """Clauses of fifteen characters or more from this repository's Chinese prose."""
    found: list[str] = []
    seen: set[str] = set()
    sources = [ROOT / "CHANGELOG.md", ROOT / "README.zh-CN.md"]
    sources += sorted((ROOT / "docs").glob("*.md"))
    for path in sources:
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        for line in text.splitlines():
            clauses: list[str] = []
            for piece in re.split(r"[。！？；，、,:：\n]", line):
                sentence = piece.strip().strip(">*-—· 　")
                if any(mark in sentence for mark in "`[]()|/\\<>#\"'"):
                    continue
                if not SENTENCE_CHARS.match(sentence):
                    continue
                clauses.append(sentence)
            # Long documents are written in clauses shorter than the requested
            # length, so neighbouring clauses of one line are joined with a
            # comma until the sentence is long enough.
            sentence = ""
            for clause in clauses:
                if len(sentence) + len(clause) > 26:
                    if len(sentence) >= minimum and sentence not in seen:
                        seen.add(sentence)
                        found.append(sentence)
                    sentence = ""
                sentence = f"{sentence}，{clause}" if sentence else clause
            if len(sentence) >= minimum and sentence not in seen:
                seen.add(sentence)
                found.append(sentence)
    return found[:limit]


def english_sentences(limit: int, minimum: int = 15) -> list[str]:
    found: list[str] = []
    seen: set[str] = set()
    for name in ("README.md", "docs/RELEASE.md", "docs/DECISIONS.md", "docs/REPOSITORY.md"):
        path = ROOT / name
        if not path.is_file():
            continue
        for piece in re.split(r"[.!?]\s|\n", path.read_text(encoding="utf-8")):
            sentence = piece.strip().strip(">*-—# \t")
            if not (minimum <= len(sentence) <= 80):
                continue
            if sentence.count(" ") < 3 or not LATIN_SENTENCE.match(sentence):
                continue
            if sum(ch.isalpha() for ch in sentence) < minimum * 0.7:
                continue
            if sentence in seen:
                continue
            seen.add(sentence)
            found.append(sentence)
    return found[:limit]


def build_pinyin_lookup(chars: list[tuple[str, str, int]], words: list[tuple[str, str, int]]) -> dict[str, str]:
    """Longest-match syllable source: the dictionaries' own readings."""
    lookup: dict[str, str] = {}
    for word, pinyin, _ in words:
        lookup.setdefault(word, pinyin)
    for char, pinyin, _ in chars:
        lookup.setdefault(char, pinyin)
    return lookup


def sentence_pinyin(sentence: str, lookup: dict[str, str], longest: int = 6) -> str | None:
    """Read the sentence as spoken pinyin, splitting on the dictionary's words."""
    syllables: list[str] = []
    index = 0
    while index < len(sentence):
        for size in range(min(longest, len(sentence) - index), 0, -1):
            piece = sentence[index:index + size]
            pinyin = lookup.get(piece)
            if pinyin is None:
                continue
            if not all(SYLLABLE.match(part) for part in pinyin.split()):
                continue
            syllables += pinyin.split()
            index += size
            break
        else:
            return None
    return " ".join(syllables)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=ROOT / "build" / "volume-data")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    base = read_dict(DICTS / "base.dict.yaml")
    chars_8105 = read_dict(DICTS / "8105.dict.yaml")
    wubi = read_dict(DICTS / "wubi86.dict.yaml", per_character_syllables=False)

    # Words: two and three characters, spread over the whole list so both very
    # common and tail vocabulary show up.
    by_length: dict[int, list[tuple[str, str, int]]] = {2: [], 3: []}
    for entry in sorted(base, key=lambda item: -item[2]):
        if len(entry[0]) in by_length:
            by_length[len(entry[0])].append(entry)
    words = spread(by_length[2], 700, 0) + spread(by_length[3], 300, 1)
    (args.out / "words.tsv").write_text(
        "".join(f"{word}\t{pinyin}\n" for word, pinyin, _ in words), encoding="utf-8",
    )

    four = spread([entry for entry in sorted(base, key=lambda item: -item[2]) if len(entry[0]) == 4], 450, 2)
    readings = {word: pinyin for word, pinyin, _ in base}
    curated = [
        (word, readings[word]) for word in IDIOMS if word in readings
    ]
    four = [(word, pinyin, 0) for word, pinyin in curated] + four[: max(0, 500 - len(curated))]
    (args.out / "four.tsv").write_text(
        "".join(f"{word}\t{pinyin}\n" for word, pinyin, _ in four), encoding="utf-8",
    )

    lookup = build_pinyin_lookup(chars_8105, base)
    sentences = []
    for sentence in chinese_sentences(400):
        # The harness types syllables and compares characters, so the target
        # text carries the characters alone.
        text = "".join(ch for ch in sentence if CJK.match(ch))
        if not 15 <= len(text) <= 26:
            continue
        pinyin = sentence_pinyin(text, lookup)
        if pinyin and len(pinyin) <= 90:
            sentences.append((text, pinyin))
        if len(sentences) >= 100:
            break
    (args.out / "sentences.tsv").write_text(
        "".join(f"{sentence}\t{pinyin}\n" for sentence, pinyin in sentences), encoding="utf-8",
    )

    wubi_words = [entry for entry in wubi if len(entry[0]) in (2, 3) and 2 <= len(entry[1]) <= 4]
    wubi_sample = spread(sorted(wubi_words, key=lambda item: -item[2]), 500, 3)
    (args.out / "wubi.tsv").write_text(
        "".join(f"{word}\t{code}\n" for word, code, _ in wubi_sample), encoding="utf-8",
    )

    chars = spread(sorted(chars_8105, key=lambda item: -item[2])[:3000], 1000, 4)
    (args.out / "chars.tsv").write_text(
        "".join(f"{char}\t{pinyin}\n" for char, pinyin, _ in chars), encoding="utf-8",
    )

    english: list[tuple[str, str, int]] = []
    for line in (ASSETS / "english-words.tsv").read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) == 3:
            english.append((parts[0], parts[1], int(parts[2])))
    english_sample = spread(english, 1000, 5)
    (args.out / "english.tsv").write_text(
        "".join(f"{lower}\n" for lower, _, _ in english_sample), encoding="utf-8",
    )
    random.Random(6).shuffle(english_sample)

    en_sentences = english_sentences(100)
    (args.out / "en-sentences.tsv").write_text(
        "".join(f"{sentence}\n" for sentence in en_sentences), encoding="utf-8",
    )

    for name in ("words", "four", "sentences", "wubi", "chars", "english", "en-sentences"):
        path = args.out / f"{name}.tsv"
        print(f"{name}: {len(path.read_text(encoding='utf-8').splitlines())} lines")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
