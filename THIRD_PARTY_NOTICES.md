# Third-party notices

This file lists the third-party code, data and models that openIME ships in its source or in the APK.
openIME uses the GPL-3.0-only license. See `LICENSE`.
This file covers only the third-party components.

| Component | Location in the repository | Source | License | Distribution check |
|---|---|---|---|---|
| librime | `app/src/main/cpp/vendor/librime/` | https://github.com/rime/librime | BSD-3-Clause | The vendored `LICENSE` file is kept. The APK contains `assets/licenses/librime-BSD-3-Clause.txt`. |
| OpenCC | `app/src/main/cpp/vendor/OpenCC/` | https://github.com/BYVoid/OpenCC | Apache-2.0 | The vendored `LICENSE` file is kept. The APK contains `assets/licenses/OpenCC-Apache-2.0.txt`. |
| Snappy | `app/src/main/cpp/vendor/snappy/` | https://github.com/google/snappy | BSD-3-Clause | The vendored `COPYING` file is kept. The APK contains `assets/licenses/snappy-BSD-3-Clause.txt`. |
| Rime Ice dictionaries | `app/src/main/assets/rime-data/openime_dicts/`, `app/src/main/assets/pinyin_phrases.tsv` | https://github.com/iDvel/rime-ice, pinned commit `75e6572bebc05b49021e842949ce947882e3e4b2` | GPL-3.0-only | Checked. The full text is in `app/src/main/assets/licenses/rime-ice-GPL-3.0.txt`. |
| Rime base data | `app/src/main/assets/rime/` | Rime, luna-pinyin, essay and other upstream data | See the `AUTHORS` file in each directory | Upstream `AUTHORS` files are kept. Do not delete them before a release. |
| sherpa-onnx Android runtime | `app/libs/sherpa-onnx-1.13.6.aar` | https://github.com/k2-fsa/sherpa-onnx | Apache-2.0 | The APK contains `assets/licenses/sherpa-onnx-Apache-2.0.txt`. |
| Streaming Paraformer bilingual (Chinese and English) INT8 model | `app/src/main/assets/models/voice/bilingual-paraformer/` | https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en | Apache-2.0 | The APK contains only `encoder.int8.onnx`, `decoder.int8.onnx` and `tokens.txt`. `assets/licenses/paraformer-model-Apache-2.0.txt` is included. `manifest.json` pins the model ID, the version and the file hashes. |
| CT-Transformer punctuation INT8 model | `app/src/main/assets/models/voice/punctuation/` | https://modelscope.cn/models/iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch and https://github.com/k2-fsa/sherpa-onnx/releases/tag/punctuation-models | Apache-2.0 | We pin the `2024-04-12-int8` export. The manifest lists the file hashes. The APK contains `ct-transformer-Apache-2.0.txt`. |
| Microsoft Fluent Emoji | `app/src/main/assets/emoji/fluent/` | https://github.com/microsoft/fluentui-emoji | MIT | The APK has only the emoji images that the app uses. It contains `assets/licenses/fluent-emoji-MIT.txt`. |

## Checks before a release

- Do not delete `LICENSE`, `COPYING`, `AUTHORS` or `NOTICE` files from vendored source directories.
- Keep the license texts in `app/src/main/assets/licenses/` in the APK.
  This includes Rime Ice, librime, OpenCC, Snappy, sherpa-onnx, the Paraformer model and Fluent Emoji.
- When you update the voice runtime or a model, check the license of the new version.
  Do not rely on the earlier result.
- When the dictionary source or its pinned commit changes, update this file, [docs/LICENSING.md](docs/LICENSING.md) and the license files in the APK.
- Keep the main license (GPL-3.0-only) the same in `LICENSE`, the README and `docs/LICENSING.md`.
  Before you change it, check the GPL-3.0-only terms of the Rime Ice dictionaries.

## Notes

Git LFS, GitHub Releases and APK packaging do not change the license of a third-party work.
This list records only what we have checked. It does not replace the upstream license texts.
