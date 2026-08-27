package llc.slacker.openime

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

internal data class RimeCandidateEntry(
    val text: String,
    val nativeIndex: Int,
)

internal fun rimeProbeHasCandidate(snapshot: Array<String>): Boolean =
    snapshot.drop(2).any { it.isNotBlank() }

internal fun rimeDataRevision(versionCode: Int): String = "apk-$versionCode"

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
    private val startupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "local-rime-startup").apply { isDaemon = true }
    }
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
                Log.i(TAG, "librime ready")
            } catch (throwable: Throwable) {
                if (nativeStartupReturned) cleanupNative()
                if (startupGate.fail(generation)) {
                    isReady = false
                    errorMessage = throwable.message ?: throwable.javaClass.simpleName
                    Log.w(TAG, "librime unavailable; keeping Kotlin fallback", throwable)
                }
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
                snapshotCandidateEntries(RimeNative.nativeSetInput(normalized))
            }.getOrDefault(emptyList())
        }
    }

    /** Let Rime learn the selected candidate, then return its committed text. */
    fun selectCandidate(input: String, candidate: String): String {
        val normalized = RimeInputNormalizer.normalize(input)
        if (!isReady || normalized.isBlank()) return ""
        return synchronized(lock) {
            runCatching {
                val snapshot = RimeNative.nativeSetInput(normalized)
                val entry = snapshotCandidateEntries(snapshot).firstOrNull { it.text == candidate }
                if (entry != null) RimeNative.nativeSelectCandidate(entry.nativeIndex) else ""
            }.getOrDefault("")
        }
    }

    /** Select an entry previously returned by [candidateEntries]. */
    fun selectCandidate(input: String, nativeIndex: Int): String {
        val normalized = RimeInputNormalizer.normalize(input)
        if (!isReady || normalized.isBlank() || nativeIndex < 0) return ""
        return synchronized(lock) {
            runCatching {
                RimeNative.nativeSetInput(normalized)
                RimeNative.nativeSelectCandidate(nativeIndex)
            }.getOrDefault("")
        }
    }

    fun commitFirst(input: String): String {
        val normalized = RimeInputNormalizer.normalize(input)
        if (!isReady || normalized.isBlank()) return ""
        return synchronized(lock) {
            runCatching {
                RimeNative.nativeSetInput(normalized)
                RimeNative.nativeCommitFirst()
            }.getOrDefault("")
        }
    }

    fun clear() {
        if (!isReady) return
        synchronized(lock) {
            runCatching { RimeNative.nativeClear() }
        }
    }

    fun shutdown() {
        startupGate.destroy()
        isReady = false
        cleanupNative()
        startupExecutor.shutdownNow()
    }

    private fun cleanupNative() {
        synchronized(lock) {
            runCatching { RimeNative.nativeShutdown() }
        }
    }

    private fun snapshotCandidateEntries(snapshot: Array<String>): List<RimeCandidateEntry> =
        snapshot.drop(2)
            .mapIndexedNotNull { index, text ->
                text.takeIf { it.isNotBlank() }?.let {
                    RimeCandidateEntry(text = it, nativeIndex = index)
                }
            }
            .distinctBy { it.text }

    private fun copyAssetsIfNeeded(sharedDir: File) {
        // An APK upgrade necessarily changes versionCode. Tie the copied Rime
        // data revision to that build identity so asset changes cannot silently
        // keep an old filesDir copy because somebody forgot to bump a magic
        // marker number. Re-copying once per app upgrade is cheap and explicit.
        val revision = rimeDataRevision(BuildConfig.VERSION_CODE)
        val marker = File(sharedDir, ".openime-rime-$revision")
        if (marker.exists() && File(sharedDir, "luna_pinyin_simp.schema.yaml").exists()) return
        deleteChildren(sharedDir)
        copyAssetTree("rime-data", sharedDir)
        marker.writeText("openIME Rime data revision $revision\n")
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
    }
}
