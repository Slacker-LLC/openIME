# Compatibility

A keyboard must work inside other apps.
This document lists each input environment, how openIME handles it and how we verify it.
Read it before you change the input path.

## Editors

| Environment | Handling | Verification |
|---|---|---|
| Normal, multiline, search and chat fields (EditText, WebView, Compose) | Pinyin composition and candidates. Enter uses the IME action or a raw Enter. | `core_regression.sh`, `ImeTestLabActivity` |
| Custom-drawn, Compose or web fields with no select-all and no `ExtractedText` | Clear and restore use only the text before and after the cursor. The gateway refuses to delete when the returned length equals the requested length. | `InputConnectionGatewayTest`, `CustomEditorTestActivity` |
| Password fields (visible, web and numeric passwords) | No composition: each letter goes in directly. No word or correction learning. openIME does not read the field text. Clipboard history works. Voice works and inserts the final text only. | `security_regression.ps1`, `VoiceFinalPolicyTest`, `ClipboardSensitivityPolicyTest` |
| Number, phone, date and time fields | The start keyboard is digits. | `EditorInfoAdapterTest` |
| Email and URL fields | The start keyboard is English. | `EditorInfoAdapterTest` |
| `TYPE_NULL` (terminals, games, remote desktops) | The start keyboard is English. openIME sends each letter as a real key event. Delete uses key events, because the `InputConnection` of these apps is often a dummy that reports success and deletes nothing. | `InputConnectionGatewayTest` |
| No personalized learning flag (incognito) | openIME does not learn words and does not record the clipboard. | `PersonalizedLearningPolicy` |
| Very large commits (large paste, long voice text) | openIME commits in chunks of at most 32,000 characters. It never splits a surrogate pair. This keeps each Binder transaction below the size limit. | `CrashResilienceTest` |

## Autofill (Android 11 and later)

`method.xml` declares `supportsInlineSuggestions`.
The keyboard requests up to five entries that are 48 dp high.
It shows the entries that the system renders in the toolbar area.

The keyboard only hosts the entries.
The system writes the filled text into the field.
The keyboard cannot read it, so entries also work in password fields.

A response can arrive before the keyboard view exists.
The service keeps the latest response and shows it when the view is ready.
It must not reject the response, because the system then falls back to the dropdown menu.
The service clears the response when the user leaves the field.

Android 10 and earlier, and providers without inline support, use the system dropdown menu.

Verification: `InlineChipTrackerTest` and `scripts/beta3_e2e.py autofill`.
The debug build includes a test provider (`TestAutofillService`) and `AutofillTestActivity`.

## Physical keyboards

Physical keyboards include tablets, foldable keyboard cases, Chromebooks, desktop mode and emulators.
In 26-key Chinese mode:

- Letters build pinyin.
- Space selects the first candidate.
- Keys 1 to 9 select a candidate.
- Enter keeps the typed pinyin.
- Esc cancels.
- `'` splits a syllable. Backspace deletes pinyin.
- `, . ? ! ; : ( )` produce full-width punctuation.
  After a digit, `, . :` stay ASCII, so `3.14` does not change.
- Ctrl, Alt and Meta combinations, capital letters and other keys go to the app unchanged.
  A capital letter first ends the current composition.

openIME does not handle the physical keyboard in English, nine-key and digit modes, in password fields and in `TYPE_NULL` editors.
The keyboard panel must be visible, because the candidates appear on the panel.

Verification: `HardwareKeyPolicyTest`, `core_regression.sh` cases 040 to 043.

## Display environments

openIME supports these environments:

- landscape (the keyboard is a bottom panel and never uses fullscreen extract mode)
- font scale 130% and 200% (key labels grow to at most 1.3 times, and function key labels shrink to fit)
- dark theme
- small and narrow screens
- tablets in portrait and landscape
- the inner screen of a foldable

Verification:

- `scripts/display_matrix_regression.py` checks for a bottom panel and for every key inside the window.
- `DisplayEnvironmentInstrumentedTest`
- `scripts/beta3_e2e.py`: cursor swipe in landscape, letter-key hints, emoji suggestions, voice processing and autofill

## Android versions

`minSdk` is 26.
CI runs all instrumentation tests on API 26, 29, 31 and 34.
Developers also run them on API 36 locally.
A release needs a pass on API 29 and API 31.

## Crashes, freezes and conflicts

- One failed key handler does not stop the keyboard process.
  openIME records the failure (only the exception type and the code location, never typed text), drops the unfinished composition and continues.
  Verification: `core_regression.sh` case 038, with the debug command `fail-next`.
- openIME keeps a crash history of Java crashes, native crashes and ANRs (from the Android 11 exit records).
  Three crashes in 10 minutes start **safe mode**.
  Safe mode turns off librime and voice preloading and types with the built-in lexicon.
  设置 → 关于与数据 → 诊断 (Settings → About and data → Diagnostics) copies the diagnostics and leaves safe mode.
- openIME writes a marker before it starts librime and clears it after the health check.
  If the marker stays and the last process died from a native crash, openIME escalates in steps:
  clean the compiled files, rename and rebuild the user dictionary, and then do not start the native engine again.
  A start that the user or the system force-stopped does not count as a crash.
- While voice input mutes media volume, openIME saves the old volume on disk and runs a two-minute watchdog.
  If the process dies during recording, the next start restores the volume.
  Verification: `VoiceMediaMuteRecoveryInstrumentedTest`.
- The lexicon and the nine-key decoder are built on background threads.
  Cold start uses about 0.3 s less main-thread time.
- Backspace does not make three synchronous Binder calls each time.
  When the editor reports a collapsed cursor, openIME does not ask for the selected text.
  A frozen app would block every call.

## Not covered

- A physical keyboard in nine-key mode (letters go to the app).
- Candidates when the user hides the keyboard panel on a physical keyboard. This needs a separate candidate window.
- Vendor differences on real devices (Xiaomi, OPPO, Samsung). We have results only from emulators and CI emulators.
