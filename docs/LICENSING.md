# 许可证与第三方组件

## 主项目

`openIME` 以 **GPL-3.0-only** 发布，全文见仓库根目录 `LICENSE`。

选择它的原因：APK 内置的 Rime Ice 词典按 GPL-3.0-only 使用（见下），主项目采用同一份许可证，
分发 APK 时整体的许可证状况没有歧义——不用争论词典数据与程序是「聚合」还是「衍生」。
其余组件（librime、OpenCC、Snappy、sherpa-onnx、Paraformer 与 CT-Transformer 模型、Fluent Emoji）均为 BSD / Apache-2.0 / MIT，
与 GPL-3.0 兼容。想改用别的许可证需要先去掉或替换 Rime Ice 词典。

## 已随仓库提供的第三方组件

第三方源码和数据的原始许可证随各自目录保留，主要包括：

- `app/src/main/cpp/vendor/librime/`
- `app/src/main/cpp/vendor/OpenCC/`
- `app/src/main/cpp/vendor/snappy/`
- `app/src/main/assets/rime/`
- `app/src/main/assets/rime-data/`

`app/src/main/assets/rime-data/openime_dicts/` 中的 `8105`、`base`、`ext` 和
`others` 词典来自 Rime Ice 固定提交
`75e6572bebc05b49021e842949ce947882e3e4b2`，按 GPL-3.0-only 使用。
`app/src/main/assets/pinyin_phrases.tsv` 是由这些词典生成的高频子集，沿用相同来源与
许可证范围。许可证全文位于
`app/src/main/assets/licenses/rime-ice-GPL-3.0.txt`，来源明细见根目录
`THIRD_PARTY_NOTICES.md`。

语音 runtime 以 `app/libs/sherpa-onnx-1.13.6.aar` 提供，上游
`k2-fsa/sherpa-onnx` 使用 Apache-2.0。内置中英双语 Streaming Paraformer 模型
`csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en`
模型卡标记为 Apache-2.0；正式包只使用其 INT8 encoder/decoder。
具体来源、文件位置和发布核对项统一记录在根目录 `THIRD_PARTY_NOTICES.md`。
标点模型固定使用 sherpa-onnx `2024-04-12-int8` 导出，上游 ModelScope
`iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch` 的模型卡标记 Apache License 2.0；
APK 附带 `ct-transformer-Apache-2.0.txt`，其文件参加语音模型清单哈希校验。
Git LFS 只负责文件存储，不改变文件的许可证。


## APK 内许可证

正式 APK 在 `assets/licenses/` 内携带主要第三方许可证文本，包括 librime、OpenCC、Snappy、Rime Ice、sherpa-onnx runtime、当前 Paraformer / CT-Transformer 模型和 Fluent Emoji。根目录 `THIRD_PARTY_NOTICES.md` 记录组件、来源、版本或固定提交与对应文件位置。

发布工作流同时把 `THIRD_PARTY_NOTICES.md` 作为 GitHub Release 附件上传，便于在 APK 外直接查看。
