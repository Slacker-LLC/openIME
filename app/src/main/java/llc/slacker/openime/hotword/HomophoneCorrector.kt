package llc.slacker.openime.hotword

/**
 * Replaces recognized text with a hotword that sounds the same.
 *
 * The bundled streaming model cannot take hotwords into its decoder, so this
 * runs after recognition: a window of the output whose pinyin equals a hotword's
 * pinyin, but whose characters differ, is swapped for that hotword.
 *
 * Matching is leftmost-longest. A window that already spells a hotword is left
 * alone. A character without a known reading ends a candidate window. Pure and
 * Android-free; the instance is immutable once built, so it is safe to share
 * between threads.
 */
internal class HomophoneCorrector(
    words: Collection<String>,
    private val readings: PinyinReadings,
) {
    private val index: Map<String, List<String>>
    private val longest: Int

    init {
        val map = LinkedHashMap<String, MutableList<String>>()
        var max = 0
        for (word in words) {
            val codePoints = word.codePoints().toArray()
            if (codePoints.size !in MIN_LENGTH..HotwordParser.MAX_WORD_LENGTH) continue
            val keys = keysFor(codePoints, 0, codePoints.size) ?: continue
            for (key in keys) {
                val bucket = map.getOrPut(key) { mutableListOf() }
                if (word !in bucket) bucket += word
            }
            max = maxOf(max, codePoints.size)
        }
        index = map
        longest = max
    }

    val isEmpty: Boolean get() = index.isEmpty()

    fun apply(text: String): String {
        if (index.isEmpty() || text.length < MIN_LENGTH) return text
        val codePoints = text.codePoints().toArray()
        val out = StringBuilder(text.length)
        var changed = false
        var i = 0
        while (i < codePoints.size) {
            val match = matchAt(codePoints, i)
            if (match == null) {
                out.appendCodePoint(codePoints[i])
                i++
            } else {
                out.append(match.text)
                changed = changed || match.replaced
                i += match.length
            }
        }
        return if (changed) out.toString() else text
    }

    private class Match(val text: String, val length: Int, val replaced: Boolean)

    private fun matchAt(codePoints: IntArray, start: Int): Match? {
        val limit = minOf(longest, codePoints.size - start)
        for (length in limit downTo MIN_LENGTH) {
            val keys = keysFor(codePoints, start, length) ?: continue
            val window = String(codePoints, start, length)
            var firstCandidate: String? = null
            for (key in keys) {
                val bucket = index[key] ?: continue
                if (window in bucket) return Match(window, length, replaced = false)
                if (firstCandidate == null) firstCandidate = bucket.first()
            }
            if (firstCandidate != null) return Match(firstCandidate, length, replaced = true)
        }
        return null
    }

    /** Space-joined syllable strings the window could be read as; null if any character is unknown. */
    private fun keysFor(codePoints: IntArray, start: Int, length: Int): List<String>? {
        var keys = listOf("")
        for (offset in 0 until length) {
            val options = readings.readings(codePoints[start + offset])
            if (options.isEmpty()) return null
            val next = ArrayList<String>(minOf(keys.size * options.size, MAX_KEYS))
            loop@ for (prefix in keys) {
                for (option in options) {
                    next += if (prefix.isEmpty()) option else "$prefix $option"
                    if (next.size >= MAX_KEYS) break@loop
                }
            }
            keys = next
        }
        return keys
    }

    private companion object {
        const val MIN_LENGTH = 2
        /** Bounds the reading combinations of a window made of polyphonic characters. */
        const val MAX_KEYS = 16
    }
}
