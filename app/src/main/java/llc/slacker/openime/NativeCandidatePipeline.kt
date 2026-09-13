package llc.slacker.openime

internal data class NativeCandidateReference(
    val input: String,
    val nativeIndex: Int,
) {
    companion object {
        private const val DEFERRED_PREFIX = "\u0000openime-deferred\u0000"
        private const val SEP = '\u0000'
        const val DEFERRED_INDEX = -1

        /**
         * Reference used before the async native snapshot exists. The editor can
         * commit immediately; RimeEngine later resolves [candidate] by text on
         * its mutation queue and learns the exact visible choice.
         */
        fun deferred(input: String, candidate: String): NativeCandidateReference =
            NativeCandidateReference(
                input = DEFERRED_PREFIX + input + SEP + candidate,
                nativeIndex = DEFERRED_INDEX,
            )

        fun decodeDeferred(encoded: String): Pair<String, String>? {
            if (!encoded.startsWith(DEFERRED_PREFIX)) return null
            val payload = encoded.removePrefix(DEFERRED_PREFIX)
            val split = payload.indexOf(SEP)
            if (split <= 0 || split >= payload.lastIndex) return null
            return payload.substring(0, split) to payload.substring(split + 1)
        }
    }
}

internal data class NativeCandidateChoice(
    val text: String,
    val reference: NativeCandidateReference,
)

/** Pure merge policy for one or more native Rime query batches. */
internal object NativeCandidatePipeline {
    fun mergeRoundRobin(
        batches: List<Pair<String, List<RimeCandidateEntry>>>,
        limit: Int = 96,
    ): List<NativeCandidateChoice> {
        if (limit <= 0 || batches.isEmpty()) return emptyList()
        val result = ArrayList<NativeCandidateChoice>(limit)
        val seen = HashSet<String>()
        val largestBatch = batches.maxOfOrNull { it.second.size } ?: 0
        for (rank in 0 until largestBatch) {
            for ((input, entries) in batches) {
                val entry = entries.getOrNull(rank) ?: continue
                if (!seen.add(entry.text)) continue
                result += NativeCandidateChoice(
                    text = entry.text,
                    reference = NativeCandidateReference(input, entry.nativeIndex),
                )
                if (result.size >= limit) return result
            }
        }
        return result
    }
}
