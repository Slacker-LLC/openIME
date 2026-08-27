# 本地语音输入法测试阶段报告

> 当前状态：核心 P0 与 Lifecycle 已通过；性能/压力为部分基线，Soak、Android 10/12、WebView/三方 Editor 仍未完成，因此不声明最终验收完成。

## 已通过

- Android 系统 IME 注册、enable、set。
- Pinyin26 `nihao → 你好`。
- Pinyin9 `64426 → 你好`。
- Digits `123 → 123`。
- Space 首候选、候选回退、提交后删除。
- Emoji、符号、多行 Enter、面板返回、模式循环。
- Unit 28/28、Lint 0 error、API 34 instrumented 4/4。
- 实机 Xiaomi 24129PN74C 全链路回归。
- 实机 Lifecycle：编辑器 A→B、hide/show、重启 composition 清理、Voice 关闭。
- 实机 Visual Structure Check：Pinyin26/English26/P9/Digits 结构检查 PASS。
- Upgrade：`DARK` 主题在 `adb install -r` 后保留。
- Perf 基线：mode P50≈63.7ms、state P50≈57.5ms（host→adb 回程）。
- 密码字段无 logcat/文件泄漏；麦克风 denied/granted 不崩溃。
- 归一化坐标：`KeyboardGeometry.kt` + 语义化 E2E bridge。
- 当前只保留拼音九键；英文九键已从产品入口移除。

## 未完成 / 风险

- Real-device `am instrument`：MIUI 上未能稳定完成，API 34 emulator 结果保留。
- Android 10 / Android 12：未实测。
- WebView / Compose / 第三方 App Editor：未实测。
- Performance：只有 host→adb 回程基线，无真实 frame/Jank 数据。
- Stress：30 轮循环无崩溃，10000 次/Soak 未执行。
- 手写识别 Provider：未配置，只做显式 `NotConfigured` 边界。

## 证据

`docs/TEST_MATRIX.md`、`docs/TEST_REPORT.md`、`docs/REAL_DEVICE_E2E.md`、`docs/visual/check/`、`docs/perf/baseline.json`、`docs/upgrade/settings-retention.json`。
