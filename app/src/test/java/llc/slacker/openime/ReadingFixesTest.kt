package llc.slacker.openime

import llc.slacker.openime.candidate.PinyinLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The reference's misread-word table, reduced to the words its own reading
 * disagrees with ours on (see scripts/import_doubao_data.py).
 */
class ReadingFixesTest {
    private fun asset(relative: String): File = sequenceOf(
        File("src/main/assets/$relative"),
        File("app/src/main/assets/$relative"),
    ).first { it.isFile }

    private val fixes by lazy { PinyinLexicon.readingFixes(asset("pinyin_reading_fixes.tsv").readLines().asSequence()) }

    /** Every syllable the generated Pinyin table knows. */
    private val syllables: Set<String> by lazy {
        asset("pinyin_chars.tsv").readLines()
            .map { it.substringBefore('\t') }
            .filter { it.isNotEmpty() && it.all { ch -> ch in 'a'..'z' } }
            .toSet()
    }

    /** Split a code the way the decoder reads it: longest syllable first. */
    private fun split(code: String): List<String>? {
        val out = ArrayList<String>()
        var index = 0
        while (index < code.length) {
            val length = (6 downTo 1).firstOrNull { index + it <= code.length && code.substring(index, index + it) in syllables }
                ?: return null
            out += code.substring(index, index + length)
            index += length
        }
        return out
    }

    @Test
    fun everyRowIsOneSyllablePerCharacterOfAKnownWord() {
        assertTrue("读音修正表不该退化成空表", fixes.size >= 40)
        fixes.forEach { (pinyin, text, weight) ->
            val parts = split(pinyin)
            assertTrue("$pinyin 必须能切成音节", parts != null)
            assertEquals("$pinyin / $text 的音节数必须等于字数", text.length, parts!!.size)
            assertTrue("$text 必须是汉字", text.all { it in '一'..'鿿' })
            assertTrue("$pinyin / $text 的权重必须为正", weight > 0)
        }
        assertEquals(fixes.size, fixes.map { it.first to it.second }.distinct().size)
    }

    @Test
    fun theReferenceReadsTheseWordsDifferentlyFromTheTables() {
        // 岑参 is Cén Shēn, read cēn cān by the tables. Rows are pinyin / word / weight.
        val byWord = fixes.associateBy { it.second }
        assertEquals("censhen", byWord.getValue("岑参").first)
        // 五行八作 takes háng and zuō, 说客 takes shuō.
        assertEquals("wuhangbazuo", byWord.getValue("五行八作").first)
        assertEquals("shuoke", byWord.getValue("说客").first)
    }

    @Test
    fun aClosedVowelNeverComesBackAsAPlainU() {
        // 捋胡子 is lv hu zi. The generated rows strip tone marks, and a strip
        // that loses the diaeresis would emit the wrong reading lu hu zi here.
        assertTrue(fixes.none { it.second == "捋胡子" })
    }
}
