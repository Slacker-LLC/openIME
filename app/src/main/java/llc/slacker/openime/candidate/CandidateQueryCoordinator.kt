package llc.slacker.openime.candidate

import android.os.Handler
import android.os.SystemClock
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.rime.RimeCandidateEntry
import llc.slacker.openime.rime.RimeEngine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal data class CandidateNativeQueryResult(
    val choices: List<NativeCandidateChoice>,
    val latencyMs: Long,
    val resultLatencyMs: Long = latencyMs,
)

internal data class CandidateQueryTicket(
    val generation: Long,
    val inputs: List<String>,
    val scheduled: Boolean,
)

/**
 * Owns asynchronous librime query scheduling and cancellation.
 *
 * Product candidate ordering, ImeState and commit semantics remain outside this
 * class. Its only job is to normalize inputs, coalesce obsolete key bursts,
 * execute JNI work off the input thread and return only the current generation.
 */
internal class CandidateQueryCoordinator(
    private val rime: RimeEngine,
    private val mainHandler: Handler,
    private val fallbackCandidatesFor: (String) -> List<String>,
    private val maxInputLength: Int,
    private val maxNineKeyPaths: Int,
    private val maxCandidates: Int,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ime-candidates").apply { isDaemon = true }
    }
    private val generation = AtomicLong(0L)

    @Volatile
    var activeInputs: List<String> = emptyList()
        private set

    fun currentGeneration(): Long = generation.get()

    fun invalidate() {
        generation.incrementAndGet()
        activeInputs = emptyList()
    }

    fun shutdown() {
        executor.shutdownNow()
        invalidate()
    }

    fun request(
        composition: String,
        mode: KeyboardMode,
        rimeInputs: List<String>,
        onResult: (
            generation: Long,
            inputs: List<String>,
            result: CandidateNativeQueryResult,
        ) -> Unit,
    ): CandidateQueryTicket {
        val request = generation.incrementAndGet()
        val requestedAt = SystemClock.elapsedRealtime()
        val queryInputs = rimeInputs
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= maxInputLength }
            .distinct()
            .take(if (mode == KeyboardMode.PINYIN_9) maxNineKeyPaths else 1)
            .toList()
        activeInputs = queryInputs

        val scheduled =
            composition.isNotBlank() &&
                composition.length <= maxInputLength &&
                (mode == KeyboardMode.PINYIN_26 || mode == KeyboardMode.PINYIN_9) &&
                queryInputs.isNotEmpty()

        if (!scheduled) {
            return CandidateQueryTicket(
                generation = request,
                inputs = queryInputs,
                scheduled = false,
            )
        }

        executor.execute {
            // Coalesce rapid key bursts before entering JNI.
            if (generation.get() != request) return@execute

            val query = queryNativeChoices(queryInputs) {
                generation.get() != request
            } ?: return@execute

            mainHandler.post {
                if (!isCurrent(request, queryInputs)) return@post
                onResult(
                    request,
                    queryInputs,
                    query.copy(
                        resultLatencyMs =
                            SystemClock.elapsedRealtime() - requestedAt,
                    ),
                )
            }
        }

        return CandidateQueryTicket(
            generation = request,
            inputs = queryInputs,
            scheduled = true,
        )
    }

    private fun isCurrent(request: Long, inputs: List<String>): Boolean =
        generation.get() == request && activeInputs == inputs

    private fun queryNativeChoices(
        inputs: List<String>,
        isCancelled: () -> Boolean,
    ): CandidateNativeQueryResult? {
        val startedAt = SystemClock.elapsedRealtime()
        val batches = ArrayList<Pair<String, List<RimeCandidateEntry>>>(inputs.size)
        for (input in inputs) {
            if (isCancelled()) return null
            batches += input to rime.candidateEntries(input)
        }
        if (isCancelled()) return null

        return CandidateNativeQueryResult(
            choices = NativeCandidatePipeline.mergeRoundRobin(
                batches = batches,
                limit = maxCandidates,
                fallbackCandidatesFor = fallbackCandidatesFor,
            ),
            latencyMs = SystemClock.elapsedRealtime() - startedAt,
        )
    }
}
