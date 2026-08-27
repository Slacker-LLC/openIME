package llc.slacker.openime

import android.os.SystemClock
import android.util.Log

/** Session timing only. No transcript, PCM, hotword or editor content is stored. */
object VoicePerformanceTrace {
    private const val TAG = "OpenImeVoicePerf"

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
    private var trace: Trace? = null

    fun begin() = synchronized(lock) {
        trace = Trace(startedAt = SystemClock.elapsedRealtime())
    }

    /** Debug callback injection is not a real microphone session. */
    fun abandon() = synchronized(lock) {
        trace = null
    }

    fun markModelReady() = mark { if (it.modelReadyAt == 0L) it.modelReadyAt = now() }
    fun markAudioRecordStart() = mark { if (it.audioRecordAt == 0L) it.audioRecordAt = now() }
    fun markFirstPcm() = mark { if (it.firstPcmAt == 0L) it.firstPcmAt = now() }
    fun markFirstDecode() = mark { if (it.firstDecodeAt == 0L) it.firstDecodeAt = now() }
    fun markFirstPartial() = mark { if (it.firstPartialAt == 0L) it.firstPartialAt = now() }
    fun markVoiceRelease() = mark { if (it.releaseAt == 0L) it.releaseAt = now() }
    fun markFinalAsr() = mark { if (it.finalAsrAt == 0L) it.finalAsrAt = now() }
    fun markPunctuationDone() = mark { if (it.punctuationDoneAt == 0L) it.punctuationDoneAt = now() }

    fun markFirstDisplay() {
        synchronized(lock) {
            val current = trace ?: return
            if (current.firstDisplayAt != 0L) return
            current.firstDisplayAt = now()
            Log.i(TAG, "firstDisplayMs=${elapsed(current, current.firstDisplayAt)}")
            if (current.finished) trace = null
        }
    }

    fun finish(droppedPcmSamples: Long, failed: Boolean = false) {
        synchronized(lock) {
            val current = trace ?: return
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
            Log.i(
                TAG,
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
            if (current.firstDisplayAt > 0L || failed) trace = null
        }
    }

    private inline fun mark(block: (Trace) -> Unit) = synchronized(lock) {
        trace?.let(block)
    }

    private fun elapsed(current: Trace, timestamp: Long): Long =
        if (timestamp == 0L) -1L else timestamp - current.startedAt

    private fun now(): Long = SystemClock.elapsedRealtime()
}
