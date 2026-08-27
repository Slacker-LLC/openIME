package llc.slacker.openime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionUpdatePolicyTest {

    @Test
    fun composingCallbackAtEndDoesNotClear() {
        assertFalse(
            shouldClearCompositionForSelectionUpdate(
                hasComposition = true,
                oldSelStart = 10,
                oldSelEnd = 10,
                newSelStart = 12,
                newSelEnd = 12,
                candidatesStart = 10,
                candidatesEnd = 12,
            ),
        )
    }

    @Test
    fun movingInsideComposingRangeDoesNotClear() {
        assertFalse(
            shouldClearCompositionForSelectionUpdate(
                hasComposition = true,
                oldSelStart = 12,
                oldSelEnd = 12,
                newSelStart = 11,
                newSelEnd = 11,
                candidatesStart = 10,
                candidatesEnd = 12,
            ),
        )
    }

    @Test
    fun clickOutsideComposingRangeClears() {
        assertTrue(
            shouldClearCompositionForSelectionUpdate(
                hasComposition = true,
                oldSelStart = 12,
                oldSelEnd = 12,
                newSelStart = 30,
                newSelEnd = 30,
                candidatesStart = 10,
                candidatesEnd = 12,
            ),
        )
    }

    @Test
    fun externalSelectionRangeClears() {
        assertTrue(
            shouldClearCompositionForSelectionUpdate(
                hasComposition = true,
                oldSelStart = 12,
                oldSelEnd = 12,
                newSelStart = 4,
                newSelEnd = 8,
                candidatesStart = 10,
                candidatesEnd = 12,
            ),
        )
    }

    @Test
    fun changedSelectionWithoutReportedComposingRangeClears() {
        assertTrue(
            shouldClearCompositionForSelectionUpdate(
                hasComposition = true,
                oldSelStart = 12,
                oldSelEnd = 12,
                newSelStart = 20,
                newSelEnd = 20,
                candidatesStart = -1,
                candidatesEnd = -1,
            ),
        )
    }

    @Test
    fun noActiveCompositionNeverClears() {
        assertFalse(
            shouldClearCompositionForSelectionUpdate(
                hasComposition = false,
                oldSelStart = 12,
                oldSelEnd = 12,
                newSelStart = 20,
                newSelEnd = 20,
                candidatesStart = -1,
                candidatesEnd = -1,
            ),
        )
    }

    @Test
    fun duplicateSelectionCallbackDoesNotClear() {
        assertFalse(
            shouldClearCompositionForSelectionUpdate(
                hasComposition = true,
                oldSelStart = 12,
                oldSelEnd = 12,
                newSelStart = 12,
                newSelEnd = 12,
                candidatesStart = -1,
                candidatesEnd = -1,
            ),
        )
    }
}
