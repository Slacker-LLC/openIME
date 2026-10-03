# Visual language decisions

- 2026-09-29｜浅色次级文字对比度｜保留键盘原始 `keySecondaryText=#6E6E73`，新增页面角色 `textSecondaryRole=#6D6D72`｜原值对 `surface=#EEF0F3` 约 4.44:1，低于规范 4.5:1；只修页面角色，避免改变键盘既有视觉。
- 2026-09-29｜候选展开图标状态｜复用 `ic_chevron_down`，展开时旋转 180°｜保持单一矢量资产，不再用 Unicode 上下箭头。
- 2026-09-29｜Setup XML 色板｜仅保留启动前必须使用的资源色，并由 TokenDriftTest 与 Kotlin token 对齐｜Android XML 在 Kotlin 初始化前需要资源颜色，不能完全移除。
- 2026-09-29｜退格连删节奏｜继续保留现有 60ms 匀速重复｜视觉语言 v1 明确要求本轮不改，留作后续交互专项。
- 2026-09-29｜语音松手尾部｜松手后保留 300ms 采集窗口，并用 generation/session 所有权阻止旧会话影响新会话｜修复 AudioRecord 尾部被立即截断，同时保持取消即时生效。
- 2026-09-29｜九键解码线程策略｜先记录 `publishNineKeyDigits -> resolveNineKey` 的 20 次窗口 P50/P95；未取得中低端机 P95 前保持现有线程，不提前迁移｜只有 P95 超过 8ms 才按规范迁到 `CandidateQueryCoordinator`。
- 2026-09-29｜九键首帧与 Rime 刷新｜候选按压或滚动期间暂缓应用异步 Rime 结果；同时记录从请求到结果可应用的端到端延迟｜防止手指下的候选列表重排；是否在 Rime 就绪时跳过首帧回退，等实测 P95 是否低于 40ms 后再定。
- 2026-09-29｜Rime 用户词库导出｜vendored librime 1.17.0 已编入 levers 模块，`UserDictManager::Export/Import` 可在关闭用户库会话后导出/合并 UTF-8 快照｜用户数据 JSON 在 Rime 会话已加载时包含自动学习词库；不可用时必须明确提示，不静默遗漏。
- 2026-09-29｜流式语音模型｜默认模型切换为 `sherpa-onnx-streaming-paraformer-bilingual-zh-en` 的 INT8 encoder/decoder；运行时使用 `OnlineParaformerModelConfig` + `greedy_search`，结束时补 300ms 静音；不再向 Paraformer stream 传 transducer-only 动态 hotwords｜优先降低模型体积和保持中英流式识别，同时遵循 sherpa-onnx v1.13.6 官方 Paraformer 配置。
- 2026-10-03｜语音词表｜内置词表随版本发布（`assets/hotwords/`），用户可导入文本词表；二者都不联网，不新增 `INTERNET` 权限，不做在线定期更新｜Paraformer 不能把热词传进解码器，所以词表走识别后的“同音替换”：读音（取自 `8105.dict.yaml`，含多音字）相同而字不同的片段改成词表写法。游戏词表默认关闭，避免日常聊天被误改。
- 2026-10-03｜整体分包｜99 个平铺文件按功能分进 theme/core/editor/data/setup/widget/floating/handwriting/rime/candidate/hotword/voice/panel/keyboard，根包只留 Service、Activity 和 JNI 类；依赖方向由 `ArchitectureLayeringTest` 固定｜包名只影响组织和可见性，不改 Manifest、native 符号和测试脚本引用的名字；Gradle 多模块暂不做，因为 `keyboard` 与 `panel`、`voice` 仍通过大接口耦合，需要先拆 `ImeKeyboardView.Listener`。
- 2026-10-04｜自动填充条带｜只声明 `supportsInlineSuggestions`、自己拼装 androidx.autofill v1 的样式 Bundle（版本表 + 一个空的 v1 样式），不引入 androidx.autofill 依赖；条目按固定像素尺寸（150dp × 40dp）渲染并横向滚动｜`InlineContentView` 是远程 Surface，没有固有尺寸，`WRAP_CONTENT` 会得到 0×0；样式 Bundle 只有十几行，为它引入第一个 androidx 运行时依赖不值得。响应早于键盘视图到达时先暂存，不能返回 false（系统会退回下拉菜单）。
- 2026-10-04｜数字行高度｜打开后键盘总高度不变，五行均分原来四行的空间（竖屏 43dp，横屏 34dp，下限 32dp）｜输入法窗口高度变化会让应用内容跳动，且面板、浮动键盘、九键都依赖同一个高度；宁可每行略矮。默认关闭。
- 2026-10-04｜空格滑动光标锁定底行｜进入光标模式（横向 18dp、横向分量大于纵向 1.25 倍、早于长按语音超时）后，同一行的其他按键 `touchLocked`（变灰、不响应）；拼音预编辑存在时移动的是预编辑光标｜手指一直在空格上，但第二根手指或漂移的拇指可能按到邻键；预编辑期间把方向键事件发给应用会打断组合。
- 2026-10-04｜“标点用空格代替”｜只作用于语音识别结果：逗号、句号、问号等写成一个空格，结尾标点直接去掉，括号和 3.5、a.b 不变｜把这项需求理解为语音文本后处理的开关（与去语气词同属“语音输入”设置组），默认关闭。
