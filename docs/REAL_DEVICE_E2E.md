# Real Device E2E Evidence

## 设备

| 项目 | 值 |
| -- | -- |
| 厂商 / 型号 | Xiaomi 24129PN74C |
| Serial | `f0e2ff6f` |
| Android | 16 (API 36) |
| 分辨率 | 1200×2670 |
| Density | 520dpi |
| Target SDK | 36 |
| 测试前默认输入法 | `com.bytedance.android.doubaoime/.ImeService` |
| 测试后默认输入法 | 已恢复为 `com.bytedance.android.doubaoime/.ImeService` |
| RECORD_AUDIO | 测试时 denied/granted/revoked 均验证，结束后恢复 denied |
| IME 独立标识 | 本原型使用 `llc.slacker.openime`，显示名为 `openIME`；`dev.openminispet.android` 保持独立 |

## 执行命令

```powershell
.\scripts\core_regression.ps1 -Serial f0e2ff6f
.\scripts\extended_regression.ps1 -Serial f0e2ff6f
```

## 结果

### Core Regression

```text
CR 020 nihao -> 你好 PASS
CR 030 64426 -> 你好 PASS
CR 040 4663 -> good PASS
CR 050 123 -> 123 PASS
CORE REGRESSION SUITE PASS
```

### Extended Regression

```text
EXT 021 space first candidate PASS
EXT 023 composition backspace PASS
EXT 080 committed backspace PASS
EXT 060 emoji commit PASS
EXT 070 symbol commit PASS
EXT 090 multiline enter PASS
EXT 110 panel back PASS
EXT 100 mode switch PASS
EXTENDED REGRESSION SUITE PASS
```

## 截图

`docs/visual/real-device/`：

- `real-pinyin26.png`
- `real-english26.png`
- `real-pinyin9.png`
- `real-english-t9.png`
- `real-digits.png`
- `real-emoji.png`
- `real-symbols.png`

## Stress / Memory

- 30 轮 mode / Emoji / panel-back 循环，共 90 次真实 IME 命令。
- 结束进程仍存活，logcat 无 FATAL/ANR。
- `dumpsys meminfo` PSS 约 29.7 MB。

## Lifecycle Evidence

`scripts/lifecycle_regression.ps1`：

```text
LC composition active on A PASS
LC composition cleared on B PASS
LC composition cleared after hide/show PASS
LC editor text retained after hide/show PASS
LC voice stopped after panel close PASS
LC app restart clears composition PASS
LIFECYCLE REGRESSION SUITE PASS
```

## Reference Baseline

用户提供的参考输入法截图已整理到 `docs/reference_ime/`，分析与落地建议见 `docs/REFERENCE_IME_GUIDE.md`。

## Performance / Upgrade

- `perf_baseline.ps1`：20 次 mode switch P50=63.7ms / P95=72.55ms；20 次 state P50=57.46ms / P95=62.39ms。这是 host→adb 回程，不是帧率/Jank。
- `upgrade_regression.ps1`：`DARK` 主题写入 `ime_settings.xml`，`adb install -r` 后仍保留，`UPGRADE SETTINGS RETENTION PASS`。

## Security Evidence

- 密码字段输入 `SECRET_IME_TEST_739251` 后，字段显示掩码。
- `adb logcat -d` 全文不包含该字符串。
- `run-as llc.slacker.openime find ... grep` 不包含该字符串。
- App 数据目录只有 `ime_settings.xml`，没有密码、composition 或 clipboard 历史数据。
- 测试明文没有出现在 adb 命令行；`type64` 使用 Base64 传输，Receiver 对 type 命令日志脱敏。
- 麦克风 denied 和 granted 两种状态点击语音按钮均不崩溃。
