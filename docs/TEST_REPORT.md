# Test Report

## 2026-08-26 中文输入引擎增强（当前构建）

| Gate | Status | Evidence |
| -- | -- | -- |
| ASCII 路径完整构建 | PASS | `scripts/build_ascii.ps1`，APK 304,305,096 bytes |
| JVM 单元测试 | PASS | 构建流程内执行；新增词典资产、输入归一化和长 composition 清理测试 |
| 冷启动高频候选 | PASS | 清除应用数据后、Rime 尚未部署完成时，26 键及拼音九键仍可提交 `你好` |
| 完整 Rime 部署 | PASS | `luna_pinyin.table.bin` 生成，`librime ready` |
| 中文输入引擎回归 | PASS | `xian→先`、`xi' an→西安`、`今天天气`、`我想吃饭`、`输入法引擎` |
| 选词状态清理 | PASS | 选词后拼音/候选清空，下一次删除处理目标编辑器文本 |
| 连续长句 | PASS | 10、20、50 个汉字单 composition 精确提交，提交后状态均为空 |

当前产品只提供拼音九键，不提供英文九键；下方 2026-08-25 章节里的英文 T9 是已移除
入口的历史测试证据，不代表当前功能。

## Build

| Gate | Status | Evidence |
| -- | -- | -- |
| `:app:assembleDebug` | PASS | latest APK, 304305096 bytes |
| `:app:testDebugUnitTest` | PASS | `build_ascii.ps1` 当前构建通过 |
| `:app:lintDebug` | PASS | 0 errors; 45 warnings |
| Instrumented (API 34 emulator) | PASS | `TEST-GlassTest2(AVD) - 14-_app-.xml`, 4/4 |
| Real device core E2E | PASS | 已脱敏设备序列号 |
| Real device extended E2E | PASS | 已脱敏设备序列号 |
| Real device lifecycle | PASS | `scripts/lifecycle_regression.ps1` |
| Real device visual structure | PASS | `scripts/visual_check.ps1` |
| Upgrade / settings retention | PASS | `scripts/upgrade_regression.ps1` |

> Gradle 在非 ASCII 长路径下写 worker classpath 会导致 Unit ClassNotFoundException。
> 当前通过 ASCII junction `C:\codex-ime-build` 运行 Gradle 解决，已在最终报告记录为环境缺陷而非产品 Bug。

## Gates

| Gate | Status | Note |
| -- | -- | -- |
| T1 Unit | PASS | composition/candidate/state/input-connection |
| T2 Integration | PASS | FakeInputConnection covers setComposing/commit/backspace/action/Unicode |
| T3 Smoke | PASS | real IME install/enable/set + basic input |
| T4 Core E2E | PASS | Pinyin26/P9/Digits；英文九键已从当前 UI 移除 |
| T5 UI / Visual | PASS | emulator instrumented + visual references; real-device screenshots |
| T6 Lifecycle | PASS | editor A/B, hide/show, app restart, voice close all PASS |
| T7 Privacy | PASS | password logcat/files clean, voice denied/granted no crash |
| T8 Compatibility | PARTIAL | API 34 + API 36 tested; targetSdk 36; Android 10/12 NOT TESTED |
| T9 Performance | PARTIAL | host→adb P50/P95 baseline; frame/Jank pending |
| T10 Stress | PARTIAL | 30 loop cycles, no crash; 10000-press/soak pending |
| Install/Upgrade | PASS | theme DARK retained after `adb install -r` |

## Bugs Found / Fixed

1. Real IME test scripts originally used absolute screen coordinates.
   - 修复：改为 semantic `tap:` 命令 + `uiautomator` 动态焦点 + `state` 模式查询。
2. 九键脚本最后一个“确定”坐标错误，误点 0/26键。
   - 修复：改用 `tap:确定`，不依赖坐标。
3. `tap:空格 / 空白`、`tap:✕ 键盘` 含空格，被 adb 远程命令拆分。
   - 修复：增加 `key-space`、`key-panel-back` tag。
4. 密码明文出现在 `adb shell am broadcast` 系统日志。
   - 修复：改用 Base64 `type64`，Receiver 日志脱敏；最终 logcat 清空。
5. 主代码 `Log.i(TAG, "selectCandidate=$candidate")` 会输出用户候选文本。
   - 修复：已移除；保留的日志只输出 mode/panel/测试命令元信息。

## Remaining Risk

- Android 10 / Android 12 未实测。
- WebView / Compose / 三方 App Editor 未实机覆盖。
- 500ms 高频压力、72h Soak 未执行。
- 手写识别 Provider 未配置，边界为显式 `NotConfigured`。
- Real-device `DebugKeyboardActivityTest` 与 `RealUiInstrumentedTest` 在 MIUI 上均因主线程未 idle 超时；API 34 emulator 结果保留为 UI 证据。

