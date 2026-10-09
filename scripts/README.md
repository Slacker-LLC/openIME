# Scripts

Every script finds the APK and the package name from the repository root.
No script uses fixed screen coordinates.
A script that needs a device requires `-Serial` or the environment variable `ANDROID_SERIAL`.

## Release and repository scripts

These scripts need no device:

```bash
python3 scripts/release_check.py check             # VERSION agrees with CHANGELOG.md (CI runs it)
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/build_rime_prebuilt.py --out <dir> # precompile the Rime dictionaries (the Gradle task prebuildRimeData runs it)
bash scripts/release_build.sh                      # build and verify a signed arm64 release (needs the signing variables, see docs/RELEASE.md)
bash scripts/setup_release_signing.sh              # one time: create the release key and write the Actions secrets
bash scripts/apply_repo_settings.sh --dry-run      # show the repository rules that would be applied (see docs/REPOSITORY.md)
bash scripts/verify_linux.sh                       # unit tests, lint, debug APK, test APK
bash scripts/fetch_rime_deps.sh                    # fetch the pinned librime dependencies
```

## Display matrix

You need a device or an emulator with the debug build of openIME as the default keyboard.
The script changes and restores rotation, font scale, resolution and dark mode:

```bash
python3 scripts/display_matrix_regression.py --serial <serial> [case ...]
```

## Device tests

```powershell
.\scripts\test_sop.ps1 -Level L0 -Serial <serial>
.\scripts\test_sop.ps1 -Level L1 -Serial <serial>
.\scripts\test_sop.ps1 -Level L2 -Serial <serial> -FreshInstall
.\scripts\test_sop.ps1 -Level L3 -Serial <serial> -FreshInstall
```

`test_sop.ps1` is the official entry point.
It stops at the first failure.
It writes step logs, before and after screenshots, UI trees, short screen recordings, logcat, meminfo, gfxinfo, APK hashes and device metadata to `.local/test-runs/`.
L2 and L3 also copy the manual checklist.
While the checklist is not complete, mark the run "automated pass". Never mark it "release pass".

To list the steps of a level without a device:

```powershell
.\scripts\test_sop.ps1 -Level L3 -ListOnly
```

`-FreshInstall` uninstalls openIME and deletes its local data.
Use it only on a device that you may reset.

Individual scripts:

```powershell
.\scripts\build_ascii.ps1
.\scripts\core_regression.ps1 -Serial <serial>
.\scripts\nine_key_regression.ps1 -Serial <serial>
.\scripts\clear_delete_voice_regression.ps1 -Serial <serial>
.\scripts\voice_lifecycle_regression.ps1 -Serial <serial>
.\scripts\voice_correction_regression.ps1 -Serial <serial>
.\scripts\typing_engine_regression.ps1 -Serial <serial>
.\scripts\extended_regression.ps1 -Serial <serial>
.\scripts\field_matrix_regression.ps1 -Serial <serial>
.\scripts\panel_data_regression.ps1 -Serial <serial>
.\scripts\lifecycle_regression.ps1 -Serial <serial>
.\scripts\visual_check.ps1 -Serial <serial>
.\scripts\visual_matrix_regression.ps1 -Serial <serial>
.\scripts\perf_baseline.ps1 -Serial <serial>
.\scripts\stress_baseline.ps1 -Serial <serial>
.\scripts\upgrade_regression.ps1 -Serial <serial>
.\scripts\security_regression.ps1 -Serial <serial>
```

| Script | What it checks |
|---|---|
| `typing_engine_regression.ps1` | Full pinyin, explicit word splitting, long sentences, extended-word candidates, composition clearing after a selection, and backspace on the target text. It finds targets by candidate text and debug state. |
| `clear_delete_voice_regression.ps1` | Three rounds of: type, swipe-up clear, clear a pinyin preedit, delete one character at a time, long-press voice callbacks, final-only callbacks and delete after voice. It checks the editor, the composition and the voice state at each step. |
| `voice_lifecycle_regression.ps1` | Typing stays responsive during model warm-up, hot reuse within 10 seconds, release after the timeout and background reload after reopening. |
| `voice_correction_regression.ps1` | After the user deletes and corrects a voice result, the next identical raw result uses the local correction pair. |
| `panel_data_regression.ps1` | System clipboard read and insert, and quick phrases: add, save, use, edit, delete. It creates only test phrases with unique numbers and deletes them when the test passes. Use an emulator or a dedicated test device. |
| `field_matrix_regression.ps1` | The default keyboard mode and key commit paths in the debug field lab: normal, multiline, password, number, phone, email, URL, search, chat, form, 10,000-character and selection-replacement fields. |
| `security_regression.ps1` | The permission surface, password composition, and leaks into logcat and private app files. |

## Rebuild the fast pinyin lexicon

After you update the built-in Rime Ice dictionaries, regenerate the frequent-word lexicon that is used during the first deployment:

```powershell
.\scripts\generate_fast_pinyin_lexicon.ps1
```

The script writes `app/src/main/assets/pinyin_phrases.tsv` with fixed rules for frequency, length and order.
Commit this file together with the source dictionaries.

## Device selection

A device script chooses the device in this order:
the `-Serial` argument, the environment variable `ANDROID_SERIAL`, and the only connected adb device.
If several devices are connected and no serial is given, the script fails.
This stops test input from going to the wrong phone.

## Output

- Build scripts copy the APK to `artifacts/openIME-1.0-debug.apk`. Git ignores this folder.
- Performance, stress and upgrade scripts write to `docs/perf/`, `docs/stress/` and `docs/upgrade/`.
- Visual scripts write to `docs/visual/check/`. `.gitignore` excludes these local screenshots.
- Official SOP evidence goes to `.local/test-runs/`. It stores only a hash prefix of the device serial.
