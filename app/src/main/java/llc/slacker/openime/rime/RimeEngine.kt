package llc.slacker.openime.rime

import android.content.Context
import android.content.res.AssetManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import llc.slacker.openime.RimeNative
import llc.slacker.openime.core.CrashGuard
import llc.slacker.openime.core.RimeStartupRecovery
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.data.PersonalizationPolicy
import llc.slacker.openime.data.PersonalizationRepository
import llc.slacker.openime.data.RimeUserDictionaryArchive
import llc.slacker.openime.data.UserDataArchiveCodec
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class RimeCandidateEntry(
    val text: String,
    val nativeIndex: Int,
    /** Input characters this candidate spells (-1 unknown); < input length means partial. */
    val consumed: Int = -1,
)

internal fun rimeProbeHasCandidate(snapshot: Array<String>?): Boolean =
    snapshot.orEmpty().drop(2).any { !it.isNullOrBlank() }

internal fun rimeDataRevision(versionCode: Long): String = "apk-$versionCode"

internal fun rimeSchemaId(fuzzyEnabled: Boolean): String =
    if (fuzzyEnabled) "luna_pinyin_simp_fuzzy" else "luna_pinyin_simp"

/**
 * One serial lane for native mutations that must never stall the IME thread.
 * Candidate discovery stays synchronous for the existing background candidate
 * worker; selection learning and clear operations are queued here instead.
 */
internal class RimeMutationQueue(
    threadName: String = "local-rime-mutations",
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    fun submit(operation: () -> Unit): Boolean {
        if (closed) return false
        return runCatching {
            executor.execute {
                if (!closed) operation()
            }
            true
        }.getOrDefault(false)
    }

    override fun close() {
        closed = true
        executor.shutdownNow()
    }
}

/**
 * Tracks ownership of one asynchronous startup attempt. Destroy invalidates the
 * current generation immediately; a stale worker may clean native state, but it
 * can never publish READY after its owner has gone away.
 */
internal class RimeStartupGate {
    private var generation = 0L
    private var starting = false
    private var destroyed = false

