package llc.slacker.openime.voice

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
    /** 文件角色到路径的映射（encoder/decoder/joiner/model/tokens/punctuation/bpeVocab/hotwords/vad）。旧清单为空，按固定布局处理。 */
    val roles: Map<String, String> = emptyMap(),
    /** transducer 家族提示（zipformer/zipformer2/conformer/lstm/ebranchformer）；空则读模型元数据。 */
    val family: String = "",
    /** 分词单元（cjkchar / bpe / cjkchar+bpe）；部分中英混合模型需要它配合 bpeVocab。 */
    val modelingUnit: String = "",
) {
    companion object {
        val TRANSDUCER_FAMILIES = setOf("zipformer", "zipformer2", "conformer", "lstm", "ebranchformer")
        val MODELING_UNITS = setOf("cjkchar", "bpe", "cjkchar+bpe")

        fun parse(json: String): VoiceModelManifest {
            val root = JSONObject(json)
            val files = buildList {
                val values = root.optJSONArray("files") ?: return@buildList
                for (index in 0 until values.length()) {
                    values.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            val roles = buildMap {
                val obj = root.optJSONObject("roles") ?: return@buildMap
                obj.keys().forEach { key -> obj.optString(key).trim().takeIf { it.isNotEmpty() }?.let { put(key, it) } }
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
                roles = roles,
                family = root.optString("family").trim(),
                modelingUnit = root.optString("modelingUnit").trim(),
            )
        }
    }

    fun toJson(): String = JSONObject()
        .put("modelId", modelId)
        .put("modelVersion", modelVersion)
        .put("language", language)
        .put("modelType", modelType)
        .put("engineVersion", engineVersion)
        .put("fileHash", fileHash)
        .put("supportsPunctuation", supportsPunctuation)
        .put("requiredMemory", requiredMemory)
        .put("files", org.json.JSONArray(files))
        .apply { if (roles.isNotEmpty()) put("roles", JSONObject(roles as Map<*, *>)) }
        .apply { if (family.isNotEmpty()) put("family", family) }
        .apply { if (modelingUnit.isNotEmpty()) put("modelingUnit", modelingUnit) }
        .toString(2)

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
        family.isNotEmpty() && family !in TRANSDUCER_FAMILIES ->
            "family 取值非法：$family（可用：${TRANSDUCER_FAMILIES.joinToString("、")}）"
        family.isNotEmpty() && modelType != "transducer" -> "只有 transducer 模型可以声明 family"
        modelingUnit.isNotEmpty() && modelingUnit !in MODELING_UNITS ->
            "modelingUnit 取值非法：$modelingUnit（可用：${MODELING_UNITS.joinToString("、")}）"
        modelingUnit.isNotEmpty() && modelType != "transducer" -> "只有 transducer 模型可以声明 modelingUnit"
        else -> VoiceModelLayoutResolver.explain(this)
    }
}

private fun MessageDigest.updateFramed(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
    update(':'.code.toByte())
    update(bytes)
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

/**
 * Stable identity for every security-relevant manifest field. The trusted
 * catalog is shipped inside the signed APK, so a downloaded manifest cannot
 * authorize itself merely by providing its own aggregate file hash.
 */
internal fun VoiceModelManifest.trustFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("openime-voice-manifest-v1".toByteArray(Charsets.US_ASCII))
    listOf(
        modelId,
        modelVersion,
        language,
        modelType,
        engineVersion,
        fileHash,
        if (supportsPunctuation) "1" else "0",
        requiredMemory.toString(),
        files.size.toString(),
    ).forEach(digest::updateFramed)
    files.forEach(digest::updateFramed)
    if (roles.isNotEmpty()) {
        digest.updateFramed("roles")
        roles.toSortedMap().forEach { (role, path) -> digest.updateFramed(role); digest.updateFramed(path) }
    }
    // Empty defaults keep the legacy (built-in) fingerprints identical.
    if (family.isNotEmpty()) {
        digest.updateFramed("family")
        digest.updateFramed(family)
    }
    if (modelingUnit.isNotEmpty()) {
        digest.updateFramed("modelingUnit")
        digest.updateFramed(modelingUnit)
    }
    return digest.digest().toHexString()
}

internal data class TrustedVoiceModelEntry(
    val publisher: String,
    val manifestFingerprint: String,
    val fileSizes: List<Long>,
) {
    fun matchesLayout(manifest: VoiceModelManifest, directory: File): Boolean {
        if (manifest.trustFingerprint() != manifestFingerprint) return false
        if (fileSizes.size != manifest.files.size) return false
        return runCatching {
            manifest.files.forEachIndexed { index, path ->
                val file = resolveContainedVoiceModelFile(directory, path)
                check(file.isFile) { "缺少模型文件: $path" }
                check(file.length() == fileSizes[index]) { "模型文件大小不匹配: $path" }
            }
        }.isSuccess
    }
}

/** Trusted publisher metadata embedded in the APK rather than supplied by a package. */
internal class TrustedVoiceModelCatalog private constructor(
    private val entriesByFingerprint: Map<String, TrustedVoiceModelEntry>,
) {
    companion object {
        fun parse(text: String): TrustedVoiceModelCatalog {
            val entries = linkedMapOf<String, TrustedVoiceModelEntry>()
            text.lineSequence().forEachIndexed { index, sourceLine ->
                val line = sourceLine.trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
                val parts = line.split('\t')
                require(parts.size == 3) { "trusted catalog line ${index + 1} malformed" }
                val publisher = parts[0]
                val fingerprint = parts[1].lowercase()
                val sizes = parts[2].split(',').map { value ->
                    value.toLongOrNull()?.takeIf { it > 0L }
                        ?: error("trusted catalog line ${index + 1} has invalid file size")
                }
                require(publisher.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) {
                    "trusted catalog line ${index + 1} has invalid publisher"
                }
                require(fingerprint.matches(Regex("[0-9a-f]{64}"))) {
                    "trusted catalog line ${index + 1} has invalid fingerprint"
                }
                require(!entries.containsKey(fingerprint)) {
                    "trusted catalog line ${index + 1} duplicates a manifest"
                }
                entries[fingerprint] = TrustedVoiceModelEntry(
                    publisher = publisher,
                    manifestFingerprint = fingerprint,
                    fileSizes = sizes,
                )
            }
            require(entries.isNotEmpty()) { "trusted voice model catalog is empty" }
            return TrustedVoiceModelCatalog(entries)
        }
    }

    fun entryFor(manifest: VoiceModelManifest): TrustedVoiceModelEntry? =
        entriesByFingerprint[manifest.trustFingerprint()]
}

/**
 * Cheap persistent cache key for an already authenticated package in app-private
 * storage. It is only consulted after the current manifest still matches the
 * signed trusted catalog and all declared file sizes still match trusted metadata.
 */
internal fun downloadedPackageCacheSignature(
    directory: File,
    manifest: VoiceModelManifest,
    publisher: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("openime-voice-cache-v1".toByteArray(Charsets.US_ASCII))
    digest.updateFramed(publisher)
    digest.updateFramed(manifest.trustFingerprint())

    val manifestFile = resolveContainedVoiceModelFile(directory, "manifest.json")
    require(manifestFile.isFile) { "缺少 manifest.json" }
    listOf(
        "manifest.json",
        manifestFile.length().toString(),
        manifestFile.lastModified().toString(),
    ).forEach(digest::updateFramed)

    manifest.files.forEach { path ->
        val file = resolveContainedVoiceModelFile(directory, path)
        require(file.isFile) { "缺少模型文件: $path" }
        listOf(path, file.length().toString(), file.lastModified().toString())
            .forEach(digest::updateFramed)
    }
    return digest.digest().toHexString()
}

data class VoiceModelSelection(
    val manifest: VoiceModelManifest?,
    val source: Source,
    val reason: String = "",
) {
    enum class Source { BUILT_IN, DOWNLOADED, NONE }

    /** 用户亲手导入并确认过的模型，与受信目录里的下载模型同一存放位置，但信任来源不同。 */
}

/**
 * Offline model package policy. The built-in package is read-only and can
 * never be deleted. Downloaded packages are accepted only when their complete
 * manifest and file layout match trusted publisher metadata shipped in the APK.
 */
class VoiceModelRepository(private val context: Context) {
    companion object {
        private const val TAG = "LocalVoiceModel"
        private const val PREFS = "voice_models"
        private const val SELECTED_MODEL = "selected_model"
        private const val VERIFIED_BUILT_IN = "verified_built_in"
        private const val VERIFIED_DOWNLOADED_PREFIX = "verified_downloaded_"
        private const val BUILT_IN_MANIFEST = "models/voice/manifest.json"
        private const val TRUSTED_DOWNLOADS = "models/voice/trusted-downloads.tsv"
        private const val DOWNLOADED_DIR = "voice-models"
        private const val USER_CONFIRMED_PREFIX = "user_confirmed_"
    }

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val downloadedRoot: File
        get() = File(context.filesDir, DOWNLOADED_DIR)
    private val trustedCatalog: TrustedVoiceModelCatalog? by lazy {
        runCatching {
            val text = context.assets.open(TRUSTED_DOWNLOADS)
                .use { it.readBytes().toString(Charsets.UTF_8) }
            TrustedVoiceModelCatalog.parse(text)
        }.onFailure {
            Log.e(TAG, "受信下载模型目录不可用，禁用 downloaded model", it)
        }.getOrNull()
    }

    fun selectAvailable(): VoiceModelSelection {
        val selectedId = preferences.getString(SELECTED_MODEL, null)
        if (!selectedId.isNullOrBlank() && isSafeVoiceModelId(selectedId)) {
            val downloaded = downloadedModelDirectory(selectedId)
            if (downloaded != null) {
                val checked = validateDownloadedDirectory(downloaded, allowCache = true)
                if (checked != null) {
                    return VoiceModelSelection(checked, VoiceModelSelection.Source.DOWNLOADED)
                }
                clearDownloadedVerification(selectedId)
                preferences.edit().remove(SELECTED_MODEL).apply()
                Log.w(TAG, "下载模型不受信或已损坏，回退内置模型: ${downloaded.name}")
            }
        }

        val builtIn = validateBuiltIn()
        return if (builtIn != null) {
            VoiceModelSelection(builtIn, VoiceModelSelection.Source.BUILT_IN)
        } else {
            VoiceModelSelection(
                manifest = null,
                source = VoiceModelSelection.Source.NONE,
                reason = "未安装语音模型。到 设置 → 语音模型 导入一个，或安装完整版",
            )
        }
    }

    fun setSelectedModel(modelId: String): Boolean {
        val model = downloadedModelDirectory(modelId) ?: return false
        if (validateDownloadedDirectory(model, allowCache = true) == null) return false
        preferences.edit().putString(SELECTED_MODEL, modelId).apply()
        return true
    }

    /** Copies only authenticated manifest members into app-private storage. */
    fun installDownloadedModel(sourceDirectory: File, modelId: String): Boolean {
        if (!isSafeVoiceModelId(modelId) || !sourceDirectory.isDirectory) return false
        if (Files.isSymbolicLink(sourceDirectory.toPath())) return false
        val manifest = validateDownloadedDirectory(sourceDirectory, allowCache = false)
            ?: return false
        if (manifest.modelId != modelId) return false

        downloadedRoot.mkdirs()
        val staging = File(downloadedRoot, ".$modelId.staging")
        val target = File(downloadedRoot, modelId)
        runCatching {
            if (staging.exists()) check(staging.deleteRecursively())
            copyVerifiedPackage(sourceDirectory, staging, manifest)
            check(validateDownloadedDirectory(staging, allowCache = false) == manifest) {
                "复制后的模型包验证失败"
            }
            if (target.exists()) check(target.deleteRecursively()) { "无法替换旧模型包" }
            clearDownloadedVerification(modelId)
            check(staging.renameTo(target)) { "模型包切换失败" }
            storeDownloadedVerification(target, manifest)
        }.onFailure {
            staging.deleteRecursively()
            Log.w(TAG, "安装下载模型失败: ${it.message}")
        }.getOrNull() ?: return false
        return true
    }

    fun deleteDownloadedModel(modelId: String): Boolean {
        val target = downloadedModelDirectory(modelId) ?: return false
        if (preferences.getString(SELECTED_MODEL, null) == modelId) useBuiltInModel()
        val deleted = target.deleteRecursively()
        if (deleted) {
            clearDownloadedVerification(modelId)
            preferences.edit().remove(USER_CONFIRMED_PREFIX + modelId).apply()
        }
        return deleted
    }

    /** 一个模型包的概览，用于导入确认与管理页展示。 */
    data class PackageInfo(
        val modelId: String,
        val modelVersion: String,
        val language: String,
        val modelType: String,
        val supportsPunctuation: Boolean,
        val totalBytes: Long,
        val trusted: Boolean,
        /** true 表示目录里原本没有 manifest.json，清单是 app 自动识别并生成的。 */
        val autoDetected: Boolean = false,
        val note: String = "",
    )

    /** 只读检查一个待导入目录：清单合法、文件齐全、整体哈希对得上。不写任何东西。 */
    fun inspectPackage(sourceDirectory: File): Result<PackageInfo> = runCatching {
        check(sourceDirectory.isDirectory && !Files.isSymbolicLink(sourceDirectory.toPath())) { "不是有效的模型目录" }
        val manifestFile = resolveContainedVoiceModelFile(sourceDirectory, "manifest.json")
        val autoDetected = !manifestFile.isFile
        val detectedNote: String
        val manifest = if (autoDetected) {
            val detected = VoiceModelAutoDetector.detect(sourceDirectory, sourceDirectory.name).getOrThrow()
            // 写进目录，后面安装、校验、复制都按同一份清单走。
            manifestFile.writeText(detected.manifest.toJson(), Charsets.UTF_8)
            detectedNote = detected.note
            detected.manifest
        } else {
            detectedNote = ""
            VoiceModelManifest.parse(manifestFile.readText(Charsets.UTF_8))
        }
        manifest.validateShape()?.let { error(it) }
        var total = 0L
        manifest.files.forEach { path ->
            val file = resolveContainedVoiceModelFile(sourceDirectory, path)
            check(file.isFile) { "缺少模型文件: $path" }
            total += file.length()
        }
        check(sha256Files(sourceDirectory, manifest.files) == manifest.fileHash) { "文件内容与清单里的 SHA-256 不一致，模型包可能已损坏" }
        PackageInfo(
            modelId = manifest.modelId,
            modelVersion = manifest.modelVersion,
            language = manifest.language,
            modelType = manifest.modelType,
            supportsPunctuation = manifest.supportsPunctuation,
            totalBytes = total,
            trusted = trustedCatalog?.entryFor(manifest) != null,
            autoDetected = autoDetected,
            note = detectedNote,
        )
    }

    /** 用户已看过 [inspectPackage] 的结果并同意：登记信任后安装并选中。 */
    fun importUserPackage(sourceDirectory: File): Result<String> = runCatching {
        val info = inspectPackage(sourceDirectory).getOrThrow()
        val manifest = VoiceModelManifest.parse(
            resolveContainedVoiceModelFile(sourceDirectory, "manifest.json").readText(Charsets.UTF_8),
        )
        if (!info.trusted) confirmUser(manifest)
        check(installDownloadedModel(sourceDirectory, manifest.modelId)) { "安装失败" }
        check(setSelectedModel(manifest.modelId)) { "无法选中新模型" }
        manifest.modelId
    }

    /** 已安装的模型（不含内置）。 */
    fun installedModels(): List<VoiceModelManifest> =
        downloadedRoot.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") && !Files.isSymbolicLink(it.toPath()) }
            .mapNotNull { dir ->
                runCatching {
                    VoiceModelManifest.parse(File(dir, "manifest.json").readText(Charsets.UTF_8))
                }.getOrNull()?.takeIf { it.modelId == dir.name }
            }
            .sortedBy { it.modelId }

    fun selectedModelId(): String? = preferences.getString(SELECTED_MODEL, null)

    private fun isUserConfirmed(manifest: VoiceModelManifest): Boolean =
        preferences.getString(USER_CONFIRMED_PREFIX + manifest.modelId, null) == manifest.trustFingerprint()

    private fun confirmUser(manifest: VoiceModelManifest) {
        check(preferences.edit().putString(USER_CONFIRMED_PREFIX + manifest.modelId, manifest.trustFingerprint()).commit()) {
            "无法保存确认状态"
        }
    }

    fun useBuiltInModel() {
        preferences.edit().remove(SELECTED_MODEL).apply()
    }

    fun isBuiltInAvailable(): Boolean = validateBuiltIn() != null

    /** Built-in assets are never targeted here; only a real downloaded directory is deletable. */
    fun canDelete(modelId: String): Boolean = downloadedModelDirectory(modelId) != null

    private fun downloadedModelDirectory(modelId: String): File? {
        if (!isSafeVoiceModelId(modelId)) return null
        return downloadedRoot.listFiles()?.firstOrNull {
            it.name == modelId && it.isDirectory && !Files.isSymbolicLink(it.toPath())
        }
    }

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

    private fun validateDownloadedDirectory(
        directory: File,
        allowCache: Boolean,
    ): VoiceModelManifest? = runCatching {
        check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "模型包目录非法" }
        val manifestFile = resolveContainedVoiceModelFile(directory, "manifest.json")
        check(manifestFile.isFile) { "缺少 manifest.json" }
        val manifest = VoiceModelManifest.parse(manifestFile.readText(Charsets.UTF_8))
        check(manifest.validateShape() == null) { manifest.validateShape().orEmpty() }

        val entry = trustedCatalog?.entryFor(manifest)
        val publisher: String
        if (entry != null) {
            check(entry.matchesLayout(manifest, directory)) { "模型文件布局与受信 metadata 不匹配" }
            publisher = entry.publisher
        } else {
            // 不在白名单：只有用户在导入时确认过同一份清单，才继续做哈希校验。
            check(isUserConfirmed(manifest)) { "模型未列入受信目录，也未经用户确认" }
            publisher = "user"
        }

        val cacheEligible = allowCache && isInstalledDownloadedDirectory(directory, manifest)
        val signature = downloadedPackageCacheSignature(directory, manifest, publisher)
        if (
            cacheEligible &&
            preferences.getString(downloadedVerificationKey(manifest.modelId), null) == signature
        ) {
            return@runCatching manifest
        }

        val actual = sha256Files(directory, manifest.files)
        check(actual == manifest.fileHash) { "下载模型 SHA-256 不匹配" }
        if (cacheEligible) {
            preferences.edit()
                .putString(downloadedVerificationKey(manifest.modelId), signature)
                .apply()
        }
        manifest
    }.onFailure { Log.w(TAG, "模型包校验失败 ${directory.name}: ${it.message}") }.getOrNull()

    private fun isInstalledDownloadedDirectory(directory: File, manifest: VoiceModelManifest): Boolean =
        runCatching {
            directory.name == manifest.modelId &&
                directory.canonicalFile.parentFile == downloadedRoot.canonicalFile
        }.getOrDefault(false)

    private fun copyVerifiedPackage(
        sourceDirectory: File,
        targetDirectory: File,
        manifest: VoiceModelManifest,
    ) {
        check(targetDirectory.mkdirs()) { "无法创建模型 staging 目录" }
        val sourceManifest = resolveContainedVoiceModelFile(sourceDirectory, "manifest.json")
        resolveContainedVoiceModelFile(targetDirectory, "manifest.json").let { target ->
            sourceManifest.copyTo(target, overwrite = false)
        }
        manifest.files.forEach { path ->
            val source = resolveContainedVoiceModelFile(sourceDirectory, path)
            val target = resolveContainedVoiceModelFile(targetDirectory, path)
            check(target.parentFile?.mkdirs() != false || target.parentFile?.isDirectory == true) {
                "无法创建模型文件目录: $path"
            }
            source.copyTo(target, overwrite = false)
        }
    }

    private fun storeDownloadedVerification(directory: File, manifest: VoiceModelManifest) {
        val entry = trustedCatalog?.entryFor(manifest) ?: return
        check(entry.matchesLayout(manifest, directory)) { "安装后的模型布局失效" }
        val signature = downloadedPackageCacheSignature(directory, manifest, entry.publisher)
        preferences.edit()
            .putString(downloadedVerificationKey(manifest.modelId), signature)
            .apply()
    }

    private fun downloadedVerificationKey(modelId: String): String =
        VERIFIED_DOWNLOADED_PREFIX + modelId

    private fun clearDownloadedVerification(modelId: String) {
        preferences.edit().remove(downloadedVerificationKey(modelId)).apply()
    }

    private fun sha256Assets(paths: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        paths.sorted().forEach { path ->
            context.assets.open(path).use { input -> digest.updateStream(input) }
        }
        return digest.digest().toHexString()
    }

    private fun sha256Files(directory: File, paths: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        paths.sorted().forEach { path ->
            val file = resolveContainedVoiceModelFile(directory, path)
            check(file.isFile) { "缺少模型文件: $path" }
            file.inputStream().use { input -> digest.updateStream(input) }
        }
        return digest.digest().toHexString()
    }

    private fun MessageDigest.updateStream(input: InputStream) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            update(buffer, 0, read)
        }
    }
}
