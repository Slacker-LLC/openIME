# Test architecture

The tests have four layers.
This document lists only entry points that exist.
For current results, see GitHub Actions and the local SOP evidence. Do not write PASS in this document.

## 1. JVM unit tests

These tests cover pure Kotlin code and policies that you can isolate. Examples:

- `CandidateEngineTest.kt`, `CandidatePipelineTest.kt`, `CandidateSnapshotTest.kt`
- `InputConnectionGatewayTest.kt`, `EditorInfoAdapterTest.kt`
- `ImeStateTest.kt`, `KeyboardLayoutMetricsTest.kt`
- tests for voice and Rime policies and lifecycles
- `ArchitectureLayeringTest.kt`, which enforces the package dependencies

Delete a legacy helper that has no production caller, together with the tests that only test it.
Do not keep it to raise the test count.

## 2. Android instrumentation tests

`app/src/androidTest` covers real views, `InputConnection`, the IME lifecycle and API compatibility. Examples:

- `AuditInteractionInstrumentedTest.kt`, `ClipboardRetentionInstrumentedTest.kt`
- `TextEditControlsInstrumentedTest.kt`, `NineKeyChineseInstrumentedTest.kt`
- `CandidatePresentationInstrumentedTest.kt`, `CompatibilityApiInstrumentedTest.kt`
- voice lifecycle and ownership tests

GitHub Actions runs these tests on API 26, 29, 31 and 34.
Other API levels and real devices are extra acceptance. They are not CI coverage.

## 3. Debug-only E2E harness

`app/src/debug` contains `DebugKeyboardActivity`, `E2ETestReceiver`, `ImeTestLabActivity`, `LifecycleTestActivity` and `SecurityTestActivity`.
Only debug builds contain them. The release APK must not depend on them.

## 4. Scripts and device SOP

`scripts/test_sop.ps1` is the single entry point for device tests.
The scripts cover:

- core and extended typing
- nine-key input
- clear, delete and voice
- field-type matrix
- lifecycle
- panel data
- security
- visual checks
- performance and stress
- upgrade

A script that needs a real device or an emulator requires an explicit serial.
This prevents it from using the wrong device.

## CI gate

`.github/workflows/android.yml` runs these steps:

1. Version and change log checks: `scripts/test_release_check.py` and `scripts/release_check.py check`
2. `:app:testDebugUnitTest`
3. `:app:lintDebug`
4. `:app:assembleDebug` and `:app:assembleDebugAndroidTest`, then a check of the package name and version in the APKs
5. All instrumentation tests on API 26, 29, 31 and 34

`am instrument` exits with 0 even when a test fails.
So the compatibility job checks that the end of the output contains `OK (N tests)`, and fails otherwise.
The reports are in the `compatibility-api-*` artifacts.

`main` requires **Build and verify** before a merge.
The compatibility tests also run on PRs and turn red when a test fails, but they do not block the merge.
[REPOSITORY.md](REPOSITORY.md) explains the reason and how to make them required.
The release workflow requires Build and verify, Compatibility API 29 and Compatibility API 31.
When a PR changes the release pipeline, CI also rehearses `lintRelease` and `assembleRelease` with a one-time key.
See [RELEASE.md](RELEASE.md).

The latest CI result must belong to the latest HEAD of the PR or branch.
A green result for an old SHA does not prove that a new commit passes.

## Coordinates and visuals

Tests and measurements use the normalized bounds in `KeyboardGeometry.kt`.
The real layout comes from the real size of the IME window, its insets and the geometry tokens.
See [Coordinate system](COORDINATE_SYSTEM.md).
`scripts/visual_check.ps1` creates visual evidence.
Do not commit local screenshots as long-term source assets.
