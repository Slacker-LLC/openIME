#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Merge the locally decoded Doubao IME lexicon into openIME's Rime data.

The Doubao lexicon is external research data and is *not* part of this
repository.  Keep it outside the checkout and pass its directory with
``--source``.  The script only writes the repository files below; check
``git diff`` afterwards and keep the data changes uncommitted.

Replacement rules ("replace whatever can be replaced", add-only coverage):

1. ``rime-data/openime_dicts/base.dict.yaml``
   - Entries that exist in both Ice and Doubao get the Doubao frequency
     mapped onto the Ice weight scale; the larger weight wins, so Doubao
     can raise a word but never demote a tuned Ice entry.
   - Every Doubao (text, pinyin) pair that Ice lacks is added.
   - The score -> weight mapping is calibrated from the pairs present on
     both sides (binned medians, monotone).
2. ``rime-data/openime_dicts/8105.dict.yaml``
   Missing single-character readings are added with weights from its own
   calibration. Existing rows keep their tuned weights: this table orders
   single-character candidates, so the import must not reshuffle it.
3. ``pinyin_phrases.tsv``
   Fully regenerated from the merged tables (2-8 Han characters, weight at
   or above ``--min-phrase-weight``, deduplicated by (pinyin, text)).
4. ``pinyin_chars.tsv``
   Existing per-syllable lists are extended with Doubao-only characters.
5. ``rime-data/openime_dicts/wubi86.dict.yaml``
   Full wubi86 table from the decoded candidate list (code -> word, weight
   mapped to the Rime scale).  The file feeds the locally added ``wubi86``
   schema; skip it by not passing a source with ``wubi86-decoded.tsv``.
6. ``handwriting-strokes.tsv``
   Stroke-sequence table for the handwriting pad (h/s/p/n/z code -> Han
   characters, characters ordered by frequency).
7. ``english-words.tsv``
   English completion word list from the decoded auxiliary table
   (lower / word / rank, sorted by lower; unranked rows are dropped).
8. ``emoji/follow-keywords.tsv``
   Association symbols per committed word from the reference's follow table
   (word / symbol / symbol ..., tab separated).  The curated
   ``emoji/keywords.tsv`` stays first in the app's merge, so it is never
   overwritten.
9. ``pinyin_reading_fixes.tsv``
   Readings for words the reference's misread-word table (its
   ``incorrectsyllable`` dictionary) reads differently from the merged base
   table, one row per corrected reading (pinyin / text / weight).  Only the
   differences are written: the table's other words already carry the right
   reading.  The app loads this small file next to ``pinyin_phrases.tsv``,
   so a correction never has to wait for the large local merge.
10. ``poem/lines.tsv``
   Lines of the reference's poetry dictionary (its ``sys_poem_gram`` table,
   whose own UI text is 「关闭联想后，将不会推荐诗词、计算结果、emoji表情。」),
   one row per line (pinyin syllables / line / the line that follows it in
   its poem).  Lines shorter than four characters are dropped, and the app
   only offers a line once the typed pinyin spells three of its syllables.

