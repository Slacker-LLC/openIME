package llc.slacker.openime

import llc.slacker.openime.candidate.Stroke
import llc.slacker.openime.candidate.StrokeLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeLexiconTest {
    // Most frequent first, as stroke_table.tsv is generated.
    private val lexicon = StrokeLexicon(
        StrokeLexicon.parse(
            sequenceOf(
                "的\tpszhhpzn",
                "一\th",
                "是\tszhhhshpn",
                "我\tphzhznp",
                "我\tphshzpn",
                "十\ths",
                "二\thh",
                "三\thhh",
                "千\tphs",
                "王\thhsh",
                "丰\thhhs",
                "bad line without a tab",
                "坏\thxs",
            ),
        ),
    )

    @Test
    fun compositionGlyphsLettersAndDigitsAllMapToStrokeCodes() {
        assertEquals("hspnz", Stroke.codeOf("一丨丿丶乛"))
        assertEquals("hspnz", Stroke.codeOf("hspnz"))
        assertEquals("hspnz", Stroke.codeOf("12345"))
        assertEquals("h?s", Stroke.codeOf("一＊丨"))
        assertEquals("h?s", Stroke.codeOf("h*s"))
        assertNull(Stroke.codeOf("ni"))
        assertNull(Stroke.codeOf(""))
    }

    @Test
    fun rowsWithUnknownStrokesAreDropped() {
        assertEquals(11, lexicon.size)
    }

    @Test
    fun anExactStrokeOrderComesBeforeLongerOnesThenFrequency() {
        // 一 is exactly 一; 十 二 三 王 丰 start with it, in table order.
        assertEquals(listOf("一", "十", "二", "三", "王", "丰"), lexicon.candidatesFor("一"))
        // 二 spells 一一 exactly, so it leads even though 三 王 丰 also match.
        assertEquals(listOf("二", "三", "王", "丰"), lexicon.candidatesFor("一一"))
    }

    @Test
    fun aCharacterWithTwoStrokeOrdersIsFoundByEitherAndListedOnce() {
        assertEquals(listOf("我"), lexicon.candidatesFor("丿一乛"))
        // 丿一丨 spells 千 exactly, so it leads; 我's second order only starts so.
        assertEquals(listOf("千", "我"), lexicon.candidatesFor("丿一丨"))
        assertEquals(listOf("我", "千"), lexicon.candidatesFor("丿一"))
    }

    @Test
    fun wildcardStandsForExactlyOneStroke() {
        // 一＊: 十 (hs) and 二 (hh) exactly, then 三 王 丰 by frequency.
        assertEquals(listOf("十", "二", "三", "王", "丰"), lexicon.candidatesFor("一＊"))
        // ＊丨: any first stroke, then 丨. 十 exactly; 的 (pszhhpzn) starts so.
        assertEquals(listOf("十", "的"), lexicon.candidatesFor("＊丨"))
        assertTrue(lexicon.candidatesFor("＊＊＊＊＊＊＊＊＊＊＊").isEmpty())
    }

    @Test
    fun anythingButStrokesHasNoCandidates() {
        assertTrue(lexicon.candidatesFor("wo").isEmpty())
        assertTrue(lexicon.candidatesFor("").isEmpty())
    }

    @Test
    fun theLimitCapsTheList() {
        assertEquals(listOf("一", "十"), lexicon.candidatesFor("一", limit = 2))
    }
}
