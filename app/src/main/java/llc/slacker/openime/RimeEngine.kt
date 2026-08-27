package llc.slacker.openime

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

internal data class RimeCandidateEntry(
    val text: String,
    val nativeIndex: Int,
)

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
        startupExecutor.execute {
            runCatching {
                val sharedDir = File(context.filesDir, "rime-data").apply { mkdirs() }
                val userDir = File(context.filesDir, "rime-user").apply { mkdirs() }
                copyAssetsIfNeeded(sharedDir)
                RimeNative.nativeStartup(sharedDir.absolutePath, userDir.absolutePath)
                synchronized(lock) {
                    // A session with no candidates is not useful; the native
                    // side has already completed synchronous deployment here.
                    val probe = RimeNative.nativeSetInput("")
                    check(probe.isNotEmpty()) { "librime session unavailable" }
                }
                isReady = true
                Log.i(TAG, "librime ready")
            }.onFailure { throwable ->
                errorMessage = throwable.message ?: throwable.javaClass.simpleName
                Log.w(TAG, "librime unavailable; keeping Kotlin fallback", throwable)
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
        if (isReady) {
            synchronized(lock) {
                runCatching { RimeNative.nativeShutdown() }
            }
        }
        isReady = false
        startupExecutor.shutdownNow()
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
        val marker = File(sharedDir, ".openime-rime-3")
        if (marker.exists() && File(sharedDir, "luna_pinyin_simp.schema.yaml").exists()) return
        deleteChildren(sharedDir)
        copyAssetTree("rime-data", sharedDir)
        marker.writeText("openIME Rime data revision 3 with Rime Ice core lexicons\n")
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
    }
}
