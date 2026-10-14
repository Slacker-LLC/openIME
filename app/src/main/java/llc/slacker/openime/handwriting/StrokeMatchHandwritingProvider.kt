package llc.slacker.openime.handwriting

import android.content.Context

/**
 * Stroke-sequence handwriting lookup (笔画识别).
 *
 * The pad hands over raw strokes. Every stroke is classified as one of the
 * five basic strokes (h 横, s 竖, p 撇, n 捺, z 折), the resulting code is
 * looked up in a stroke table (code -> characters) and the characters become
 * candidates: exact matches first, then characters whose longer code
 * continues the drawn sequence. This is deliberately a table matcher, not a
 * learned recognizer; the whole feature stays offline and deterministic.
 *
 * The table ships only in local builds (handwriting-strokes.tsv); without it
 * [forContext] returns [UnavailableHandwritingProvider] and the pad keeps
 * showing its "not configured" message instead of fake candidates.
 */
internal class StrokeMatchHandwritingProvider internal constructor(
    private val exact: Map<String, String>,
    private val sortedCodes: List<String>,
) : HandwritingProvider {

    override val isAvailable: Boolean get() = exact.isNotEmpty()

    override fun recognize(strokes: List<Stroke>): HandwritingResult {
        if (strokes.isEmpty()) return HandwritingResult.Success(emptyList())
        val code = buildString {
            for (stroke in strokes) {
                HandwritingStrokeClassifier.classify(stroke.points)?.let { append(it) }
            }
        }
        if (code.isEmpty()) return HandwritingResult.Success(emptyList())
        val candidates = LinkedHashSet<String>()
        collect(code, candidates)
        if (candidates.isEmpty() && code.length > 1) {
            // One misread stroke should not kill the whole match: retry
            // without the tail before giving up.
            collect(code.dropLast(1), candidates)
        }
        return HandwritingResult.Success(candidates.take(MAX_CANDIDATES).toList())
    }

    private fun collect(code: String, into: MutableSet<String>) {
        exact[code]?.let { chars -> for (char in chars) into.add(char.toString()) }
        var index = lowerBound(code)
        var codesVisited = 0
        while (index < sortedCodes.size && into.size < MAX_CANDIDATES && codesVisited < MAX_PREFIX_CODES) {
            val candidate = sortedCodes[index]
            if (!candidate.startsWith(code)) break
            if (candidate.length > code.length) {
                val chars = exact[candidate]
                if (chars != null) {
                    for (char in chars) {
                        into.add(char.toString())
                        if (into.size >= MAX_CANDIDATES) break
                    }
                }
                codesVisited++
            }
            index++
        }
    }

    private fun lowerBound(code: String): Int {
        var low = 0
        var high = sortedCodes.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (sortedCodes[middle] < code) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        const val ASSET = "handwriting-strokes.tsv"
        private const val MAX_CANDIDATES = 36
        private const val MAX_PREFIX_CODES = 24

        @Volatile
        private var cached: HandwritingProvider? = null

        /** Cheap presence check for entry visibility; does not parse the table. */
        fun assetPresent(context: Context): Boolean =
            runCatching { context.assets.open(ASSET).close() }.isSuccess

        /** One shared instance per process; parsing happens on first use. */
        fun forContext(context: Context): HandwritingProvider {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: load(context).also { cached = it }
            }
        }

        private fun load(context: Context): HandwritingProvider = runCatching {
            val exact = HashMap<String, String>()
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isEmpty() || line[0] == '#') return@forEach
                    val parts = line.split('\t', limit = 2)
                    if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                        exact[parts[0]] = parts[1]
                    }
                }
            }
            if (exact.isEmpty()) {
                UnavailableHandwritingProvider
            } else {
                StrokeMatchHandwritingProvider(exact, exact.keys.sorted())
            }
        }.getOrElse { UnavailableHandwritingProvider }
    }
}
