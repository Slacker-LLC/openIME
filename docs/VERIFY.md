# 验证结果（模拟器，设备序列号不写入仓库）

## 构建
- `assembleDebug`：成功，APK 见 `artifacts/openIME-1.0-debug.apk`。
- `testDebugUnitTest`：成功，CandidateEngine/状态等测试通过。
- `lintDebug`：成功，0 error。
- `connectedDebugAndroidTest`：成功，emulator-5558 上 3 个测试全部通过。

## 实测链路
1. `adb -s <serial> install -r artifacts/openIME-1.0-debug.apk`。
2. `ime enable` / `ime set llc.slacker.openime/.LocalVoiceImeService`。
3. 打开 `MainActivity` 测试输入框，两次聚焦后 IME Window 由 `LocalVoiceImeService` 弹出。
4. 26 键输入 `nihao`，候选出现，提交后 EditText = `你好`。
5. 九键输入 `64426`，候选出现，提交后 EditText = `你好`。
6. 英文 T9 `4663` 产生 `good`。
7. 数字 `123` 直接上屏。
8. 符号、Emoji、手写、语音、剪贴板、文本编辑、设置、游戏、五套主题均截图验证。

## 截图
第 2 轮 After：`docs/visual/01_real_pinyin26_after.png` 至 `21_theme_macos_keyboard_after.png`，另有 `22_real_key_popup_after.png`、`v2_real_width360_after.png`、`v2_real_width393_after.png`。

上轮 Before 全部保留：`30_chinese26.png`、`31_english26.png`、`32_chinese9.png`、`33_english_t9.png`、`34_digits.png`、`36_symbols.png`、`37_emoji.png`、`38_handwriting.png`、`39_voice.png`、`40_clipboard.png`、`41_settings.png`、`42_text_editor.png`、`42_gaming.png`、`43_candidate_expanded.png`、`50_theme_ios.png`、`51_theme_dark.png`、`52_theme_cyberpunk.png`、`53_theme_classic.png`、`54_theme_macos.png`、`211_ime_after.png`、`212_ime_input.png`、`218_nine_ok.png`、`220_icons.png`。