Originals are backed up once to ``.local/doubao-import/originals/`` and a
report is written to ``.local/doubao-import/report.json``.
"""

import argparse
import io
import json
import os
import re
import statistics
import sys
import unicodedata
from pathlib import Path

HAN_RE = re.compile(
    "^["
    "\u3007"
    "\u3400-\u4dbf"
    "\u4e00-\u9fff"
    "\uf900-\ufaff"
    "\U00020000-\U0002fa1f"
    "]+$"
)
PINYIN_RE = re.compile(r"^[a-z]+( [a-z]+)*$")
WUBI_CODE_RE = re.compile(r"^[a-z]{1,8}$")
STROKE_CODE_RE = re.compile(r"^[hspnz]{1,64}$")
ENGLISH_WORD_RE = re.compile(r"^[A-Za-z][A-Za-z'\-]{0,31}$")
MAX_SYLLABLES = 10
MAX_WEIGHT = 20_000_000
# A poetry line shorter than this is not worth recommending: two or three
# characters are ordinary words, and every one of them would match.
POEM_MIN_CHARS = 4


def parse_rime(path: Path):
    """Return (header_lines, entries) where entries are (text, pinyin, weight, line)."""
    header = []
    entries = []
    body = False
    with io.open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.rstrip("\n")
            if not body:
                header.append(line)
                if line.strip() == "...":
                    body = True
                continue
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 3:
                continue
            try:
                weight = int(parts[2])
            except ValueError:
                continue
            entries.append((parts[0], parts[1].strip(), weight, line))
    return header, entries


def calibrate(pairs, bins=48, min_bin=20):
    """Map Doubao scores (0..1) to Ice-like weights using binned medians."""
    pairs = sorted(pairs)
    xs = [p[0] for p in pairs]
    lo, hi = xs[0], xs[-1]
    knots = []
    if hi > lo:
        for i in range(bins):
            a = lo + (hi - lo) * i / bins
            b = lo + (hi - lo) * (i + 1) / bins
            seg = [w for s, w in pairs if a <= s < b]
            if len(seg) >= min_bin:
                knots.append([(a + b) / 2.0, float(statistics.median(seg))])
    if not knots:
        knots = [[lo, float(statistics.median(w for _, w in pairs))]]
    for i in range(1, len(knots)):
        if knots[i][1] < knots[i - 1][1]:
            knots[i][1] = knots[i - 1][1]

    def apply(score):
        if len(knots) == 1:
            w = knots[0][1]
        elif score <= knots[0][0]:
            x0, y0 = knots[0]
            k = max(0.0, y0 - 1.0) / max(x0, 1e-9)
            w = 1.0 + k * score
        elif score >= knots[-1][0]:
            (x1, y1), (x2, y2) = knots[-2], knots[-1]
            k = (y2 - y1) / max(x2 - x1, 1e-9)
            w = y2 + k * (score - x2)
        else:
            w = knots[-1][1]
            for (xa, ya), (xb, yb) in zip(knots, knots[1:]):
                if xa <= score <= xb:
                    t = (score - xa) / max(xb - xa, 1e-9)
                    w = ya + (yb - ya) * t
                    break
        return max(1, min(MAX_WEIGHT, int(round(w))))

    return apply, knots


def merge_entries(entries, doubao_score, weight_map, upgrade=True):
    """Merge rows; return rows and the number of raised weights.

    ``upgrade`` controls the max rule. Phrase tables use it (the Doubao
    frequencies live on a comparable scale and may raise a tuned entry).
    The single-character table does not: its weights order character
    candidates and are hand-tuned, so only missing readings are added.
    """
    rows = []
    upgraded = 0
    for text, py, weight, line in entries:
        score = doubao_score.get((text, py))
        if upgrade and score is not None:
            mapped = weight_map(score)
            if mapped > weight:
                weight = mapped
                line = "%s\t%s\t%d" % (text, py, weight)
                upgraded += 1
        rows.append((py, weight, text, line))
    return rows, upgraded


def write_rows(path: Path, header, rows):
    rows.sort(key=lambda r: (r[0], -r[1], r[2], r[3]))
    tmp = path.with_suffix(path.suffix + ".tmp")
    with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
        for line in header:
            f.write(line + "\n")
        for row in rows:
            f.write(row[3] + "\n")
    os.replace(tmp, path)


def toneless(syllable: str) -> str:
    """`nuó` -> `nuo`, the spelling the Rime tables use.

    A front rounded vowel keeps its diaeresis in the tonic vowel, but the
    tables spell that syllable with ``v`` (``lǚ`` is ``lv``, never ``lu``),
    so the diaeresis has to survive the tone marks.
    """
    out = []
    for character in syllable:
        if character in "üǖǘǚǜ":  # ü ǖ ǘ ǚ ǜ
            out.append("v")
            continue
        out.append("".join(c for c in unicodedata.normalize("NFD", character)
                           if not unicodedata.combining(c)))
    return "".join(out)


def build_reading_fixes(src: Path, base_entries, assets: Path, report):
    """Write pinyin_reading_fixes.tsv from the reference's misread-word table.

    ``incorrectsyllable-decoded.tsv`` names a word and, per annotated
    position, the reading that word needs.  Most of those words already carry
    that reading in the merged base table; only the ones that do not are
    written out, as the word's existing pinyin with the annotated positions
    replaced.  The app reads this file directly, so the corrections reach
    candidates without a rerun of the large tables.
    """
    path = src / "incorrectsyllable-decoded.tsv"
    if not path.is_file():
        report["reading_fixes"] = {"skipped": "incorrectsyllable-decoded.tsv not found"}
        return
    entries_by_word = {}
    for text, pinyin, weight, _ in base_entries:
        if len(text) < 2:
            continue
        syllables = tuple(pinyin.split())
        if len(syllables) == len(text):
            entries_by_word.setdefault(text, []).append((syllables, weight))

    rows = {}
    records = satisfied = unknown = 0
    with io.open(path, encoding="utf-8") as f:
        next(f)
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 4:
                continue
            word, notes = parts[2], parts[3]
            wanted = {}
            for token in notes.split():
                position, reading = token.split(":", 1)
                wanted[int(position)] = toneless(reading)
            if not HAN_RE.match(word) or not wanted or len(word) < 2:
                continue
            records += 1
            entries = entries_by_word.get(word)
            if not entries:
                unknown += 1
                continue
            if any(all(syllables[i] == reading for i, reading in wanted.items())
                   for syllables, _ in entries):
                satisfied += 1
                continue
            syllables, weight = max(entries, key=lambda e: e[1])
            fixed = [syllables[i] if i < len(syllables) else "" for i in range(len(syllables))]
            for position, reading in wanted.items():
                if position < len(fixed):
                    fixed[position] = reading
            text = " ".join(fixed)
            if not PINYIN_RE.match(text):
                continue
            key = (text, word)
            rows[key] = max(rows.get(key, 0), weight)

    if not rows:
        report["reading_fixes"] = {"records": records, "written": 0}
        return
    out = assets / "pinyin_reading_fixes.tsv"
    tmp = out.with_suffix(out.suffix + ".tmp")
    with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Readings from the reference's misread-word table, where the merged\n")
        f.write("# tables read the word differently.  Generated by scripts/import_doubao_data.py.\n")
        for (pinyin, word), weight in sorted(rows.items()):
            f.write("%s\t%s\t%d\n" % (pinyin.replace(" ", ""), word, weight))
    os.replace(tmp, out)
    report["reading_fixes"] = {
        "records": records,
        "already_correct": satisfied,
        "word_not_in_base": unknown,
        "written": len(rows),
    }
    print("reading fixes: %d rows written (%d records, %d already correct, %d words unknown)"
          % (len(rows), records, satisfied, unknown))


def build_wubi_dict(src: Path, rime: Path, report):
    """Generate openime_dicts/wubi86.dict.yaml from the decoded candidate table."""
    path = src / "wubi86-decoded.tsv"
    if not path.is_file():
        report["wubi"] = {"skipped": "wubi86-decoded.tsv not found"}
        return
    best = {}
    rows = 0
    with io.open(path, encoding="utf-8") as f:
        next(f)
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 6:
                continue
            code, word, weight = parts[1], parts[4], parts[5]
            if not WUBI_CODE_RE.match(code) or not HAN_RE.match(word):
                continue
            try:
                value = int(weight)
            except ValueError:
                continue
            rows += 1
            score = max(0.0, min(1.0, (1400.0 - value) / 1190.0))
            mapped = int(round(score * 1_000_000)) + 1
            key = (code, word)
            if mapped > best.get(key, 0):
                best[key] = mapped
    out = rime / "wubi86.dict.yaml"
    tmp = out.with_suffix(".yaml.tmp")
    with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Rime dictionary: wubi86\n")
        f.write("# Generated locally by scripts/import_doubao_data.py; do not commit.\n")
        f.write("---\nname: wubi86\nversion: \"1\"\nsort: by_weight\n...\n")
        for (code, word), weight in sorted(best.items(), key=lambda kv: (kv[0][0], -kv[1], kv[0][1])):
            f.write("%s\t%s\t%d\n" % (word, code, weight))
    os.replace(tmp, out)
    codes = set(code for code, _ in best)
    report["wubi"] = {"rows": rows, "entries": len(best), "codes": len(codes),
                      "max_code_length": max((len(c) for c in codes), default=0)}


def build_handwriting_asset(src: Path, assets: Path, doubao, report):
    """Generate handwriting-strokes.tsv: stroke code -> characters, by frequency."""
    path = src / "bihua-decoded.tsv"
    if not path.is_file():
        report["handwriting"] = {"skipped": "bihua-decoded.tsv not found"}
        return
    single_score = {}
    for text, _py, score in doubao:
        if len(text) == 1 and score > single_score.get(text, -1.0):
            single_score[text] = score
    codes = {}
    with io.open(path, encoding="utf-8") as f:
        next(f)
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 5:
                continue
            code, chars = parts[1], parts[4]
            if not STROKE_CODE_RE.match(code):
                continue
            bucket = codes.get(code)
            if bucket is None:
                bucket = codes[code] = []
            seen = set(bucket)
            for ch in chars:
                if ch in seen or not HAN_RE.match(ch):
                    continue
                bucket.append(ch)
                seen.add(ch)
    total = 0
    for bucket in codes.values():
        bucket.sort(key=lambda ch: -single_score.get(ch, 0.0))
        total += len(bucket)
    out = assets / "handwriting-strokes.tsv"
    tmp = out.with_suffix(".tsv.tmp")
    with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write("# stroke code (h s p n z) to Han characters, most frequent first\n")
        for code, chars in sorted(codes.items()):
            f.write("%s\t%s\n" % (code, "".join(chars)))
    os.replace(tmp, out)
    report["handwriting"] = {"codes": len(codes), "entries": total}


def build_english_asset(src: Path, assets: Path, report):
    """Generate english-words.tsv: lower / word / rank, sorted by lower.

    The auxiliary table carries a frequency rank (1 = most common); rank 0
    means unranked and those rows are dropped, so completions come from a
    compact ranked list instead of 118k unordered entries.
    """
    path = src / "englishsys-aux-decoded.tsv"
    if not path.is_file():
        report["english"] = {"skipped": "englishsys-aux-decoded.tsv not found"}
        return
    best = {}
    total = 0
    with io.open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 7 or parts[0] == "rec_idx":
                continue
            word = parts[2]
            if not ENGLISH_WORD_RE.match(word):
                continue
            try:
                rank = int(parts[5])
            except ValueError:
                continue
            total += 1
            if rank <= 0:
                continue
            key = (word.lower(), word)
            if rank < best.get(key, 1 << 30):
                best[key] = rank
    out = assets / "english-words.tsv"
    tmp = out.with_suffix(".tsv.tmp")
    with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write("# English completion words: lower / word / rank (1 = most common; local build data)\n")
        for (lower, word), rank in sorted(best.items(), key=lambda kv: (kv[0][0], kv[1], kv[0][1])):
            f.write("%s\t%s\t%d\n" % (lower, word, rank))
    os.replace(tmp, out)
    report["english"] = {"entries": len(best), "source_rows": total}


def build_emoji_association_asset(src: Path, assets: Path, report):
    """Generate emoji/follow-keywords.tsv: word <tab> symbol <tab> symbol ...

    follow_dict.dat holds the association lists the reference shows after a
    word is committed: the key is the committed word, the first list is the
    symbols that go with it (中国 -> 🇨🇳, 火 -> 🔥).  The decoded TSV in the
    teardown joins that list with '|', which the file itself does not treat as
    a separator — one of its rows *is* the '|' symbol — so read the raw table
    out of the APK instead (structure: DECODE-BATCH-C.md §3).

    Items are tab separated here so a kaomoji with a space in it survives as
    one chip. The curated emoji/keywords.tsv stays the first table the app
    reads, so a keyword both files carry keeps its curated emoji.
    """
    apk = src / "base.apk"
    if not apk.is_file():
        report["emoji_association"] = {"skipped": "base.apk not found"}
        return
    import struct
    import zipfile

    with zipfile.ZipFile(apk) as archive:
        blob = archive.read("assets/dict/84ac6a563f50")
    head = struct.unpack("<16I", blob[:64])
    if head[0] != 12037 or head[2] != 0x30 or head[3] != len(blob):
        report["emoji_association"] = {"skipped": "unexpected follow_dict header"}
        return
    count = head[4]
    index = blob[head[5]:head[5] + head[6]]
    data = blob[head[8]:head[8] + head[9]]
    nodes = [struct.unpack("<HII", index[i * 10:i * 10 + 10]) for i in range(count)]

    # Breadth-first node order: a node's children are the index rows between
    # its own first_child and the next node's, so one forward pass writes
    # every key as the parent's key plus the node's UTF-16 code unit.
    keys = {0: ""}
    for i in range(count - 1):
        begin, end = nodes[i][1], nodes[i + 1][1]
        parent = keys.get(i)
        if parent is None or begin >= end:
            continue
        for child in range(begin, min(end, count)):
            keys[child] = parent + chr(nodes[child][0])

    rows = {}
    bad = 0
    for i in range(1, count):
        word = keys.get(i)
        if word is None:  # the last index row is the trie's sentinel
            continue
        offset = nodes[i][2]
        stop = nodes[i + 1][2] if i + 1 < count else len(data)
        block = data[offset:stop]
        if not block:
            continue
        cursor = 4
        symbols = []
        (n1,) = struct.unpack("<H", block[cursor:cursor + 2])
        cursor += 2
        for _ in range(n1):
            (length,) = struct.unpack("<H", block[cursor:cursor + 2])
            cursor += length + 2
            if length % 2 or cursor > len(block):
                bad += 1
                break
            symbols.append(block[cursor - length:cursor].decode("utf-16-le"))
        if not symbols:
            continue
        rows[word] = [item for item in symbols if item and "\t" not in item]

    out = assets / "emoji" / "follow-keywords.tsv"
    tmp = out.with_suffix(".tsv.tmp")
    with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Association symbols per committed word, from the reference's follow_dict.dat\n")
        f.write("# (local build data). First column is the word, the rest are its symbols,\n")
        f.write("# tab separated so a symbol may contain a space. See DECODE-BATCH-C.md §3.\n")
        for word in sorted(rows):
            f.write("%s\t%s\n" % (word, "\t".join(rows[word])))
    os.replace(tmp, out)
    report["emoji_association"] = {
        "entries": len(rows),
        "symbols": sum(len(v) for v in rows.values()),
        "bad_blocks": bad,
    }


def build_poems(src: Path, assets: Path, report):
    """Write poem/lines.tsv from the reference's poetry dictionary.

    ``poem-decoded.tsv`` carries every line of the reference's ``sys_poem_gram``
    table (5,966 lines) with the syllables it is typed by, and
    ``poem-assoc.tsv`` the line that follows it in its own poem.  The app
    offers a line as a candidate as soon as the typed pinyin spells three of
    its syllables, so only lines that read cleanly are useful: four Han
    characters or more, one syllable per character, no punctuation inside.

    The following line is written next to its own line so a whole line the
    user typed out can be continued (`春眠不觉晓` -> `处处闻啼鸟`).
    """
    decoded, assoc = src / "poem-decoded.tsv", src / "poem-assoc.tsv"
    if not decoded.is_file():
        report["poems"] = {"skipped": "poem-decoded.tsv not found"}
        return

    texts, rows, by_id = {}, {}, {}
    with io.open(decoded, encoding="utf-8") as f:
        next(f)
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 8:
                continue
            _, _, line_id, nchar, nsyl, _, syllables, text = parts[:8]
            syllables = syllables.strip('"').split()
            texts[int(line_id)] = text
            if (int(nchar) < POEM_MIN_CHARS or len(syllables) != int(nchar)
                    or not all(s.isalpha() for s in syllables) or not HAN_RE.match(text)):
                continue
            key = (tuple(syllables), text)
            rows[key] = ""
            by_id[int(line_id)] = key

    if assoc.is_file():
        with io.open(assoc, encoding="utf-8") as f:
            next(f)
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 8 or parts[4] != "0":
                    continue  # one entry per group is enough: the nearest line
                key = by_id.get(int(parts[2]))
                refs = [int(x) for x in parts[6].split()]
                follow = texts.get(refs[0]) if refs else None
                if key is not None and follow and HAN_RE.match(follow):
                    rows[key] = follow

    if not rows:
        report["poems"] = {"written": 0}
        return
    out = assets / "poem" / "lines.tsv"
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(out.suffix + ".tmp")
    with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Lines of the reference's poetry dictionary (sys_poem_gram), the\n")
        f.write("# syllables they are typed by, and the line that follows each one.\n")
        f.write("# Generated by scripts/import_doubao_data.py.\n")
        for (syllables, text), follow in sorted(rows.items()):
            f.write("%s\t%s\t%s\n" % (" ".join(syllables), text, follow))
    os.replace(tmp, out)
    report["poems"] = {"lines": len(rows), "continued": sum(1 for v in rows.values() if v)}
    print("poems: %d lines written (%d with a following line)"
          % (len(rows), sum(1 for v in rows.values() if v)))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", default=str(Path(__file__).resolve().parent.parent))
    ap.add_argument("--source", default=os.environ.get("DOUBAO_DATA", "/shared/doubaoime-teardown"))
    ap.add_argument("--min-phrase-weight", type=int, default=100_000)
    ap.add_argument("--max-phrases", type=int, default=120_000)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--reading-fixes-only", action="store_true",
                    help="write pinyin_reading_fixes.tsv and stop, without rewriting the tables")
    ap.add_argument("--poems-only", action="store_true",
                    help="write poem/lines.tsv and stop, without rewriting the tables")
    args = ap.parse_args()

    repo = Path(args.repo)
    src = Path(args.source)
    lexicon_path = src / "ime-lexicon.tsv"
    if not lexicon_path.is_file():
        sys.exit("missing %s (pass --source)" % lexicon_path)

    rime = repo / "app" / "src" / "main" / "assets" / "rime-data" / "openime_dicts"
    assets = repo / "app" / "src" / "main" / "assets"
    base_path = rime / "base.dict.yaml"
    char_path = rime / "8105.dict.yaml"
    phrases_path = assets / "pinyin_phrases.tsv"
    chars_path = assets / "pinyin_chars.tsv"

    backup = repo / ".local" / "doubao-import" / "originals"
    backup.mkdir(parents=True, exist_ok=True)
    for src_path in (base_path, char_path, phrases_path, chars_path):
        dst = backup / src_path.name
        if not dst.exists() and src_path.exists():
            dst.write_bytes(src_path.read_bytes())

    report = {"source": str(src), "backup": str(backup)}

    print("[1/5] parse Ice tables ...")
    # Always calibrate and merge against the pristine Ice sources: reading the
    # (possibly already merged) working files would let the score -> weight
    # mapping feed on its own output and inflate weights on every rerun.
    base_source = backup / "base.dict.yaml"
    char_source = backup / "8105.dict.yaml"
    base_header, base_entries = parse_rime(base_source if base_source.is_file() else base_path)
    char_header, char_entries = parse_rime(char_source if char_source.is_file() else char_path)
    base_pairs = set((t, p) for t, p, _, _ in base_entries)
    char_pairs = set((t, p) for t, p, _, _ in char_entries)
    report["ice_base_entries"] = len(base_entries)
    report["ice_char_entries"] = len(char_entries)

    print("[1b/5] write reading fixes ...")
    if not args.dry_run:
        build_reading_fixes(src, base_entries, assets, report)
    if args.reading_fixes_only:
        (repo / ".local" / "doubao-import" / "report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return

    print("[1c/5] write poem lines ...")
    if not args.dry_run:
        build_poems(src, assets, report)
    if args.poems_only:
        (repo / ".local" / "doubao-import" / "report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return

    print("[2/5] read Doubao lexicon ...")
    doubao = []  # (text, pinyin, score)
    with io.open(lexicon_path, encoding="utf-8") as f:
        next(f)
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 4:
                continue
            text, py, score = parts[0], parts[1], parts[2]
            if not HAN_RE.match(text):
                continue
            if not PINYIN_RE.match(py):
                continue
            if py.count(" ") + 1 > MAX_SYLLABLES:
                continue
            doubao.append((text, py, float(score)))
    report["doubao_rows"] = len(doubao)
    doubao_score = dict(((t, p), s) for t, p, s in doubao)

    print("[3/5] calibrate score -> weight ...")
    join = []
    for t, p, w, _ in base_entries:
        s = doubao_score.get((t, p))
        if s is not None:
            join.append((s, w))
    phrase_map, phrase_knots = calibrate(join)
    report["phrase_knots"] = [[round(k[0], 4), int(k[1])] for k in phrase_knots]
    report["phrase_join_pairs"] = len(join)

    join_chars = []
    for t, p, w, _ in char_entries:
        s = doubao_score.get((t, p))
        if s is not None:
            join_chars.append((s, w))
    char_map, char_knots = calibrate(join_chars)
    report["char_knots"] = [[round(k[0], 4), int(k[1])] for k in char_knots]
    report["char_join_pairs"] = len(join_chars)

    print("[4/5] merge dictionaries ...")
    base_rows, base_upgraded = merge_entries(base_entries, doubao_score, phrase_map)
    char_rows, char_upgraded = merge_entries(char_entries, doubao_score, char_map, upgrade=False)
    report["base_weight_upgraded"] = base_upgraded
    report["char_weight_upgraded"] = char_upgraded

    base_add = []
    char_add = []
    for text, py, score in doubao:
        if len(text) == 1:
            if (text, py) not in char_pairs:
                # Added readings enter below the tuned band: 8105 orders single
                # character candidates, and an unlisted reading is rare by
                # construction (variants, dialect characters, extra readings).
                char_add.append((text, py, max(1, int(round(score * 1000)))))
        else:
            if (text, py) not in base_pairs:
                base_add.append((text, py, phrase_map(score)))
    report["base_additions"] = len(base_add)
    report["char_additions"] = len(char_add)
    for text, py, weight in base_add:
        base_rows.append((py, weight, text, "%s\t%s\t%d" % (text, py, weight)))
    for text, py, weight in char_add:
        char_rows.append((py, weight, text, "%s\t%s\t%d" % (text, py, weight)))

    if not args.dry_run:
        write_rows(base_path, base_header, base_rows)
        write_rows(char_path, char_header, char_rows)

    print("[5/5] regenerate fast files ...")
    merged = [(r[2], r[0], r[1]) for r in base_rows + char_rows]
    phrase_entries = {}
    for text, py, w in merged:
        if not (2 <= len(text) <= 8):
            continue
        if not HAN_RE.match(text):
            continue
        if w < args.min_phrase_weight:
            continue
        key = (py, text)
        if w > phrase_entries.get(key, 0):
            phrase_entries[key] = w
    while len(phrase_entries) > args.max_phrases:
        weights = sorted(phrase_entries.values(), reverse=True)
        cut = weights[args.max_phrases]
        phrase_entries = {k: v for k, v in phrase_entries.items() if v >= cut}
    report["phrase_entries"] = len(phrase_entries)
    if not args.dry_run:
        rows = sorted(phrase_entries.items(), key=lambda kv: (kv[0][0], -kv[1], kv[0][1]))
        tmp = phrases_path.with_suffix(".tsv.tmp")
        with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
            f.write("# generated from bundled Rime Ice + local Doubao merge; minimum weight=%d\n"
                    % args.min_phrase_weight)
            for (py, text), w in rows:
                f.write("%s\t%s\t%d\n" % (py.replace(" ", ""), text, w))
        os.replace(tmp, phrases_path)

    char_keys = []
    existing = {}
    with io.open(chars_path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            py, chars = line.split("\t", 1)
            char_keys.append(py)
            existing[py] = set(chars)
    char_extended = 0
    for text, py, _ in doubao:
        if len(text) != 1 or " " in py:
            continue
        bucket = existing.get(py)
        if bucket is None or text in bucket:
            continue
        bucket.add(text)
        char_extended += 1
    report["pinyin_chars_added"] = char_extended
    if not args.dry_run:
        tmp = chars_path.with_suffix(".tsv.tmp")
        with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
            for py in char_keys:
                f.write("%s\t%s\n" % (py, "".join(sorted(existing[py], key=ord))))
        os.replace(tmp, chars_path)

    print("[6/9] build wubi86 dictionary ...")
    build_wubi_dict(src, rime, report)
    print("[7/9] build handwriting stroke asset ...")
    build_handwriting_asset(src, assets, doubao, report)
    print("[8/9] build English word list ...")
    build_english_asset(src, assets, report)
    print("[9/9] build emoji association keywords ...")
    build_emoji_association_asset(src, assets, report)

    sample = {}
    for probe in ("上海", "伤害", "你好", "我想", "微信"):
        s = doubao_score.get((probe, next((p for t, p, _ in doubao if t == probe), "")))
        if s is not None:
            sample[probe] = phrase_map(s)
    report["sanity_mapped_weights"] = sample
    report_path = repo / ".local" / "doubao-import" / "report.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")

    print("base: weight-upgraded %d, +%d new (was %d entries)"
          % (base_upgraded, len(base_add), len(base_entries)))
    print("8105: weight-upgraded %d, +%d new (was %d entries)"
          % (char_upgraded, len(char_add), len(char_entries)))
    print("phrases: %d entries; pinyin_chars: +%d chars"
          % (report["phrase_entries"], char_extended))
    if "entries" in report.get("wubi", {}):
        print("wubi86: %d entries over %d codes (max code length %d)"
              % (report["wubi"]["entries"], report["wubi"]["codes"], report["wubi"]["max_code_length"]))
    if "entries" in report.get("handwriting", {}):
        print("handwriting: %d codes / %d char slots"
              % (report["handwriting"]["codes"], report["handwriting"]["entries"]))
    if "entries" in report.get("english", {}):
        print("english: %d words" % report["english"]["entries"])
    if "entries" in report.get("emoji_association", {}):
        print("emoji association: %d words / %d symbols"
              % (report["emoji_association"]["entries"], report["emoji_association"]["symbols"]))
    print("mapped sanity:", sample)
    print("report: %s" % report_path)
    if args.dry_run:
        print("(dry run: no data file was written)")


if __name__ == "__main__":
    main()
