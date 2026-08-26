# 许可证与第三方组件

## 主项目

`openIME` 当前尚未选择主项目许可证。仓库公开可见，但在添加明确许可证前，不能
把“公开”理解为允许任意复制、再分发或商业使用。后续由项目所有者选择许可证后，
应在仓库根目录增加标准 `LICENSE` 文件，并同步更新本页和 README。

## 已随仓库提供的第三方组件

第三方源码和数据的原始许可证随各自目录保留，主要包括：

- `app/src/main/cpp/vendor/librime/`
- `app/src/main/cpp/vendor/OpenCC/`
- `app/src/main/cpp/vendor/snappy/`
- `app/src/main/assets/rime/`
- `app/src/main/assets/rime-data/`

语音 runtime 以 `app/libs/sherpa-onnx-1.13.6.aar` 提供，语音模型位于
`app/src/main/assets/models/voice/`。发布 APK 前应同时核对 runtime、模型和词典的
上游许可证与再分发条件；Git LFS 只负责文件存储，不改变文件的许可证。
