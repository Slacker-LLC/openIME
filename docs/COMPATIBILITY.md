# 输入环境兼容性

键盘要在别人的应用里工作，所以「哪些环境、怎么处理、怎么验证」写在这里。改输入链路之前先看一遍。

## 编辑器

| 环境 | 处理 | 验证 |
|---|---|---|
| 普通 / 多行 / 搜索 / 聊天（EditText、WebView、Compose） | 拼音预编辑 + 候选；回车按 IME action 或原始回车 | `core_regression.sh`、`ImeTestLabActivity` |
| 自绘 / Compose / Web，没有「全选」也没有 ExtractedText | 清空 / 撤回改用光标前后文本，答案长度等于请求长度时拒绝删除 | `InputConnectionGatewayTest`，`CustomEditorTestActivity` |
| 密码（含可见密码、网页密码、数字密码） | 不组合（字母逐个直接上屏）、不学习词库和纠错、不读取输入框正文；可以使用剪贴板历史；语音可用，只把最终结果一次性上屏（不显示中间结果） | `security_regression.ps1`；`VoiceFinalPolicyTest`、`ClipboardSensitivityPolicyTest` |
| 数字 / 电话 / 日期时间 | 起始键盘为数字 | `EditorInfoAdapterTest` |
| 邮箱 / URL | 起始键盘为英文 | `EditorInfoAdapterTest` |
| TYPE_NULL（终端、游戏、远程桌面） | 起始英文；每个字母立即以真实按键事件送出；退格 / 前删用按键事件（它们的 InputConnection 多半是 dummy 模式的 BaseInputConnection，`deleteSurroundingText` 返回 true 却什么也没删） | `InputConnectionGatewayTest` |
| 无个性化学习标志（隐身模式） | 不学习、不记录剪贴板 | `PersonalizedLearningPolicy` |
| 一次提交几十万字（大段粘贴、长语音） | 分块提交，每块不超过 32000 个字符且不拆代理对，避免超过 Binder 单次事务上限 | `CrashResilienceTest` |

## 自动填充（Android 11+）

`method.xml` 声明 `supportsInlineSuggestions`，键盘请求最多 5 个 48dp 高的条目并把系统渲染的条目放进工具栏位置。键盘只托管条目：
填入的内容由系统直接写入输入框，键盘既拿不到也不读取，所以密码框里同样可以显示条目。条目响应可能先于键盘视图到达（新输入框刚获得焦点），
服务会暂存最近一次响应，视图建好后再显示，而不是拒绝（拒绝会让系统退回下拉菜单）。离开输入框时清除。
Android 11 以下和不支持内嵌建议的提供者仍使用系统的下拉菜单。
验证：`InlineChipTrackerTest`；`scripts/beta3_e2e.py autofill`（debug 构建自带测试提供者 `TestAutofillService` 和 `AutofillTestActivity`）。

## 物理键盘（平板、折叠屏键盘套、Chromebook、桌面模式、模拟器）

中文 26 键模式下：字母组成拼音，空格选首选，1–9 选候选，回车保留已输入拼音，Esc 取消，`'` 分词，退格删拼音；
`, . ? ! ; : ( )` 输出全角标点（数字后的 `, . :` 保持 ASCII，3.14 不会变成 3。14）；Ctrl / Alt / Meta 组合键、
大写字母和其他按键原样交给应用（大写会先结束当前预编辑）。英文 / 九键 / 数字模式、密码框、TYPE_NULL 编辑器不接管。
需要键盘面板可见（候选显示在面板上）。验证：`HardwareKeyPolicyTest`，`core_regression.sh` 040–043。

## 显示环境

横屏（不进入全屏提取模式，键盘是底部面板）、字体 130% / 200%（按键标签最多放大到 1.3 倍，功能键标签自动缩小）、
深色、小屏、窄屏、平板竖 / 横、折叠屏内屏。验证：`scripts/display_matrix_regression.py`（断言底部面板且每个键都在窗口内）、
`DisplayEnvironmentInstrumentedTest`、`scripts/beta3_e2e.py`（横屏下空格滑动光标、数字行、表情联想、语音处理、自动填充）。
数字行打开时键盘总高度不变，五行均分原来四行的空间（竖屏约 43dp，横屏约 34dp，不低于 32dp）。

## Android 版本

`minSdk` 26；CI 在 API 26（minSdk）、29、31、34 上运行全部仪器测试，本地另在 API 36 上运行；发布前必须通过的是 API 29 和 31。

## 崩溃、卡死与冲突

- 一次按键处理失败不会让键盘进程退出：记录（只含异常类型和代码位置，不含输入内容）、丢弃半成品预编辑、继续工作。
  验证：`core_regression.sh` 038（调试命令 `fail-next` 注入一次失败）。
- 崩溃历史：Java 崩溃、原生崩溃和 ANR（Android 11+ 的进程退出记录）。10 分钟内 3 次进入**安全模式**：
  关闭 librime 和语音预加载，用内置词库继续输入，「设置 → 关于与数据 → 诊断」可复制诊断信息或退出安全模式。
- librime 启动前写标记，通过健康检查后清除。留下标记且上个进程确实是原生崩溃时逐级处理：清理编译产物 →
  把用户词库改名备份并重建 → 不再启动原生引擎。被用户或系统强停的启动不计为崩溃。
- 语音输入静音媒体音量时，原音量同时写入磁盘并有两分钟看门狗；进程在录音中途死掉，下次启动恢复，音乐 / 视频不会一直没声。
  验证：`VoiceMediaMuteRecoveryInstrumentedTest`。
- 词库与九键解码器在后台线程构建，不再占用主线程（冷启动曾多占约 0.3 秒）。
- 退格不再每次向应用发起三次同步 Binder 调用：编辑器已经报告光标是收起状态时，不再去问「选中了什么」。
  应用卡住时，每次调用都会让键盘跟着等。

## 尚未覆盖

- 九键模式下的物理键盘（字母直接交给应用）；
- 物理键盘用户隐藏键盘面板后的候选显示（需要独立的候选窗口）；
- 真机上的 OEM 差异（小米、OPPO、三星）：目前只有模拟器与 CI 模拟器的结果。
