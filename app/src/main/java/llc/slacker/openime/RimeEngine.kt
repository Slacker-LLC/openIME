package llc.slacker.openime

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class RimeCandidateEntry(
    val text: String,
    val nativeIndex: Int,
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
class RimeEngine(private val context: Context) {
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

    fun start() {
        if (isReady) return
        val generation = startupGate.begin() ?: return
        startupExecutor.execute {
            var nativeStartupReturned = false
            try {
                val sharedDir = File(context.filesDir, "rime-data").apply { mkdirs() }
                val userDir = File(context.filesDir, "rime-user").apply { mkdirs() }
                if (!startupGate.isCurrent(generation)) return@execute
                copyAssetsIfNeeded(sharedDir)
                if (!startupGate.isCurrent(generation)) return@execute

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
                errorMessage = ""
                isReady = true
                Log.i(TAG, "librime ready schema=$activeSchemaId")
            } catch (throwable: Throwable) {
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

    /**
     * Return display text together with its absolute librime candidate index.
     * The index must travel with a nine-key path; the same label can occur for
     * several ambiguous Pinyin inputs and cannot safely be selected by the
     * preview string alone.
     */
    internal fun candidateEntries(input: String): List<RimeCandidateEntry> {
        val normalized = RimeInputNormalizer.normalize(input)
        if (!isReady || normalized.isBlank()) return emptyList()
        return synchronized(lock) {
            runCatching {
                if (!syncSchemaFromSettingsLocked()) {
                    emptyList()
                } else {
                    snapshotCandidateEntries(RimeNative.nativeSetInput(normalized))
                }
            }.getOrDefault(emptyList())
        }
    }

    /** Let Rime learn the selected candidate, then return its committed text. */
    fun selectCandidate(input: String, candidate: String): String {
        val normalized = RimeInputNormalizer.normalize(input)
        if (!isReady || normalized.isBlank()) return ""
        return synchronized(lock) {
            runCatching {
                if (!syncSchemaFromSettingsLocked()) return@runCatching ""
                val snapshot = RimeNative.nativeSetInput(normalized)
                val entry = snapshotCandidateEntries(snapshot).firstOrNull { it.text == candidate }
                if (entry != null) RimeNative.nativeSelectCandidate(entry.nativeIndex).orEmpty() else ""
            }.getOrDefault("")
        }
    }

    /**
     * Queue learning for an already-rendered native entry and return
     * immediately. The editor commit is owned by CandidateSnapshot, so the IME
     * thread never needs native committed text here.
     */
    fun selectCandidate(input: String, nativeIndex: Int): String {
        val normalized = RimeInputNormalizer.normalize(input)
        if (!isReady || normalized.isBlank() || nativeIndex < 0) return ""
        mutationQueue.submit {
            if (isReady) {
                synchronized(lock) {
                    if (isReady) {
                        runCatching {
                            // nativeIndex belongs to the schema that produced the
                            // rendered snapshot. Do not switch schemas between
                            // rendering and consuming that index; the next native
                            // candidate query will synchronize a changed setting.
                            RimeNative.nativeSetInput(normalized)
                            RimeNative.nativeSelectCandidate(nativeIndex)
                        }
                    }
                }
            }
        }
        return ""
    }

    fun commitFirst(input: String): String {
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
        runCatching {
            if (!startupExecutor.awaitTermination(STARTUP_SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "librime startup did not settle before shutdown")
            }
        }
        cleanupNative()
    }

    /**
     * Mirrors the persisted fuzzy setting. [syncSchemaFromSettingsLocked] runs
     * inside every candidate query, and reading SharedPreferences is a disk
     * read plus an XML parse — it used to happen once per keystroke even when
     * the value had not changed in months.
     */
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
                // make_strings() can return null outright, or an array whose
                // tail slots are still null after an allocation failure. The
                // old code dereferenced the element unconditionally, threw
                // NPE, and the surrounding runCatching turned that into a
                // silent empty candidate list with no diagnostic at all.
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
        copyAssetTree("rime-data", sharedDir)
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
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destination.mkdirs()
        children.forEach { child ->
            copyAssetTree("$assetPath/$child", File(destination, child))
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
