package llc.slacker.openime

import llc.slacker.openime.text.HanWordSegmenter
import llc.slacker.openime.text.HanToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HanWordSegmenterTest {

    private fun segment(text: String, vararg words: String): List<HanToken> {
        val known = words.toSet()
        return HanWordSegmenter.segment(text) { it in known }
    }

    /** Whatever the split, the pieces read the text again, offsets and all. */
    private fun assertReadsBack(text: String, tokens: List<HanToken>) {
        assertEquals("the tokens must spell the text", text, tokens.joinToString("") { it.text })
        tokens.forEach { token ->
            assertEquals(text.substring(token.start, token.end), token.text)
        }
        assertEquals(0, tokens.first().start)
        assertEquals(text.length, tokens.last().end)
    }

    @Test
    fun theLongestWordWinsAtEachPosition() {
        val tokens = segment("今天天气不错", "今天", "天气", "不错", "天气不错")
        assertEquals(listOf("今天", "天气不错"), tokens.map { it.text })
        assertTrue(tokens.all { it.selectable })
        assertReadsBack("今天天气不错", tokens)
    }

    @Test
    fun aWordIsNotStretchedPastTheTables() {
        // 「今天」 is known and 「今天天气」 is not: the split keeps the word and
        // reads the rest on its own rather than swallowing it.
        val tokens = segment("今天天气", "今天", "天气")
        assertEquals(listOf("今天", "天气"), tokens.map { it.text })
    }

    @Test
    fun aCharacterNoWordCarriesIsShownButNotSelectable() {
        val tokens = segment("今a", "今天")
        assertEquals(listOf("今", "a"), tokens.map { it.text })
        assertFalse("今 is not a word of the tables here", tokens.first().selectable)
        assertTrue("a letter run is worth picking", tokens.last().selectable)
        assertReadsBack("今a", tokens)
    }

    @Test
    fun lettersDigitsAndSeparatorsEachFormOneToken() {
        val tokens = segment("iPhone 15，开发 4G", "开发")
        assertEquals(listOf("iPhone", " ", "15", "，", "开发", " ", "4G"), tokens.map { it.text })
        assertEquals(
            listOf(true, false, true, false, true, false, true),
            tokens.map { it.selectable },
        )
        assertReadsBack("iPhone 15，开发 4G", tokens)
    }

    @Test
    fun theLongestMatchIsCappedAtTheTablesOwnLongestWord() {
        // 依法追究刑事责任 is the longest row of pinyin_phrases.tsv at 8 characters.
        val eight = "依法追究刑事责任"
        val tokens = segment(eight, eight)
        assertEquals(listOf(eight), tokens.map { it.text })
        assertTrue(tokens.single().selectable)
        // A ninth character starts a token of its own rather than extending it.
        val nine = eight + "的"
        val split = segment(nine, eight, "的")
        assertEquals(listOf(eight, "的"), split.map { it.text })
        assertReadsBack(nine, split)
    }

    @Test
    fun anEmptyTextSegmentsToNothing() {
        assertEquals(emptyList<HanToken>(), segment("", "今天"))
    }
}
