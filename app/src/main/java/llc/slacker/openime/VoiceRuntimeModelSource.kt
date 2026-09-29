package llc.slacker.openime

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

internal data class SherpaRuntimeModelFiles(
    val storage: SherpaRuntimeStorage,
    val encoder: String,
    val decoder: String,
    val tokens: String,
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
    if (manifest.modelType != "paraformer") return null
    val required = listOf(SHERPA_ENCODER, SHERPA_DECODER, SHERPA_TOKENS)
    if (!manifest.files.containsAll(required)) return null

    return when (selection.source) {
        VoiceModelSelection.Source.BUILT_IN -> SherpaRuntimeModelFiles(
            storage = SherpaRuntimeStorage.ASSETS,
            encoder = SHERPA_ENCODER,
            decoder = SHERPA_DECODER,
            tokens = SHERPA_TOKENS,
        )

        VoiceModelSelection.Source.DOWNLOADED -> {
            if (!isSafeVoiceModelId(manifest.modelId)) return null
            val canonicalDownloadedRoot = downloadedRoot.canonicalFile
            val packageRoot = File(canonicalDownloadedRoot, manifest.modelId)
            if (!packageRoot.isDirectory || Files.isSymbolicLink(packageRoot.toPath())) return null
            if (packageRoot.canonicalFile.parentFile != canonicalDownloadedRoot) return null

            fun resolve(path: String): String {
                val file = resolveContainedVoiceModelFile(packageRoot, path)
                require(file.isFile) { "缺少模型文件: $path" }
                return file.absolutePath
            }

            runCatching {
                SherpaRuntimeModelFiles(
                    storage = SherpaRuntimeStorage.FILES,
                    encoder = resolve(SHERPA_ENCODER),
                    decoder = resolve(SHERPA_DECODER),
                    tokens = resolve(SHERPA_TOKENS),
                )
            }.getOrNull()
        }

        VoiceModelSelection.Source.NONE -> null
    }
}
