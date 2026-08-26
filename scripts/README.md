# 测试脚本

所有脚本都从仓库根目录解析 APK 和包名。它们不会依赖固定屏幕坐标；需要设备的
脚本必须显式传入 `-Serial`，或者设置 `ANDROID_SERIAL`。

## 常用命令

```powershell
.\scripts\build_ascii.ps1
.\scripts\core_regression.ps1 -Serial <serial>
.\scripts\extended_regression.ps1 -Serial <serial>
.\scripts\lifecycle_regression.ps1 -Serial <serial>
.\scripts\visual_check.ps1 -Serial <serial>
.\scripts\perf_baseline.ps1 -Serial <serial>
.\scripts\stress_baseline.ps1 -Serial <serial>
.\scripts\upgrade_regression.ps1 -Serial <serial>
```

## 设备选择

设备脚本的优先级为：命令行 `-Serial`、环境变量 `ANDROID_SERIAL`、当前唯一已连接
的 adb 设备。如果连接了多个设备且没有明确指定 Serial，脚本会直接失败，避免把
测试输入发送到错误的手机。

## 输出

- 构建脚本将 APK 复制到被忽略的 `artifacts/openIME-1.0-debug.apk`。
- 性能、压力和升级脚本写入 `docs/perf/`、`docs/stress/` 和 `docs/upgrade/`。
- 视觉脚本写入 `docs/visual/check/`；这些本地截图默认由 `.gitignore` 排除。
