# openIME

<p align="center">
  <img src="docs/images/openime-brand.png" width="96" height="96" alt="openIME">
</p>

<p align="center"><strong>本地优先的 Android 中文输入法。</strong></p>

openIME 是独立 Android 系统输入法，包名 `llc.slacker.openime`。拼音候选、用户学习和语音识别均在设备内运行；应用不声明 `INTERNET` 权限。

[![Android CI](https://github.com/Slacker-LLC/openIME/actions/workflows/android.yml/badge.svg)](https://github.com/Slacker-LLC/openIME/actions/workflows/android.yml)

## 下载

正式版本从 [GitHub Releases](https://github.com/Slacker-LLC/openIME/releases/latest) 下载，各版本的变化见 [CHANGELOG.md](CHANGELOG.md)。发布 APK 使用固定发布签名，文件名为 `openIME-v{版本}-arm64-release.apk`，同一 Release 同时提供 `SHA256SUMS.txt` 和第三方许可清单。下载后先校验再安装：

```bash
sha256sum -c SHA256SUMS.txt
```

当前正式发布包只包含 `arm64-v8a`；开发用 Debug APK 仍保留 `x86_64`，用于模拟器回归。

版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)，唯一来源是根目录 `VERSION`；发布流程见 [docs/RELEASE.md](docs/RELEASE.md)。

## 安装四步

1. **启用**：安装 APK 后打开 openIME，进入系统输入法设置并启用 `openIME`。
2. **切换**：回到引导页，打开系统输入法选择器并切换到 `openIME`。
3. **授权（可选）**：需要本地语音输入时授权麦克风；不授权可直接跳过。
4. **试打**：在引导页输入框确认键盘、候选和上屏链路正常。

语音输入长按空格达到系统长按阈值后开始录音，松手后识别并上屏。

## 截图

真实截图由 `scripts/visual_matrix_regression.ps1` 和视觉验收流程生成。仓库不提交占位图；26 键、九键与设置页的浅色/深色截图将在真实视觉矩阵验收后写入 `docs/images/`。

## 项目定位

openIME 的界面、输入法引擎和本地语音链路均在同一个独立 APK 内运行：

```text
键盘 View
    ↓
输入状态与候选栏
    ↓
高频快速词库（首次部署期间即时可用）
    ↓
librime / OpenCC（完整拼音、候选、学习、简繁转换）
    ↓
InputConnection
    ↓
当前应用的输入框
```

语音输入使用 APK 内置的 sherpa-onnx runtime 和中英双语模型；短按空格提交空格或
首选候选，长按空格进入语音输入。没有网络语音服务，也没有 `INTERNET` 权限。

## 当前能力

- 26 键拼音、9 键拼音、英文 26 键、数字与符号输入；不提供英文九键。
- 基于 librime 的全拼、简拼、显式分词、候选词、用户学习、光标编辑、删除和提交链路。
- 内置约 90 万条 Rime Ice 基础及扩展词典记录；首次部署完整词典时，高频快速词库仍可即时提供候选。
- OpenCC 简繁转换与 Rime 词典数据，候选结果通过 `setComposingText()` 更新，选中后
  通过 `commitText()` 写入当前编辑器。
- Emoji、符号、剪贴板、文本编辑、浮动键盘和设置面板；手写目前仅保留笔迹采集 UI，识别引擎尚未接入，正式入口默认隐藏。
- 根据输入法窗口实际可用宽度动态计算列宽与间距；宽屏限制内容最大宽度并居中，
  系统底部区域通过 WindowInsets 处理。
- 空格短按输入空格或提交首选，长按达到系统长按时长（`ViewConfiguration.getLongPressTimeout()`）进入唯一的语音输入流程；删除键上滑清空。
- 选词或首选上屏后统一清除拼音、候选与 Rime composition，随后删除键只处理目标输入框。
- Android 密码编辑器的隐私边界、麦克风权限失败回退和本地模型校验。

## 仓库结构

```text
app/                  Android APK、IME Service、Rime JNI、内置模型与词典
scripts/              PowerShell/Bash 构建、回归、性能、视觉检查与发布脚本
docs/                 架构、适配、测试、发布与仓库管理文档
gradle/               Gradle Wrapper
.github/              GitHub Actions（CI 与发布）、Dependabot、Issue 与 PR 模板
VERSION               版本号的唯一来源（MAJOR.MINOR.PATCH）
CHANGELOG.md          各版本变更记录，同时是 Release 说明的来源
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

首次克隆还需恢复锁定版本的 Rime 原生依赖与头文件（Linux / Git Bash）：

```bash
bash scripts/fetch_rime_deps.sh
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

Debug APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`，仅用于开发与回归；正式分发使用 GitHub Release 中固定签名的 arm64 APK。

开发设备可用 ADB 安装 Debug APK：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable --user 0 llc.slacker.openime/.LocalVoiceImeService
adb shell ime set --user 0 llc.slacker.openime/.LocalVoiceImeService
```

调试测试 Activity 和 E2E Receiver 只存在于 debug 变体，不会成为正式输入法的公共控制入口。

## 验证

Linux 下可一次执行 JVM 测试、Lint、Debug APK 和仪器测试 APK 构建：

```bash
bash scripts/verify_linux.sh
# 仅在明确指定测试设备时运行设备测试：
bash scripts/verify_linux.sh emulator-5554
```

首次启动首页会显示启用状态，提供系统输入法设置与切换入口；麦克风在用户选择
开启本地语音时申请，不影响普通打字。候选展开区按词长分配宽度，相同候选快照
不会重建列表；常用语与自定义符号编辑保留旋转前的草稿。

本地 JVM 测试与构建：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain
```

真实 IME 回归需要明确指定设备 Serial，避免误操作其他手机：

```powershell
.\scripts\test_sop.ps1 -Level L0 -Serial <serial>
.\scripts\core_regression.ps1 -Serial <serial>
.\scripts\typing_engine_regression.ps1 -Serial <serial>
.\scripts\extended_regression.ps1 -Serial <serial>
.\scripts\lifecycle_regression.ps1 -Serial <serial>
.\scripts\visual_check.ps1 -Serial <serial>
```

更多说明见：

- [文档索引](docs/README.md)
- [正式测试 SOP](docs/TEST_SOP.md)
- [输入法架构](docs/ARCHITECTURE.md)
- [本地语音模型接入边界](docs/LOCAL_VOICE_MODEL.md)
- [适配与坐标规范](docs/COORDINATE_SYSTEM.md)
- [测试流程 SOP](docs/TEST_SOP.md)
- [脚本说明](scripts/README.md)
- [发布与版本管理](docs/RELEASE.md)
- [仓库管理](docs/REPOSITORY.md)
- [贡献指南](CONTRIBUTING.md)

## 隐私与安全

**本应用不含联网权限，数据只存在本机。**

语音 PCM 只在当前会话的有界内存缓冲区中处理，结束、取消或失败时清空；模型和词典随 APK 提供。密码输入框不写入候选、剪贴板或日志，但允许用户从剪贴板粘贴。设置中的“关于与数据”可导出/合并导入用户数据；剪贴板历史不导出。卸载会清除本机全部数据，包括学习的用户词库。

发现安全问题请不要直接创建公开 Issue，先按 [SECURITY.md](SECURITY.md) 联系维护者。

## 许可证

主项目许可证尚未单独声明；公开仓库不等同于授予再分发或商业使用许可。第三方
组件的许可证保留在各自目录中，详见 [docs/LICENSING.md](docs/LICENSING.md)。
