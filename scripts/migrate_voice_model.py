#!/usr/bin/env python3
"""Install the pinned INT8 streaming Paraformer model into openIME assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
VOICE_ROOT = ASSETS / "models" / "voice"
TARGET_ROOT = VOICE_ROOT / "bilingual-paraformer"
OLD_ROOT = VOICE_ROOT / "bilingual-zipformer"

MODEL_ID = "sherpa-onnx-streaming-paraformer-bilingual-zh-en-int8"
MODEL_VERSION = "hf-8e40c43-int8"
MODEL_TYPE = "paraformer"
ENGINE_VERSION = "sherpa-onnx-v1.13.6"
LANGUAGE = "zh-CN,en-US"
REQUIRED_MEMORY = 520_000_000

EXPECTED_SHA256 = {
    "encoder.int8.onnx": "81a70226a8934e6ed92aa1d4fc486b428b5398e2f2619ed4897b7294cab90e9a",
    "decoder.int8.onnx": "f3cca9f77bb9d93c8fcbfb63ae617b6b1ee96818df3aa3b151c40658fe38594f",
}

TARGET_FILES = [
    "models/voice/bilingual-paraformer/decoder.int8.onnx",
    "models/voice/bilingual-paraformer/encoder.int8.onnx",
    "models/voice/bilingual-paraformer/tokens.txt",
]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def update_framed(digest, value: str) -> None:
    data = value.encode("utf-8")
    digest.update(str(len(data)).encode("ascii"))
    digest.update(b":")
    digest.update(data)


def manifest_fingerprint(manifest: dict[str, object]) -> str:
    digest = hashlib.sha256()
    digest.update(b"openime-voice-manifest-v1")
    values = [
        str(manifest["modelId"]),
        str(manifest["modelVersion"]),
        str(manifest["language"]),
        str(manifest["modelType"]),
        str(manifest["engineVersion"]),
        str(manifest["fileHash"]),
        "1" if manifest["supportsPunctuation"] else "0",
        str(manifest["requiredMemory"]),
        str(len(manifest["files"])),
    ]
    for value in values:
        update_framed(digest, value)
    for path in manifest["files"]:
        update_framed(digest, str(path))
    return digest.hexdigest()


def verify_source(source: Path) -> None:
    for filename, expected in EXPECTED_SHA256.items():
        path = source / filename
        if not path.is_file():
            raise SystemExit(f"missing upstream model file: {path}")
        actual = sha256_file(path)
        if actual != expected:
            raise SystemExit(
                f"{filename} SHA-256 mismatch: expected {expected}, got {actual}"
            )

    tokens = source / "tokens.txt"
    if not tokens.is_file():
        raise SystemExit(f"missing upstream token file: {tokens}")
    lines = tokens.read_text(encoding="utf-8").splitlines()
    if len(lines) != 8404:
        raise SystemExit(f"unexpected tokens.txt line count: {len(lines)}")
    if lines[:3] != ["<blank> 0", "<s> 1", "</s> 2"]:
        raise SystemExit("unexpected tokens.txt header")


def install(source: Path) -> None:
    verify_source(source)

    if TARGET_ROOT.exists():
        shutil.rmtree(TARGET_ROOT)
    TARGET_ROOT.mkdir(parents=True)

    for filename in ("decoder.int8.onnx", "encoder.int8.onnx", "tokens.txt"):
        shutil.copy2(source / filename, TARGET_ROOT / filename)

    if OLD_ROOT.exists():
        shutil.rmtree(OLD_ROOT)

    aggregate = hashlib.sha256()
    for relative in sorted(TARGET_FILES):
        path = ASSETS / relative
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                aggregate.update(chunk)

    manifest = {
        "modelId": MODEL_ID,
        "modelVersion": MODEL_VERSION,
        "language": LANGUAGE,
        "modelType": MODEL_TYPE,
        "engineVersion": ENGINE_VERSION,
        "fileHash": aggregate.hexdigest(),
        "supportsPunctuation": False,
        "requiredMemory": REQUIRED_MEMORY,
        "files": TARGET_FILES,
    }

    (VOICE_ROOT / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    sizes = [str((ASSETS / relative).stat().st_size) for relative in TARGET_FILES]
    fingerprint = manifest_fingerprint(manifest)
    catalog = (
        "# openIME trusted downloaded voice model catalog v1\n"
        "# publisher<TAB>manifest_fingerprint_sha256<TAB>file_sizes_in_manifest_order\n"
        f"Slacker-LLC\t{fingerprint}\t{','.join(sizes)}\n"
    )
    (VOICE_ROOT / "trusted-downloads.tsv").write_text(catalog, encoding="utf-8")

    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    print(f"manifest fingerprint: {fingerprint}")
    print(f"trusted sizes: {','.join(sizes)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "source",
        type=Path,
        help="Extracted sherpa-onnx-streaming-paraformer-bilingual-zh-en directory",
    )
    args = parser.parse_args()
    source = args.source.resolve()
    if not source.is_dir():
        raise SystemExit(f"source directory does not exist: {source}")
    install(source)


if __name__ == "__main__":
    main()
