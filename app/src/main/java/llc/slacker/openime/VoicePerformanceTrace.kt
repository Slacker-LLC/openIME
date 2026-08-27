package llc.slacker.openime

import android.os.SystemClock
import android.util.Log

/** Session timing only. No transcript, PCM, hotword or editor content is stored. */
object VoicePerformanceTrace {
    private const val TAG = "OpenImeVoicePerf"
    private const val MAX_ACTIVE_TRACES = 8

    data class Token internal constructor(val generation: Long)

    private data class Trace(
        val startedAt: Long,
        var modelReadyAt: Long = 0L,
        var audioRecordAt: Long = 0L,
        var firstPcmAt: Long = 0L,
        var firstDecodeAt: Long = 0L,
        var firstPartialAt: Long = 0L,
        var firstDisplayAt: Long = 0L,
        var releaseAt: Long = 0L,
        var finalAsrAt: Long = 0L,
        var punctuationDoneAt: Long = 0L,
        var finished: Boolean = false,
    )

    private val lock = Any()
    private val traces = LinkedHashMap<Long, Trace>()
    private var nextGeneration = 0L
    private var latestGeneration: Long? = null
    @Volatile
    private var testClock: (() -> Long)? = null
    @Volatile
    private var testLogger: ((String) -> Unit)? = null

    fun begin(): Token = synchronized(lock) {
        val token = Token(++nextGeneration)
        traces[token.generation] = Trace(startedAt = now())
        latestGeneration = token.generation
        while (traces.size > MAX_ACTIVE_TRACES) {
            traces.remove(traces.keys.first())
        }
        token
    }

    /** Debug callback injection is not a real microphone session. */
    fun abandon() {
        currentToken()?.let(::abandon)
    }

    fun abandon(token: Token) = synchronized(lock) {
        removeLocked(token.generation)
    }

    fun markModelReady() = currentToken()?.let(::markModelReady)
    fun markModelReady(token: Token) = mark(token) { if (it.modelReadyAt == 0L) it.modelReadyAt = now() }

    fun markAudioRecordStart() = currentToken()?.let(::markAudioRecordStart)
    fun markAudioRecordStart(token: Token) = mark(token) { if (it.audioRecordAt == 0L) it.audioRecordAt = now() }

    fun markFirstPcm() = currentToken()?.let(::markFirstPcm)
    fun markFirstPcm(token: Token) = mark(token) { if (it.firstPcmAt == 0L) it.firstPcmAt = now() }

    fun markFirstDecode() = currentToken()?.let(::markFirstDecode)
    fun markFirstDecode(token: Token) = mark(token) { if (it.firstDecodeAt == 0L) it.firstDecodeAt = now() }

    fun markFirstPartial() = currentToken()?.let(::markFirstPartial)
    fun markFirstPartial(token: Token) = mark(token) { if (it.firstPartialAt == 0L) it.firstPartialAt = now() }

    fun markVoiceRelease() = currentToken()?.let(::markVoiceRelease)
    fun markVoiceRelease(token: Token) = mark(token) { if (it.releaseAt == 0L) it.releaseAt = now() }

    fun markFinalAsr() = currentToken()?.let(::markFinalAsr)
    fun markFinalAsr(token: Token) = mark(token) { if (it.finalAsrAt == 0L) it.finalAsrAt = now() }

    fun markPunctuationDone() = currentToken()?.let(::markPunctuationDone)
    fun markPunctuationDone(token: Token) = mark(token) { if (it.punctuationDoneAt == 0L) it.punctuationDoneAt = now() }

    fun markFirstDisplay() {
        currentToken()?.let(::markFirstDisplay)
    }

    fun markFirstDisplay(token: Token) {
        synchronized(lock) {
            val current = traces[token.generation] ?: return
            if (current.firstDisplayAt != 0L) return
            current.firstDisplayAt = now()
            log("traceGeneration=${token.generation} firstDisplayMs=${elapsed(current, current.firstDisplayAt)}")
            if (current.finished) removeLocked(token.generation)
        }
    }

    fun finish(droppedPcmSamples: Long, failed: Boolean = false) {
        currentToken()?.let { finish(it, droppedPcmSamples, failed) }
    }

    fun finish(
        token: Token,
        droppedPcmSamples: Long,
        failed: Boolean = false,
    ) {
        synchronized(lock) {
            val current = traces[token.generation] ?: return
            if (current.finished) return
            current.finished = true
            val finishedAt = now()
            val punctuationMs = if (current.finalAsrAt > 0L && current.punctuationDoneAt > 0L) {
                current.punctuationDoneAt - current.finalAsrAt
            } else {
                -1L
            }
            val finalizeMs = if (current.releaseAt > 0L && current.punctuationDoneAt > 0L) {
                current.punctuationDoneAt - current.releaseAt
            } else {
                -1L
            }
            log(
                "traceGeneration=${token.generation} " +
                    "modelReadyMs=${elapsed(current, current.modelReadyAt)} " +
                    "micStartMs=${elapsed(current, current.audioRecordAt)} " +
                    "firstPcmMs=${elapsed(current, current.firstPcmAt)} " +
                    "firstDecodeMs=${elapsed(current, current.firstDecodeAt)} " +
                    "firstPartialMs=${elapsed(current, current.firstPartialAt)} " +
                    "firstDisplayMs=${elapsed(current, current.firstDisplayAt)} " +
                    "finalizeMs=$finalizeMs punctuationMs=$punctuationMs " +
                    "droppedPcmSamples=$droppedPcmSamples degraded=${droppedPcmSamples > 0L} " +
                    "failed=$failed sessionDurationMs=${finishedAt - current.startedAt}",
            )
            if (current.firstDisplayAt > 0L || failed) removeLocked(token.generation)
        }
    }

    internal fun currentTokenForBackend(): Token? = currentToken()

    internal fun isActiveForTest(token: Token): Boolean = synchronized(lock) {
        traces.containsKey(token.generation)
    }

    internal fun activeGenerationsForTest(): List<Long> = synchronized(lock) {
        traces.keys.toList()
    }

    internal fun resetForTest(
        clock: (() -> Long)? = null,
        logger: ((String) -> Unit)? = null,
    ) = synchronized(lock) {
        traces.clear()
        nextGeneration = 0L
        latestGeneration = null
        testClock = clock
        testLogger = logger
    }

    private fun currentToken(): Token? = synchronized(lock) {
        val generation = latestGeneration ?: return@synchronized null
        if (traces.containsKey(generation)) Token(generation) else null
    }

    private inline fun mark(token: Token, block: (Trace) -> Unit) = synchronized(lock) {
        traces[token.generation]?.let(block)
    }

    private fun removeLocked(generation: Long) {
        traces.remove(generation)
        if (latestGeneration == generation) latestGeneration = traces.keys.lastOrNull()
    }

    private fun elapsed(current: Trace, timestamp: Long): Long =
        if (timestamp == 0L) -1L else timestamp - current.startedAt

    private fun now(): Long = testClock?.invoke() ?: SystemClock.elapsedRealtime()

    private fun log(message: String) {
        val logger = testLogger
        if (logger != null) logger(message) else Log.i(TAG, message)
    }
}
