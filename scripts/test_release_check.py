#!/usr/bin/env python3
"""Tests for release_check.py. Run: python3 -m unittest discover -s scripts -p 'test_*.py'"""

from __future__ import annotations

import contextlib
import io
import tempfile
import unittest
from pathlib import Path

import release_check as rc

CHANGELOG = """# 更新记录

## [Unreleased]

- 还没发布的改动。

## [1.1.0] - 2026-11-01

### 新增
- 新功能。

## [1.0.0] - 2026-10-02

首个正式版。

[Unreleased]: https://example.invalid/compare/v1.1.0...HEAD
[1.1.0]: https://example.invalid/compare/v1.0.0...v1.1.0
"""


class VersionTests(unittest.TestCase):
    def test_version_code_formula(self) -> None:
        self.assertEqual(rc.version_code("1.0.0"), 10000)
        self.assertEqual(rc.version_code("1.2.3"), 10203)
        self.assertEqual(rc.version_code("2.10.99"), 21099)

    def test_every_release_is_greater_than_the_one_before(self) -> None:
        order = ["1.0.0", "1.0.1", "1.0.99", "1.1.0", "1.99.99", "2.0.0"]
        codes = [rc.version_code(version) for version in order]
        self.assertEqual(codes, sorted(set(codes)))

    def test_rejects_anything_but_plain_semver(self) -> None:
        for bad in ["1.0", "v1.0.0", "1.0.0-rc1", "1.0.0+build", "01.0.0", "0.9.0", "1.100.0", "1.0.100", ""]:
            with self.subTest(bad=bad), self.assertRaises(rc.ReleaseCheckError):
                rc.parse_version(bad)


class ChangelogTests(unittest.TestCase):
    def test_accepts_a_consistent_changelog(self) -> None:
        rc.check_changelog(CHANGELOG, "1.1.0")

    def test_version_must_match_the_newest_section(self) -> None:
        with self.assertRaisesRegex(rc.ReleaseCheckError, "newest CHANGELOG"):
            rc.check_changelog(CHANGELOG, "1.0.0")
        with self.assertRaisesRegex(rc.ReleaseCheckError, "newest CHANGELOG"):
            rc.check_changelog(CHANGELOG, "1.2.0")

    def test_requires_unreleased_first(self) -> None:
        text = CHANGELOG.replace("## [Unreleased]", "## [Next]")
        with self.assertRaises(rc.ReleaseCheckError):
            rc.check_changelog(text, "1.1.0")

    def test_rejects_empty_sections(self) -> None:
        text = "## [Unreleased]\n\n## [1.0.0] - 2026-10-02\n\n"
        with self.assertRaisesRegex(rc.ReleaseCheckError, "empty"):
            rc.check_changelog(text, "1.0.0")

    def test_rejects_ascending_versions_and_dates(self) -> None:
        ascending = "## [Unreleased]\n\n## [1.0.0] - 2026-10-02\n- a\n\n## [1.1.0] - 2026-09-01\n- b\n"
        with self.assertRaisesRegex(rc.ReleaseCheckError, "descending"):
            rc.check_changelog(ascending, "1.0.0")
        later = "## [Unreleased]\n\n## [1.1.0] - 2026-10-01\n- a\n\n## [1.0.0] - 2026-10-02\n- b\n"
        with self.assertRaisesRegex(rc.ReleaseCheckError, "dated after"):
            rc.check_changelog(later, "1.1.0")

    def test_rejects_bad_dates_and_headings(self) -> None:
        with self.assertRaisesRegex(rc.ReleaseCheckError, "invalid date"):
            rc.check_changelog("## [Unreleased]\n\n## [1.0.0] - 2026-13-45\n- a\n", "1.0.0")
        with self.assertRaisesRegex(rc.ReleaseCheckError, "must be"):
            rc.check_changelog("## [Unreleased]\n\n## 1.0.0\n- a\n", "1.0.0")

    def test_a_yanked_newest_release_is_not_a_valid_target(self) -> None:
        text = "## [Unreleased]\n\n## [1.0.0] - 2026-10-02 [YANKED]\n- a\n"
        with self.assertRaisesRegex(rc.ReleaseCheckError, "YANKED"):
            rc.check_changelog(text, "1.0.0")

    def test_notes_are_exactly_the_section_body(self) -> None:
        self.assertEqual(rc.changelog_notes(CHANGELOG, "1.1.0"), "### 新增\n- 新功能。")
        # The last section stops before the link reference definitions.
        self.assertEqual(rc.changelog_notes(CHANGELOG, "1.0.0"), "首个正式版。")
        with self.assertRaises(rc.ReleaseCheckError):
            rc.changelog_notes(CHANGELOG, "9.9.9")


class BadgingTests(unittest.TestCase):
    SAMPLE = (
        "package: name='llc.slacker.openime' versionCode='10000' versionName='1.0.0' "
        "platformBuildVersionName='16' compileSdkVersion='36'\nsdkVersion:'26'\n"
    )

    def test_parses_the_package_line(self) -> None:
        self.assertEqual(rc.parse_badging(self.SAMPLE), ("llc.slacker.openime", 10000, "1.0.0"))

    def test_rejects_output_without_a_package_line(self) -> None:
        with self.assertRaises(rc.ReleaseCheckError):
            rc.parse_badging("ERROR: not an apk")


class CommandTests(unittest.TestCase):
    def run_main(self, root: Path, *argv: str) -> tuple[int, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            status = rc.main(["--root", str(root), *argv])
        return status, out.getvalue() + err.getvalue()

    def fixture(self, version: str) -> Path:
        directory = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: [path.unlink() for path in directory.iterdir()] and directory.rmdir())
        (directory / "VERSION").write_text(version + "\n", encoding="utf-8")
        (directory / "CHANGELOG.md").write_text(CHANGELOG, encoding="utf-8")
        return directory

    def test_check_passes_and_prints_the_version_code(self) -> None:
        status, output = self.run_main(self.fixture("1.1.0"), "check")
        self.assertEqual(status, 0, output)
        self.assertIn("10100", output)

    def test_check_enforces_the_tag(self) -> None:
        root = self.fixture("1.1.0")
        self.assertEqual(self.run_main(root, "check", "--tag", "v1.1.0")[0], 0)
        status, output = self.run_main(root, "check", "--tag", "v1.0.0")
        self.assertEqual(status, 1)
        self.assertIn("does not match", output)
        self.assertEqual(self.run_main(root, "check", "--tag", "1.1.0")[0], 1)

    def test_a_version_bump_without_a_changelog_section_fails(self) -> None:
        status, output = self.run_main(self.fixture("1.2.0"), "check")
        self.assertEqual(status, 1)
        self.assertIn("newest CHANGELOG", output)

    def test_notes_default_to_the_current_version(self) -> None:
        status, output = self.run_main(self.fixture("1.1.0"), "notes")
        self.assertEqual(status, 0)
        self.assertIn("新功能", output)


class RepositoryTests(unittest.TestCase):
    def test_this_repository_is_consistent(self) -> None:
        """The checked-in VERSION and CHANGELOG.md must satisfy the release rules."""
        version = rc.read_version()
        rc.check_changelog((rc.ROOT / "CHANGELOG.md").read_text(encoding="utf-8"), version)


if __name__ == "__main__":
    unittest.main()
