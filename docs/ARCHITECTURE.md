# openIME 架构

本文描述当前生产运行时，而不是目标重构形态。重构计划见 [REPAIR_PLAN.md](REPAIR_PLAN.md)。

## 生产运行链

```text
Android InputMethodService
        │
        ▼
LocalVoiceImeService
        ├── ImeState
        ├── CandidatePipeline / CandidateSnapshot
        │       ├── CandidateEngine（Rime 未就绪时的本地回退 + 英文联想）
        │       ├── NativeCandidatePipeline
        │       └── RimeEngine → RimeNative (JNI) → librime/OpenCC
        ├── InputConnectionGateway
        ├── VoiceModelLifecycleManager
        │       ├── VoiceModelRepository
        │       ├── LocalAudioVoiceBackend / VoiceAudioRouteManager
        │       └── sherpa-onnx
        └── IME Window
                └── ImeKeyboardView（orchestration / geometry / editor state）
                        ├── ImeTopZone + CandidateBarController
                        ├── Pinyin26KeyboardRenderer / Pinyin9KeyboardRenderer
                        ├── NumericKeyboardRenderer
                        ├── ImePanelRenderer
                        │       ├── ClipboardPanelController
                        │       ├── TextEditorPanelController
                        │       └── SettingsPanelController
                        ├── VoicePanelController / VoicePanelView
                        ├── InlineVoicePresenter
                        ├── FloatingKeyboardController
                        ├── BackspaceGestureController / BackspaceKeyFactory
                        ├── SpaceVoiceGestureController / SpaceVoiceKeyFactory
                        ├── KeyPopupController
                        ├── NineKeySegmentRepairController
                        ├── ImeThemeApplier
                        ├── PanelHeaderFactory
                        └── EmojiCellFactory
```

生产运行时只存在一个顶层键盘 View：`ImeKeyboardView`。它负责 WindowInsets、响应式几何、编辑器/composition 协调和各具体 UI owner 的编排；候选、键盘布局、Panel、Voice presentation、Theme traversal、Popup 与 held-key gesture 已由上图中的具体类分别持有。当前架构不使用版本化键盘 View 命名。

## 状态所有权

业务事实应由 Service/领域模块持有；View 只保留瞬时显示状态。

- `LocalVoiceImeService / ImeState`：编辑器、键盘模式、Panel、composition、候选、Shift、设置、隐私状态。
- `CandidatePipeline / CandidateSnapshot / RimeEngine`：候选生成、generation、native identity、Rime session。
- `VoiceModelLifecycleManager`：本地 ASR runtime、预热、录音与 cooldown。
- `InputConnectionGateway`：所有目标编辑器副作用。
- `ImeKeyboardView`：当前编辑器/composition 协调、响应式测量几何、Panel/window 编排等顶层瞬时状态。
- 各具体 UI owner：只持有自己表面的瞬时状态，例如候选滚动、Panel tab/scroll、Voice presentation generation、held-key gesture pointer、Popup 生命周期和 floating drag。

`NineKeyUiState` 是 CandidatePipeline 实例内的 session-scoped 歧义路径/显式选择缓存，不持有候选排序或编辑器状态；它不是第二套候选 source of truth。继续重构时不要把这类局部状态重新提升成全局状态。

## 输入提交原则

1. 半成品拼音通过 composing 更新，不直接作为普通文本写入目标应用。
2. 选择候选、空格、Enter 或明确提交动作才执行 commit。
3. 删除优先处理 openIME 自己的 composition，再处理目标编辑器文本。
4. 候选提交使用已经渲染的 `CandidateSnapshot`/native identity，避免旧异步结果提交到新 composition。
5. 切换输入框、模式或结束会话时必须使旧 generation/session 失效。
6. 密码编辑器不组合、不读取正文、不进入个性化学习和热词/纠错学习；剪贴板历史和语音可用，语音只一次性上屏最终结果。要求关闭个性化学习的编辑器和来源应用标记为敏感的剪贴板内容不进入持久历史。

## 候选与 Rime

- librime 就绪后是生产中文候选的权威来源。
- `CandidateEngine` 是本地回退、英文联想和部分九键辅助，不应被理解成第二套权威中文引擎。
- `CandidateSnapshot` 保证“用户看到的候选”和“真正上屏的候选”属于同一 generation。
- native 单次查询进入 JNI 后当前不可从 Kotlin 中断；在有真机延迟数据之前不要修改 librime 内部。

## UI 与窗口

- Docked/Floating 是 IME Window 状态，不属于 Panel。
- 横竖屏只影响响应式几何，不自动改变用户的 Floating/Docked 选择。
- `KeyboardLayoutMetrics` 只做纯 dp 计算；View 负责把结果应用到 LayoutParams。
- `KeyPopupController` 负责 transient key popup 的定位、动画和生命周期。
- `FloatingKeyboardController` 负责浮动卡片 chrome、drag handle 和本地 drag 交互；WindowManager 边界仍由 Service 持有。
- `ImeThemeApplier` 负责把当前 tokens 递归应用到已构建的 native View 树。
- 所有布局基于当前 IME Window 实际尺寸和 WindowInsets，不使用固定屏幕坐标。

## Voice

`VoiceModelLifecycleManager` 是 ASR runtime 的唯一 owner。模型校验、预热、构建和释放不在 IME 主线程执行。`VoicePanelController` 只持有 presentation-side session/generation，`InlineVoicePresenter` 只负责顶部内联状态，`SpaceVoiceGestureController` 只负责长按/上滑取消手势。长按判定跟随 Android 配置的 touch-and-hold timeout；松手、取消和旧 session 回调必须保持 generation 隔离。

## 模块与分包

