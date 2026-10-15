package llc.slacker.openime

import llc.slacker.openime.candidate.PoemIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoemIndexTest {

    private val index = PoemIndex.parse(
        sequenceOf(
            "# Lines of the reference's poetry dictionary.",
            "chun mian bu jue xiao\t春眠不觉晓\t处处闻啼鸟",
            "chun feng bu xiang shi\t春风不相识\t",
            "luo ri gu ren qing\t落日故人情\t挥手自兹去",
            "hai shang sheng ming yue\t海上生明月\t天涯共此时",
        ),
    )

    /** 春眠不觉晓 spelled out: the whole line, then the line that follows it. */
    @Test
    fun aLineSpelledOutOffersItsOwnNextLine() {
        assertEquals(
            listOf("春眠不觉晓", "处处闻啼鸟"),
            index.suggestionsFor("chunmianbujuexiao"),
        )
    }

    /** A half-typed line offers the line itself; only its own follow needs the whole line. */
    @Test
    fun threeSyllablesAreEnoughToOfferTheLine() {
        assertEquals(listOf("春眠不觉晓"), index.suggestionsFor("chunmianbu"))
        assertEquals(listOf("春风不相识"), index.suggestionsFor("chunfengbu"))
        assertEquals(listOf("海上生明月"), index.suggestionsFor("haishangsheng"))
    }

    /** `春风` is every day's typing; two syllables would offer a line all day. */
    @Test
    fun twoSyllablesAreNotARecommendation() {
        assertTrue(index.suggestionsFor("chunfeng").isEmpty())
        assertTrue(index.suggestionsFor("chunmian").isEmpty())
    }

    @Test
    fun aHalfTypedSyllableMatchesNothing() {
        assertTrue(index.suggestionsFor("chunmi").isEmpty())
        assertTrue(index.suggestionsFor("chunmianbuj").isEmpty())
    }

    @Test
    fun caseAndApostrophesAreIgnored() {
        assertEquals(
            listOf("春眠不觉晓", "处处闻啼鸟"),
            index.suggestionsFor("Chun'mian'bu'jue'xiao"),
        )
        assertEquals(listOf("春风不相识"), index.suggestionsFor("CHUNFENGBU"))
    }

    @Test
    fun theMostSpecificLineComesFirst() {
        // 落日故人情 is offered by its own three syllables too, but the line the
        // user spelled out whole ranks first.
        assertEquals(
            listOf("落日故人情", "挥手自兹去"),
            index.suggestionsFor("luorigurenqing"),
        )
    }

    @Test
    fun anEmptyOrUnrelatedCompositionOffersNothing() {
        assertTrue(index.suggestionsFor("").isEmpty())
        assertTrue(index.suggestionsFor("   ").isEmpty())
        assertTrue(index.suggestionsFor("nihao").isEmpty())
        assertTrue(PoemIndex.EMPTY.suggestionsFor("chunmianbujuexiao").isEmpty())
    }
}
