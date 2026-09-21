# openIME 开发 / 深度审计总计划

最后更新：2026-09-21

这份文档是后续开发的长期接续入口。无论是否更换 ChatGPT 对话、Codex 会话或本地环境，都先读取本文件，再继续当前板块。不要重新从头规划，也不要只凭聊天记忆判断进度。

## 一、当前总状态

仓库：`Slacker-LLC/openIME`

当前稳定主线已经包含第一轮深度修复。第一轮修复 PR：`#105`，已合并；对应 merge commit：`cb4c4ec15e25037a29bd77091a7a4a71a6df3690`。

当前正在推进第 11/12 板块的 UI/交互收敛。开发分支：`codex/uiux-redesign`；当前未创建 PR；本文更新时最新提交：`1391919`。远端 `origin/main` 当前无新增提交，本地分支领先 143 个提交；后续仍需以最新远端 HEAD/CI 为准，不要假设这里记录的 SHA 永远最新。

当前开发原则：一个板块一个板块深挖；先调查和定位，再修复，再补测试，再跑 CI，再合并；不要把多个尚未审完的板块混在同一个 PR 中。

## 二、固定的 14 个开发 / 审计板块

### Block 1：输入会话生命周期 / InputConnection
状态：第一轮已完成并合入 main，但后续发现新问题仍可回补。

重点范围：
- `onStartInput / onStartInputView / onFinishInput / onFinishInputView`
- 编辑器切换、restart、selection 更新、composition 生命周期
- 普通文本/选区不被输入法误删
- Unicode 删除、Emoji/扩展汉字代理对
- `clearAllText()`、向前/向后删除、复制/剪切/粘贴
- 密码字段、隐私字段、富文本/自定义编辑器边界
- stale callback / 旧 InputConnection 串到新输入框

已完成的重要修复：
- clear-all 失败恢复原选区，并避免先删除普通选中文本
- API 26/27 向前删除按 Unicode code point 处理，避免拆代理对
- 输入会话收尾只清理由 openIME 自己持有的 composition
- UserPhrase 持久化退出窗口收紧

下一次重新进入本块时：只查尚未覆盖的 editor/restart/selection 边界，不重复已有修复。

### Block 2：中文拼音 26 键
状态：进行中，当前最高优先级。

重点范围：
- 连续拼音、分词、简拼、完整拼音
- Rime 与 Kotlin fallback 候选一致性
- 模糊音 `n/l`、`zh/z`、`ch/c`、`sh/s`、`en/eng`、`in/ing`
- 候选排序、首候选、输入原文保护
- 首次输入卡顿、索引初始化、热路径分配
- 候选异步 generation / stale query
- Rime 未 ready、失败、重启时 fallback 体验
- 长拼音、异常输入、大小写、分隔符

当前已提交的开发：
- 多音节模糊音从“只处理整串首尾”改成按真实拼音音节匹配
- 固定回归：例如 `hulan` 在 n/l 模糊音开启时应能命中 `hunan -> 湖南`
- 模糊变体数量限制在 16 以内
- 同一候选生成周期复用 fuzzy expansion 结果
- 连续拼音 segmentation index 不再在第一次真实按键时 lazy 构建，而是初始化并共享

当前下一步：
1. 等 PR #106 最新 HEAD 的 Build + unit test + lint + APK 通过。
2. 检查 API 29 / API 31 compatibility instrumentation。
3. 继续审 CandidateEngine / CandidatePipeline / RimeEngine 在 26 键路径中的排序、fallback 和异步阻塞问题。
4. 若无新阻断，合并 #106。
5. 合并后立即更新本文件，并转 Block 3。

### Block 3：中文拼音 9 键
状态：待系统审计。

重点范围：
- 仅中文 9 键，不做英文 9 键
- 不允许 0 键
- 2-9 映射、分词键、候选路径
- `NineKeyLocalDecoder`、`CandidatePipeline`、Rime 的排序一致性
- 多路径查询数量、阻塞、候选延迟
- 路径权重 vs round-robin 的不一致
- 长数字流、分词、多音节、候选恢复
- 删除、空格、候选上屏逻辑

已知待查重点：正常 9 键和 segmented path 目前存在两套候选排序逻辑，需要统一审计。

### Block 4：英文 26 键
状态：已有部分修复，但尚未整块审完。

