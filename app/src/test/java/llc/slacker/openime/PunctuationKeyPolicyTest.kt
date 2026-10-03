package llc.slacker.openime

import llc.slacker.openime.keyboard.PunctuationKeyPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PunctuationKeyPolicyTest {
    @Test
    fun chineseModeUsesFullWidthCommaAndPeriod() {
        val spec = PunctuationKeyPolicy.spec(english = false)
        assertEquals("，", spec.comma)
        assertEquals("。", spec.period)
    }

    @Test
    fun englishModeUsesAsciiCommaAndPeriod() {
        val spec = PunctuationKeyPolicy.spec(english = true)
        assertEquals(",", spec.comma)
        assertEquals(".", spec.period)
    }
}
