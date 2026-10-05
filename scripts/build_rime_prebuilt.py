#!/usr/bin/env python3
"""Compile openIME's Rime dictionaries on the build machine.

librime compiles every *.dict.yaml into binary tables before it can answer a
query. Done on the phone, that takes about a minute after each install or
upgrade, during which only the small Kotlin fallback dictionary is available.
This script runs the same compiler on the host instead:

1. builds `openime_rime_deployer` from the vendored librime (the same sources
   and versions as the APK's native library) into build/rime-host;
2. runs `--build` over app/src/main/assets/rime-data;
3. writes the compiled files to <out>/rime-data/build/ and a content hash of
   the packaged Rime data to <out>/rime-data.revision;
4. writes <out>/stroke_table.tsv for the 笔画 keyboard: every character of
   stroke.dict.yaml with its stroke code (h s p n z), most frequent first by
   the 8105 table's weights. The keyboard reads it directly, so
   stroke.dict.yaml itself stays out of the APK.

The APK ships that directory as librime's prebuilt data dir
(shared_data_dir/build), so the phone only copies it. The output is
byte-identical to what librime compiles on an x86_64 emulator and loads as is
on arm64 phones; the dictionary sources it came from stay out of the APK.

The phone copies the data again only when that hash changes, so an upgrade
that leaves the dictionaries alone skips the copy.

    python3 scripts/build_rime_prebuilt.py --out app/build/generated/rime-prebuilt
"""

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ROOT / "app" / "src" / "main" / "assets" / "rime-data"
NATIVE = ROOT / "app" / "src" / "main" / "cpp"
HOST_BUILD = ROOT / "build" / "rime-host"
CMAKE_VERSION = "3.22.1"

# Every file the runtime needs from the compiler. A missing one means a
# dictionary failed to compile; the deployer's exit code cannot tell, because
# default.yaml also lists schemas openIME does not ship and those always fail.
EXPECTED = [
    "default.yaml",
    "luna_pinyin.schema.yaml",
    "luna_pinyin.table.bin",
    "luna_pinyin.prism.bin",
    "luna_pinyin.reverse.bin",
    "luna_pinyin_simp.schema.yaml",
    "luna_pinyin_simp.prism.bin",
    "luna_pinyin_simp_fuzzy.schema.yaml",
    "luna_pinyin_simp_fuzzy.prism.bin",
    "stroke.schema.yaml",
    "stroke.table.bin",
    "stroke.prism.bin",
    "stroke.reverse.bin",
]


def cmake_tools():
    """The SDK's CMake, the one the Android build uses, else the host's."""
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(env)
        if sdk:
            bin_dir = Path(sdk) / "cmake" / CMAKE_VERSION / "bin"
            if (bin_dir / "cmake").exists() and (bin_dir / "ninja").exists():
                return str(bin_dir / "cmake"), str(bin_dir / "ninja"), []
    cmake = shutil.which("cmake")
    ninja = shutil.which("ninja")
    if not cmake or not ninja:
        sys.exit(f"build_rime_prebuilt: need cmake {CMAKE_VERSION} from the Android SDK, or cmake and ninja on PATH")
    # CMake 4 refuses the vendored dependencies' old minimum versions.
    return cmake, ninja, ["-DCMAKE_POLICY_VERSION_MINIMUM=3.5"]


def build_deployer():
    cmake, ninja, extra = cmake_tools()
    HOST_BUILD.mkdir(parents=True, exist_ok=True)
    if not (HOST_BUILD / "build.ninja").exists():
        subprocess.run(
            [
                cmake,
                "-G", "Ninja",
                f"-DCMAKE_MAKE_PROGRAM={ninja}",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DOPENIME_HOST_DEPLOYER=ON",
                *extra,
                str(NATIVE),
            ],
            cwd=HOST_BUILD,
            check=True,
            stdout=subprocess.DEVNULL,
        )
    subprocess.run([ninja, "openime_rime_deployer"], cwd=HOST_BUILD, check=True, stdout=subprocess.DEVNULL)
    return HOST_BUILD / "openime_rime_deployer"


def compile_dictionaries(deployer, out_dir):
    with tempfile.TemporaryDirectory(prefix="openime-rime-") as work:
        work = Path(work)
        shared = work / "shared"
        user = work / "user"
        staging = work / "staging"
        shutil.copytree(SOURCES, shared, ignore=shutil.ignore_patterns("build"))
        user.mkdir()
        subprocess.run(
            [str(deployer), "--build", str(user), str(shared), str(staging)],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        missing = [name for name in EXPECTED if not (staging / name).is_file()]
        if missing:
            sys.exit("build_rime_prebuilt: librime did not produce " + ", ".join(missing))
        target = out_dir / "rime-data" / "build"
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True)
        for name in EXPECTED:
            shutil.copy2(staging / name, target / name)
        return target


def dict_body(path):
    """The tab-separated rows after a Rime dict.yaml's `...` header line."""
    in_body = False
    with open(path, encoding="utf-8") as lines:
        for line in lines:
            if not in_body:
                in_body = line.strip() == "..."
                continue
            if line.startswith("#") or not line.strip():
                continue
            yield line.rstrip("\n").split("\t")


def write_stroke_table(out_dir):
    weights = {}
    for fields in dict_body(SOURCES / "openime_dicts" / "8105.dict.yaml"):
        if len(fields) >= 3 and fields[2].strip().isdigit():
            weights[fields[0]] = max(weights.get(fields[0], 0), int(fields[2]))
    rows = []
    seen = set()
    for fields in dict_body(SOURCES / "stroke.dict.yaml"):
        if len(fields) < 2 or len(fields[0]) != 1:
            continue
        character, code = fields[0], fields[1].strip()
        if not code or set(code) - set("hspnz") or (character, code) in seen:
            continue
        seen.add((character, code))
        rows.append((-weights.get(character, 0), len(rows), character, code))
    rows.sort()
    target = out_dir / "stroke_table.tsv"
    with open(target, "w", encoding="utf-8", newline="\n") as table:
        for _, _, character, code in rows:
            table.write(f"{character}\t{code}\n")
    return len(rows)


def content_hash(compiled, excluded):
    """SHA-256 over every source file, the compiled tables and the names kept
    out of the APK: anything that changes what the phone copies changes it."""
    digest = hashlib.sha256()
    entries = [("src", path.relative_to(SOURCES), path) for path in SOURCES.rglob("*") if path.is_file()]
    entries += [("bin", path.relative_to(compiled), path) for path in compiled.iterdir()]
    for kind, relative, path in sorted(entries):
        digest.update(f"{kind}:{relative.as_posix()}\0".encode())
        digest.update(path.read_bytes())
        digest.update(b"\0")
    for name in sorted(excluded):
        digest.update(f"exclude:{name}\0".encode())
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True, type=Path, help="generated assets root")
    parser.add_argument(
        "--exclude", action="append", default=[],
        help="a source file name the APK leaves out (repeatable); part of the content hash",
    )
    args = parser.parse_args()
    out = args.out.resolve()
    target = compile_dictionaries(build_deployer(), out)
    revision = content_hash(target, args.exclude)
    (out / "rime-data.revision").write_text(revision + "\n", encoding="utf-8")
    strokes = write_stroke_table(out)
    total = sum(path.stat().st_size for path in target.iterdir())
    print(
        f"build_rime_prebuilt: {len(EXPECTED)} files, {total // 1024} KiB in {target}, "
        f"revision {revision[:12]}, {strokes} stroke codes"
    )


if __name__ == "__main__":
    main()
