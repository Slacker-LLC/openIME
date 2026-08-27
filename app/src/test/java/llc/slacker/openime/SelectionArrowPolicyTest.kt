package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionArrowPolicyTest {
    @Test
    fun leftArrowCollapsesSelectionAtLeftEdge() {
        assertEquals(
            2 to 2,
            collapseSelectionForAdjacentArrow(
                currentStart = 2,
                currentEnd = 5,
                requestedStart = 1,
                requestedEnd = 1,
            ),
        )
    }

    @Test
    fun rightArrowCollapsesSelectionAtRightEdge() {
        assertEquals(
            5 to 5,
            collapseSelectionForAdjacentArrow(
                currentStart = 2,
                currentEnd = 5,
                requestedStart = 6,
                requestedEnd = 6,
            ),
        )
    }

    @Test
    fun collapsedCursorStillMovesNormally() {
        assertEquals(
            2 to 2,
            collapseSelectionForAdjacentArrow(
                currentStart = 3,
                currentEnd = 3,
                requestedStart = 2,
                requestedEnd = 2,
            ),
        )
    }

    @Test
    fun explicitSelectionRequestIsNotRewritten() {
        assertEquals(
            1 to 4,
            collapseSelectionForAdjacentArrow(
                currentStart = 2,
                currentEnd = 5,
                requestedStart = 1,
                requestedEnd = 4,
            ),
        )
    }
}
