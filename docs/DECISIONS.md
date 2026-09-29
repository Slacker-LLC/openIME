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
