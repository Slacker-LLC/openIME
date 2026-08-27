package llc.slacker.openime

internal data class NativeCandidateReference(
    val input: String,
    val nativeIndex: Int,
)

internal data class NativeCandidateChoice(
    val text: String,
    val reference: NativeCandidateReference,
)

/** Pure merge policy shared by 26-key and ambiguous nine-key Rime queries. */
internal object NativeCandidatePipeline {

    /**
     * Interleave equal native ranks across Pinyin paths, then preserve path
     * score order. This prevents the first guessed nine-key path from filling
     * all 96 slots before another valid path gets a single candidate.
     */
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