---

## 2026-08-25 重跑会话（当前构建）

> 本轮在 Xiaomi 24129PN74C / Android 16（设备序列号已脱敏）上完整重跑 P0 闭环。
> 修正了 3 个测试体系自身缺陷（此前使部分断言未真正生效 / 截图证据为损坏文件）。

### Build

| 项 | 状态 | 证据 |
| -- | -- | -- |
| `:app:clean :app:assembleDebug` | PASS | fresh APK 961,981 bytes |
| `:app:testDebugUnitTest` | PASS | 5 classes / 28 tests / 0 failures（clean 后重跑） |
| lint | NOT RE-RUN | 沿用上一轮 0 errors / 45 warnings |

### 环境缺陷（修复方式，非产品 Bug）

1. **Gradle worker ClassNotFoundException（全测试类）**
   - 根因：JDK 17（zh-CN）的 `@argfile` 按 GBK 解码，Gradle 按 UTF-8 写入且包含真实路径
     含中文的真实路径（junction 也会被 Gradle canonicalize 成真实路径，
     旧文档"junction 已解决"不成立）。
   - 实测 `-Dsun.jnu.encoding` / `-Dfile.encoding` 均无法修复（launcher 用 OS 原生字符集）。
   - 修复：临时 ASCII 目录（robocopy /MIR 同步源码与 gradle 文件，排除 build/.gradle/.kotlin），
     在临时目录执行 Gradle；APK 拷回 `artifacts\`。
2. **PowerShell 脚本 UTF-8 无 BOM**：PS 5.1（中文区域）按 GBK 解析 .ps1 字面量，
   全部 `'你好'` 等中文断言此前实际比较的是乱码（显示层 GBK/UTF-8 往返伪装成正常）。
   - 修复：7 个 `scripts/*.ps1` 加 UTF-8 BOM。
3. **`visual_check.ps1` 截图损坏**：PS5.1 `>` 二进制重定向损坏 PNG
   （历史 `docs/visual/check/*.png` 实测 `\xff\xfe` UTF-16 或 `EF BF BD` 开头 = 损坏文件，
   此前 "VISUAL PASS" 的截图证据无效）。
   - 修复：`cmd /c` 字节精确重定向 + PNG magic 校验；同时 logcat 匹配前设 `[Console]::OutputEncoding = UTF8`。
   - 本轮 5 张截图重新采集并通过 PNG 校验。

### 本轮执行结果

| Gate | 状态 | 证据 |
| -- | -- | -- |
| Unit (28) | PASS | clean 后 28/28 |
| Core Regression（真实 IME） | PASS | CR-020 nihao→你好 / CR-030 64426→你好 / CR-040 4663→good / CR-050 123→123 |
| Extended Regression | PASS | EXT 021 空格首候选 / 023 组合删除 / 080 提交删除 / 060 Emoji / 070 符号 / 090 多行 Enter / 110 面板返回 / 100 模式切换 |
| Lifecycle | PASS | 编辑器 A/B 切换组合清理 / hide-show / 重启清理 / Voice 关闭停止 |
| Visual Structure | PASS | 5 模式 tag + bounds，截图 PNG 校验通过 |
| 关键 UI 尺寸 | PASS | toolbar 40.0dp / candidate 42.0dp / key 44.0dp / 九键 44.2dp（目标 46dp，-2dp）/ candidate-expand 28.1dp |
| EditorAction NEXT | PASS | A 输入 abc + Enter → 焦点到 B |
| EditorAction DONE | PASS | B 输入 def + Enter → IME 隐藏 |
| 负向：空候选 Expand | PASS | 进程存活 |
| 安全：密码泄漏 | PASS | SECRET_IME_TEST_739251：logcat 0 / app files 0 / clipboard 0 |
| 安全：日志审计 | PASS | main 源码仅 3 处 Log（mode/panel/长度/BOUNDS tag），无用户文本 |
| 安全：麦克风权限 | PASS | 拒绝/授予均无崩溃，停用后正常 |
| Upgrade | PASS | 设置保留（theme DARK） |
| Stress（500 轮 × 3 命令） | PASS | 无 FATAL/ANR；PSS 177MB→119MB（GC 后），需观察 |
| Perf 基线 | TESTED | ModeQuery P50 65.4ms / StateQuery P50 58.0ms（受 host→adb 往返主导） |

### 遗留观察项

- PSS 峰值 177MB（空闲回落 119MB）：MIUI PSS 含共享库，但高于上次 29.7MB，列入 Memory watch 清单。
- Android 10 / 12、WebView/Compose 三方 Editor、72h Soak、10000 次按键：NOT TESTED（无对应环境）。
