package llc.slacker.openime.hotword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomophoneCorrectorTest {
    private val small = PinyinReadings.of(
        '打' to "da", '大' to "da", '野' to "ye", '爷' to "ye", '夜' to "ye",
        '补' to "bu", '刀' to "dao", '到' to "dao", '不' to "bu",
        '长' to "chang", '长' to "zhang", '场' to "chang", '张' to "zhang",
    )

    @Test
    fun replacesASameSoundingWindow() {
        val corrector = HomophoneCorrector(listOf("打野"), small)
        assertEquals("我是打野。", corrector.apply("我是大爷。"))
    }

    @Test
    fun keepsAWindowThatAlreadySpellsTheHotword() {
        val corrector = HomophoneCorrector(listOf("打野"), small)
        assertEquals("打野来了", corrector.apply("打野来了"))
    }

    @Test
    fun leavesDifferentSoundingTextAndTextWithoutReadingsAlone() {
        val corrector = HomophoneCorrector(listOf("打野"), small)
        assertEquals("补刀 ok", corrector.apply("补刀 ok"))
        assertEquals("你好", corrector.apply("你好"))
    }

    @Test
    fun matchesAnyReadingOfAPolyphonicCharacter() {
        val corrector = HomophoneCorrector(listOf("场到"), small)
        // 长 can be read chang, so 长到 sounds like 场到.
        assertEquals("场到", corrector.apply("长到"))
    }

    @Test
    fun correctsSeveralHotwordsInOneSentenceLeftToRight() {
        val corrector = HomophoneCorrector(listOf("打野", "补刀"), small)
        assertEquals("打野在补刀", corrector.apply("大夜在不到"))
    }

    @Test
    fun anEmptyWordListIsANoOp() {
        val corrector = HomophoneCorrector(emptyList(), small)
        assertTrue(corrector.isEmpty)
        assertEquals("大爷", corrector.apply("大爷"))
    }

    @Test
    fun bundledReadingsKnowCommonCharactersAndPolyphones() {
        val lines = sequenceOf(
            "src/main/assets/rime-data/openime_dicts/8105.dict.yaml",
            "app/src/main/assets/rime-data/openime_dicts/8105.dict.yaml",
        ).map(::File).first { it.isFile }.readLines(Charsets.UTF_8).asSequence()
        val readings = PinyinReadings.parseRimeDict(lines)
        assertTrue("da" in readings.readings('打'.code))
        assertTrue("ye" in readings.readings('野'.code))
        assertTrue(readings.readings('长'.code).containsAll(listOf("chang", "zhang")))
        assertTrue("one-off readings must be filtered", "heng" !in readings.readings('行'.code))
        assertTrue(readings.readings('行'.code).containsAll(listOf("hang", "xing")))
        val corrector = HomophoneCorrector(listOf("打野", "王者荣耀"), readings)
        assertEquals("他是打野", corrector.apply("他是大爷"))
        assertEquals("我在玩王者荣耀", corrector.apply("我在玩王者容耀"))
    }
}
