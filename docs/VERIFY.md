# 验证结果（模拟器，设备序列号不写入仓库）

## 构建
- `assembleDebug`：成功，APK 见 `artifacts/openIME-1.0-debug.apk`。
- `testDebugUnitTest`：成功，CandidateEngine/状态等测试通过。
- `lintDebug`：成功，0 error。
- `connectedDebugAndroidTest`：成功，API 34 模拟器上 3 个测试全部通过（序列号不入库）。

## 实测链路
1. `adb -s <serial> install -r artifacts/openIME-1.0-debug.apk`。
2. `ime enable` / `ime set llc.slacker.openime/.LocalVoiceImeService`。
3. 打开 `MainActivity` 测试输入框，两次聚焦后 IME Window 由 `LocalVoiceImeService` 弹出。
4. 26 键输入 `nihao`，候选出现，提交后 EditText = `你好`。
5. 九键输入 `64426`，候选出现，提交后 EditText = `你好`。
6. 数字 `123` 直接上屏。
7. `typing_engine_regression.ps1` 验证全拼、显式分词、长句和选词状态清理。

## 截图
第 2 轮 After：`docs/visual/01_real_pinyin26_after.png` 至 `21_theme_macos_keyboard_after.png`，另有 `22_real_key_popup_after.png`、`v2_real_width360_after.png`、`v2_real_width393_after.png`。

旧版 Before 截图仍保留作历史对照，其中英文 T9 截图不代表当前产品入口。
