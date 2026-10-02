#!/usr/bin/env python3
"""Release metadata checks shared by CI, the release workflow and maintainers.

The root VERSION file is the single source of truth. app/build.gradle.kts reads
it and derives versionCode with the same formula used here, so this script can
verify both the repository and a built APK.

  release_check.py version                  print VERSION and the derived versionCode
  release_check.py check [--tag vX.Y.Z]     VERSION is valid, CHANGELOG.md has the dated
                                            section for it right below [Unreleased], and
                                            (with --tag) the tag is exactly v<VERSION>
  release_check.py notes [--version X.Y.Z]  print that CHANGELOG section (release notes body)
  release_check.py apk PATH [--aapt2 PATH]  versionName / versionCode / package inside the APK
                                            match VERSION

Exit status is non-zero on any problem. Messages are GitHub Actions annotations
when run there.
"""

from __future__ import annotations

import argparse
import datetime
import glob
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import NamedTuple, Optional

ROOT = Path(__file__).resolve().parent.parent
APPLICATION_ID = "llc.slacker.openime"

SEMVER = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)")
UNRELEASED_HEADING = re.compile(r"##\s+\[Unreleased\]\s*")
RELEASE_HEADING = re.compile(
    r"##\s+\[(?P<version>[^\]]+)\]\s+-\s+(?P<date>\d{4}-\d{2}-\d{2})(?P<yanked>\s+\[YANKED\])?\s*"
)


class ReleaseCheckError(Exception):
    """A problem a maintainer has to fix before releasing."""


class Section(NamedTuple):
    version: str
    date: str
    yanked: bool
    body: str


def parse_version(text: str) -> tuple[int, int, int]:
    match = SEMVER.fullmatch(text.strip())
    if not match:
        raise ReleaseCheckError(
            f"version '{text.strip()}' must be MAJOR.MINOR.PATCH without a prefix or suffix"
        )
    major, minor, patch = (int(part) for part in match.groups())
    if major < 1 or minor > 99 or patch > 99:
        raise ReleaseCheckError(
            f"version {text.strip()} is out of range (major >= 1, minor and patch <= 99)"
        )
    return major, minor, patch


def version_code(version: str) -> int:
    """Same formula as app/build.gradle.kts: major * 10000 + minor * 100 + patch."""
    major, minor, patch = parse_version(version)
    return major * 10_000 + minor * 100 + patch


def read_version(root: Path = ROOT) -> str:
    version = (root / "VERSION").read_text(encoding="utf-8").strip()
    parse_version(version)
    return version


def parse_changelog(text: str) -> tuple[bool, list[Section]]:
    """Return (has_unreleased_heading_first, released sections in file order)."""
    headings: list[tuple[int, str]] = []
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if line.startswith("## "):
            headings.append((index, line))
    if not headings:
        return False, []

    unreleased_first = bool(UNRELEASED_HEADING.fullmatch(headings[0][1]))
    sections: list[Section] = []
    for position, (index, heading) in enumerate(headings):
        match = RELEASE_HEADING.fullmatch(heading)
        if not match:
            if position == 0 and unreleased_first:
                continue
            raise ReleaseCheckError(
                f"CHANGELOG.md heading '{heading}' must be '## [Unreleased]' or "
                "'## [X.Y.Z] - YYYY-MM-DD'"
            )
        end = headings[position + 1][0] if position + 1 < len(headings) else len(lines)
        body_lines = lines[index + 1:end]
        # Link reference definitions at the very bottom belong to no section.
        while body_lines and (
            not body_lines[-1].strip() or re.fullmatch(r"\[[^\]]+\]:\s+\S+", body_lines[-1].strip())
        ):
            body_lines.pop()
        sections.append(
            Section(
                version=match.group("version"),
                date=match.group("date"),
                yanked=bool(match.group("yanked")),
                body="\n".join(body_lines).strip(),
            )
        )
    return unreleased_first, sections


