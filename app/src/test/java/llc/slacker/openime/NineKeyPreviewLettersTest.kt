package llc.slacker.openime

import llc.slacker.openime.candidate.CandidateEngine
import llc.slacker.openime.candidate.CandidatePipeline
import llc.slacker.openime.candidate.NineKeyLocalDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The nine-key pre-edit shows letters, never digits: beta6 left "669" or a
 * whole digit string in it whenever no word read every digit.
 */
class NineKeyPreviewLettersTest {
    private val engine = CandidateEngine()
    private val pipeline = CandidatePipeline(engine)
    private val decoder = NineKeyLocalDecoder(engine)

    private fun assertLettersFor(digits: String, preview: String) {
        assertTrue("digits in the pre-edit for $digits: '$preview'", preview.none { it.isDigit() })
        val letters = preview.filter { it in 'a'..'z' }
        assertEquals("one letter per digit for $digits: '$preview'", digits.length, letters.length)
        assertEquals("letters must type the same digits: '$preview'", digits, NineKeyLocalDecoder.digitsForPinyin(letters))
    }

    @Test
    fun everyDigitStringIsSpelledWithLettersOnly() {
        val random = Random(20261005)
        repeat(3000) {
            val length = random.nextInt(1, 33)
            val digits = (1..length).map { "23456789"[random.nextInt(8)] }.joinToString("")
            assertLettersFor(digits, decoder.spellDigits(digits).joinToString(""))
        }
    }

    @Test
    fun thePreviewNeverFallsBackToDigits() {
        val random = Random(7)
        val samples = listOf("644264658846649669", "669", "4", "5", "7", "8", "9", "6442646", "999999", "7777", "88888888") +
            (1..400).map { (1..random.nextInt(1, 25)).map { "23456789"[random.nextInt(8)] }.joinToString("") }
        samples.forEach { digits ->
            val preview = pipeline.resolveNineKey(digits, "", null, fuzzy = false).preview
            assertLettersFor(digits, preview)
        }
    }

    @Test
    fun anUnfinishedLastSyllableShowsItsStart() {
        // 46 starts gong / gou but is no syllable: its letters, not "46".
        assertEquals(listOf("go"), decoder.spellDigits("46"))
        // Whole syllables where they fit.
        assertEquals(listOf("ni", "hao"), decoder.spellDigits("64426"))
    }

    @Test
    fun aWordSpellingOnlyTheStartAlignsItsPartAndSpellsTheRest() {
        // 你好工 for 6442646: the last character typed only up to "go".
        assertEquals(listOf("ni", "hao", "go"), decoder.readingForPrefix("6442646", "你好工"))
        // 你好 covers 64426; the remaining 46 are spelled, not left as digits.
        val rest = decoder.readingForPrefix("6442646", "你好")!!
        assertEquals(listOf("ni", "hao"), rest.take(2))
        assertLettersFor("6442646", rest.joinToString(""))
        // A word that does not fit the digits at all is no guide.
        assertEquals(null, decoder.readingForPrefix("6442646", "我们"))
    }

    @Test
    fun aWordSpellingEveryDigitStillUsesItsReading() {
        assertEquals(listOf("ni", "hao"), decoder.readingForPrefix("64426", "你好"))
        assertEquals(listOf("zhong", "guo"), decoder.readingForPrefix("94664486", "中国"))
    }
}
