# 第三方资源

## Microsoft Fluent Emoji

`app/src/main/assets/emoji/fluent/` 中的 3D 表情资源来自 Microsoft 的
[fluentui-emoji](https://github.com/microsoft/fluentui-emoji) 项目，按其 MIT License 使用。

应用只打包「Smileys & Emotion」中的基础黄豆脸/情绪表情；没有把手势、动物、食物或贴图资源打进 APK。

## Rime Ice 中文词库

`app/src/main/assets/rime-data/openime_dicts/` 中的 `8105`、`base`、`ext`、
`others` 词库，以及由它们生成的 `app/src/main/assets/pinyin_phrases.tsv` 高频子集，
来自 [rime-ice](https://github.com/iDvel/rime-ice)，固定于提交
`75e6572bebc05b49021e842949ce947882e3e4b2`，按 GPL-3.0-only 使用。

完整许可证随 APK 和源码保存在
`app/src/main/assets/licenses/rime-ice-GPL-3.0.txt`。openIME 没有引入体积大、
部署耗时更长的 `tencent` 自动注音词表。
