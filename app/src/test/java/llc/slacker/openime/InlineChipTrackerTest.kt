package llc.slacker.openime

import llc.slacker.openime.keyboard.InlineChipTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineChipTrackerTest {
    private val published = mutableListOf<List<String>>()
    private val tracker = InlineChipTracker<String>(maxChips = 3) { published += it }

    private fun last() = published.lastOrNull().orEmpty()

    @Test
    fun chipsKeepProviderOrderWhateverOrderTheyInflateIn() {
        val token = tracker.begin(3)
        tracker.deliver(token, 2, "third")
        assertEquals(listOf("third"), last())
        tracker.deliver(token, 0, "first")
        assertEquals(listOf("first", "third"), last())
        tracker.deliver(token, 1, "second")
        assertEquals(listOf("first", "second", "third"), last())
    }

    @Test
    fun aChipFromAnOlderResponseIsDropped() {
        val old = tracker.begin(2)
        val current = tracker.begin(1)
        tracker.deliver(old, 0, "stale")
        assertTrue(last().isEmpty())
        tracker.deliver(current, 0, "fresh")
        assertEquals(listOf("fresh"), last())
    }

    @Test
    fun clearDropsPendingChipsAndPublishesNothing() {
        val token = tracker.begin(2)
        tracker.deliver(token, 0, "a")
        tracker.clear()
        assertTrue(last().isEmpty())
        tracker.deliver(token, 1, "late")
        assertTrue(last().isEmpty())
    }

    @Test
    fun anEmptyResponseClearsTheStrip() {
        val token = tracker.begin(2)
        tracker.deliver(token, 0, "a")
        tracker.begin(0)
        assertTrue(last().isEmpty())
    }

    @Test
    fun suggestionsBeyondTheLimitAreIgnored() {
        val token = tracker.begin(10)
        tracker.deliver(token, 5, "ignored")
        assertTrue(last().isEmpty())
        (0..2).forEach { tracker.deliver(token, it, "chip$it") }
        assertEquals(listOf("chip0", "chip1", "chip2"), last())
    }

    @Test
    fun aChipThatCouldNotBeBuiltLeavesAGapNotACrash() {
        val token = tracker.begin(3)
        tracker.deliver(token, 0, "a")
        tracker.deliver(token, 1, null)
        tracker.deliver(token, 2, "c")
        assertEquals(listOf("a", "c"), last())
    }

    @Test
    fun onlyNonEmptyResponsesAreAccepted() {
        assertTrue(tracker.accepts(1))
        assertFalse(tracker.accepts(0))
        assertFalse(InlineChipTracker<String>(maxChips = 0) {}.accepts(1))
    }
}