代码按功能分成子包，依赖只能自下而上；方向由 `ArchitectureLayeringTest` 固定，新增依赖必须先改那张表。

```text
                         app（根包：Service、Activity、RimeNative）
                                    │
                               keyboard ────────────────┐
              ┌──────────┬──────────┼──────────┬────────┤
            panel      voice    candidate  floating  hotword
              │          │          │          │         │
   widget  handwriting   │         rime        │       setup
              └──────────┴──────────┴────┬─────┴─────────┘
                                         data
                                       editor
                                        core
                                       theme
```

| 包 | 职责 | 依赖 |
|---|---|---|
| `theme` | 设计 token、主题、绘制工具、外观枚举、参考尺寸 | 无 |
| `core` | `ImeState`、键盘模式、静态数据、崩溃保护 | theme |
| `editor` | 目标编辑器边界：`InputConnectionGateway`、`EditorInfoAdapter`、Enter/选区策略 | core |
| `data` | SharedPreferences 和文件仓库、用户数据导入导出 | core、editor、theme |
| `setup` | Activity 页面共用的 UI 工具 `SetupUi` | data、theme |
| `widget` | 可复用控件：`ImeKeyView`（按键）、`SwipeUpDetector` | theme |
| `floating` | 浮动键盘窗口、拖动、卡片外观 | theme |
| `handwriting` | 手写板 | data、theme |
| `rime` | librime 引擎封装、输入规范化、native 候选引用 | core、data（及 JNI 类 `RimeNative`） |
| `candidate` | 候选管线、快照、九键本地解码、模糊音、拼音词典 | core、rime |
| `hotword` | 语音词表：解析、同音纠正、打字候选加权、管理界面 | setup、theme |
| `voice` | 语音识别、模型生命周期、语音面板、识别后处理 | data、editor、hotword、theme |
| `panel` | 工具、剪贴板、设置、文本编辑等面板 | core、data、handwriting、setup、theme、widget |
| `keyboard` | `ImeKeyboardView` 编排、26 键/九键/数字键盘、顶部区、手势、弹窗 | 以上除 app 外的全部 |
| `app`（根包） | Manifest 里的 Service 和 Activity、JNI 类 | 全部 |

规则：

- 低层包不能引用高层包。唯一的例外写在测试里：`panel` 和 `keyboard` 按类名启动几个 Activity，`rime` 调用 JNI 类
  `RimeNative`（它的包名和方法名绑定 native 符号，不能移动）。
- 根包只放 Manifest、JNI 和测试脚本按名字引用的入口类，别的东西不要放进来。
- 语音层通过窄接口回到界面：`VoiceSessionHost`（键盘监听器继承它）和 `VoiceEditorContext`（Service 提供编辑器信息），
  不直接依赖 `ImeKeyboardView` 或 Service。
- 新功能自成一个包，按“纯逻辑 / Android 边界 / 唯一入口”拆分，样板是 `hotword`：纯逻辑文件不 import `android.*`，
  包外只通过 `HotwordRuntime` 和管理 Activity 使用，由 `HotwordModuleBoundaryTest` 检查。
- 可见性默认 `internal`，只有 Manifest 需要的 Activity 是 public。

仍然偏大的地方：`ImeKeyboardView` 约 3000 行，`LocalVoiceImeService` 约 1800 行，`ImeKeyboardView.Listener` 有数十个方法。
它们是下一步拆分的对象，拆分时沿用上面的包边界，不要新增跨包依赖。

## 语音词表（hotword 模块）

```text
VoiceRecognitionBackend（Paraformer）
        │ 最终文本
        ▼
LocalAudioVoiceBackend：标点 → VoiceCorrectionRepository.apply → HotwordRuntime.apply → onFinal
                                                                      │
                                      HotwordPackStore ──启用的词──▶ HomophoneCorrector ◀── PinyinReadings
                                      ├ assets/hotwords/*.txt（内置，随版本发布）
                                      └ files/hotwords/*.txt（用户导入，规范化后保存）
```

- 流式 Paraformer 无法把热词传进解码器，所以词表在识别之后工作：文本里读音与某个热词相同、
  但字不同的片段，改成热词的写法。最左最长匹配；已经写对的不动。
- 读音来自 `rime-data/openime_dicts/8105.dict.yaml`，包含多音字；权重不足最大读音 1% 的冷僻读音
  被丢弃，避免无关词被当成同音。
- 词表格式：UTF-8 文本，一行一个 2～8 个汉字的词，`#` 开头为注释，支持 `# title:`、
  `# description:`、`# default: on|off`。单个文件上限 512 KB、5000 个词。
- 内置词表由 `default` 头决定初始开关：科技、应用默认开，游戏默认关；导入的词表默认开。
- 不联网：词表只随版本更新或由用户导入，应用不声明 `INTERNET` 权限。
- 已知取舍：同音替换不看上下文，两个字的词在日常语句里也可能同音，所以游戏词表默认关闭，
  每个词表都可以单独关闭。

## Native 与第三方代码

`app/src/main/cpp/local_rime_jni.cc` 和 CMake glue 是本项目维护边界。vendored librime/OpenCC/Boost 等第三方源码不作为日常架构重构对象；除非有明确 native 缺陷和测试证据，否则不要改 vendor 源码。

## 测试边界

- JVM Unit：纯策略、候选、输入连接、状态、几何。
- Android Instrumentation：真实 View/IME 交互、API 29/31 兼容性。
- debug source set：E2E Receiver 和测试 Activity，只用于测试 APK。
- PowerShell/Bash scripts：真实 IME、视觉、性能、升级、安全和 SOP 证据。

测试说明见 [TEST_ARCHITECTURE.md](TEST_ARCHITECTURE.md)。
