<p align="center">
  <img src="docs/images/openime-brand.png" width="96" height="96" alt="openIME logo">
</p>

<h1 align="center">openIME</h1>

<p align="center"><strong>A local-first Chinese input method for Android.</strong></p>

<p align="center">
  <a href="https://github.com/Slacker-LLC/openIME/releases"><img alt="Release" src="https://img.shields.io/github/v/release/Slacker-LLC/openIME?include_prereleases"></a>
  <a href="https://github.com/Slacker-LLC/openIME/actions/workflows/android.yml"><img alt="Android CI" src="https://github.com/Slacker-LLC/openIME/actions/workflows/android.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0-only" src="https://img.shields.io/badge/license-GPL--3.0--only-blue"></a>
</p>

<p align="center">English · <a href="README.zh-CN.md">简体中文</a></p>

> **Beta software (0.0.x).**
> Features, settings and data formats can change between releases.
> Do not use openIME as your only keyboard.
> Report problems in [Issues](https://github.com/Slacker-LLC/openIME/issues).

openIME is a system keyboard for Android.
Pinyin input, word learning and voice recognition all run on the device.
The app has no `INTERNET` permission, so your text cannot leave the phone.

<table>
  <tr>
    <td><img src="docs/images/keyboard-pinyin26.png" width="200" alt="26-key pinyin keyboard with candidates for nihao"></td>
    <td><img src="docs/images/keyboard-pinyin9.png" width="200" alt="Nine-key pinyin keyboard with the syllable column"></td>
    <td><img src="docs/images/keyboard-stroke.png" width="200" alt="Stroke keyboard"></td>
    <td><img src="docs/images/keyboard-emoji.png" width="200" alt="Emoji panel"></td>
  </tr>
  <tr align="center">
    <td>26-key pinyin</td>
    <td>Nine-key pinyin</td>
    <td>Stroke</td>
    <td>Emoji</td>
  </tr>
  <tr>
    <td><img src="docs/images/app-home-light.png" width="200" alt="App home screen, light theme"></td>
    <td><img src="docs/images/app-settings-light.png" width="200" alt="Settings screen, light theme"></td>
    <td><img src="docs/images/app-home-dark.png" width="200" alt="App home screen with keyboard, dark theme"></td>
    <td><img src="docs/images/app-settings-dark.png" width="200" alt="Settings screen, dark theme"></td>
  </tr>
  <tr align="center">
    <td>Home</td>
    <td>Settings</td>
    <td>Dark theme</td>
    <td>Dark settings</td>
  </tr>
</table>

## Features

- **Keyboards:** 26-key pinyin, nine-key pinyin, stroke, English 26-key, digits and symbols.
  Emoji, symbols, clipboard history, quick phrases, text editing and a floating keyboard are also available.
- **Pinyin engine:** [librime](https://github.com/rime/librime) with about 900,000 Rime Ice dictionary entries.
  The dictionary is compiled at build time.
  The full dictionary is ready a few seconds after the first start.
- **Pinyin input:** full pinyin, abbreviations, manual word splitting, fuzzy pinyin, user-word learning and simplified/traditional conversion.
- **Nine-key input:** a column on the left lists the pinyin for the next character.
  Tap one syllable to lock it.
- **Voice input:** hold the space key and speak.
  openIME inserts the text when you release the key.
  The bilingual (Chinese and English) model is in the APK and works without a network.
  Options remove filler words and replace punctuation with spaces.
- **Gestures:** swipe up on the delete key to clear the text.
  Swipe left or right on the space key to move the cursor.
- **Emoji suggestions:** after you choose a word, the suggestion bar shows related emoji.
  The word list is in the APK.
- **Autofill (Android 11 and later):** account names and codes from your password manager appear in the toolbar.
- **Numbers and symbols:** each letter key shows a number or symbol.
  Swipe up or press and hold to enter it.
- **Display support:** portrait, landscape, tablets, foldables, dark theme and large font sizes.
  Raw key input fields (terminals, remote desktops, games) and physical keyboards also work.
  See [Compatibility](docs/COMPATIBILITY.md).
- **Self-protection:** after repeated crashes or freezes, openIME starts in safe mode so you can still type.
  In the app, 设置 → 关于与数据 (Settings → About and data) copies diagnostics and exports or imports your data.

## Install

1. Open [Releases](https://github.com/Slacker-LLC/openIME/releases).
   Download the latest `openIME-v*-arm64-release.apk`.
2. Check the SHA-256 checksum that the release notes show:

   ```bash
   sha256sum openIME-v*-arm64-release.apk
   ```

3. Install the APK.
4. Open openIME. Follow the steps to enable it and select it as your keyboard.
5. For voice input, allow the microphone.
   Typing works without it.

Requirements:

- Android 8.0 (API 26) or later.
- An `arm64-v8a` device. This includes most phones made in recent years.

All releases use the same signing key.
The certificate SHA-256 is in [docs/release-cert.sha256](docs/release-cert.sha256).
You can install a new release over an old one.

### Known limitations

- Handwriting input has no recognition engine yet. The entry point is hidden.
- The nine-key keyboard does not work with a physical keyboard.
- Some vendor Android versions limit background input methods. Few devices have been tested.
- Android does not install a version that is older than the installed one.
  Uninstall first. Export your data before you uninstall.

## Privacy and security

- The manifest has no `INTERNET` permission. `allowBackup` is off.
- The dictionary, the voice models and the runtime are in the APK.
- Voice audio stays in memory. openIME clears it when recognition ends, is canceled or fails.
- In password fields, openIME does not compose pinyin, learn words, read the field text or write logs.
  Voice input inserts the final text only.
- Crash records contain the exception type and the stack trace. They never contain typed text.
- Uninstall removes all data on the device, including learned words.

Do not report a security problem in a public issue.
Follow [SECURITY.md](SECURITY.md).

## Build from source

Requirements: JDK 17, Android SDK Platform 36, NDK `27.0.12077973`, CMake `3.22.1` and Git LFS.

```bash
git lfs install && git lfs pull      # voice models and the sherpa-onnx AAR
bash scripts/fetch_rime_deps.sh      # pinned librime dependencies
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
bash scripts/verify_linux.sh         # unit tests, lint, debug APK, test APK
```

The debug APK is for development only.
It includes `x86_64` and test activities, and we do not release it.
Install it and select it as the keyboard:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable --user 0 llc.slacker.openime/.LocalVoiceImeService
adb shell ime set --user 0 llc.slacker.openime/.LocalVoiceImeService
```

Repository layout:

```text
app/          Android app, input method service, Rime JNI, models and dictionaries
scripts/      Build, test and release scripts
docs/         Architecture, testing, release and repository documents
.github/      CI, release workflow, Dependabot, issue and PR templates
VERSION       Single source of the version number
CHANGELOG.md  Change log. It is also the source of the release notes.
```

## Documentation

- [Documentation index](docs/README.md)
- [Architecture](docs/ARCHITECTURE.md) · [Compatibility](docs/COMPATIBILITY.md) · [Test procedure](docs/TEST_SOP.md)
- [Release process](docs/RELEASE.md) · [Repository settings](docs/REPOSITORY.md)
- [Contributing](CONTRIBUTING.md) · [Security policy](SECURITY.md) · [Change log](CHANGELOG.md)

## License

openIME uses the [GPL-3.0-only](LICENSE) license.
Third-party components keep their own licenses.
See [docs/LICENSING.md](docs/LICENSING.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
