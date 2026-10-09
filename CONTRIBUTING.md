# Contributing

Thank you for your interest in openIME.
This guide tells you how to prepare, change and submit code.

## Prepare

1. Install JDK 17, Android SDK 36, NDK `27.0.12077973`, CMake `3.22.1` and Git LFS.
2. Run `git lfs pull`. Make sure the voice models and the AAR are not text pointers.
3. Read the [README](README.md), the [architecture](docs/ARCHITECTURE.md) and the [test architecture](docs/TEST_ARCHITECTURE.md).
4. For app screens, follow the [app UI specification](docs/APP_UI_SPEC.md).
   Use the existing tokens and resources. Do not add new sizes or colors.

## Branches and commits

- Create a short-lived branch from the latest `main`.
  Name it `type/topic`. The types are `feat`, `fix`, `docs`, `chore`, `ci`, `refactor` and `test`.
  Example: `fix/pinyin-candidate`.
- Keep one topic in each commit.
  Write the message as one short sentence that states the result.
  Example: `fix: keep the rest of the input composing`.
- We squash every PR. The PR title and description become the commit on `main`.
- Do not commit these items:
  `local.properties`, build output, screenshots outside `docs/images/`, UI dumps, device logs,
  passwords, recordings, signing keys and unverified model files.

## Change log and version

- Add each user-visible change to `## [Unreleased]` in `CHANGELOG.md`, in the same PR.
  User-visible changes are features, behavior, fixes, permissions and data formats.
  Internal changes do not need an entry.
- Do not change `VERSION` in a feature PR. Only a release PR changes it.
  See the [release process](docs/RELEASE.md).

## Check your change

Run these commands before you open a PR:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/release_check.py check
git diff --check
```

The CI runs the same checks.

If you change the input path, run one core regression on a named device or emulator:

```powershell
.\scripts\core_regression.ps1 -Serial <serial>
```

If you change layout or insets, also run `scripts/visual_check.ps1`.
In the PR, state the Android version, the window width and whether you used the floating keyboard.

If you change an app screen, run `scripts/beta4_e2e.py`.
Check normal, 320 dp wide with large fonts, and wide screens.
Keep the screenshots, UI XML, result JSON and logs.

## Pull requests

Describe these items in the PR:

- the purpose of the change
- the parts of the product that it affects
- the test commands and their results
- known limitations
- any change to dictionaries, models, permissions or data formats

Remove personal data from screenshots.
Do not upload real typed text, passwords, clipboard content or recordings.

`main` is protected.
A PR needs a passing **Build and verify** check before it can merge.
The compatibility tests on API 26, 29, 31 and 34 also run on each PR.
Fix a failing compatibility test before you merge.

## Conduct

Be respectful and keep discussions about the work.
Report security problems in private. See [SECURITY.md](SECURITY.md).
