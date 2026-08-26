# Web 原型 → Android 映射（最终版）

| 原型模块 | Android 实现 | 真实能力 |
|---|---|---|
| 26 键中文/英文 | `ImeKeyboardView.renderPinyin26/English26` | 真实 composition/commit，Shift/Caps 状态机 |
| 九键中文 | `renderPinyin9` + `CandidateEngine.get9KeyCandidates` | 真实拼音候选 |
| 九键英文 T9 | `renderEnglish9` + `getT9EnglishCandidates` | 真实数字 → 单词 |
| 数字键盘 | `renderDigits` | commit |
| Toolbar | 单行紧凑 TextView + 矢量图标 `res/drawable/ic_*.xml` | 真实点击；主题着色 |
| Root UI | `MainDock/Toolbar/Candidate/KeyboardHost` + `PanelOverlay` + `CandidateOverlay` | 面板替换主键盘；候选展开覆盖键盘主体 |
| Key 组件 | `ImeKeyView.kt` | 主字符居中、secondary 右上、图标/按压状态 |
| 候选栏/展开 | `renderCandidateRow/renderExpanded` | InputConnection |
| 符号/Emoji/贴纸 | `ImeData.symbols/emojis/stickers` | commit |
| 手写 | `HandwritingPadView` + `HandwritingProvider` | UI 真，识别引擎未配置并明确提示 |
| 语音 | `SpeechRecognitionProvider` | 真，部分/最终/RMS/权限/服务错误，不支持方言明确报错 |
| 剪贴板 | `ClipboardHistoryRepository` + `InputConnectionGateway` | 真 ClipboardManager，密码框不记录 |
| 文本编辑 | `InputConnectionGateway` | 真，上下/撤销受目标编辑器限制 |
| 设置/主题 | `ImeSettingsRepository` + `ImeDesignTokens` | 持久化，五套主题 |
| Skin DIY | Design Token 已预留 `skinRadius/skinOpacity/skinFontSize/skinPrimaryColor` | 正式 UI 无独立入口，按提示词不强行增加 |
| AI Writer | 未接入 | 当前项目无现成 Agent/API 架构，按提示词不擅自引入模型/联网 |
| Long Press/Popup | `showPopup`/`setOnLongClickListener` | 真实 |
