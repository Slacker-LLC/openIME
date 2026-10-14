package llc.slacker.openime.voice

import java.io.File
import java.security.MessageDigest

/**
 * 流式语音模型的种类，与 sherpa-onnx 的 OnlineModelConfig 一一对应。
 * [roles] 是这一类模型必须有的文件角色；tokens 所有类型都要。
 */
internal enum class StreamingModelKind(val id: String, val roles: List<String>) {
    PARAFORMER("paraformer", listOf("encoder", "decoder", "tokens")),
    TRANSDUCER("transducer", listOf("encoder", "decoder", "joiner", "tokens")),
    ZIPFORMER2_CTC("zipformer2-ctc", listOf("model", "tokens")),
    NEMO_CTC("nemo-ctc", listOf("model", "tokens")),
    T_ONE_CTC("t-one-ctc", listOf("model", "tokens")),
    ;

    companion object {
        fun fromId(id: String): StreamingModelKind? = entries.firstOrNull { it.id == id }

        /** 可选角色：标点、bpe 词表、热词、Silero VAD；有就用，没有不影响类型。 */
        val OPTIONAL_ROLES = setOf("punctuation", "bpeVocab", "hotwords", "vad")
    }
}

/** 清单里声明的"角色到文件"的映射，加上推断出的类型。 */
internal data class VoiceModelLayout(
    val kind: StreamingModelKind,
    val files: Map<String, String>,
) {
    fun path(role: String): String = files.getValue(role)
}

internal object VoiceModelLayoutResolver {
    // 旧清单（内置模型）没有 roles 字段，用这三个固定路径。
    private const val LEGACY_ROOT = "models/voice/bilingual-paraformer"

    /**
     * 解析一份清单。没有 roles 时按旧的固定布局（只对 paraformer 有效）；
     * 有 roles 时必须齐全，且每个路径都在 files 列表里。
     * 返回 null 表示这份清单不能用，[explain] 给出原因。
     */
    fun resolve(manifest: VoiceModelManifest): VoiceModelLayout? = parse(manifest).layout

    fun explain(manifest: VoiceModelManifest): String? = parse(manifest).error

    private data class Result(val layout: VoiceModelLayout? = null, val error: String? = null)

    private fun parse(manifest: VoiceModelManifest): Result {
        val kind = StreamingModelKind.fromId(manifest.modelType)
            ?: return Result(error = "不支持的模型类型 ${manifest.modelType}，支持：" +
                StreamingModelKind.entries.joinToString("、") { it.id })

        val roles = manifest.roles
        if (roles.isEmpty()) {
            if (kind != StreamingModelKind.PARAFORMER) {
                return Result(error = "${kind.id} 模型的清单必须声明 roles（各文件的用途）")
            }
            val legacy = mapOf(
                "encoder" to "$LEGACY_ROOT/encoder.int8.onnx",
                "decoder" to "$LEGACY_ROOT/decoder.int8.onnx",
                "tokens" to "$LEGACY_ROOT/tokens.txt",
            )
            return if (manifest.files.containsAll(legacy.values)) {
                Result(VoiceModelLayout(kind, legacy))
            } else {
                Result(error = "清单缺少 encoder / decoder / tokens 文件")
            }
        }

        val missing = kind.roles.filter { it !in roles }
        if (missing.isNotEmpty()) return Result(error = "清单缺少文件角色：${missing.joinToString("、")}")
        val unknown = roles.keys - kind.roles.toSet() - StreamingModelKind.OPTIONAL_ROLES
        if (unknown.isNotEmpty()) return Result(error = "清单含有未知文件角色：${unknown.joinToString("、")}")
        roles.forEach { (role, path) ->
            if (path !in manifest.files) return Result(error = "角色 $role 指向的文件 $path 不在文件列表里")
            if (!isSafeVoiceModelRelativePath(path)) return Result(error = "角色 $role 的路径非法：$path")
        }
        val kept = LinkedHashMap<String, String>()
        kind.roles.forEach { kept[it] = roles.getValue(it) }
        StreamingModelKind.OPTIONAL_ROLES.forEach { role -> roles[role]?.let { kept[role] = it } }
        return Result(VoiceModelLayout(kind, kept))
    }

    /** 清单声明的标点模型路径；旧清单靠文件名里含 punctuation 兜底。 */
    fun punctuationPath(manifest: VoiceModelManifest): String? {
        if (!manifest.supportsPunctuation) return null
        manifest.roles["punctuation"]?.let { return it }
        return manifest.files.firstOrNull { it.contains("punctuation") && it.endsWith(".onnx") }
    }
}

/**
 * 从一个没有 manifest.json 的目录里推断模型布局并生成清单。
 * 面向 sherpa-onnx 官方发布的原始压缩包：文件名遵循 encoder-*.onnx、decoder-*.onnx、joiner-*.onnx、tokens.txt。
 * 同时有 int8 和 fp32 时优先 int8（体积小、手机上更快）。
 */
internal object VoiceModelAutoDetector {
    data class Detected(val manifest: VoiceModelManifest, val note: String)

