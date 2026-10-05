# Licensing

## Main project

openIME uses the **GPL-3.0-only** license. The full text is in `LICENSE`.

The APK contains the Rime Ice dictionaries, which use GPL-3.0-only.
The main project uses the same license.
This way, the license of the APK as a whole is clear.
We do not need to decide if the dictionary data and the program are an aggregate or a derivative work.

All other components use BSD, Apache-2.0 or MIT licenses, which are compatible with GPL-3.0.
These components are librime, OpenCC, Snappy, sherpa-onnx, the Paraformer and CT-Transformer models and Fluent Emoji.
To use a different main license, first remove or replace the Rime Ice dictionaries.

## Third-party components in the repository

Each directory keeps the original license of its third-party source and data:

- `app/src/main/cpp/vendor/librime/`
- `app/src/main/cpp/vendor/OpenCC/`
- `app/src/main/cpp/vendor/snappy/`
- `app/src/main/assets/rime/`
- `app/src/main/assets/rime-data/`

### Dictionaries

The `8105`, `base`, `ext` and `others` dictionaries in `app/src/main/assets/rime-data/openime_dicts/` come from Rime Ice.
We pin commit `75e6572bebc05b49021e842949ce947882e3e4b2`.
They use GPL-3.0-only.

`app/src/main/assets/pinyin_phrases.tsv` is a subset of frequent entries that we generate from these dictionaries.
It has the same source and license.

The license text is in `app/src/main/assets/licenses/rime-ice-GPL-3.0.txt`.

The APK does not contain the dictionary text files.
It contains binary dictionaries that librime compiles at build time (`assets/rime-data/build/`, made by `scripts/build_rime_prebuilt.py`).
The source files are always public in this repository.
The license files ship in the APK.

### Voice runtime and models

- The runtime is `app/libs/sherpa-onnx-1.13.6.aar`. The upstream project `k2-fsa/sherpa-onnx` uses Apache-2.0.
- The speech model is `csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en`.
  Its model card states Apache-2.0. The release APK uses only the INT8 encoder and decoder.
- The punctuation model is the sherpa-onnx `2024-04-12-int8` export of ModelScope `iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch`.
  Its model card states Apache License 2.0.
  The APK contains `ct-transformer-Apache-2.0.txt`.
  The file hashes are part of the voice model manifest.

Git LFS only stores the files. It does not change their licenses.

## Licenses in the APK

The release APK carries the main third-party license texts in `assets/licenses/`.
These are librime, OpenCC, Snappy, Rime Ice, the sherpa-onnx runtime, the Paraformer and CT-Transformer models and Fluent Emoji.

`THIRD_PARTY_NOTICES.md` lists each component, its source, its version or pinned commit and its file location.
The release notes link to this file, so you can read it outside the APK.
