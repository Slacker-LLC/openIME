package llc.slacker.openime

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

internal fun isSafeVoiceModelId(modelId: String): Boolean =
    modelId.length in 1..128 &&
        modelId != "." &&
        modelId != ".." &&
        modelId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))

internal fun isSafeVoiceModelRelativePath(path: String): Boolean {
    if (path.isBlank() || path.length > 512 || '\u0000' in path || '\\' in path) return false
    if (path.startsWith('/') || File(path).isAbsolute) return false
    val segments = path.split('/')
    return segments.all { segment -> segment.isNotEmpty() && segment != "." && segment != ".." }
}

/**
 * Resolve a package member without allowing traversal or symlink indirection.
 * The package is untrusted input, so both lexical and filesystem containment
 * are checked before any model bytes are opened.
 */
internal fun resolveContainedVoiceModelFile(root: File, relativePath: String): File {
    require(isSafeVoiceModelRelativePath(relativePath)) { "非法模型文件路径: $relativePath" }
    val canonicalRoot = root.canonicalFile
    require(canonicalRoot.isDirectory) { "模型包目录不存在" }

    val unresolved = File(canonicalRoot, relativePath)
    var cursor: File? = unresolved
    while (cursor != null && cursor != canonicalRoot) {
        require(!Files.isSymbolicLink(cursor.toPath())) { "模型文件不能使用符号链接: $relativePath" }
        cursor = cursor.parentFile
    }
    require(cursor == canonicalRoot) { "模型文件不在包目录内: $relativePath" }

    val resolved = unresolved.canonicalFile
    val prefix = canonicalRoot.path + File.separator
    require(resolved.path.startsWith(prefix)) { "模型文件越出包目录: $relativePath" }
    return resolved
}

