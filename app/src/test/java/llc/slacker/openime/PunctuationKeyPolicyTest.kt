package llc.slacker.openime

import llc.slacker.openime.keyboard.PunctuationKeyPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PunctuationKeyPolicyTest {
    @Test
    fun chineseModeUsesFullWidthCommaAndPeriod() {
        val spec = PunctuationKeyPolicy.spec(english = false)
        assertEquals("，", spec.merged)
        assertEquals("。", spec.mergedCorner)
        assertEquals("，", spec.comma)
        assertEquals("。", spec.period)
    }

    @Test
    fun englishMergedKeyLeadsWithThePeriod() {
        // The reference draws 句号逗号 with the period as the main label and the
        // comma as the corner mark (input_kbd_english26.xml:154-161).
        val spec = PunctuationKeyPolicy.spec(english = true)
        assertEquals(".", spec.merged)
        assertEquals(",", spec.mergedCorner)
        assertEquals(",", spec.comma)
        assertEquals(".", spec.period)
    }

    @Test
    fun anAddressFieldTakesTheUriAdapterMarks() {
        val chinese = PunctuationKeyPolicy.spec(english = false, uriField = true)
        assertEquals("/", chinese.comma)
        assertEquals(".", chinese.period)
        // The merged key carries no uri_adapter_symbol, so it keeps its marks.
        assertEquals("，", chinese.merged)
        assertEquals("。", chinese.mergedCorner)

        val english = PunctuationKeyPolicy.spec(english = true, uriField = true)
        assertEquals("/", english.comma)
        assertEquals(".", english.period)
        assertEquals(".", english.merged)
        assertEquals(",", english.mergedCorner)
    }
}
