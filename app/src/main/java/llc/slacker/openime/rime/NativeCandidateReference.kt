package llc.slacker.openime.rime

internal data class NativeCandidateReference(
    val input: String,
    val nativeIndex: Int,
    /** Normalized-input characters the candidate spells; -1 when unknown. */
    val consumed: Int = -1,
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
