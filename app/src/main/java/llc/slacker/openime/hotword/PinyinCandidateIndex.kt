package llc.slacker.openime.hotword

/**
 * Lets hotwords show up while typing pinyin. Pure and Android-free.
 *
 * A word is offered when the typed pinyin is exactly its full pinyin, so
 * `daye` can offer 打野 even if the dictionary ranks it low or lacks it.
 * [boost] only reorders: the top candidate keeps its place and at most
 * [MAX_BOOSTED] hotwords follow it, so everyday typing is not rearranged.
 */
internal class PinyinCandidateIndex(
    words: Collection<String>,
    readings: PinyinReadings,
) {
    private val byPinyin: Map<String, List<String>>

    init {
        val map = LinkedHashMap<String, MutableList<String>>()
        for (word in words) {
            val codePoints = word.codePoints().toArray()
            if (codePoints.size !in 2..HotwordParser.MAX_WORD_LENGTH) continue
            for (key in joinedKeys(codePoints, readings)) {
                val bucket = map.getOrPut(key) { mutableListOf() }
                if (word !in bucket) bucket += word
            }
        }
        byPinyin = map
    }

    val isEmpty: Boolean get() = byPinyin.isEmpty()

    /** Hotwords whose full pinyin is exactly [composition]. */
    fun exact(composition: String): List<String> {
        val key = composition.lowercase().filter { it in 'a'..'z' }
        if (key.length < MIN_COMPOSITION || key.length != composition.count { it != '\'' && it != ' ' }) {
            return emptyList()
        }
        return byPinyin[key].orEmpty()
    }

    fun boost(composition: String, candidates: List<String>): List<String> {
        val hot = exact(composition).take(MAX_BOOSTED)
        if (hot.isEmpty()) return candidates
        val head = candidates.firstOrNull()
        return buildList {
            head?.let(::add)
            hot.filter { it != head }.forEach(::add)
            candidates.drop(1).filter { it !in hot }.forEach(::add)
        }
    }

    private fun joinedKeys(codePoints: IntArray, readings: PinyinReadings): List<String> {
        var keys = listOf("")
        for (codePoint in codePoints) {
            val options = readings.readings(codePoint)
            if (options.isEmpty()) return emptyList()
            val next = ArrayList<String>(minOf(keys.size * options.size, MAX_KEYS))
            loop@ for (prefix in keys) {
                for (option in options) {
                    next += prefix + option
                    if (next.size >= MAX_KEYS) break@loop
                }
            }
            keys = next
        }
        return keys
    }

    private companion object {
        const val MIN_COMPOSITION = 3
        const val MAX_BOOSTED = 3
        const val MAX_KEYS = 16
    }
}
