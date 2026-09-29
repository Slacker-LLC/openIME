# Visual language decisions

- 2026-09-29｜浅色次级文字对比度｜保留键盘原始 `keySecondaryText=#6E6E73`，新增页面角色 `textSecondaryRole=#6D6D72`｜原值对 `surface=#EEF0F3` 约 4.44:1，低于规范 4.5:1；只修页面角色，避免改变键盘既有视觉。
- 2026-09-29｜候选展开图标状态｜复用 `ic_chevron_down`，展开时旋转 180°｜保持单一矢量资产，不再用 Unicode 上下箭头。
- 2026-09-29｜Setup XML 色板｜仅保留启动前必须使用的资源色，并由 TokenDriftTest 与 Kotlin token 对齐｜Android XML 在 Kotlin 初始化前需要资源颜色，不能完全移除。
- 2026-09-29｜退格连删节奏｜继续保留现有 60ms 匀速重复｜视觉语言 v1 明确要求本轮不改，留作后续交互专项。
- 2026-09-29｜语音松手尾部｜松手后保留 300ms 采集窗口，并用 generation/session 所有权阻止旧会话影响新会话｜修复 AudioRecord 尾部被立即截断，同时保持取消即时生效。
