# 测试脚本

所有脚本都从仓库根目录解析 APK 和包名。它们不会依赖固定屏幕坐标；需要设备的
脚本必须显式传入 `-Serial`，或者设置 `ANDROID_SERIAL`。

## 常用命令

```powershell
.\scripts\test_sop.ps1 -Level L0 -Serial <serial>
.\scripts\test_sop.ps1 -Level L1 -Serial <serial>
.\scripts\test_sop.ps1 -Level L2 -Serial <serial> -FreshInstall
.\scripts\test_sop.ps1 -Level L3 -Serial <serial> -FreshInstall
.\scripts\build_ascii.ps1
.\scripts\core_regression.ps1 -Serial <serial>
.\scripts\nine_key_regression.ps1 -Serial <serial>
.\scripts\clear_delete_voice_regression.ps1 -Serial <serial>
.\scripts\typing_engine_regression.ps1 -Serial <serial>
.\scripts\extended_regression.ps1 -Serial <serial>
.\scripts\panel_data_regression.ps1 -Serial <serial>
.\scripts\lifecycle_regression.ps1 -Serial <serial>
.\scripts\visual_check.ps1 -Serial <serial>
.\scripts\perf_baseline.ps1 -Serial <serial>
.\scripts\stress_baseline.ps1 -Serial <serial>
.\scripts\upgrade_regression.ps1 -Serial <serial>
```

`test_sop.ps1` 是正式统一入口：失败即停，并把步骤日志、前后截图、UI 树、短录屏、
logcat、meminfo、gfxinfo、APK 哈希和设备元数据写入 `.local/test-runs/`。L2/L3 同时复制
人工验收清单，清单未完成时只能标记“自动化通过”，不能标记发布通过。

查看某一级将执行哪些脚本而不连接设备：

```powershell
.\scripts\test_sop.ps1 -Level L3 -ListOnly
```

`-FreshInstall` 会卸载 openIME 并清除它的本地数据，只能用于允许重置的测试设备。

`typing_engine_regression.ps1` 覆盖全拼、显式分词、连续长句、扩展词候选、选词后
composition 清空及随后回删目标文本。它使用候选文本和调试状态定位，不依赖屏幕坐标。

`clear_delete_voice_regression.ps1` 连续执行 3 轮输入、删除键上滑清空、拼音预编辑清空、
逐字删除、长按空格语音回调和语音后再次删除，并逐步核对编辑器、composition 与语音状态。

`panel_data_regression.ps1` 验证系统剪贴板读取/插入与常用语新增、保存、使用、编辑、删除。
它只创建带唯一编号的测试短语并在通过后删除；建议在模拟器或专用测试设备上执行。

`field_matrix_regression.ps1` 驱动 debug-only 输入框实验室，检查普通、多行、密码、数字、
电话、邮箱、URL、搜索、聊天、表单、1 万字和选区替换输入框的默认键盘模式与关键提交链路。
`security_regression.ps1` 检查权限面、密码 composition、logcat 和应用私有文件泄漏。

更新内置 Rime Ice 词典后，可重新生成首次部署期间使用的高频词库：

```powershell
.\scripts\generate_fast_pinyin_lexicon.ps1
```

生成脚本会按固定词频、长度和排序规则写入
`app/src/main/assets/pinyin_phrases.tsv`；该文件需要与来源词典一并提交。

## 设备选择

设备脚本的优先级为：命令行 `-Serial`、环境变量 `ANDROID_SERIAL`、当前唯一已连接
的 adb 设备。如果连接了多个设备且没有明确指定 Serial，脚本会直接失败，避免把
测试输入发送到错误的手机。

## 输出

- 构建脚本将 APK 复制到被忽略的 `artifacts/openIME-1.0-debug.apk`。
- 性能、压力和升级脚本写入 `docs/perf/`、`docs/stress/` 和 `docs/upgrade/`。
- 视觉脚本写入 `docs/visual/check/`；这些本地截图默认由 `.gitignore` 排除。
- 正式 SOP 证据写入 `.local/test-runs/`，其中设备序列号只保存哈希前缀。
