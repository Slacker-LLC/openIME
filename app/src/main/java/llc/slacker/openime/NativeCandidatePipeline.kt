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

/**
 * Small LRU bridge between the immediate Kotlin nine-key frame and the later
 * native Rime frame. It is keyed by the authoritative T9 code, so a stale
 * candidate list for another composition can never be merged accidentally.
 */
internal object NineKeyFallbackRegistry {
    private const val MAX_CODES = 32
    private const val MAX_PER_CODE = 96
    private val lock = Any()
    private val byCode = object : LinkedHashMap<String, List<String>>(MAX_CODES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean =
            size > MAX_CODES
    }

    fun remember(code: String?, candidates: List<String>) {
        val key = code?.takeIf(::isNineKeyCode) ?: return
        val clean = candidates
            .asSequence()
            .filter { it.isNotBlank() && it.none(Char::isDigit) }
            .distinct()
            .take(MAX_PER_CODE)
            .toList()
        synchronized(lock) {
            if (clean.isEmpty()) byCode.remove(key) else byCode[key] = clean
        }
    }

    fun candidatesFor(code: String): List<String> = synchronized(lock) {
        byCode[code].orEmpty()
    }

    private fun isNineKeyCode(value: String): Boolean =
        value.isNotEmpty() && value.all { it in '2'..'9' || it == '\'' }
}

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

        // If librime returned nothing, leave the result empty. The service then
        // follows its established offline path, including UserPhraseRepository.
        // Only a real native refresh is allowed to keep the immediate fallback
        // tail so an unavailable Rime session is never misclassified as native.
        if (result.isEmpty()) return result

        // A non-empty native callback used to replace the entire immediate list.
        // Keep Rime's authoritative ordering first, but retain useful first-frame
        // choices that native did not return on its current page. Deferred
        // references make those choices learnable if tapped after the refresh.
        for ((input, _) in batches) {
            for (candidate in NineKeyFallbackRegistry.candidatesFor(input)) {
                if (!seen.add(candidate)) continue
                result += NativeCandidateChoice(
                    text = candidate,
                    reference = NativeCandidateReference.deferred(input, candidate),
                )
                if (result.size >= limit) return result
            }
        }
        return result
    }
}
