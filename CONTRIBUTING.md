# 贡献指南

## 开始之前

1. 安装 JDK 17、Android SDK 36、NDK `27.0.12077973`、CMake `3.22.1` 和 Git LFS。
2. 执行 `git lfs pull`，确认语音模型和 AAR 不是文本指针。
3. 先阅读 [README.md](README.md)、[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
   和 [docs/TEST_ARCHITECTURE.md](docs/TEST_ARCHITECTURE.md)。

## 分支与提交

- 从 `main` 创建短生命周期分支，例如 `fix/pinyin-candidate` 或 `docs/repository`。
- 每个提交只解决一个主题，提交说明使用清晰的中文动词开头，例如
  `修复九键拼音候选提交`、`整理 GitHub Actions 构建流程`。
- 不要提交 `local.properties`、Gradle/build 输出、根目录截图、UI dump、设备日志、
  密码、录音或未经确认的模型文件。

## 提交前检查

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain
git diff --check
git status --short
```

如果改动了真实输入链路，使用明确的设备 Serial 运行至少一组核心回归：

```powershell
.\scripts\core_regression.ps1 -Serial <serial>
```

如果改动了布局或 Insets，再运行 `visual_check.ps1`，并在 PR 中说明测试设备的
Android 版本、窗口宽度和是否使用浮动键盘。

## Pull Request

PR 描述应包含：改动目的、影响范围、测试命令和结果、已知限制，以及是否修改了
词典、模型、权限或数据格式。涉及截图时请脱敏；不要在 PR 中上传真实输入内容、
密码、剪贴板或录音。
