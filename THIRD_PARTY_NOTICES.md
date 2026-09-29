# 第三方资源与分发核对

本文件记录 openIME 实际随源码或 APK 分发的主要第三方代码、数据和模型。主项目许可证仍由项目所有者决定，本文件不为 openIME 本身授予许可证。

| 组件 | 仓库内位置 | 上游/来源 | 许可证 | 分发核对 |
|---|---|---|---|---|
| librime | `app/src/main/cpp/vendor/librime/` | https://github.com/rime/librime | BSD-3-Clause | vendored `LICENSE` 保留；APK 同时包含 `assets/licenses/librime-BSD-3-Clause.txt` |
| OpenCC | `app/src/main/cpp/vendor/OpenCC/` | https://github.com/BYVoid/OpenCC | Apache-2.0 | vendored `LICENSE` 保留；APK 同时包含 `assets/licenses/OpenCC-Apache-2.0.txt` |
| Snappy | `app/src/main/cpp/vendor/snappy/` | https://github.com/google/snappy | BSD-3-Clause | vendored `COPYING` 保留；APK 同时包含 `assets/licenses/snappy-BSD-3-Clause.txt` |
| Rime Ice 词典 | `app/src/main/assets/rime-data/openime_dicts/`、`pinyin_phrases.tsv` | https://github.com/iDvel/rime-ice，固定提交 `75e6572bebc05b49021e842949ce947882e3e4b2` | GPL-3.0-only | 已核对；完整文本随 APK/源码位于 `app/src/main/assets/licenses/rime-ice-GPL-3.0.txt` |
| Rime 基础数据 | `app/src/main/assets/rime/` | Rime / luna-pinyin / essay 等上游数据 | 以各目录 `AUTHORS` / 上游许可为准 | 已保留上游 AUTHORS；发布前不得删除这些归属文件 |
| sherpa-onnx Android runtime | `app/libs/sherpa-onnx-1.13.6.aar` | https://github.com/k2-fsa/sherpa-onnx | Apache-2.0 | APK 包含 `assets/licenses/sherpa-onnx-Apache-2.0.txt` |
| 中英双语 Zipformer 语音模型 | `app/src/main/assets/models/voice/bilingual-zipformer/` | sherpa-onnx 模型 `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20`；原模型 https://huggingface.co/csukuangfj/k2fsa-zipformer-chinese-english-mixed | Apache-2.0 | APK 包含 `assets/licenses/zipformer-model-Apache-2.0.txt`；`manifest.json` 固定模型 ID/版本与文件哈希 |
| Microsoft Fluent Emoji | `app/src/main/assets/emoji/fluent/` | https://github.com/microsoft/fluentui-emoji | MIT | 仅打包当前实际使用的基础情绪表情资源；APK 包含 `assets/licenses/fluent-emoji-MIT.txt` |

## 发布前检查

- 不要删除 vendored 源码目录中的 LICENSE、COPYING、AUTHORS 或 NOTICE 类文件。
- `app/src/main/assets/licenses/` 下的第三方许可文本必须继续随 APK 打包，包括 Rime Ice、librime、OpenCC、Snappy、sherpa-onnx、Zipformer 模型和 Fluent Emoji。
- 语音 runtime 与模型升级时，重新核对**具体版本/模型**的许可证，不要只沿用本文件旧结论。
- 内置词库来源或固定提交变化时，同步更新本文件、`docs/LICENSING.md` 和 APK 内许可证文件。
- 主项目 `LICENSE` 在项目所有者决定前保持缺失；README 对主项目许可状态的现有表述保持不变。

## 备注

Git LFS、GitHub Release 或 APK 打包方式不会改变第三方作品本身的许可证义务。此清单只记录仓库当前已核对的信息，不替代各上游许可证原文。