重点范围：
- 原始输入永远不被词典候选静默替换
- 首字母大写、Caps Lock、全大写
- 英文联想、首候选策略
- EMAIL / URL / username 等结构化字段
- 标点、空格、Enter 前 composition commit
- Shift 状态跨编辑器泄漏

已合入的修复：英文候选保留用户原始输入并处理大小写；避免 `cod` 因第一候选变成 `code` 后再提交。

### Block 5：数字 / 电话 / 金融 / 符号输入
状态：已有部分修复，待整块审计。

重点范围：
- NUMBER / DECIMAL / PHONE 分离
- 电话键盘应有 `0-9`、ASCII `*`、`#`、`+`
- 金融符号独立，不与 PHONE 混用
- 中文/英文符号、半角/全角
- EditorInfo 对不同 inputType 的映射
- Enter/Done/Next/Search 等 IME action

已合入的修复：PHONE 不再直接复用金融数字侧栏；修正全角星号问题。

### Block 6：候选系统 / 个性化学习
状态：部分修复，待整块审计。

重点范围：
- candidate generation、stale result、single-thread executor 阻塞
- learned / native / fallback 融合排序
- UserPhraseRepository 持久化、flush、并发覆盖
- 用户词学习触发时机和隐私边界
- 候选数量、扩展候选面板、首候选策略
- 上屏后的 association / next-word
- 输入延迟与日志开销

已知风险：旧 native Rime query 虽不会覆盖新 generation，但进入 native 后无法中断，会阻塞后续新请求。

### Block 7：语音输入
状态：已有多轮修复，仍需整块专项审计。

重点范围：
- 150ms 长按判定必须真实生效
- 按住说话、松手立即结束并上屏、上滑取消
- AudioRecord 启停、尾帧、first-character loss
- session generation / stale callback / 串输入框
- partial/final/result commit
- 密码框入口禁用语音
- 模型 preload、内存、420MB ASR 生命周期
- NUMBER/PHONE/EMAIL/URL 不应无意义自动预热
- 错误恢复、取消、Stop、后台切换

已合入的重要修复：
- V2 不再把 150ms 又延长到系统 long-press timeout
- 密码框在 backend/model 启动前拒绝录音
- 结构化字段收紧 ASR 自动 preload
- 语音尾帧/旧 session 回调已有防护

### Block 8：手写
状态：尚未系统审计。

重点范围：
- 手写面板真实功能是否完整，不允许只有 UI 壳
- stroke 收集、清空、撤销、识别结果
- 手写候选上屏
- 面板切换时资源和状态释放
- 触控采样、延迟、缩放/方向
- 与语音/拼音 composition 的互斥

### Block 9：Emoji / 符号 / 剪贴板 / 常用语
状态：部分性能修复，待整块审计。

重点范围：
- Emoji 全量分类和 Meme 筛选
- Fluent Emoji 图片加载、缓存、解码线程
- 不在 UI 线程逐项 `assets.open()`
- 避免一次性创建超大 View 树
- 7 类符号面板、分类导航、筛选
- 剪贴板读取/插入/隐私
- 常用语添加、编辑、分类、删除、保存、上屏
- 面板切换/关闭状态

已合入：Emoji asset existence probing 的主线程 I/O 已收紧。仍需继续检查 Recycler/懒加载等结构性性能问题。

### Block 10：按键触控 / 手势 / 反馈
状态：部分已优化，待系统审计。

重点范围：
- 普通点击 pressed state
- 长按气泡、popup 位置、125% Windows/Android 字体缩放无关，但 Android 自身 fontScale/density 要测
- backspace repeat
- 上滑清空
- 空格长按语音
- 多指、ACTION_CANCEL、pointer id
- 声音、震动、accessibility performClick
- 气泡不越界、横竖屏/浮动键盘

已完成补强：浮动键盘拖动句柄同时提供可执行的“贴底固定”键盘/无障碍动作；删除键的上滑清空保留原手势，并额外暴露“清空全部”无障碍 action，避免核心功能只对触摸手势可用。

### Block 11：UI / 视觉系统 / 布局
状态：已多轮修改，但未按完整板块验收。

重点范围：
- 键高、字体、间距、候选条、工具栏
- 空格键视觉和真实居中
- 拼音26/9、英文、数字、符号各布局一致性
- 深色/浅色/跟随系统
- Accent color 与对比度
- 自定义颜色不能制造不可读状态
- 浮动键盘、横屏、小屏、大字体
- 不做“AI 生成感”的过度装饰

