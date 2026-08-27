package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioRouteManagerTest {

    @Test
    fun staleOwnerCannotRestoreAfterNewSessionTakesOwnership() {
        val ownership = VoiceRouteOwnership<String>()
        var prepareCount = 0
        val restored = mutableListOf<String>()

        val first = ownership.acquire {
            prepareCount += 1
            "original-route"
        }
        val second = ownership.acquire {
            prepareCount += 1
            "must-not-replace-baseline"
        }

        assertTrue(first.firstOwner)
        assertFalse(second.firstOwner)
        assertEquals(1, prepareCount)
        assertFalse(ownership.release(first.owner, restored::add))
        assertTrue(restored.isEmpty())

        assertTrue(ownership.release(second.owner, restored::add))
        assertEquals(listOf("original-route"), restored)
    }

    @Test
    fun nextIndependentSessionCapturesANewBaselineAfterRestore() {
        val ownership = VoiceRouteOwnership<String>()
        val restored = mutableListOf<String>()

        val first = ownership.acquire { "route-a" }
        assertTrue(ownership.release(first.owner, restored::add))

        val second = ownership.acquire { "route-b" }
        assertTrue(second.firstOwner)
        assertTrue(ownership.release(second.owner, restored::add))

        assertEquals(listOf("route-a", "route-b"), restored)
    }

    @Test
    fun alreadyReleasedOwnerCannotRestoreTwice() {
        val ownership = VoiceRouteOwnership<String>()
        val restored = mutableListOf<String>()
        val lease = ownership.acquire { "route" }

        assertTrue(ownership.release(lease.owner, restored::add))
        assertFalse(ownership.release(lease.owner, restored::add))
        assertEquals(listOf("route"), restored)
    }
}
