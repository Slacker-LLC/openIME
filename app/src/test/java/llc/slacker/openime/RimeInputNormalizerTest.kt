package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Test

class RimeInputNormalizerTest {

    @Test
    fun editableBoundariesBecomeRimeDelimiters() {
        assertEquals("xi'an", RimeInputNormalizer.normalize("xi an"))
        assertEquals("xi'an", RimeInputNormalizer.normalize("xi|an"))
        assertEquals("xi'an", RimeInputNormalizer.normalize("  XI  | an  "))
    }

    @Test
    fun continuousAndInitialPinyinStayUnchanged() {
        assertEquals("woxiangchifan", RimeInputNormalizer.normalize("woxiangchifan"))
        assertEquals("nh", RimeInputNormalizer.normalize("NH"))
    }

    @Test
    fun nineKeyDigitsAndExplicitBoundariesStayNative() {
        assertEquals("64426", RimeInputNormalizer.normalize("64426"))
        assertEquals("64'426", RimeInputNormalizer.normalize("64'426"))
        assertEquals("94'26'426", RimeInputNormalizer.normalize(" 94 | 26  426 "))
    }
}
