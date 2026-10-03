package llc.slacker.openime

import java.io.File
import llc.slacker.openime.candidate.PinyinLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinLexiconCharWeightTest {
    private val dictionary: File = sequenceOf(
        "src/main/assets/rime-data/openime_dicts/8105.dict.yaml",
        "app/src/main/assets/rime-data/openime_dicts/8105.dict.yaml",
    ).map(::File).first { it.isFile }

    @Test
    fun parsesRowsAfterTheHeaderOnly() {
        val rows = PinyinLexicon.charWeights(
            sequenceOf("# comment", "你\tni\t1", "---", "...", "你\tni\t1422192", "伱\tni\t12", "ab\tni\t5", "尼\tni\tx", "好"),
        )
        assertEquals(listOf(Triple("ni", "你", 1422192), Triple("ni", "伱", 12)), rows)
    }

    @Test
    fun theMostCommonCharacterOutranksRareVariantsForASyllable() {
        val rows = PinyinLexicon.charWeights(dictionary.readLines(Charsets.UTF_8).asSequence())
        fun top(pinyin: String) = rows.filter { it.first == pinyin }.maxByOrNull { it.third }!!.second
        assertEquals("你", top("ni"))
        assertTrue("rare variant must rank far below", rows.none { it.first == "ni" && it.second == "伱" && it.third > 1000 })
    }
}
