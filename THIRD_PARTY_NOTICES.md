# 第三方资源与分发核对

本文件记录 openIME 实际随源码或 APK 分发的主要第三方代码、数据和模型。openIME 本身的许可证见仓库根目录 `LICENSE`（GPL-3.0-only），本文件只记录第三方组件。

| 组件 | 仓库内位置 | 上游/来源 | 许可证 | 分发核对 |
|---|---|---|---|---|
| librime | `app/src/main/cpp/vendor/librime/` | https://github.com/rime/librime | BSD-3-Clause | vendored `LICENSE` 保留；APK 同时包含 `assets/licenses/librime-BSD-3-Clause.txt` |
| OpenCC | `app/src/main/cpp/vendor/OpenCC/` | https://github.com/BYVoid/OpenCC | Apache-2.0 | vendored `LICENSE` 保留；APK 同时包含 `assets/licenses/OpenCC-Apache-2.0.txt` |
| Snappy | `app/src/main/cpp/vendor/snappy/` | https://github.com/google/snappy | BSD-3-Clause | vendored `COPYING` 保留；APK 同时包含 `assets/licenses/snappy-BSD-3-Clause.txt` |
| Rime Ice 词典 | `app/src/main/assets/rime-data/openime_dicts/`、`pinyin_phrases.tsv` | https://github.com/iDvel/rime-ice，固定提交 `75e6572bebc05b49021e842949ce947882e3e4b2` | GPL-3.0-only | 已核对；完整文本随 APK/源码位于 `app/src/main/assets/licenses/rime-ice-GPL-3.0.txt` |
| Rime 基础数据 | `app/src/main/assets/rime/` | Rime / luna-pinyin / essay 等上游数据 | 以各目录 `AUTHORS` / 上游许可为准 | 已保留上游 AUTHORS；发布前不得删除这些归属文件 |
| sherpa-onnx Android runtime | `app/libs/sherpa-onnx-1.13.6.aar` | https://github.com/k2-fsa/sherpa-onnx | Apache-2.0 | APK 包含 `assets/licenses/sherpa-onnx-Apache-2.0.txt` |
| 中英双语 Streaming Paraformer INT8 语音模型 | `app/src/main/assets/models/voice/bilingual-paraformer/` | https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en | Apache-2.0 | 只打包 `encoder.int8.onnx`、`decoder.int8.onnx` 和 `tokens.txt`；APK 包含 `assets/licenses/paraformer-model-Apache-2.0.txt`，`manifest.json` 固定模型 ID/版本与文件哈希 |
| CT-Transformer 中英标点 INT8 模型 | `app/src/main/assets/models/voice/punctuation/` | https://modelscope.cn/models/iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch 、https://github.com/k2-fsa/sherpa-onnx/releases/tag/punctuation-models | Apache-2.0 | 固定 `2024-04-12-int8` 导出，清单包含文件哈希；APK 附 `ct-transformer-Apache-2.0.txt` |
| Microsoft Fluent Emoji | `app/src/main/assets/emoji/fluent/` | https://github.com/microsoft/fluentui-emoji | MIT | 仅打包当前实际使用的基础情绪表情资源；APK 包含 `assets/licenses/fluent-emoji-MIT.txt` |

## 发布前检查

- 不要删除 vendored 源码目录中的 LICENSE、COPYING、AUTHORS 或 NOTICE 类文件。
- `app/src/main/assets/licenses/` 下的第三方许可文本必须继续随 APK 打包，包括 Rime Ice、librime、OpenCC、Snappy、sherpa-onnx、Paraformer 模型和 Fluent Emoji。
- 语音 runtime 与模型升级时，重新核对**具体版本/模型**的许可证，不要只沿用本文件旧结论。
- 内置词库来源或固定提交变化时，同步更新本文件、`docs/LICENSING.md` 和 APK 内许可证文件。
- 主项目 `LICENSE`（GPL-3.0-only）与 README、`docs/LICENSING.md` 保持一致；更换主项目许可证前先核对 Rime Ice 词典的 GPL-3.0-only 义务。

## 备注

Git LFS、GitHub Release 或 APK 打包方式不会改变第三方作品本身的许可证义务。此清单只记录仓库当前已核对的信息，不替代各上游许可证原文。
