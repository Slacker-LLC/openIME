#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Compile openIME's built-in pinyin engine tables from the decoded sources.

This script is the only bridge between the external, read-only decoded data
(``--source``) and the binaries that ship in the APK assets (``--out``):

  lex.bin     word index: syllable table, syllable-id sequence per word,
              UTF-8 text blob and 0..10000 scores.
  bigram.bin  word-pair table keyed by ``(idA << 32) | idB``.

Both files are little endian.  Every lex.bin section starts on a 4-byte
boundary, the bigram key array starts on an 8-byte boundary::

  lex.bin    u32 magic 0x31454D49 ("IME1"), u32 word_count,
             u32 syllable_count, u32 off_syllables, u32 off_syl_offsets,
             u32 off_syl_ids, u32 off_text_offsets, u32 off_text_blob,
             u32 off_scores, u32 text_blob_size           (= 40 byte header)
             syllables:     u8 length + ASCII bytes per syllable, in
                            syllable-id-map.tsv order (id = position)
             syl_offsets:   u32[word_count + 1], first element index of each
                            word's syllable-id sequence inside syl_ids
             syl_ids:       u16[last syl_offsets entry], ids concatenated
             text_offsets:  u32[word_count + 1], start of each word's UTF-8
                            text inside text_blob
             text_blob:     UTF-8 texts concatenated
             scores:        u16[word_count], round(score * 10000)

  bigram.bin u32 magic 0x31424D49 ("IMB1"), u32 pair_count,
             u64[pair_count] keys (ascending), u16[pair_count] scores

Words are ordered by syllable-id sequence (element-wise, shorter first), then
by score descending, then by text ascending.  Bigram pairs are ordered by
key ascending.

A build writes ``<name>.tmp`` first and finishes with ``os.replace``, so a
failed build never leaves a truncated binary behind.  Afterwards both files
are read back and the sanity checks are printed and appended to the build
report (``<out>/编译报告.txt``).

Inputs (columns are tab separated; the first line of ime-lexicon.tsv and
ime-bigram.tsv is a header):
  ime-lexicon.tsv      word / pinyin / score / weight / src
  syllable-id-map.tsv  id / syllable / ...   syllable catalogue
  ime-bigram.tsv       w1 / w2 / score / src
