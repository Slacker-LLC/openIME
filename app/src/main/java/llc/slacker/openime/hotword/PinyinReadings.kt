package llc.slacker.openime.hotword

/**
 * Toneless pinyin readings per Chinese character. Pure; built from the bundled
 * Rime single-character table (`rime-data/openime_dicts/8105.dict.yaml`, lines
 * of `character<TAB>pinyin<TAB>weight` after the `...` marker).
 *
 * A polyphonic character appears on several lines and so has several readings;
 * matching treats any of them as a possible pronunciation. Readings that carry
 * less than [MIN_RELATIVE_WEIGHT] of the character's heaviest reading are
 * dropped, otherwise one-off readings would make unrelated words "sound alike".
 */
internal class PinyinReadings private constructor(
    private val byCodePoint: Map<Int, List<String>>,
) {
    fun readings(codePoint: Int): List<String> = byCodePoint[codePoint].orEmpty()

    companion object {
        const val MIN_RELATIVE_WEIGHT = 0.01

        fun parseRimeDict(lines: Sequence<String>): PinyinReadings {
            val weights = HashMap<Int, MutableMap<String, Long>>()
            var inBody = false
            for (line in lines) {
                if (!inBody) {
                    inBody = line.trim() == "..."
                    continue
                }
                val fields = line.split('\t')
                if (fields.size < 2) continue
                val character = fields[0]
                if (character.codePointCount(0, character.length) != 1) continue
                val syllable = fields[1].trim()
                if (syllable.isEmpty() || ' ' in syllable) continue
                val weight = fields.getOrNull(2)?.trim()?.toLongOrNull() ?: 1L
                val perChar = weights.getOrPut(character.codePointAt(0)) { LinkedHashMap() }
                perChar.merge(syllable, weight, Long::plus)
            }
            return PinyinReadings(
                weights.mapValues { (_, bySyllable) ->
                    val heaviest = bySyllable.values.max().toDouble()
                    bySyllable.filterValues { it >= heaviest * MIN_RELATIVE_WEIGHT }.keys.toList()
                },
            )
        }

        fun of(vararg pairs: Pair<Char, String>): PinyinReadings {
            val map = HashMap<Int, MutableList<String>>()
            pairs.forEach { (char, syllable) ->
                map.getOrPut(char.code) { mutableListOf() } += syllable
            }
            return PinyinReadings(map)
        }
    }
}