    fun detect(root: File, fallbackId: String): Result<Detected> = runCatching {
        val onnx = root.walkTopDown().filter { it.isFile && it.name.endsWith(".onnx") }.toList()
        val tokens = root.walkTopDown().filter { it.isFile && it.name == "tokens.txt" }.toList()
        check(tokens.size == 1) { if (tokens.isEmpty()) "没有找到 tokens.txt" else "找到多个 tokens.txt，无法判断用哪个" }
        check(onnx.isNotEmpty()) { "没有找到 .onnx 模型文件" }

        fun rel(f: File) = f.relativeTo(root).invariantSeparatorsPath
        fun isPunct(f: File) = rel(f).contains("punct", ignoreCase = true)
        fun isVad(f: File) = f.name == "silero_vad.onnx"
        fun pick(prefix: String): File? {
            val matches = onnx.filter { it.name.startsWith(prefix) && !isPunct(it) }
            return matches.firstOrNull { it.name.contains("int8") } ?: matches.minByOrNull { it.name.length }
        }

        val encoder = pick("encoder")
        val decoder = pick("decoder")
        val joiner = pick("joiner")
        val punctuation = onnx.firstOrNull(::isPunct)
        val vad = onnx.firstOrNull(::isVad)
        // silero_vad.onnx 是可选的端点模型，不能参与单文件 CTC 的家族猜测。
        val others = onnx.filter { it != punctuation && it != vad && it != encoder && it != decoder && it != joiner }

        val (kind, roles) = when {
            encoder != null && decoder != null && joiner != null ->
                StreamingModelKind.TRANSDUCER to mapOf("encoder" to encoder, "decoder" to decoder, "joiner" to joiner)
            encoder != null && decoder != null ->
                StreamingModelKind.PARAFORMER to mapOf("encoder" to encoder, "decoder" to decoder)
            encoder == null && decoder == null && joiner == null && others.size == 1 -> {
                // 单文件 CTC 无法从文件名可靠区分家族（zipformer2-ctc / nemo-ctc / t-one-ctc），
                // 用错家族会让原生层直接失败；必须由 manifest.json 声明。
                error(
                    "单文件 CTC 模型无法仅凭文件名识别类型（zipformer2-ctc / nemo-ctc / t-one-ctc），" +
                        "请在包的根目录放一个 manifest.json，声明 modelType 和 roles.model（例如 " +
                        "\"roles\": {\"model\": \"model.onnx\"}）。",
                )
            }
            else -> error("无法识别这是哪一类模型。支持：" + StreamingModelKind.entries.joinToString("、") { it.id })
        }

        val bpeVocab = root.walkTopDown()
            .filter { it.isFile && (it.name == "bpe.vocab" || it.name == "bpe.model") }
            .toList()
            .minByOrNull { it.relativeTo(root).invariantSeparatorsPath }
        val hotwords = root.walkTopDown()
            .filter { it.isFile && it.name == "hotwords.txt" }
            .toList()
            .minByOrNull { it.relativeTo(root).invariantSeparatorsPath }

        val family = if (kind == StreamingModelKind.TRANSDUCER) {
            val hint = root.name.lowercase()
            listOf("zipformer2", "zipformer", "conformer", "ebranchformer", "lstm")
                .firstOrNull { it in hint }
                .orEmpty()
        } else {
            ""
        }

        val files = LinkedHashMap<String, String>()
        roles.forEach { (role, file) -> files[role] = rel(file) }
        files["tokens"] = rel(tokens[0])
        punctuation?.let { files["punctuation"] = rel(it) }
        bpeVocab?.let { files["bpeVocab"] = rel(it) }
        hotwords?.let { files["hotwords"] = rel(it) }
        vad?.let { files["vad"] = rel(it) }
        val fileList = files.values.toList()

        val digest = MessageDigest.getInstance("SHA-256")
        fileList.sorted().forEach { path ->
            File(root, path).inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
        }
        val total = fileList.sumOf { File(root, it).length() }
        val id = safeId(root.name.ifBlank { fallbackId })
        val manifest = VoiceModelManifest(
            modelId = id,
            modelVersion = "imported",
            language = "unknown",
            modelType = kind.id,
            engineVersion = "sherpa-onnx",
            fileHash = digest.digest().joinToString("") { "%02x".format(it) },
            supportsPunctuation = punctuation != null,
            // 运行时内存没法从文件推断，按文件总量的 2 倍估，并给个下限，让内存准入检查有依据。
            requiredMemory = maxOf(total * 2, 200_000_000L),
            files = fileList,
            roles = files,
            family = family,
        )
        val extras = buildList {
            if (family.isNotEmpty()) add("家族 $family")
            if (bpeVocab != null) add("含 bpe 词表")
            if (hotwords != null) add("含热词表")
            if (vad != null) add("含 VAD 模型")
        }
        val noteSuffix = if (extras.isEmpty()) "" else "，" + extras.joinToString("、")
        Detected(manifest, "自动识别为 ${kind.id}，共 ${fileList.size} 个文件$noteSuffix")
    }

    internal fun safeId(raw: String): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-', '.', '_')
        return cleaned.take(100).ifEmpty { "imported-model" }
    }
}
