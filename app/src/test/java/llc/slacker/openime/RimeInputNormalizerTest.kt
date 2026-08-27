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
}