已完成补强：Accent override 的文字前景、焦点环、主键主次级文字和设置页状态栏统一走同一套 WCAG 对比度策略；18 个强调色均有至少 4.5:1 的文字前景回归门禁。

已完成补强：键盘焦点环和强调色预设圆点均按背景对比度选择可见状态，预设色支持硬件键盘 / 无障碍焦点反馈。

已完成补强：工具面板卡片补齐按下与键盘焦点反馈，并通过可点击控件交互审计回归；方向或字体缩放变化时，已打开的面板按新的响应式尺寸重建，进行中的语音会话只调整容器而不替换识别闭包。

已完成补强：首页、偏好设置、常用语编辑和自定义符号管理统一声明 `adjustResize`，软键盘弹出时保持表单操作区可达；常用语编辑会恢复重建后的焦点与滚动位置。

已完成补强：真实系统输入法窗口在存在硬件键盘时仍按触摸优先策略显示；从 openIME 切换到其他输入法再切回时，会替换已经 shutdown 的旧渲染器并恢复键盘、候选栏和布局状态。

已完成补强：原生键盘、候选栏、工具面板、设置页输入框和首页步骤胶囊统一使用语义几何尺度；键面 8dp、普通控制 12dp、卡片/输入框 16dp、步骤胶囊 28dp、全圆控件独立使用 pill 半径，并以 48dp 触控目标和 56dp 主行高作为尺寸基线。API 36 模拟器已实际检查橙色强调色下的首页、输入框焦点环和 openIME 键盘窗口，轮廓保持一致。

### Block 12：设置 / 首页 / 状态同步
状态：设置快照同步、子页返回、生命周期与无障碍状态已完成代码收敛，仍待真实系统设置流程验收。

重点范围：
- App 独立设置页和键盘内设置必须共用同一套实现
- 设置保存后活动 IME 是否立即刷新
- SETTINGS -> FUZZY_SETTINGS 返回层级
- 首页启用输入法 / 切换输入法状态
- theme/accent/sound/haptic/popup/fuzzy 持久化
- standalone settings Activity 生命周期
- accessibility description

已完成：独立设置页与活动 IME 通过同一套 `applyPersistedSettings()` 一次应用完整持久化快照，避免主题、外观、皮肤参数分次重绘；`SETTINGS -> FUZZY_SETTINGS` 已有返回栈回归测试；密码字段的文本编辑面板会提前禁用全选、复制、剪切、粘贴并说明原因，普通编辑器还会依据实时选区和剪贴板内容提前禁用复制、剪切、粘贴，避免点击后才失败。

已完成补强：自定义强调色支持键盘“完成”直接应用；常用语和自定义符号表单支持“下一步/完成”键盘流转，并有 AndroidTest 回归覆盖。

已完成补强：独立偏好设置 Activity 重建后恢复设置面板滚动位置，避免旋转或系统回收把用户送回页面顶部。

已完成补强：方向或字体缩放变化时，已打开的表情、符号、设置等面板会按新的响应式尺寸重建内部网格；正在进行的语音会话只调整容器高度，不替换识别会话闭包，避免配置变化打断输入。

已完成补强：独立偏好设置 Activity 销毁前主动关闭键盘渲染器的 Handler、动画、弹窗和语音回调，避免旋转或返回后旧页面继续持有 Activity 上下文。

已完成补强：首页启用、切换、语音权限和设置入口现在同时提供动作描述与独立状态描述，读屏用户可以直接区分“当前步骤、已完成、暂不可用、已授权”等状态。

已完成补强：首页完成步骤改为强调色浅底、绿色勾选和可再次打开的结果态；当前步骤仍使用主强调色，避免完成后的流程卡片看起来像禁用项。

仍待查：首页启用输入法 / 切换输入法状态，以及活动 IME 与 standalone 设置页之间在真实系统设置流程中的即时刷新；API 36 模拟器已实际完成触摸输入、中文候选、候选上屏、英文/中文切换、emoji 面板和输入法切换回进程验证；已有 instrumentation 仍作为辅助门禁，API 29 已覆盖对应专项回归，真机差异仍需后续设备验证。

### Block 13：Native / Rime / JNI / ABI
状态：未做完整专项审计。

