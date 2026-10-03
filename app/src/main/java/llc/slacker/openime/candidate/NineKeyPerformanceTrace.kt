package llc.slacker.openime.candidate

import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

internal object NineKeyPerformanceTrace {
    private const val TAG = "OpenImeNineKeyPerf"
    private const val WINDOW_SIZE = 20

    private val constructionCount = AtomicInteger(0)
    private val lock = Any()
    private val resolveSamplesNs = LinkedHashMap<Int, MutableList<Long>>()

    fun recordDecoderConstruction(elapsedNs: Long, threadName: String) {
        val ordinal = constructionCount.incrementAndGet()
        safeLog(
            "decoder-init ordinal=${ordinal} first=${ordinal == 1} " +
                "elapsedMs=${formatMs(elapsedNs)} thread=$threadName " +
                "phase=CandidatePipeline.init",
        )
    }

    fun recordResolve(digitLength: Int, elapsedNs: Long, threadName: String) {
        if (digitLength !in 1..NineKeyLocalDecoder.MAX_DIGITS) return
        val summary = synchronized(lock) {
            val samples = resolveSamplesNs.getOrPut(digitLength) { ArrayList(WINDOW_SIZE) }
            samples += elapsedNs
            if (samples.size < WINDOW_SIZE) return@synchronized null
            val window = samples.takeLast(WINDOW_SIZE).sorted()
            ResolveSummary(
                p50Ns = percentile(window, 0.50),
                p95Ns = percentile(window, 0.95),
            ).also {
                samples.clear()
            }
        } ?: return

        safeLog(
            "resolve length=$digitLength samples=$WINDOW_SIZE " +
                "p50Ms=${formatMs(summary.p50Ns)} " +
                "p95Ms=${formatMs(summary.p95Ns)} thread=$threadName",
        )
    }

    private fun safeLog(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun percentile(sorted: List<Long>, quantile: Double): Long {
        if (sorted.isEmpty()) return 0L
        val index = (ceil(sorted.size * quantile).toInt() - 1)
            .coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun formatMs(valueNs: Long): String =
        String.format(Locale.US, "%.3f", valueNs / 1_000_000.0)

    private data class ResolveSummary(
        val p50Ns: Long,
        val p95Ns: Long,
    )
}
