package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardRetentionPolicyTest {

    private val now = 1_000_000_000L

    @Test
    fun countTrimKeepsOldPinsAndNewestUnpinnedEntriesInOrder() {
        val entries = (30 downTo 1).map { ClipboardEntry("$it", now, it == 1 || it == 5) }
        val retained = ClipboardRetentionPolicy.trimToCount(entries, 24)

        assertEquals(entries.take(22) + entries.filter { it.pinned }, retained)
    }

    @Test
    fun countTrimNeverEvictsPinsEvenWhenTheyExceedLimit() {
        val pins = (1..25).map { ClipboardEntry("pin $it", now, true) }
        val entries = listOf(ClipboardEntry("new", now)) + pins

        assertEquals(pins, ClipboardRetentionPolicy.trimToCount(entries, 24))
    }

    @Test
    fun countTrimWithoutPinsKeepsNewestEntries() {
        val entries = (30 downTo 1).map { ClipboardEntry("$it", now) }

        assertEquals(entries.take(24), ClipboardRetentionPolicy.trimToCount(entries, 24))
        assertEquals(entries, ClipboardRetentionPolicy.trimToCount(entries, 40))
    }

    @Test
    fun unpinnedEntriesExpireAfterTwentyFourHours() {
        val fresh = ClipboardEntry("fresh", now - ClipboardRetentionPolicy.UNPINNED_TTL_MS, false)
        val expired = ClipboardEntry("expired", now - ClipboardRetentionPolicy.UNPINNED_TTL_MS - 1L, false)

        assertTrue(ClipboardRetentionPolicy.retain(fresh, now))
        assertFalse(ClipboardRetentionPolicy.retain(expired, now))
    }

    @Test
    fun pinnedEntriesIgnoreAgeAndLegacyMissingTimestamp() {
        val oldPinned = ClipboardEntry("pinned", 1L, true)
        val legacyPinned = ClipboardEntry("legacy", 0L, true)

        assertTrue(ClipboardRetentionPolicy.retain(oldPinned, now))
        assertTrue(ClipboardRetentionPolicy.retain(legacyPinned, now))
    }

    @Test
    fun legacyUnpinnedEntryWithoutTimestampIsPrunedPredictably() {
        assertFalse(ClipboardRetentionPolicy.retain(ClipboardEntry("legacy", 0L, false), now))
    }

    @Test
    fun clockRollbackDoesNotDeleteARecentlyWrittenEntry() {
        assertTrue(ClipboardRetentionPolicy.retain(ClipboardEntry("future", now + 1_000L, false), now))
    }

    @Test
    fun prunePreservesOrderOfRetainedItems() {
        val entries = listOf(
            ClipboardEntry("fresh", now - 1_000L, false),
            ClipboardEntry("expired", now - ClipboardRetentionPolicy.UNPINNED_TTL_MS - 1L, false),
            ClipboardEntry("pinned", 1L, true),
        )

        assertEquals(listOf("fresh", "pinned"), ClipboardRetentionPolicy.prune(entries, now).map { it.text })
    }
}
