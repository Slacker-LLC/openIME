package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionControllerTest {

    private val controller = CompositionController(CandidateEngine())

    @Test
    fun pinyinAccumulatesAndProducesCandidates() {
        var s = controller.append("n")
        s = controller.append("i")
        assertTrue(s.composition == "ni")
        assertTrue(s.candidates.contains("你"))
        s = controller.append("h")
        s = controller.append("a")
        s = controller.append("o")
        assertEquals("nihao", s.composition)
        assertTrue(s.candidates.contains("你好"))
    }

    @Test
    fun backspaceRemovesLastLetter() {
        controller.append("n")
        val s = controller.backspace()
        assertEquals("", s.composition)
    }

    @Test
    fun selectCandidateClearsComposition() {
        controller.append("n")
        controller.append("i")
        val s = controller.selectCandidate("你")
        assertEquals("", s.composition)
        assertTrue(s.candidates.isEmpty())
    }

    @Test
    fun selectingFromLongCompositionClearsAllEditableState() {
        controller.replace("woxiangchifan")
        val s = controller.selectCandidate("我想吃饭")
        assertEquals("", s.composition)
        assertTrue(s.candidates.isEmpty())
    }

    @Test
    fun nineKeyDigitsResolve() {
        val s = controller.set9KeyNumber("64426")
        assertTrue(s.candidates.contains("你好"))
    }

    @Test
    fun t9EnglishResolves() {
        val s = controller.setT9("843")
        assertTrue(s.composition == "843")
    }

    @Test
    fun fuzzyEnabledExpands() {
        var s = controller.append("z", fuzzy = true)
        s = controller.append("i", fuzzy = true)
        assertTrue(s.candidates.any { it.contains("之") || it.contains("只") })
    }
}
