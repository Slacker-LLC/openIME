# openIME

独立的 Android 系统输入法，包名为 `llc.slacker.openime`，应用显示名为
`openIME`。它不依赖 `Minis for Android`，也不与其他输入法共享进程、数据或服务。

[![Android CI](https://github.com/Slacker-LLC/openIME/actions/workflows/android.yml/badge.svg)](https://github.com/Slacker-LLC/openIME/actions/workflows/android.yml)

## 项目定位

openIME 的界面、输入法引擎和本地语音链路均在同一个独立 APK 内运行：

```text
键盘 View
    ↓
输入状态与候选栏
    ↓
librime / OpenCC（中文拼音、候选、简繁转换）
    ↓
InputConnection
    ↓
当前应用的输入框
```

语音输入使用 APK 内置的 sherpa-onnx runtime 和中英双语模型；短按空格提交空格或
首选候选，长按空格进入语音输入。没有网络语音服务，也没有 `INTERNET` 权限。

## 当前能力

- 26 键拼音、9 键拼音、英文 26 键、英文 T9、数字与符号输入。
- 基于 librime 的预编辑、候选词、分词、光标编辑、删除和提交链路。
- OpenCC 简繁转换与 Rime 词典数据，候选结果通过 `setComposingText()` 更新，选中后
  通过 `commitText()` 写入当前编辑器。
- Emoji、符号、剪贴板、文本编辑、手写入口、浮动键盘和设置面板。
- 根据输入法窗口实际可用宽度动态计算列宽与间距；宽屏限制内容最大宽度并居中，
  系统底部区域通过 WindowInsets 处理。
- 空格短按/长按语音、删除键上滑清空预编辑与候选状态。
- Android 密码编辑器的隐私边界、麦克风权限失败回退和本地模型校验。

## 仓库结构

```text
app/                  Android APK、IME Service、Rime JNI、内置模型与词典
scripts/              Windows PowerShell 构建、回归、性能和视觉检查脚本
docs/                 架构、适配、测试证据和本地模型接入文档
ui-suite/             Web UI 原型与交互参考，不参与 APK 构建
gradle/               Gradle Wrapper
.github/              GitHub Actions、Issue 模板和 PR 模板
```

第三方 C/C++ 源码位于 `app/src/main/cpp/vendor/`，其上游许可证随源代码保留。

## 环境要求

- Android Studio 或 JDK 17。
- Android SDK Platform 36。
- Android NDK `27.0.12077973`。
- CMake `3.22.1`。
- Git LFS（语音模型和 sherpa-onnx AAR 使用 LFS）。

首次克隆后请确认大文件已下载：

```bash
git lfs install
git lfs pull
```

## 构建

标准构建：

```bash
./gradlew :app:assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug
```

如果 Windows 工作区路径包含中文导致 Gradle/JDK 17 的测试 worker 无法解析类路径，
使用仓库提供的 ASCII 临时构建脚本：

```powershell
.\scripts\build_ascii.ps1
```

APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。本地交付副本可放在被忽略的
`artifacts/openIME-1.0-debug.apk`，不会提交到源码仓库。

## 安装并启用

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable --user 0 llc.slacker.openime/.LocalVoiceImeService
adb shell ime set --user 0 llc.slacker.openime/.LocalVoiceImeService
```

也可以打开 APK 的设置页，按系统提示启用 `openIME`。调试测试 Activity 和 E2E
Receiver 只存在于 debug 变体，不会成为正式输入法的公共控制入口。

## 验证

本地 JVM 测试与构建：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain
```

真实 IME 回归需要明确指定设备 Serial，避免误操作其他手机：

```powershell
.\scripts\core_regression.ps1 -Serial <serial>
.\scripts\extended_regression.ps1 -Serial <serial>
.\scripts\lifecycle_regression.ps1 -Serial <serial>
.\scripts\visual_check.ps1 -Serial <serial>
```

更多说明见：

- [文档索引](docs/README.md)
- [输入法架构](docs/ARCHITECTURE.md)
- [本地语音模型接入边界](docs/LOCAL_VOICE_MODEL.md)
- [适配与坐标规范](docs/COORDINATE_SYSTEM.md)
- [测试报告](docs/TEST_REPORT.md)
- [脚本说明](scripts/README.md)

## 隐私与安全

语音 PCM 只在当前会话的有界内存缓冲区中处理，结束、取消或失败时清空；模型和
词典随 APK 提供。密码输入框不写入候选、剪贴板或日志。发现安全问题请不要直接
创建公开 Issue，先按 [SECURITY.md](SECURITY.md) 联系维护者。

## 许可证

主项目许可证尚未单独声明；公开仓库不等同于授予再分发或商业使用许可。第三方
组件的许可证保留在各自目录中，详见 [docs/LICENSING.md](docs/LICENSING.md)。
