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

## Native 与第三方代码

`app/src/main/cpp/local_rime_jni.cc` 和 CMake glue 是本项目维护边界。vendored librime/OpenCC/Boost 等第三方源码不作为日常架构重构对象；除非有明确 native 缺陷和测试证据，否则不要改 vendor 源码。

## 测试边界

- JVM Unit：纯策略、候选、输入连接、状态、几何。
- Android Instrumentation：真实 View/IME 交互、API 29/31 兼容性。
- debug source set：E2E Receiver 和测试 Activity，只用于测试 APK。
- PowerShell/Bash scripts：真实 IME、视觉、性能、升级、安全和 SOP 证据。

测试说明见 [TEST_ARCHITECTURE.md](TEST_ARCHITECTURE.md)。
