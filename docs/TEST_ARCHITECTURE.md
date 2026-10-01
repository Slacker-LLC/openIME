# Test Architecture

openIME 的测试分四层。文档只记录真实存在的入口；具体当前结果以 GitHub Actions 和本地 SOP 证据为准，不在文档里长期写死 PASS。

## 1. JVM Unit

覆盖纯 Kotlin/可隔离策略，例如：

- `CandidateEngineTest.kt`
- `CandidatePipelineTest.kt`
- `CandidateSnapshotTest.kt`
- `InputConnectionGatewayTest.kt`
- `EditorInfoAdapterTest.kt`
- `ImeStateTest.kt`
- `KeyboardLayoutMetricsTest.kt`
- Voice / Rime policy 与生命周期相关单测

已经没有生产调用方的历史 helper 及其“自测自己”的测试应直接删除，而不是为了测试数量保留。

## 2. Android Instrumentation

`app/src/androidTest` 覆盖真实 View、InputConnection、IME 生命周期和 API 兼容：

- `AuditInteractionInstrumentedTest.kt`
- `ClipboardRetentionInstrumentedTest.kt`
- `TextEditControlsInstrumentedTest.kt`
- `NineKeyChineseInstrumentedTest.kt`
- `CandidatePresentationInstrumentedTest.kt`
- `CompatibilityApiInstrumentedTest.kt`
- voice lifecycle / ownership tests

GitHub Actions 当前兼容矩阵运行 API 29 和 API 31。更高 API 和真机属于额外验收，不应冒充 CI 覆盖。

## 3. Debug-only E2E Harness

`app/src/debug` 包含：

- `DebugKeyboardActivity`
- `E2ETestReceiver`
- `ImeTestLabActivity`
- `LifecycleTestActivity`
- `SecurityTestActivity`

这些入口只属于 debug 构建，release APK 不应依赖它们。

## 4. Script / Device SOP

`scripts/test_sop.ps1` 是统一设备测试入口。现有脚本覆盖：

- core/extended typing
- 9-key
- clear/delete/voice
- field matrix
- lifecycle
- panel data
- security
- visual
- performance/stress
- upgrade

脚本需要真实设备或 emulator 时必须明确 serial，避免命中错误设备。

## CI Gate

`.github/workflows/android.yml` 当前执行：

1. 版本与变更记录检查：`scripts/test_release_check.py`、`scripts/release_check.py check`
2. `:app:testDebugUnitTest`
3. `:app:lintDebug`
4. `:app:assembleDebug`、`:app:assembleDebugAndroidTest`，并校验 APK 内的包名与版本
5. API 29 全部仪器测试
6. API 31 全部仪器测试

`am instrument` 即使有测试失败也以 0 退出，所以兼容矩阵会检查输出末尾的 `OK (N tests)`，
否则让 job 失败；报告保存在 `compatibility-api-*` artifact 里。

`main` 要求 **Build and verify** 通过才能合并；兼容矩阵在 PR 上同样运行并会真实变红，
但不阻止合并（原因与如何改成必须通过见 [REPOSITORY.md](REPOSITORY.md)）。
发布工作流额外要求这三项检查都通过，并在 PR 改到发布流水线时用一次性密钥演练
`lintRelease` + `assembleRelease`（见 [RELEASE.md](RELEASE.md)）。

PR 或分支上的“最新 HEAD”必须对应最新 CI；旧 SHA 的绿色结果不能证明新提交通过。

## 坐标与视觉

`KeyboardGeometry.kt` 的 normalized bounds 用于测试和测量；正式布局仍由当前 IME Window 的实际宽高、Insets 和语义几何 token 决定。视觉证据由 `scripts/visual_check.ps1` 生成，不把本地截图当作长期源码资产提交。