"""

import argparse
import io
import os
import re
import struct
import sys
import time
from array import array
from bisect import bisect_left
from pathlib import Path

MAGIC_LEX = 0x31454D49     # "IME1" little endian
MAGIC_BIGRAM = 0x31424D49  # "IMB1" little endian
LEX_HEADER = struct.Struct("<10I")
BIGRAM_HEADER = struct.Struct("<2I")

ALIGN = 4                  # every lex.bin section starts on a 4-byte boundary
SYLLABLE_RE = re.compile(r"[a-z]{1,6}")
MAX_SYLLABLES = 10         # drop rows with more syllables than this
LEX_SCORE_MAX = 10000      # round(score * 10000)
BIGRAM_SCORE_MAX = 65535   # round(score * 65535)

SPOT_WORDS = ("你好", "上海", "微信", "我想")
SPOT_BIGRAMS = (("你", "好"), ("上海", "什么"))
EXTRA_BIGRAMS = (("今天", "天气"), ("你好", "自私"))
BISECT_PROBES = (("ni hao", "你好"), ("shang hai", "上海"))

REPORT_NAME = "编译报告.txt"

assert array("H").itemsize == 2, "u16 arrays must be 2 bytes"
assert array("I").itemsize == 4, "u32 arrays must be 4 bytes"
assert array("Q").itemsize == 8, "u64 arrays must be 8 bytes"


def le_bytes(values, code):
    """Return the little-endian byte string of an array of ``code`` items."""
    buf = array(code, values)
    if sys.byteorder != "little":
        buf.byteswap()
    return buf.tobytes()


def read_le_array(data, offset, count, code):
    """Read ``count`` little-endian items of ``code`` starting at ``offset``."""
    buf = array(code)
    buf.frombytes(bytes(data[offset:offset + count * buf.itemsize]))
    if sys.byteorder != "little":
        buf.byteswap()
    return buf


def atomic_write(path, data):
    """Write ``path`` through ``path.tmp`` + os.replace."""
    tmp = path.with_name(path.name + ".tmp")
    with io.open(tmp, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(tmp, path)


def load_syllables(path):
    """Parse syllable-id-map.tsv.

    Rows whose syllable column matches ``^[a-z]{1,6}$`` are kept and numbered
    0..N-1 in file order.  A syllable that occurs several times keeps every
    row in the table (ids stay stable) but maps to its first id when words
    are encoded.

    Returns ``(syllables, syllable_to_id, rows, duplicates)``.
    """
    syllables = []
    syllable_to_id = {}
    duplicates = []
    rows = 0
    with io.open(path, encoding="utf-8") as stream:
        for line in stream:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2:
                continue
            rows += 1
            syllable = parts[1]
            if SYLLABLE_RE.fullmatch(syllable) is None:
                continue
            if syllable in syllable_to_id:
                duplicates.append(syllable)
            else:
                syllable_to_id[syllable] = len(syllables)
            syllables.append(syllable)
    return syllables, syllable_to_id, rows, duplicates


def build_lexicon(path, syllable_to_id, spot_words):
    """Parse ime-lexicon.tsv into ``word -> (score_int, syllable ids)``.

    Rows with an empty pinyin, an unknown syllable, more than MAX_SYLLABLES
    syllables or an unreadable score are dropped and counted by reason.  The
    table has unique words; should a duplicate appear, the highest score wins.
    """
    entries = {}
    spot_scores = {}
    stats = {
        "rows": 0,
        "kept": 0,
        "dropped_bad_row": 0,
        "dropped_empty_pinyin": 0,
        "dropped_unknown_syllable": 0,
        "dropped_too_many_syllables": 0,
        "dropped_bad_score": 0,
        "duplicate_words_merged": 0,
        "unknown_syllables": set(),
    }
    with io.open(path, encoding="utf-8") as stream:
        for line_no, line in enumerate(stream):
            if line_no == 0 and line.startswith("word\t"):
                continue
            stats["rows"] += 1
            parts = line.rstrip("\n").split("\t")
            word = parts[0]
            if len(parts) < 3 or not word:
                stats["dropped_bad_row"] += 1
                continue
            tokens = parts[1].split()
            if not tokens:
                stats["dropped_empty_pinyin"] += 1
                continue
            try:
                score = float(parts[2])
            except ValueError:
                stats["dropped_bad_score"] += 1
                continue
            ids = []
            unknown = False
            for token in tokens:
                syllable_id = syllable_to_id.get(token)
                if syllable_id is None:
                    stats["unknown_syllables"].add(token)
                    unknown = True
                    break
                ids.append(syllable_id)
            if unknown:
                stats["dropped_unknown_syllable"] += 1
                continue
            if len(ids) > MAX_SYLLABLES:
                stats["dropped_too_many_syllables"] += 1
                continue
            score_int = int(round(score * LEX_SCORE_MAX))
            score_int = max(0, min(LEX_SCORE_MAX, score_int))
            previous = entries.get(word)
            if previous is None:
                entries[word] = (score_int, tuple(ids))
                stats["kept"] += 1
            else:
                stats["duplicate_words_merged"] += 1
                if score_int > previous[0]:
                    entries[word] = (score_int, tuple(ids))
            if word in spot_words:
                spot_scores[word] = score
    return entries, stats, spot_scores


def sort_lexicon(entries):
    """Order words by syllable ids, then score descending, then text."""
    return sorted(entries, key=lambda word: (entries[word][1], -entries[word][0], word))


def encode_lex(words, entries, syllables):
    """Serialize the complete lex.bin image."""
    syllable_blob = bytearray()
    for syllable in syllables:
        raw = syllable.encode("ascii")
        syllable_blob.append(len(raw))
        syllable_blob += raw

    syl_offsets = array("I", [0])
    syl_ids = array("H")
    text_offsets = array("I", [0])
    text_blob = bytearray()
    scores = array("H")
    for word in words:
        score_int, ids = entries[word]
        syl_ids.extend(ids)
        syl_offsets.append(len(syl_ids))
        text_blob += word.encode("utf-8")
        text_offsets.append(len(text_blob))
        scores.append(score_int)

    body = bytearray()
    offsets = {}

    def add(name, blob, pad=True):
        offsets[name] = LEX_HEADER.size + len(body)
        body.extend(blob)
        if pad:
            body.extend(b"\x00" * (-(LEX_HEADER.size + len(body)) % ALIGN))

    add("syllables", bytes(syllable_blob))
    add("syl_offsets", le_bytes(syl_offsets, "I"))
    add("syl_ids", le_bytes(syl_ids, "H"))
    add("text_offsets", le_bytes(text_offsets, "I"))
    add("text_blob", bytes(text_blob))
    add("scores", le_bytes(scores, "H"), pad=False)

    header = LEX_HEADER.pack(
        MAGIC_LEX,
        len(words),
        len(syllables),
        offsets["syllables"],
        offsets["syl_offsets"],
        offsets["syl_ids"],
        offsets["text_offsets"],
        offsets["text_blob"],
        offsets["scores"],
        len(text_blob),
    )
    return bytes(header + body)


def build_bigram(path, word_to_id, check_pairs):
    """Parse ime-bigram.tsv into ``key -> score_int`` with max-score dedupe."""
    pairs = {}
    stats = {
        "rows": 0,
        "kept": 0,
        "dropped_bad_row": 0,
        "dropped_bad_score": 0,
        "dropped_w1_missing": 0,
        "dropped_w2_missing": 0,
        "dropped_both_missing": 0,
        "duplicate_pairs_merged": 0,
        "min_key": None,
        "min_key_score": None,
    }
    source_check = {}
    with io.open(path, encoding="utf-8") as stream:
        for line_no, line in enumerate(stream):
            if line_no == 0 and line.startswith("w1\t"):
                continue
            stats["rows"] += 1
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                stats["dropped_bad_row"] += 1
                continue
            w1, w2 = parts[0], parts[1]
            try:
                score = float(parts[2])
            except ValueError:
                stats["dropped_bad_score"] += 1
                continue
            id1 = word_to_id.get(w1)
            id2 = word_to_id.get(w2)
            if id1 is None and id2 is None:
                stats["dropped_both_missing"] += 1
            elif id1 is None:
                stats["dropped_w1_missing"] += 1
            elif id2 is None:
                stats["dropped_w2_missing"] += 1
            else:
                key = (id1 << 32) | id2
                score_int = int(round(score * BIGRAM_SCORE_MAX))
                score_int = max(0, min(BIGRAM_SCORE_MAX, score_int))
                previous = pairs.get(key)
                if previous is None:
                    pairs[key] = score_int
                    stats["kept"] += 1
                else:
                    stats["duplicate_pairs_merged"] += 1
                    if score_int > previous:
                        pairs[key] = score_int
                if stats["min_key"] is None or key < stats["min_key"]:
                    stats["min_key"] = key
                    stats["min_key_score"] = score
                elif key == stats["min_key"] and score > stats["min_key_score"]:
                    stats["min_key_score"] = score
            if (w1, w2) in check_pairs:
                source_check[(w1, w2)] = score
    return pairs, stats, source_check


def encode_bigram(pairs):
    """Serialize the complete bigram.bin image from sorted ``(key, score)``."""
    buf = bytearray(BIGRAM_HEADER.pack(MAGIC_BIGRAM, len(pairs)))
    buf += le_bytes((key for key, _ in pairs), "Q")
    buf += le_bytes((score for _, score in pairs), "H")
    return bytes(buf)


def read_lex_bin(path):
    """Read back lex.bin into plain Python containers for verification."""
    data = path.read_bytes()
    (magic, word_count, syllable_count, off_syl, off_so, off_si, off_to,
     off_tb, off_sc, text_size) = LEX_HEADER.unpack_from(data, 0)
    if magic != MAGIC_LEX:
        raise ValueError("bad lex.bin magic: 0x%08X" % magic)

    syllables = []
    pos, end = off_syl, off_so
    while pos < end:
        length = data[pos]
        if length == 0:  # alignment padding after the last entry
            break
        pos += 1
        syllables.append(data[pos:pos + length].decode("ascii"))
        pos += length

    lex = {
        "size": len(data),
        "word_count": word_count,
        "syllable_count": syllable_count,
        "offsets": {
            "syllables": off_syl,
            "syl_offsets": off_so,
            "syl_ids": off_si,
            "text_offsets": off_to,
            "text_blob": off_tb,
            "scores": off_sc,
        },
        "text_size": text_size,
        "syllables": syllables,
    }
    lex["syl_offsets"] = read_le_array(data, off_so, word_count + 1, "I")
    lex["syl_ids"] = read_le_array(data, off_si, lex["syl_offsets"][-1], "H")
    lex["text_offsets"] = read_le_array(data, off_to, word_count + 1, "I")
    lex["text_blob"] = data[off_tb:off_tb + text_size]
    lex["scores"] = read_le_array(data, off_sc, word_count, "H")
    return lex


def read_bigram_bin(path):
    """Read back bigram.bin into plain Python containers."""
    data = path.read_bytes()
    magic, pair_count = BIGRAM_HEADER.unpack_from(data, 0)
    if magic != MAGIC_BIGRAM:
        raise ValueError("bad bigram.bin magic: 0x%08X" % magic)
    return {
        "size": len(data),
        "pair_count": pair_count,
        "keys": read_le_array(data, BIGRAM_HEADER.size, pair_count, "Q"),
        "scores": read_le_array(data, BIGRAM_HEADER.size + 8 * pair_count, pair_count, "H"),
    }


def lex_seq(lex, index):
    """Syllable-id tuple of entry ``index`` in a read-back lex.bin."""
    start = lex["syl_offsets"][index]
    stop = lex["syl_offsets"][index + 1]
    return tuple(lex["syl_ids"][start:stop])


def lex_text(lex, index):
    """UTF-8 word of entry ``index`` in a read-back lex.bin."""
    start = lex["text_offsets"][index]
    stop = lex["text_offsets"][index + 1]
    return lex["text_blob"][start:stop].decode("utf-8")


def lex_lower_bound(lex, target):
    """First entry whose syllable-id sequence is >= ``target`` (dict order)."""
    lo, hi = 0, lex["word_count"]
    while lo < hi:
        mid = (lo + hi) // 2
        if lex_seq(lex, mid) < target:
            lo = mid + 1
        else:
            hi = mid
    return lo


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Build app/src/main/assets/imengine/{lex,bigram}.bin from the decoded tables."
    )
    parser.add_argument(
        "--source",
        type=Path,
        default=Path("/shared/doubaoime-teardown"),
        help="directory with ime-lexicon.tsv, syllable-id-map.tsv, ime-bigram.tsv",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "app/src/main/assets/imengine",
        help="output directory for lex.bin, bigram.bin and the build report",
    )
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    source = args.source
    out_dir = args.out
    out_dir.mkdir(parents=True, exist_ok=True)
    lex_path = out_dir / "lex.bin"
    bigram_path = out_dir / "bigram.bin"

    lines = []

    def log(text=""):
        lines.append(text)
        print(text, flush=True)

    log("== openIME pinyin engine data build ==")
    log("source: %s" % source)
    log("time:   %s" % time.strftime("%Y-%m-%d %H:%M:%S"))

    syllables, syllable_to_id, syl_rows, syl_duplicates = load_syllables(source / "syllable-id-map.tsv")
    log("syllable table: %d rows -> %d syllable ids (%d unique); duplicate ids: %s"
        % (syl_rows, len(syllables), len(syllable_to_id),
           ", ".join(sorted(set(syl_duplicates))) if syl_duplicates else "none"))

    entries, lex_stats, spot_scores = build_lexicon(
        source / "ime-lexicon.tsv", syllable_to_id, set(SPOT_WORDS))
    log("lexicon: %d rows -> %d words kept" % (lex_stats["rows"], lex_stats["kept"]))
    log("lexicon dropped: unknown syllable %d, empty pinyin %d, >%d syllables %d, bad row %d, bad score %d; duplicate words merged %d"
        % (lex_stats["dropped_unknown_syllable"], lex_stats["dropped_empty_pinyin"],
           MAX_SYLLABLES, lex_stats["dropped_too_many_syllables"],
           lex_stats["dropped_bad_row"], lex_stats["dropped_bad_score"],
           lex_stats["duplicate_words_merged"]))
    if lex_stats["unknown_syllables"]:
        log("lexicon unknown syllable samples: %s"
            % ", ".join(sorted(lex_stats["unknown_syllables"])[:20]))

    words = sort_lexicon(entries)
    atomic_write(lex_path, encode_lex(words, entries, syllables))
    log("wrote %s (%d bytes) via .tmp + os.replace" % (lex_path, os.path.getsize(lex_path)))

    word_to_id = {word: index for index, word in enumerate(words)}
    del entries, words

    check_pairs = set(SPOT_BIGRAMS) | set(EXTRA_BIGRAMS)
    pairs, bigram_stats, bigram_source_scores = build_bigram(
        source / "ime-bigram.tsv", word_to_id, check_pairs)
    log("bigram: %d rows -> %d pairs kept" % (bigram_stats["rows"], bigram_stats["kept"]))
    log("bigram dropped: w1 missing %d, w2 missing %d, both missing %d, bad row %d, bad score %d; duplicate pairs merged %d"
        % (bigram_stats["dropped_w1_missing"], bigram_stats["dropped_w2_missing"],
           bigram_stats["dropped_both_missing"], bigram_stats["dropped_bad_row"],
           bigram_stats["dropped_bad_score"], bigram_stats["duplicate_pairs_merged"]))

    ordered_pairs = sorted(pairs.items())
    del pairs
    atomic_write(bigram_path, encode_bigram(ordered_pairs))
    log("wrote %s (%d bytes) via .tmp + os.replace" % (bigram_path, os.path.getsize(bigram_path)))
    del word_to_id, ordered_pairs

    # ------------------------------------------------------------- verify --
    log()
    log("== read-back verification ==")
    lex = read_lex_bin(lex_path)
    bigram = read_bigram_bin(bigram_path)

    log("1) containers")
    log("   lex.bin:    %d bytes, word_count=%d, syllable_count=%d, text_blob_size=%d"
        % (lex["size"], lex["word_count"], lex["syllable_count"], lex["text_size"]))
    log("   bigram.bin: %d bytes, pair_count=%d, key_at=%d, scores_at=%d"
        % (bigram["size"], bigram["pair_count"], BIGRAM_HEADER.size,
           BIGRAM_HEADER.size + 8 * bigram["pair_count"]))
    section_text = ", ".join("%s@%d" % (name, off)
                             for name, off in sorted(lex["offsets"].items(), key=lambda item: item[1]))
    log("   lex sections: %s" % section_text)
    aligned = all(off % ALIGN == 0 for off in lex["offsets"].values())
    log("   checks: first section right after header=%s; sections 4-byte aligned=%s; "
        "lex ends after scores=%s; syl_offsets monotone=%s; text_offsets monotone=%s; "
        "blob size matches=%s; syllable rows match=%s; max syllable id < count=%s; "
        "bigram keys ascending=%s"
        % (lex["offsets"]["syllables"] == LEX_HEADER.size,
           aligned,
           lex["size"] == lex["offsets"]["scores"] + 2 * lex["word_count"],
           all(lex["syl_offsets"][i] <= lex["syl_offsets"][i + 1] for i in range(lex["word_count"])),
           all(lex["text_offsets"][i] <= lex["text_offsets"][i + 1] for i in range(lex["word_count"])),
           lex["text_size"] == len(lex["text_blob"]) == lex["text_offsets"][-1],
           len(lex["syllables"]) == lex["syllable_count"],
           (len(lex["syl_ids"]) == 0 or max(lex["syl_ids"]) < lex["syllable_count"]),
           all(bigram["keys"][i] < bigram["keys"][i + 1] for i in range(bigram["pair_count"] - 1))))
    log("   bigram size check: 8 + 10 * pair_count = %d = %d"
        % (BIGRAM_HEADER.size + 10 * bigram["pair_count"], bigram["size"]))

    text_index = {}
    ordered_ok = True
    previous_key = None
    for index in range(lex["word_count"]):
        word = lex_text(lex, index)
        text_index[word] = index
        key = (lex_seq(lex, index), -lex["scores"][index], word)
        if previous_key is not None and key < previous_key:
            ordered_ok = False
        previous_key = key
    log("   lex read-back order (syllables, -score, text) non-decreasing=%s" % ordered_ok)
    lex["syllable_to_id"] = {}
    for index, syllable in enumerate(lex["syllables"]):
        lex["syllable_to_id"].setdefault(syllable, index)

    log("2) word spot check (read back vs source TSV)")
    for word in SPOT_WORDS:
        index = text_index.get(word)
        if index is None:
            log("   %s: MISSING from lex.bin" % word)
            continue
        ids = lex_seq(lex, index)
        decoded = " ".join(lex["syllables"][sid] for sid in ids)
        score = lex["scores"][index]
        source_score = spot_scores.get(word)
        if source_score is None:
            verdict = "source row not captured"
        else:
            source_int = int(round(source_score * LEX_SCORE_MAX))
            verdict = "source score=%d match=%s" % (source_int, "yes" if source_int == score else "NO")
        log("   %s: ids=%s (%s) score=%d text=%r %s"
            % (word, list(ids), decoded, score, lex_text(lex, index), verdict))

    log("3) bigram spot check")
    for pair in list(SPOT_BIGRAMS) + list(EXTRA_BIGRAMS):
        label = "(required)" if pair in SPOT_BIGRAMS else "(extra)"
        id1 = text_index.get(pair[0])
        id2 = text_index.get(pair[1])
        source_score = bigram_source_scores.get(pair)
        if id1 is None or id2 is None:
            log("   %s %s: word missing from lex.bin" % (label, pair))
            continue
        key = (id1 << 32) | id2
        position = bisect_left(bigram["keys"], key)
        if position < bigram["pair_count"] and bigram["keys"][position] == key:
            score = bigram["scores"][position]
            if source_score is None:
                verdict = "source score not captured"
            else:
                source_int = int(round(source_score * BIGRAM_SCORE_MAX))
                verdict = "source score=%d match=%s" % (source_int, "yes" if source_int == score else "NO")
            log("   %s %s: key=0x%016X score=%d %s" % (label, pair, key, score, verdict))
        elif source_score is None:
            absent = [word for word in pair if word not in text_index]
            note = "; %s not in lex.bin" % "/".join(absent) if absent else ""
            log("   %s %s: not present in source TSV -> skipped%s" % (label, pair, note))
        else:
            log("   %s %s: source score=%g but MISSING from bigram.bin"
                % (label, pair, source_score))

    if bigram_stats["min_key"] is not None:
        key = bigram_stats["min_key"]
        position = bisect_left(bigram["keys"], key)
        if position < bigram["pair_count"] and bigram["keys"][position] == key:
            score = bigram["scores"][position]
            expected = int(round(bigram_stats["min_key_score"] * BIGRAM_SCORE_MAX))
            log("   (extra) lowest key (%s, %s): key=0x%016X score=%d source score=%d match=%s"
                % (lex_text(lex, key >> 32), lex_text(lex, key & 0xFFFFFFFF), key, score, expected,
                   "yes" if score == expected else "NO"))
        else:
            log("   (extra) lowest key 0x%016X MISSING from read-back bigram" % key)

    log("4) binary search self test (read-back data)")
    for phrase, expected in BISECT_PROBES:
        missing = [token for token in phrase.split() if token not in lex["syllable_to_id"]]
        if missing:
            log("   [%s] -> syllable(s) %s not in table" % (phrase, missing))
            continue
        target = tuple(lex["syllable_to_id"][token] for token in phrase.split())
        index = lex_lower_bound(lex, target)
        candidates = []
        scan = index
        while scan < lex["word_count"] and lex_seq(lex, scan) == target:
            candidates.append(scan)
            scan += 1
        if not candidates:
            log("   [%s] -> MISS (no entry)" % phrase)
            continue
        preview = ", ".join("%s(%d)" % (lex_text(lex, i), lex["scores"][i])
                            for i in candidates[:3])
        log("   [%s] ids=%s -> entry#%d, %d candidate(s): %s%s"
            % (phrase, list(target), candidates[0], len(candidates), preview,
               " ..." if len(candidates) > 3 else ""))
        first = lex_text(lex, candidates[0])
        if first == expected:
            log("        first candidate matches expected %s (score %d)"
                % (expected, lex["scores"][candidates[0]]))
        else:
            hit = next((i for i in candidates if lex_text(lex, i) == expected), None)
            if hit is None:
                log("        MISMATCH: expected %s not among candidates" % expected)
            else:
                log("        expected %s is candidate #%d (score %d); first candidate %s has the higher source score %d"
                    % (expected, candidates.index(hit) + 1, lex["scores"][hit], first,
                       lex["scores"][candidates[0]]))

    log()
    log("done.")

    # Small asset the app compares against its copied tables; the big files are
    # compressed inside the APK and cannot be opened through a file descriptor.
    revision = "lex=%d\nbigram=%d\n" % (
        (out_dir / "lex.bin").stat().st_size,
        (out_dir / "bigram.bin").stat().st_size,
    )
    atomic_write(out_dir / "revision.txt", revision.encode("ascii"))
    log("revision: %s" % revision.replace("\n", " ").strip())

    report_path = out_dir / REPORT_NAME
    atomic_write(report_path, ("\n".join(lines) + "\n").encode("utf-8"))
    print("report: %s" % report_path, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
