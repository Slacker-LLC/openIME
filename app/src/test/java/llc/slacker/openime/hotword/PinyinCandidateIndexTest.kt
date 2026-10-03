package llc.slacker.openime.hotword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PinyinCandidateIndexTest {
    private val readings = PinyinReadings.of('打' to "da", '野' to "ye", '大' to "da", '爷' to "ye")
    private val index = PinyinCandidateIndex(listOf("打野"), readings)

    @Test
    fun offersAHotwordForItsExactFullPinyin() {
        assertEquals(listOf("打野"), index.exact("daye"))
        assertEquals(listOf("打野"), index.exact("DaYe"))
        assertEquals(listOf("打野"), index.exact("da'ye"))
    }

    @Test
    fun doesNotOfferPartialOrUnrelatedInput() {
        assertTrue(index.exact("day").isEmpty())
        assertTrue(index.exact("dayex").isEmpty())
        assertTrue(index.exact("dy").isEmpty())
        assertTrue(index.exact("").isEmpty())
    }

    @Test
    fun boostKeepsTheTopCandidateAndPlacesHotwordsRightBehindIt() {
        assertEquals(
            listOf("大爷", "打野", "大爷们", "达也"),
            index.boost("daye", listOf("大爷", "大爷们", "达也", "打野")),
        )
    }

    @Test
    fun boostIsANoOpWhenNothingMatchesOrTheHotwordIsAlreadyFirst() {
        val list = listOf("你好", "尼好")
        assertEquals(list, index.boost("nihao", list))
        assertEquals(listOf("打野", "大爷"), index.boost("daye", listOf("打野", "大爷")))
    }

    @Test
    fun aHotwordMissingFromTheDictionaryStillAppears() {
        assertEquals(listOf("打野"), index.boost("daye", emptyList()))
        assertEquals(listOf("大爷", "打野"), index.boost("daye", listOf("大爷")))
    }

    @Test
    fun worksWithTheBundledReadings() {
        val lines = sequenceOf(
            "src/main/assets/rime-data/openime_dicts/8105.dict.yaml",
            "app/src/main/assets/rime-data/openime_dicts/8105.dict.yaml",
        ).map(::File).first { it.isFile }.readLines(Charsets.UTF_8).asSequence()
        val real = PinyinCandidateIndex(listOf("王者荣耀", "蛋仔派对"), PinyinReadings.parseRimeDict(lines))
        assertEquals(listOf("王者荣耀"), real.exact("wangzherongyao"))
        assertEquals(listOf("蛋仔派对"), real.exact("danzaipaidui"))
    }
}
