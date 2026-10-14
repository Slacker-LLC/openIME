package llc.slacker.openime.voice

import java.io.File
import java.nio.file.Files

internal const val VOICE_MODEL_DOWNLOADED_DIR = "voice-models"
internal const val SHERPA_MODEL_ROOT = "models/voice/bilingual-paraformer"
internal const val SHERPA_ENCODER = "$SHERPA_MODEL_ROOT/encoder.int8.onnx"
internal const val SHERPA_DECODER = "$SHERPA_MODEL_ROOT/decoder.int8.onnx"
internal const val SHERPA_TOKENS = "$SHERPA_MODEL_ROOT/tokens.txt"

internal enum class SherpaRuntimeStorage {
    ASSETS,
    FILES,
}

/** 运行时要交给 sherpa-onnx 的文件。路径含义因 [kind] 而异，缺的角色为 null。 */
internal data class SherpaRuntimeModelFiles(
    val storage: SherpaRuntimeStorage,
    val kind: StreamingModelKind,
    val encoder: String? = null,
    val decoder: String? = null,
    val joiner: String? = null,
    val model: String? = null,
    val tokens: String,
    /** transducer 家族提示（传给 sherpa 的 modelType；空=读模型元数据）。 */
    val family: String = "",
    /** 分词单元与 bpe 词表（中英混合模型可能需要）。 */
    val modelingUnit: String = "",
    val bpeVocab: String? = null,
    /** 热词表；有它时 transducer 改用 modified_beam_search。 */
    val hotwords: String? = null,
    /** Silero VAD 模型（可选）；有它时端点判断用 isSpeechDetected。 */
    val vad: String? = null,
)

internal fun VoiceModelSelection.runtimeIdentity(): String {
    val value = manifest
    return listOf(
        source.name,
        value?.modelId.orEmpty(),
        value?.modelVersion.orEmpty(),
        value?.fileHash.orEmpty(),
    ).joinToString(":")
}

internal fun resolveSherpaRuntimeModelFiles(
    selection: VoiceModelSelection,
    downloadedRoot: File,
): SherpaRuntimeModelFiles? {
    val manifest = selection.manifest ?: return null
    val layout = VoiceModelLayoutResolver.resolve(manifest) ?: return null

    fun build(storage: SherpaRuntimeStorage, resolve: (String) -> String): SherpaRuntimeModelFiles =
        SherpaRuntimeModelFiles(
            storage = storage,
            kind = layout.kind,
            encoder = layout.files["encoder"]?.let(resolve),
            decoder = layout.files["decoder"]?.let(resolve),
            joiner = layout.files["joiner"]?.let(resolve),
            model = layout.files["model"]?.let(resolve),
            tokens = resolve(layout.path("tokens")),
            family = manifest.family,
            modelingUnit = manifest.modelingUnit,
            bpeVocab = layout.files["bpeVocab"]?.let(resolve),
            hotwords = layout.files["hotwords"]?.let(resolve),
            vad = layout.files["vad"]?.let(resolve),
        )

    return when (selection.source) {
        VoiceModelSelection.Source.BUILT_IN -> build(SherpaRuntimeStorage.ASSETS) { it }

        VoiceModelSelection.Source.DOWNLOADED -> {
            if (!isSafeVoiceModelId(manifest.modelId)) return null
            val canonicalDownloadedRoot = downloadedRoot.canonicalFile
            val packageRoot = File(canonicalDownloadedRoot, manifest.modelId)
            if (!packageRoot.isDirectory || Files.isSymbolicLink(packageRoot.toPath())) return null
            if (packageRoot.canonicalFile.parentFile != canonicalDownloadedRoot) return null

            runCatching {
                build(SherpaRuntimeStorage.FILES) { path ->
                    val file = resolveContainedVoiceModelFile(packageRoot, path)
                    require(file.isFile) { "缺少模型文件: $path" }
                    file.absolutePath
                }
            }.getOrNull()
        }

        VoiceModelSelection.Source.NONE -> null
    }
}
