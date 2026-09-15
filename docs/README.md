# openIME 文档

这里放面向维护者、测试人员和模型集成工作的文档。根目录 [README.md](../README.md)
负责快速开始；本页负责按主题导航。

## 架构与适配

- [ARCHITECTURE.md](ARCHITECTURE.md)：Android IME、Rime JNI、候选和语音链路。
- [COORDINATE_SYSTEM.md](COORDINATE_SYSTEM.md)：归一化坐标与实际窗口自适应规则。
- [MAPPING.md](MAPPING.md)：Web 原型到 Android 组件的映射。
- [REFERENCE_IME_GUIDE.md](REFERENCE_IME_GUIDE.md)：参考输入法 UI 基线与取舍。
- [LOCAL_VOICE_MODEL.md](LOCAL_VOICE_MODEL.md)：APK 内置本地语音模型的目录、校验和运行边界。
- [LICENSING.md](LICENSING.md)：主项目与第三方组件的许可证边界。

## 测试与交付

- [TEST_SOP.md](TEST_SOP.md)：L0～L3 正式测试流程、失败门禁和发布条件。
- [TEST_SOP_CHECKLIST.md](TEST_SOP_CHECKLIST.md)：L2/L3 多设备与人工交互验收清单。
- [TEST_ARCHITECTURE.md](TEST_ARCHITECTURE.md)：测试层级和调试入口。
- [DEVELOPMENT_AUDIT_PLAN.md](DEVELOPMENT_AUDIT_PLAN.md)：板块划分、当前进度和接续入口。

历史测试结果不再以固定文档保留在此。每次测试的原始证据由
`scripts/test_sop.ps1` 写入统一的证据目录，需要长期保存时再单独整理。

`docs/visual/`、`docs/reference_ime/` 以及根目录的截图和 UI dump 属于本地测试
证据，默认不提交；需要共享时请只提交经过筛选、脱敏且有说明的证据。