def check_changelog(text: str, version: str) -> None:
    unreleased_first, sections = parse_changelog(text)
    if not unreleased_first:
        raise ReleaseCheckError("CHANGELOG.md must start with '## [Unreleased]'")
    if not sections:
        raise ReleaseCheckError(f"CHANGELOG.md has no section for {version}")

    previous: Optional[tuple[int, int, int]] = None
    previous_date: Optional[datetime.date] = None
    for section in sections:
        numbers = parse_version(section.version)
        try:
            when = datetime.date.fromisoformat(section.date)
        except ValueError as error:
            raise ReleaseCheckError(
                f"CHANGELOG.md [{section.version}] has an invalid date '{section.date}'"
            ) from error
        if not section.body:
            raise ReleaseCheckError(f"CHANGELOG.md [{section.version}] is empty")
        if previous is not None and numbers >= previous:
            raise ReleaseCheckError(
                f"CHANGELOG.md versions must be strictly descending; [{section.version}] "
                "is not below the section above it"
            )
        if previous_date is not None and when > previous_date:
            raise ReleaseCheckError(
                f"CHANGELOG.md [{section.version}] is dated after the newer release above it"
            )
        previous, previous_date = numbers, when

    top = sections[0]
    if top.version != version:
        raise ReleaseCheckError(
            f"VERSION is {version} but the newest CHANGELOG.md section is [{top.version}]. "
            "A version bump and its changelog section land together (see docs/RELEASE.md)."
        )
    if top.yanked:
        raise ReleaseCheckError(f"CHANGELOG.md marks [{version}] as [YANKED]; bump the version")


def changelog_notes(text: str, version: str) -> str:
    _, sections = parse_changelog(text)
    for section in sections:
        if section.version == version:
            if not section.body:
                raise ReleaseCheckError(f"CHANGELOG.md [{version}] is empty")
            return section.body
    raise ReleaseCheckError(f"CHANGELOG.md has no section for {version}")


BADGING = re.compile(
    r"package: name='(?P<name>[^']+)' versionCode='(?P<code>\d+)' versionName='(?P<version>[^']*)'"
)


def parse_badging(text: str) -> tuple[str, int, str]:
    match = BADGING.search(text)
    if not match:
        raise ReleaseCheckError("could not read the package line from 'aapt2 dump badging'")
    return match.group("name"), int(match.group("code")), match.group("version")


def find_aapt2(explicit: Optional[str]) -> str:
    if explicit:
        return explicit
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2")))
        if candidates:
            return candidates[-1]
    return "aapt2"


def check_apk(apk: Path, version: str, aapt2: Optional[str]) -> None:
    if not apk.is_file():
        raise ReleaseCheckError(f"APK not found: {apk}")
    tool = find_aapt2(aapt2)
    try:
        result = subprocess.run(
            [tool, "dump", "badging", str(apk)], capture_output=True, text=True, check=True
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise ReleaseCheckError(f"'{tool} dump badging' failed: {error}") from error
    name, code, shown = parse_badging(result.stdout)
    problems = []
    if name != APPLICATION_ID:
        problems.append(f"package is {name}, expected {APPLICATION_ID}")
    if shown != version:
        problems.append(f"versionName is {shown}, VERSION says {version}")
    if code != version_code(version):
        problems.append(f"versionCode is {code}, expected {version_code(version)}")
    if problems:
        raise ReleaseCheckError(f"{apk.name}: " + "; ".join(problems))


def fail(message: str) -> int:
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::error title=Release check::{message}")
    else:
        print(f"error: {message}", file=sys.stderr)
    return 1


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root (tests only)")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("version")
    check = sub.add_parser("check")
    check.add_argument("--tag", help="release tag that must equal v<VERSION>")
    notes = sub.add_parser("notes")
    notes.add_argument("--version")
    apk = sub.add_parser("apk")
    apk.add_argument("path", type=Path)
    apk.add_argument("--aapt2")
    args = parser.parse_args(argv)

    try:
        version = read_version(args.root)
        changelog = (args.root / "CHANGELOG.md").read_text(encoding="utf-8")
        if args.command == "version":
            print(f"{version} {version_code(version)}")
        elif args.command == "check":
            if args.tag is not None and args.tag != f"v{version}":
                raise ReleaseCheckError(f"tag {args.tag} does not match VERSION {version} (expected v{version})")
            check_changelog(changelog, version)
            print(f"OK: {version} (versionCode {version_code(version)})")
        elif args.command == "notes":
            print(changelog_notes(changelog, args.version or version))
        elif args.command == "apk":
            check_apk(args.path, version, args.aapt2)
            print(f"OK: {args.path.name} is {APPLICATION_ID} {version} ({version_code(version)})")
    except (ReleaseCheckError, OSError) as error:
        return fail(str(error))
    return 0


if __name__ == "__main__":
    sys.exit(main())
