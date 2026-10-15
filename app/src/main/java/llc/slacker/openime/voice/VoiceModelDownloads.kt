package llc.slacker.openime.voice

/** Official packages whose streaming layouts are supported by our sherpa-onnx v1.13.6 runtime. */
internal object VoiceModelDownloads {
    data class Model(
        val id: String,
        val title: String,
        val description: String,
        val archiveBytes: Long,
    ) {
        val url: String
            get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$id.tar.bz2"

        val downloadDescription: String
            get() = "$description 官方包约 ${(archiveBytes + 524_288) / 1_048_576} MiB，含浮点和量化版本，导入时优先使用 int8 文件。"
    }

    // v1.13.6: scripts/apk/generate-asr-apk-script.py:26-30,142-164,249-265;
    // sherpa-onnx/kotlin-api/OnlineRecognizer.kt:273-283,324-334.
    // These upstream archives contain both fp32 and int8 ONNX files; our detector prefers int8.
    val models = listOf(
        Model(
            id = "sherpa-onnx-streaming-paraformer-bilingual-zh-en",
            title = "中英双语 · Paraformer",
            description = "与内置识别模型同系列。",
            archiveBytes = 1_047_319_737L,
        ),
        Model(
            id = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            title = "中文轻量 · Zipformer 14M",
            description = "中文流式识别，支持语音热词。",
            archiveBytes = 74_004_050L,
        ),
    )

    const val IMPORT_INSTRUCTIONS =
        "复制下面的官方地址到浏览器下载，下载完直接在选择器里选中这个包导入即可：" +
            "官方包是 tar.bz2，导入时不需要解压或改名。包内保留 tokens.txt 和 encoder / decoder" +
            "（Zipformer 还需 joiner）的 ONNX 文件，无需自行编写清单。" +
            "模型识别文件不包含内置版另附的标点模型。"
}
