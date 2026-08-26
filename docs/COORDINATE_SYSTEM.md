# Keyboard Coordinate System v1

## 原则

键盘测试和 UI 计算不再依赖具体的屏幕像素，例如：

```text
1080×2400 上 Q 键在 (123, 1876)
```

而是统一使用输入法内容区域自身的归一化坐标：

```text
原点：IME 内容区域左上角
范围：0.0 ~ 1.0
运行时：normalized × keyboardWidth / keyboardHeight → 真实 px
```

这样 720p、1080p、2K、不同 DPI、横屏和圆角屏都不需要改测试基准。

## 实现

`app/src/main/java/llc/slacker/openime/KeyboardGeometry.kt`

- `NormalizedBounds(left, top, right, bottom)`：归一化矩形。
- `NormalizedBounds.fromView(view, root)`：从真实 `View` 测量值生成归一化坐标。
- `toPx(rootWidth, rootHeight)`：运行时转换为真实像素。

`ImeKeyboardView.normalizedBoundsReport()` 输出的是：

```text
key|x,y,w,h
```

其中 x/y/w/h 全部相对于当前 IME Root，而不是屏幕。

## 布局规则

- 整行：使用 `Row + Weight + Relative Insets`。
- 按键宽度：优先使用 weight，而不是写死每个键的 XY。
- 26 键：第一行 10 键、第二行 9 键居中、第三行 Shift/M 区、底部功能键。
- 九键 / T9 / 数字：左筛选栏、中网格、右操作栏。
- 高度和字体：dp/sp 约束最小/最大值，避免平板和折叠屏爆炸。
- Popup、动效、锚点：全部相对于 Key Bounds 计算。

## 自动化定位

`scripts/core_regression.ps1` 和 `scripts/extended_regression.ps1` 不读取绝对 XY：

- `E2ETestReceiver` 通过 `tap:<semantic-label>` 驱动真实 `InputMethodService` 的 click listener。
- 焦点定位通过 `uiautomator dump` 实时读取 EditText bounds。
- 模式定位通过 `state` 命令读取 `ImeState`。

因此脚本可以运行在 emulator 和真实小米手机上。
