package llc.slacker.openime

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test

class ImeBottomInsetPolicyTest {

    @Test
    fun zeroInsetPreservesExistingHeight() {
        assertEquals(
            296,
            ImeBottomInsetPolicy.measuredHeight(296, 0, View.MeasureSpec.UNSPECIFIED, 0),
        )
    }

    @Test
    fun navigationInsetAddsSafeAreaWithoutShrinkingKeyboardBody() {
        assertEquals(
            320,
            ImeBottomInsetPolicy.measuredHeight(296, 24, View.MeasureSpec.UNSPECIFIED, 0),
        )
    }

    @Test
    fun parentConstraintStillCapsTheIme() {
        assertEquals(
            304,
            ImeBottomInsetPolicy.measuredHeight(296, 24, View.MeasureSpec.AT_MOST, 304),
        )
        assertEquals(
            300,
            ImeBottomInsetPolicy.measuredHeight(296, 24, View.MeasureSpec.EXACTLY, 300),
        )
    }

    @Test
    fun reportedInsetIsBoundedAndNeverNegative() {
        assertEquals(0, ImeBottomInsetPolicy.clampInset(-5, 32))
        assertEquals(18, ImeBottomInsetPolicy.clampInset(18, 32))
        assertEquals(32, ImeBottomInsetPolicy.clampInset(70, 32))
    }
}
