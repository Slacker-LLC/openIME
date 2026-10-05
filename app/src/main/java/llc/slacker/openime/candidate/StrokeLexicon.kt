package llc.slacker.openime.candidate

import android.content.Context

/**
 * The five strokes of the 笔画 keyboard, in the order mainstream stroke input
 * uses (and numbers 1–5): 横 竖 撇 点 折. [code] is the letter Rime's stroke
 * table uses for it.
 */
enum class Stroke(val code: Char, val glyph: String, val label: String, val digit: Char) {
    HENG('h', "一", "横", '1'),
    SHU('s', "丨", "竖", '2'),
    PIE('p', "丿", "撇", '3'),
    DIAN('n', "丶", "点", '4'),
    ZHE('z', "乛", "折", '5'),
    ;

    companion object {
        /** Shown in the composition for 通配: any one stroke. */
        const val WILDCARD_GLYPH = "＊"
        const val WILDCARD_CODE = '?'

        /**
         * The stroke code of a composition, or null when it holds anything but
         * strokes. Accepts the glyphs the keyboard types as well as the letters,
         * digits and `*`/`?` a user might type into the pre-edit field.
         */
        fun codeOf(composition: String): String? {
            if (composition.isEmpty()) return null
            val code = StringBuilder(composition.length)
            for (char in composition) {
                code.append(
                    when (char) {
                        '*', '?', '＊', '？' -> WILDCARD_CODE
                        else -> entries.firstOrNull {
                            it.glyph[0] == char || it.code == char || it.digit == char
                        }?.code ?: return null
                    },
                )
            }
            return code.toString()
        }
    }
}

/**
 * Characters by stroke order for the 笔画 keyboard.
 *
 * The table (stroke_table.tsv, generated at build time from Rime's stroke
 * dictionary) lists every character with its stroke code, most frequent first
 * by the 8105 table's weights, so common simplified characters lead and rare or
 * traditional ones still come up further down. A query returns the characters
 * whose strokes start with what was typed: those it spells exactly first (一
 * for 一), then the rest, each group in frequency order. 通配 (`?`) stands for
 * any one stroke.
 *
 * About 110,000 rows are kept as one packed code array instead of strings
 * (~2 MB rather than ~12 MB), and loaded only once the keyboard is first used.
 */
class StrokeLexicon internal constructor(rows: Sequence<Pair<String, String>>) {
    private val characters: Array<String>
    private val codes: ByteArray
    private val offsets: IntArray

    init {
        val characterList = ArrayList<String>(STROKE_TABLE_ROWS)
        val codeBytes = java.io.ByteArrayOutputStream(STROKE_TABLE_ROWS * 10)
        val offsetList = ArrayList<Int>(STROKE_TABLE_ROWS + 1)
        offsetList += 0
        for ((character, code) in rows) {
            if (character.isEmpty() || code.isEmpty() || code.any { it !in STROKE_CODES }) continue
            characterList += character
            code.forEach { codeBytes.write(it.code) }
            offsetList += codeBytes.size()
        }
        characters = characterList.toTypedArray()
        codes = codeBytes.toByteArray()
        offsets = offsetList.toIntArray()
    }

    val size: Int get() = characters.size

    /** Characters for [composition] (glyphs or codes, see [Stroke.codeOf]). */
    fun candidatesFor(composition: String, limit: Int = DEFAULT_LIMIT): List<String> {
        val pattern = Stroke.codeOf(composition) ?: return emptyList()
        val exact = LinkedHashSet<String>()
        val longer = LinkedHashSet<String>()
        for (row in characters.indices) {
            val start = offsets[row]
            val length = offsets[row + 1] - start
            if (length < pattern.length || !matches(start, pattern)) continue
            val character = characters[row]
            if (length == pattern.length) {
                exact += character
            } else if (longer.size < limit) {
                longer += character
            }
        }
        return (exact.asSequence() + longer.asSequence()).distinct().take(limit).toList()
    }

    private fun matches(start: Int, pattern: String): Boolean {
        for (index in pattern.indices) {
            val wanted = pattern[index]
            if (wanted != Stroke.WILDCARD_CODE && codes[start + index].toInt() != wanted.code) return false
        }
        return true
    }

    companion object {
        private const val ASSET = "stroke_table.tsv"
        private const val STROKE_TABLE_ROWS = 112_000
        private const val DEFAULT_LIMIT = 96
        private val STROKE_CODES = Stroke.entries.map { it.code }.toSet()

        @Volatile
        private var cached: StrokeLexicon? = null

        /** Loads the table once per process; later calls return the same instance. */
        fun load(context: Context): StrokeLexicon {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    StrokeLexicon(parse(lines))
                }.also { cached = it }
            }
        }

        /** The loaded table, or null before [load] has finished. */
        fun current(): StrokeLexicon? = cached

        internal fun parse(lines: Sequence<String>): Sequence<Pair<String, String>> =
            lines.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1).trim()
            }
    }
}
