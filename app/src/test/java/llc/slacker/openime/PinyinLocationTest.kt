package llc.slacker.openime

import llc.slacker.openime.core.PinyinLocation
import org.junit.Assert.assertEquals
import org.junit.Test

class PinyinLocationTest {
    @Test
    fun missingOrUnknownValuesFallBackToTheCandidateBar() {
        listOf(null, "", "inputbox", "候选栏", "INPUT_BOX ").forEach { stored ->
            assertEquals(PinyinLocation.CANDIDATE_BAR, PinyinLocation.fromKey(stored))
        }
    }

    @Test
    fun bothStoredValuesRestore() {
        assertEquals(PinyinLocation.CANDIDATE_BAR, PinyinLocation.fromKey("candidate_bar"))
        assertEquals(PinyinLocation.INPUT_BOX, PinyinLocation.fromKey("input_box"))
    }

    /** 两个标签就是参考皮肤设置项里的那两句。 */
    @Test
    fun labelsKeepTheReferenceCopy() {
        assertEquals("显示在候选栏", PinyinLocation.CANDIDATE_BAR.label)
        assertEquals("显示在输入框", PinyinLocation.INPUT_BOX.label)
    }
}
