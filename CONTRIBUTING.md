# 贡献指南

## 开始之前

1. 安装 JDK 17、Android SDK 36、NDK `27.0.12077973`、CMake `3.22.1` 和 Git LFS。
2. 执行 `git lfs pull`，确认语音模型和 AAR 不是文本指针。
3. 先阅读 [README.md](README.md)、[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
   和 [docs/TEST_ARCHITECTURE.md](docs/TEST_ARCHITECTURE.md)。

## 分支与提交

- 从最新的 `main` 创建短生命周期分支，命名为 `类型/主题`，类型取 `feat`、`fix`、`docs`、
  `chore`、`ci`、`refactor`、`test`，例如 `fix/pinyin-candidate`、`docs/repository`。
- 分支里的提交保持“一个提交一个主题”，说明用简短的一句话写结果（中文或英文均可），例如
  `修复九键拼音候选提交`、`fix: keep the rest of the input composing`。合并时会被 squash，
  最终进入 `main` 的提交信息是 PR 标题和描述。
- 不要提交 `local.properties`、Gradle/build 输出、根目录截图、UI dump、设备日志、
  密码、录音、签名密钥或未经确认的模型文件。

## 变更记录与版本

- 用户可见的改动（功能、行为、修复、权限、数据格式）在同一个 PR 里写进 `CHANGELOG.md` 的
  `## [Unreleased]`；纯内部改动不需要。
- 不要在功能 PR 里修改 `VERSION`：版本只在发布 PR 里升级。版本规则、签名和发布步骤见
  [docs/RELEASE.md](docs/RELEASE.md)，分支与合并规则见 [docs/REPOSITORY.md](docs/REPOSITORY.md)。

## 提交前检查

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain
git diff --check
git status --short
```

Linux / macOS / Git Bash 上再运行版本与变更记录检查（CI 也会运行）：

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/release_check.py check
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

`main` 受保护：只能通过 PR 合并，且 **Build and verify** 必须通过；PR 一律 squash 合并，
合并后分支自动删除。API 29 / 31 兼容测试同样会在 PR 上运行，红了请先修再合并。
