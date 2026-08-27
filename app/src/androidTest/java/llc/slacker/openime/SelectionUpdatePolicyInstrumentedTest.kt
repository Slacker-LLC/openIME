package llc.slacker.openime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectionUpdatePolicyInstrumentedTest {

    @Test
    fun externalCursorMoveInvalidatesActiveComposition() {
        assertTrue(
            shouldClearCompositionForSelectionUpdate(
                hasComposition = true,
                oldSelStart = 7,
                oldSelEnd = 7,
                newSelStart = 20,
                newSelEnd = 20,
                candidatesStart = 5,
                candidatesEnd = 7,
            ),
        )
    }

    @Test
    fun imeComposingSelectionUpdateIsKept() {
        assertFalse(
            shouldClearCompositionForSelectionUpdate(
                hasComposition = true,
                oldSelStart = 5,
                oldSelEnd = 5,
                newSelStart = 7,
                newSelEnd = 7,
                candidatesStart = 5,
                candidatesEnd = 7,
            ),
        )
    }
}