    @Synchronized
    fun begin(): Long? {
        if (destroyed || starting) return null
        generation += 1
        starting = true
        return generation
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean =
        !destroyed && starting && generation == token

    @Synchronized
    fun complete(token: Long): Boolean {
        if (!isCurrent(token)) return false
        starting = false
        return true
    }

    @Synchronized
    fun fail(token: Long): Boolean {
        if (!isCurrent(token)) return false
        starting = false
        return true
    }

    @Synchronized
    fun destroy() {
        destroyed = true
        starting = false
        generation += 1
    }
}

/**
 * Owns one process-local librime session and exposes IME-friendly operations.
 *
 * Rime deployment is deliberately done off the IME main thread. A slow first
 * dictionary build must never block Android's key dispatch or make the keyboard
 * look frozen. Until it is ready, callers keep using the existing local
 * fallback engine; once ready, Chinese candidates come from librime.
 */
class RimeEngine(
    private val context: Context,
    private val assetManager: AssetManager = context.assets,
    private val assetRoot: String = "rime-data",
) {
    private val lock = Any()
    private val startupGate = RimeStartupGate()
    private val mutationQueue = RimeMutationQueue()
    private val startupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "local-rime-startup").apply { isDaemon = true }
    }
    private var activeSchemaId: String? = null
    @Volatile
    var isReady: Boolean = false
        private set
    @Volatile
    var errorMessage: String = ""
        private set

    init {
        PersonalizationRepository.configure(context)
    }

    fun start() {
        if (isReady) return
        val generation = startupGate.begin() ?: return
        startupExecutor.execute {
            var nativeStartupReturned = false
            try {
                val dataDirName = assetRoot.replace('/', '_')
                val sharedDir = File(context.filesDir, dataDirName).apply { mkdirs() }
                // Keep the production user database path stable; custom test
                // asset roots get an isolated user directory instead.
                val userDirName = if (assetRoot == "rime-data") "rime-user" else "$dataDirName-user"
                val userDir = File(context.filesDir, userDirName).apply { mkdirs() }
                if (!startupGate.isCurrent(generation)) return@execute
                copyAssetsIfNeeded(sharedDir)
                if (!startupGate.isCurrent(generation)) return@execute

                // A previous start that died inside native code leaves its marker
                // behind; escalate (clear compiled data, set the user database
                // aside, finally skip librime) instead of crashing in a loop.
                val recovery = RimeStartupRecovery(File(context.filesDir, "$dataDirName-startup"))
                if (recovery.lastStartupDiedNatively()) {
                    when (CrashGuard.previousExitWasNativeCrash(context)) {
                        // Killed by the user or the system while starting, not by librime.
                        false -> recovery.failedWithoutCrash()
                        // Android 11+ already recorded it in the exit history.
                        true -> Unit
                        // Older Android cannot say: assume the worst.
                        null -> CrashGuard.recordNativeStartupCrash(context)
                    }
                }
                val action = if (CrashGuard.isSafeMode(context)) {
                    recovery.failedWithoutCrash() // already counted above; do not count it every session
                    RimeStartupRecovery.Action.SKIP_NATIVE
                } else {
                    recovery.begin()
                }
                when (action) {
                    RimeStartupRecovery.Action.NORMAL -> Unit
                    RimeStartupRecovery.Action.CLEAN_BUILD -> clearCompiledData(sharedDir, userDir)
                    RimeStartupRecovery.Action.RESET_USER_DATA -> {
                        clearCompiledData(sharedDir, userDir)
                        setUserDataAside(userDir)
                    }
                    RimeStartupRecovery.Action.SKIP_NATIVE -> {
                        errorMessage = "librime is off after repeated crashes"
                        startupGate.fail(generation)
                        Log.w(TAG, "librime skipped (safe mode); using the Kotlin fallback")
                        return@execute
                    }
                }

                // nativeStartup is internally serialized. Even if destroy races
                // this call, nativeShutdown will either run after it or this
                // stale worker will perform the same idempotent cleanup below.
                RimeNative.nativeStartup(sharedDir.absolutePath, userDir.absolutePath)
                nativeStartupReturned = true
                if (!startupGate.isCurrent(generation)) {
                    cleanupNative()
                    return@execute
                }

                synchronized(lock) {
                    // The persisted fuzzy setting is the source of truth for the
                    // live native session. Select the matching schema before the
                    // health probe so READY never publishes with stale semantics.
                    check(syncSchemaFromSettingsLocked()) {
                        "librime requested schema unavailable"
                    }
                    // Prove that the selected schema and translator are actually
                    // usable. An empty-input snapshot can look non-empty even
                    // when no schema can produce candidates.
                    val probe = try {
                        RimeNative.nativeSetInput(HEALTH_PROBE_INPUT)
                    } finally {
                        RimeNative.nativeClear()
                    }
                    check(rimeProbeHasCandidate(probe)) {
                        "librime schema/candidate pipeline unavailable"
                    }
                }
                if (!startupGate.complete(generation)) {
                    cleanupNative()
                    return@execute
                }
                recovery.succeeded()
                errorMessage = ""
                isReady = true
                Log.i(TAG, "librime ready schema=$activeSchemaId")
            } catch (throwable: Throwable) {
                // A Java exception is not a native crash: lift the marker so it is not counted as one.
                runCatching {
                    RimeStartupRecovery(File(context.filesDir, "${assetRoot.replace('/', '_')}-startup")).failedWithoutCrash()
                }
                if (nativeStartupReturned) cleanupNative()
                if (startupGate.fail(generation)) {
                    isReady = false
                    errorMessage = throwable.message ?: throwable.javaClass.simpleName
                    Log.w(TAG, "librime unavailable; keeping Kotlin fallback", throwable)
                }
                // An Error (OutOfMemoryError, StackOverflowError) is not made
                // recoverable by falling back to a second dictionary: the
                // fallback needs the same memory that is already gone. Record
                // it, clean up, then let it surface instead of hiding a fatal
                // condition behind a silent "fallback" state.
                if (throwable is Error) throw throwable
            }
        }
    }

    fun candidates(input: String): List<String> {
        return candidateEntries(input).map { it.text }
    }

    /** Return display text together with its absolute librime candidate index. */
    internal fun candidateEntries(input: String): List<RimeCandidateEntry> {
        val normalized = RimeInputNormalizer.normalize(input)
        if (!isReady || normalized.isBlank()) return emptyList()
        return synchronized(lock) {
            runCatching {
                if (!syncSchemaFromSettingsLocked()) {
                    emptyList()
                } else {
                    val snapshot = RimeNative.nativeSetInput(normalized)
                    val entries = snapshotCandidateEntries(snapshot)
                    val ends = RimeNative.nativeCandidateEnds(snapshot.orEmpty().size - 2)
                    if (ends == null) entries else entries.map { entry ->
                        entry.copy(consumed = ends.getOrElse(entry.nativeIndex) { -1 })
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    /** Let Rime learn the selected candidate, then return its committed text. */
    fun selectCandidate(input: String, candidate: String, allowLearning: Boolean = true): String {
        if (!allowLearning) return candidate
        val normalized = RimeInputNormalizer.normalize(input)
        if (!isReady || normalized.isBlank()) return ""
        val allowPersonalization = PersonalizationPolicy.allow(
            (context as? InputMethodService)?.currentInputEditorInfo,
        )
        val committed = synchronized(lock) {
            runCatching {
                if (!syncSchemaFromSettingsLocked()) return@runCatching ""
                val snapshot = RimeNative.nativeSetInput(normalized)
                val entry = snapshotCandidateEntries(snapshot).firstOrNull { it.text == candidate }
                if (entry != null) RimeNative.nativeSelectCandidate(entry.nativeIndex).orEmpty() else ""
            }.getOrDefault("")
        }
        if (allowPersonalization && committed.isNotBlank()) {
            PersonalizationRepository.record(committed)
        }
        return committed
    }

    /**
     * Queue learning for a rendered native entry. A deferred reference uses
     * nativeIndex=-1 and carries both input code and visible text; it is resolved
     * on this background lane so a fast tap never blocks the IME thread.
     */
    fun selectCandidate(input: String, nativeIndex: Int, allowLearning: Boolean = true): String {
        // Capture the editor's policy at submission time. Private selections
        // must never enter the mutation queue, even if the editor later changes.
        if (!allowLearning) return ""
        val allowPersonalization = PersonalizationPolicy.allow(
            (context as? InputMethodService)?.currentInputEditorInfo,
        )

        val deferred = NativeCandidateReference.decodeDeferred(input)
        val sourceInput = deferred?.first ?: input
        val deferredText = deferred?.second
        val normalized = RimeInputNormalizer.normalize(sourceInput)
        if (!isReady || normalized.isBlank()) return ""
        if (nativeIndex < 0 && deferred == null) return ""

        mutationQueue.submit {
            if (!isReady) return@submit
            val committed = synchronized(lock) {
                if (!isReady) return@synchronized ""
                runCatching {
                    val resolvedIndex = if (deferred != null) {
                        // Deferred snapshots were rendered before a native
                        // query completed. Synchronize the current schema,
                        // replay the exact code, and locate the visible word.
                        if (!syncSchemaFromSettingsLocked()) return@runCatching ""
                        val snapshot = RimeNative.nativeSetInput(normalized)
                        snapshotCandidateEntries(snapshot)
                            .firstOrNull { it.text == deferredText }
                            ?.nativeIndex
                            ?: return@runCatching ""
                    } else {
                        // A concrete native index belongs to the schema that
                        // produced the rendered snapshot. Do not switch schemas
                        // before consuming it.
                        RimeNative.nativeSetInput(normalized)
                        nativeIndex
                    }
                    RimeNative.nativeSelectCandidate(resolvedIndex).orEmpty()
                }.getOrDefault("")
            }
            if (allowPersonalization && committed.isNotBlank()) {
                PersonalizationRepository.record(committed)
            }
        }
        return ""
    }

    fun isUserLearnedCandidate(
        input: String,
        candidate: String,
        onComplete: (Boolean) -> Unit,
    ): Boolean {
        val normalized = RimeInputNormalizer.normalize(input)
        val visible = candidate.trim()
        if (!isReady || normalized.isBlank() || visible.isEmpty()) return false

        return mutationQueue.submit {
            val learned = synchronized(lock) {
                if (!isReady) {
                    false
                } else {
                    runCatching {
                        if (!syncSchemaFromSettingsLocked()) return@runCatching false
                        val snapshot = RimeNative.nativeSetInput(normalized)
                        val entry = snapshotCandidateEntries(snapshot)
                            .firstOrNull { it.text == visible }
                            ?: return@runCatching false
                        RimeNative.nativeIsUserLearnedCandidate(entry.nativeIndex)
                    }.getOrDefault(false)
                }
            }
            onComplete(learned)
        }
    }

    /**
     * Delete one user-learned candidate without blocking the IME thread.
     * Built-in dictionary entries are not deletable and report false.
     */
    fun deleteCandidate(
        input: String,
        candidate: String,
        onComplete: (Boolean) -> Unit,
    ): Boolean {
        val normalized = RimeInputNormalizer.normalize(input)
        val visible = candidate.trim()
        if (!isReady || normalized.isBlank() || visible.isEmpty()) return false

        return mutationQueue.submit {
            val deleted = synchronized(lock) {
                if (!isReady) {
                    false
                } else {
                    runCatching {
                        if (!syncSchemaFromSettingsLocked()) return@runCatching false
                        val snapshot = RimeNative.nativeSetInput(normalized)
                        val entry = snapshotCandidateEntries(snapshot)
                            .firstOrNull { it.text == visible }
                            ?: return@runCatching false
                        if (!RimeNative.nativeIsUserLearnedCandidate(entry.nativeIndex)) {
                            return@runCatching false
                        }
                        RimeNative.nativeDeleteCandidate(entry.nativeIndex)
                    }.getOrDefault(false)
                }
            }
            onComplete(deleted)
        }
    }

    internal fun exportUserDictionaries(
        targetDir: File,
        onComplete: (List<RimeUserDictionaryArchive>?) -> Unit,
    ): Boolean {
        if (!isReady) return false
        return mutationQueue.submit {
            val exported: List<RimeUserDictionaryArchive>? = synchronized(lock) {
                if (!isReady) return@synchronized null
                try {
                    if (!syncSchemaFromSettingsLocked()) return@synchronized null
                    if (!targetDir.exists() && !targetDir.mkdirs()) {
                        return@synchronized null
                    }
                    val mappings =
                        RimeNative.nativeExportUserDictionaries(targetDir.absolutePath)
                            ?: return@synchronized null
                    val result = mutableListOf<RimeUserDictionaryArchive>()
                    for (mapping in mappings) {
                        val separator = mapping.indexOf('\t')
                        if (separator <= 0 || separator >= mapping.lastIndex) {
                            return@synchronized null
                        }
                        val name = mapping.substring(0, separator).trim()
                        val path = mapping.substring(separator + 1)
                        val file = File(path)
                        if (
                            !UserDataArchiveCodec.isSafeRimeDictionaryName(name) ||
                            !file.isFile
                        ) {
                            return@synchronized null
                        }
                        result += RimeUserDictionaryArchive(
                            name = name,
                            content = file.readText(),
                        )
                    }
                    result
                } catch (error: Exception) {
                    Log.w(TAG, "Rime user dictionary export failed", error)
                    null
                }
            }
            onComplete(exported)
        }
    }

    internal fun importUserDictionaries(
        sourceDir: File,
        dictionaries: List<RimeUserDictionaryArchive>,
        onComplete: (Int?) -> Unit,
    ): Boolean {
        if (!isReady) return false
        return mutationQueue.submit {
            val imported = synchronized(lock) {
                if (!isReady) {
                    null
                } else {
                    runCatching {
                        if (!syncSchemaFromSettingsLocked()) return@runCatching null
                        sourceDir.mkdirs()
                        var total = 0
                        dictionaries.forEachIndexed { index, item ->
                            val file = File(sourceDir, "dict-$index.userdb.txt")
                            file.writeText(item.content)
                            val count = RimeNative.nativeImportUserDictionary(
                                item.name,
                                file.absolutePath,
                            )
                            if (count < 0) return@runCatching null
                            total += count
                        }
                        total
                    }.getOrNull()
                }
            }
            onComplete(imported)
        }
    }

    fun commitFirst(input: String, allowLearning: Boolean = true): String {
        if (!allowLearning) return candidates(input).firstOrNull().orEmpty()
        val normalized = RimeInputNormalizer.normalize(input)
        if (!isReady || normalized.isBlank()) return ""
        return synchronized(lock) {
            runCatching {
                if (!syncSchemaFromSettingsLocked()) return@runCatching ""
                RimeNative.nativeSetInput(normalized)
                RimeNative.nativeCommitFirst().orEmpty()
            }.getOrDefault("")
        }
    }

    /** Queue session cleanup instead of waiting for an in-flight native query. */
    fun clear() {
        if (!isReady) return
        mutationQueue.submit {
            if (isReady) {
                synchronized(lock) {
                    if (isReady) runCatching { RimeNative.nativeClear() }
                }
            }
        }
    }

    fun shutdown() {
        startupGate.destroy()
        isReady = false
        mutationQueue.close()
        // shutdownNow() does not wait for the task currently running. If that
        // task is inside nativeStartup it can re-initialize librime *after*
        // the cleanup below finalized it, leaving a live session nobody owns
        // and no way to destroy. Give it a bounded grace period first.
        startupExecutor.shutdownNow()
        val startupSettled = runCatching {
            startupExecutor.awaitTermination(STARTUP_SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (startupSettled) {
            cleanupNative()
        } else {
            // nativeStartup serializes all native state behind its own mutex.
            // Calling nativeShutdown here would wait on that mutex forever when
            // a slow first deployment is still running. The stale worker sees
            // the invalidated gate after nativeStartup returns and performs the
            // cleanup itself.
            Log.w(TAG, "librime startup did not settle before shutdown")
        }
    }

    /** Cached persisted fuzzy setting; invalidated explicitly from settings UI. */
    @Volatile
    private var cachedFuzzyPinyin: Boolean? = null

    /** Drop the cached fuzzy setting so the next query re-reads it. */
    fun invalidateSettingsCache() {
        cachedFuzzyPinyin = null
    }

    private fun fuzzyPinyinEnabled(): Boolean {
        cachedFuzzyPinyin?.let { return it }
        val value = ImeSettingsRepository.loadFuzzy(context)
        cachedFuzzyPinyin = value
        return value
    }

    private fun syncSchemaFromSettingsLocked(): Boolean {
        val desiredSchemaId = rimeSchemaId(fuzzyPinyinEnabled())
        if (activeSchemaId == desiredSchemaId) return true
        if (!RimeNative.nativeSelectSchema(desiredSchemaId)) return false
        activeSchemaId = desiredSchemaId
        RimeNative.nativeClear()
        Log.i(TAG, "librime schema=$desiredSchemaId")
        return true
    }

    private fun cleanupNative() {
        synchronized(lock) {
            activeSchemaId = null
            runCatching { RimeNative.nativeShutdown() }
        }
    }

    private fun snapshotCandidateEntries(snapshot: Array<String>?): List<RimeCandidateEntry> =
        snapshot.orEmpty()
            .drop(2)
            .mapIndexedNotNull { index, text ->
                if (text.isNullOrBlank()) {
                    null
                } else {
                    RimeCandidateEntry(text = text, nativeIndex = index)
                }
            }
            .distinctBy { it.text }

    private fun copyAssetsIfNeeded(sharedDir: File) {
        // Read the identity of the actually installed APK instead of relying on
        // generated BuildConfig fields. This stays valid even when BuildConfig
        // generation is disabled and automatically changes on every upgrade.
        val revision = rimeDataRevision(installedVersionCode())
        val marker = File(sharedDir, ".openime-rime-$revision")
        val requiredSchemasPresent =
            File(sharedDir, "luna_pinyin_simp.schema.yaml").exists() &&
                File(sharedDir, "luna_pinyin_simp_fuzzy.schema.yaml").exists()
        if (marker.exists() && requiredSchemasPresent) return
        deleteChildren(sharedDir)
        copyAssetTree(assetRoot, sharedDir)
        marker.writeText("openIME Rime data revision $revision\n")
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = assetManager.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assetManager.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destination.mkdirs()
        children.forEach { child ->
            copyAssetTree("$assetPath/$child", File(destination, child))
        }
    }

    private fun clearCompiledData(sharedDir: File, userDir: File) {
        listOf(File(sharedDir, "build"), File(userDir, "build")).forEach { build ->
            if (build.isDirectory) {
                deleteChildren(build)
                build.delete()
            }
        }
        Log.w(TAG, "cleared compiled librime data after a native startup failure")
    }

    /** Keep one backup of a user database that may be damaged and start with an empty one. */
    private fun setUserDataAside(userDir: File) {
        val backup = File(userDir.parentFile, "${userDir.name}.corrupt")
        if (backup.exists()) {
            deleteChildren(backup)
            backup.delete()
        }
        if (userDir.renameTo(backup)) {
            userDir.mkdirs()
            Log.w(TAG, "user dictionary set aside as ${backup.name}")
        }
    }

    private fun deleteChildren(directory: File) {
        directory.listFiles().orEmpty().forEach { child ->
            if (child.isDirectory) deleteChildren(child)
            child.delete()
        }
    }

    private companion object {
        const val TAG = "RimeEngine"
        const val HEALTH_PROBE_INPUT = "ni"
        /**
         * Upper bound for waiting on an in-flight startup before tearing down.
         * Short enough to stay off the IME shutdown path, long enough for the
         * common case where the worker is between cancellation checks.
         */
        const val STARTUP_SHUTDOWN_GRACE_MS = 1_500L
    }
}
