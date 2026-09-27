# Android 能力映射

本文只记录当前 Android 产品的真实实现，不再维护已删除的 Web 原型映射。

| 能力 | 当前实现 | 状态 |
|---|---|---|
| 中文 26 键 | `ImeKeyboardView` + `CandidatePipeline` + Rime | 生产可用 |
| 中文 9 键 | `ImeKeyboardView` + `NineKeyLocalDecoder` + Rime | 生产可用；无 0、无英文九键 |
| 英文 26 键 | `CandidatePipeline` / `CandidateEngine` | 生产可用 |
| 数字/电话/小数 | `EditorInfoAdapter` + numeric renderer | 生产可用 |
| 候选栏/展开 | `ImeKeyboardView` + `CandidateSnapshot` | 生产可用 |
| 按键组件 | `ImeKeyView` | 主/副标签、图标、按压反馈 |
| 长按 Popup | `KeyPopupController` | 定位、边缘限制、入场动画 |
| 符号 | `ImeData.symbols` + `CustomSymbolRepository` | 生产可用 |
| Emoji | `ImeData` + `EmojiRecentRepository` + Fluent assets | 当前正式 UI 仍以表情类为主，待扩展完整分类 |
| 剪贴板 | `ClipboardHistoryRepository` + `InputConnectionGateway` | 普通编辑器可用；密码编辑器不暴露持久历史/粘贴入口 |
| 常用语 | `QuickPhraseRepository` / `QuickPhraseEditActivity` | 生产可用 |
| 文本编辑 | `InputConnectionGateway` | 生产可用，能力随目标 EditorInfo/选区变化 |
| 语音 | `VoiceModelLifecycleManager` + sherpa-onnx | 本地语音；150ms 长按空格 |
| 手写 | `HandwritingPadView` + `UnavailableHandwritingProvider` | 只有笔迹 UI，识别引擎未接入，正式入口隐藏 |
| 浮动键盘 | `LocalVoiceImeService` WindowManager + `ImeKeyboardView` | Docked/Floating Window Mode |
| 设置 | `ImeSettingsRepository` + 键盘内/Activity 设置 UI | 持久化 |
| Rime | `RimeEngine` → `RimeNative` → librime/OpenCC | 中文生产权威候选 |
| 编辑器副作用 | `InputConnectionGateway` | 唯一 commit/delete/selection/clipboard 边界 |

## 明确不存在的产品状态

- 不提供英文九键。
- Floating 不是 Panel。
- 手写识别尚未上线。
- 项目无 AI Writer/联网模型功能。
- 已删除的 Web `ui-suite` 不再是源码或数据的 source of truth。

## 当前临时架构

生产运行时只有 `ImeKeyboardView`。后续 UI 清理只从这个唯一 renderer 向具体组件/controller 机械拆分，不再引入版本化键盘 View 命名。
