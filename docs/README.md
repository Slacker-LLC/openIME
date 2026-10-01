# openIME 文档

根目录 [README.md](../README.md) 负责快速开始；这里放维护、架构、测试和集成文档。

## 当前有效文档

- [ARCHITECTURE.md](ARCHITECTURE.md)：当前生产运行时、状态所有权和模块边界。
- [REPAIR_PLAN.md](REPAIR_PLAN.md)：当前稳定化、架构债和 UI/交互清理执行基线。
- [MAPPING.md](MAPPING.md)：Android 产品能力到真实实现的映射。
- [COORDINATE_SYSTEM.md](COORDINATE_SYSTEM.md)：归一化坐标与窗口自适应规则。
- [REFERENCE_IME_GUIDE.md](REFERENCE_IME_GUIDE.md)：参考输入法 UI 基线与取舍。
- [NINE_KEY_REFERENCE.md](NINE_KEY_REFERENCE.md)：九键 / 左栏 / 删除手势：商业与开源输入法的做法及 openIME 的取舍。
- [LOCAL_VOICE_MODEL.md](LOCAL_VOICE_MODEL.md)：本地语音模型目录、校验和运行边界。
- [TEST_ARCHITECTURE.md](TEST_ARCHITECTURE.md)：自动化层级、debug harness 和 CI 门禁。
- [TEST_SOP.md](TEST_SOP.md)：L0～L3 正式测试流程。
- [TEST_SOP_CHECKLIST.md](TEST_SOP_CHECKLIST.md)：多设备与人工交互验收清单。
- [LICENSING.md](LICENSING.md)：主项目与第三方组件许可证边界。
- [RELEASE.md](RELEASE.md)：固定签名、arm64 正式包、标签发布、校验和与第三方清单。

旧的按 PR/分支推进的审计状态文档已删除。当前状态以 GitHub 分支/PR/CI 为准，长期执行顺序只维护在 `REPAIR_PLAN.md`，避免两份计划互相冲突。

本地测试证据默认写入 `.local/test-runs/`、`docs/visual/`、`docs/perf/` 等被忽略目录；只有经过筛选、脱敏且确有长期价值的证据才提交仓库。