/** Metadata required for an installable offline voice model package. */
data class VoiceModelManifest(
    val modelId: String,
    val modelVersion: String,
    val language: String,
    val modelType: String,
    val engineVersion: String,
    val fileHash: String,
    val supportsPunctuation: Boolean,
    val requiredMemory: Long,
    val files: List<String>,
) {
    companion object {
        fun parse(json: String): VoiceModelManifest {
            val root = JSONObject(json)
            val files = buildList {
                val values = root.optJSONArray("files") ?: return@buildList
                for (index in 0 until values.length()) {
                    values.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            return VoiceModelManifest(
                modelId = root.optString("modelId").trim(),
                modelVersion = root.optString("modelVersion").trim(),
                language = root.optString("language").trim(),
                modelType = root.optString("modelType").trim(),
                engineVersion = root.optString("engineVersion").trim(),
                fileHash = root.optString("fileHash").trim().lowercase(),
                supportsPunctuation = root.optBoolean("supportsPunctuation", false),
                requiredMemory = root.optLong("requiredMemory", 0L),
                files = files,
            )
        }
    }

    fun validateShape(): String? = when {
        !isSafeVoiceModelId(modelId) -> "modelId 非法"
        modelVersion.isBlank() -> "缺少 modelVersion"
        language.isBlank() -> "缺少 language"
        modelType.isBlank() -> "缺少 modelType"
        engineVersion.isBlank() -> "缺少 engineVersion"
        !fileHash.matches(Regex("[0-9a-f]{64}")) -> "fileHash 不是 SHA-256"
        requiredMemory <= 0L -> "requiredMemory 无效"
        files.isEmpty() -> "没有模型文件列表"
        files.distinct().size != files.size -> "模型文件列表包含重复项"
        files.any { !isSafeVoiceModelRelativePath(it) } -> "模型文件路径非法"
        else -> null
    }
}

data class VoiceModelSelection(
    val manifest: VoiceModelManifest?,
    val source: Source,
    val reason: String = "",
) {
    enum class Source { BUILT_IN, DOWNLOADED, NONE }
}

/**
 * Offline model package policy. The built-in package is read-only and can
 * never be deleted. Downloaded packages are staged and verified before they
 * become selectable; invalid packages are marked unavailable for this app
 * session so a broken download is not retried on every key press.
 */
class VoiceModelRepository(private val context: Context) {
    companion object {
        private const val TAG = "LocalVoiceModel"
        private const val PREFS = "voice_models"
        private const val SELECTED_MODEL = "selected_model"
        private const val VERIFIED_BUILT_IN = "verified_built_in"
        private const val BUILT_IN_MANIFEST = "models/voice/manifest.json"
        private const val DOWNLOADED_DIR = "voice-models"
    }

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val downloadedRoot: File
        get() = File(context.filesDir, DOWNLOADED_DIR)

    fun selectAvailable(): VoiceModelSelection {
        val selectedId = preferences.getString(SELECTED_MODEL, null)
        if (!selectedId.isNullOrBlank() && isSafeVoiceModelId(selectedId)) {
            val downloaded = downloadedRoot.listFiles()
                ?.firstOrNull { it.isDirectory && !Files.isSymbolicLink(it.toPath()) && it.name == selectedId }
            if (downloaded != null) {
                val checked = validateDirectory(downloaded)
                if (checked != null) {
                    return VoiceModelSelection(checked, VoiceModelSelection.Source.DOWNLOADED)
                }
                Log.w(TAG, "下载模型不可用，回退内置模型: ${downloaded.name}")
            }
        }

        val builtIn = validateBuiltIn()
        return if (builtIn != null) {
            VoiceModelSelection(builtIn, VoiceModelSelection.Source.BUILT_IN)
        } else {
            VoiceModelSelection(
                manifest = null,
                source = VoiceModelSelection.Source.NONE,
                reason = "APK 未包含有效的内置本地语音模型",
            )
        }
    }

    fun setSelectedModel(modelId: String): Boolean {
        if (!isSafeVoiceModelId(modelId)) return false
        val model = downloadedRoot.listFiles()
            ?.firstOrNull {
                it.isDirectory && !Files.isSymbolicLink(it.toPath()) && it.name == modelId
            }
            ?: return false
        if (validateDirectory(model) == null) return false
        preferences.edit().putString(SELECTED_MODEL, modelId).apply()
        return true
    }

    /** Copies a verified downloaded package into the private model directory. */
    fun installDownloadedModel(sourceDirectory: File, modelId: String): Boolean {
        if (!isSafeVoiceModelId(modelId) || !sourceDirectory.isDirectory) return false
        if (Files.isSymbolicLink(sourceDirectory.toPath())) return false
        if (validateDirectory(sourceDirectory)?.modelId != modelId) return false
        downloadedRoot.mkdirs()
        val staging = File(downloadedRoot, ".$modelId.staging")
        val target = File(downloadedRoot, modelId)
        runCatching {
            if (staging.exists()) staging.deleteRecursively()
            sourceDirectory.copyRecursively(staging, overwrite = true)
            check(validateDirectory(staging)?.modelId == modelId)
            if (target.exists()) target.deleteRecursively()
            check(staging.renameTo(target)) { "模型包切换失败" }
        }.onFailure {
            staging.deleteRecursively()
            Log.w(TAG, "安装下载模型失败: ${it.message}")
        }.getOrNull() ?: return false
        return true
    }

    fun deleteDownloadedModel(modelId: String): Boolean {
        if (!canDelete(modelId)) return false
        val target = File(downloadedRoot, modelId)
        if (!target.isDirectory || Files.isSymbolicLink(target.toPath())) return false
        if (preferences.getString(SELECTED_MODEL, null) == modelId) useBuiltInModel()
        return target.deleteRecursively()
    }

    fun useBuiltInModel() {
        preferences.edit().remove(SELECTED_MODEL).apply()
    }

    fun isBuiltInAvailable(): Boolean = validateBuiltIn() != null

    fun canDelete(modelId: String): Boolean =
        isSafeVoiceModelId(modelId) && modelId != validateBuiltIn()?.modelId

    private fun validateBuiltIn(): VoiceModelManifest? = runCatching {
        val json = context.assets.open(BUILT_IN_MANIFEST).use { it.readBytes().toString(Charsets.UTF_8) }
        val manifest = VoiceModelManifest.parse(json)
        check(manifest.validateShape() == null) { manifest.validateShape().orEmpty() }
        manifest.files.forEach { path ->
            context.assets.open(path).use { Unit }
        }
        val signature = builtInVerificationSignature(manifest)
        if (preferences.getString(VERIFIED_BUILT_IN, null) == signature) {
            return@runCatching manifest
        }
        check(sha256Assets(manifest.files) == manifest.fileHash) { "内置模型 SHA-256 不匹配" }
        check(preferences.edit().putString(VERIFIED_BUILT_IN, signature).commit()) {
            "无法保存内置模型校验状态"
        }
        manifest
    }.onFailure { Log.w(TAG, "内置模型校验失败: ${it.message}") }.getOrNull()

    @Suppress("DEPRECATION")
    private fun builtInVerificationSignature(manifest: VoiceModelManifest): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        return listOf(
            versionCode.toString(),
            manifest.modelId,
            manifest.modelVersion,
            manifest.fileHash,
        ).joinToString(":")
    }

    private fun validateDirectory(directory: File): VoiceModelManifest? = runCatching {
        check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "模型包目录非法" }
        val manifestFile = resolveContainedVoiceModelFile(directory, "manifest.json")
        check(manifestFile.isFile) { "缺少 manifest.json" }
        val manifest = VoiceModelManifest.parse(manifestFile.readText(Charsets.UTF_8))
        check(manifest.validateShape() == null) { manifest.validateShape().orEmpty() }
        val actual = sha256Files(directory, manifest.files)
        check(actual == manifest.fileHash) { "下载模型 SHA-256 不匹配" }
        manifest
    }.onFailure { Log.w(TAG, "模型包校验失败 ${directory.name}: ${it.message}") }.getOrNull()

    private fun sha256Assets(paths: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        paths.sorted().forEach { path ->
            context.assets.open(path).use { input -> digest.updateStream(input) }
        }
        return digest.digest().toHex()
    }

    private fun sha256Files(directory: File, paths: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        paths.sorted().forEach { path ->
            val file = resolveContainedVoiceModelFile(directory, path)
            check(file.isFile) { "缺少模型文件: $path" }
            file.inputStream().use { input -> digest.updateStream(input) }
        }
        return digest.digest().toHex()
    }

    private fun MessageDigest.updateStream(input: InputStream) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            update(buffer, 0, read)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
