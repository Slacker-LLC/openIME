# openIME（独立 APK）

独立 Android 系统输入法，非 WebView。设计基准：`ui-suite/`（未压缩 HTML/CSS/JS 原型，仅源码参考，不打包进 APK）。

## 核心实现

- `LocalVoiceImeService`：`InputMethodService` 生命周期、EditorInfo 适配、密码框隐私边界。
- `ImeKeyboardView`：原生 View 键盘/候选/工具栏/全部面板。
- `CandidateEngine`：399 拼音、28 词组、英文联想、9 键映射、真实 T9 数字映射、模糊音。
- `InputConnectionGateway`：`InputConnection` 注入，setComposing/commit/delete/光标/剪贴板。
- `SpeechRecognitionProvider`：Android 系统语音识别，真实 partial/final/RMS/error。
- `HandwritingPadView`/`HandwritingProvider`：Canvas 笔迹与识别边界，未配置时明确提示。
- `ClipboardHistoryRepository`：真实剪贴板历史持久化。

## 构建与启用

```bash
cd <project>
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable llc.slacker.openime/.LocalVoiceImeService
adb shell ime set llc.slacker.openime/.LocalVoiceImeService
```

## 自动化与归一化坐标

- `scripts/core_regression.ps1`：核心四路真实 IME 回归。
- `scripts/extended_regression.ps1`：空格/删除/Emoji/Symbol/Enter/面板/模式。
- `scripts/lifecycle_regression.ps1`：编辑器切换、hide/show、composition 清理、Voice 停止。
- `scripts/visual_check.ps1`：真实 IME 结构与归一化 bounds 检查。
- `scripts/perf_baseline.ps1`：ADB 命令延迟基线。
- `scripts/upgrade_regression.ps1`：设置覆盖安装保留测试。
- `docs/COORDINATE_SYSTEM.md`：normalized 0..1 坐标规范。
- `docs/TEST_MATRIX.md` / `docs/TEST_REPORT.md`：最新测试证据。
- `docs/reference_ime/`：用户提供的参考输入法布局基线；分析见 `docs/REFERENCE_IME_GUIDE.md`。

## 最新验证状态

已在 API 34 模拟器（Instrumented 4/4）与 Xiaomi 24129PN74C / Android 16 实机（核心 + 扩展回归全部 PASS）上执行；密码泄漏、麦克风权限、隐藏/显示 IME 也已实测。性能、压力、Soak、Android 10/12 兼容和真实 ActivityScenario 仍为待测项。