重点范围：
- RimeEngine 锁和线程模型
- native session 生命周期
- clear/set/query 原子性
- JNI 异常、native crash、安全边界
- ABI、16KB page alignment
- x86_64 CI 与 arm64 实机差异
- Rime 数据部署、升级、用户数据
- Native query 取消能力或隔离策略

### Block 14：性能 / 并发 / 测试 / CI / 发布
状态：持续进行，最终总验收板块。

重点范围：
- 冷启动、键盘首次弹出、首键/第二键 latency
- 主线程 I/O、对象分配、图片解码
- PSS/RSS、ASR preload、LMK 压力
- executor 队列、Handler callback、资源泄漏
- unit test / instrumentation / regression coverage
- API 29 / 31 compatibility
- Debug APK 构建、安装、真实输入测试
- 16KB zipalign / ELF LOAD alignment
- release/versionCode/versionName
- CI 超时/不稳定测试与失败日志

## 三、固定推进顺序

默认顺序：

`Block 1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8 -> 9 -> 10 -> 11 -> 12 -> 13 -> 14`

Block 1 已完成第一轮，所以当前从 Block 2 继续。除非发现会导致数据损坏、隐私泄漏、崩溃或严重输入错误的跨板块高优先级问题，否则不要跳板块。

每个板块使用同一流程：

1. 只读审计当前 main + 当前开发分支。
2. 列出“已确认 Bug / 高风险待复现 / 设计缺口”，不要把猜测当 Bug。
3. 优先修正确性、数据安全、串状态问题，再修性能，再修视觉。
4. 每个修复补对应回归测试。
5. 小提交，提交信息明确。
6. 开/更新独立 PR。
7. 至少确认 Build + unit test + lint + assembleDebug。
8. 涉及 Android 行为时继续确认 API 29 / 31 instrumentation。
9. 合并后更新本文件的状态、PR、关键提交和“下一步”。
10. 再进入下一板块。

## 四、当前已确认的主线成果

第一轮 #105 已合并的重点包括：
- 150ms 语音长按恢复真实生效
- 英文原始输入/大小写保护
- clear-all 失败选区恢复及非破坏性处理
- Unicode forward-delete 修复
- 密码字段在语音入口直接禁用
- ASR 自动预热策略收紧
- PHONE 键盘 ASCII `* # +` 修复
- Emoji asset probing 主线程 I/O 收紧
- UserPhrase 持久化丢失窗口收紧
- 输入会话收尾 composition 边界收紧

不要在后续板块重复推翻这些修复，除非有测试或源码证据证明它们有回归。

## 五、当前立即要做的事

当前活动板块：Block 2 中文拼音 26 键。

当前活动 PR：`#106 Fix multi-syllable fuzzy Pinyin fallback`

当前开发分支：`fix/pinyin26-audit-round2`

当前工作项：
- 跟踪最新 HEAD 的 Android CI；旧 CI 结果不能冒充新 HEAD 结果。
- 若 Build/unit/lint 失败，先修失败，不继续叠新功能。
- 若 Build 通过，继续审 26 键 CandidateEngine / CandidatePipeline / RimeEngine。
- 重点继续找：候选 native query 阻塞、fallback/native 排序偏差、首次输入/第二字母 latency、长拼音路径。
- Block 2 完整验收后合并 #106，更新本文，然后进入 Block 3 中文 9 键。

## 六、新对话 / 新 Agent 的接续指令

如果换了 ChatGPT 对话、Codex 会话或其他 Agent，直接给它下面这句话：

> 继续开发 `Slacker-LLC/openIME`。先读取 `docs/DEVELOPMENT_AUDIT_PLAN.md`，再核对 GitHub 当前 `main`、文档中记录的活动 PR/分支和最新 CI。不要重新规划整个项目，也不要重复已经合并的修复；从文档“当前立即要做的事”继续，并在每次合并/板块切换后更新这份文档。

如果文档记录与 GitHub 真实状态冲突：以 GitHub 当前 main、PR HEAD、CI 为准，然后立即修正文档。

## 七、维护规则

这份文档不是一次性说明，而是项目状态机的一部分。

必须在以下事件后更新：
- 一个 PR 合并
- 一个板块完成
- 切换到下一板块
- 发现会改变优先级的高风险问题
- 当前活动分支/PR 改变
- 原计划被证据推翻

不要在文档中记录会快速失真的细枝末节日志；保留板块状态、关键问题、关键提交/PR、当前下一步即可。
