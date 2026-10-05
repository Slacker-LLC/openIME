package llc.slacker.openime

import llc.slacker.openime.rime.RimeStartupGate
import llc.slacker.openime.rime.rimeDataRevision
import llc.slacker.openime.rime.rimeProbeHasCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RimeStartupGateTest {
    @Test
    fun onlyOneStartupCanBeInFlight() {
        val gate = RimeStartupGate()
        val first = gate.begin()

        assertNotNull(first)
        assertNull(gate.begin())
        assertTrue(gate.isCurrent(first!!))
    }

    @Test
    fun failedAttemptCanRetry() {
        val gate = RimeStartupGate()
        val first = gate.begin()!!

        assertTrue(gate.fail(first))
        assertFalse(gate.isCurrent(first))
        assertNotNull(gate.begin())
    }

    @Test
    fun destroyInvalidatesStaleWorkerAndBlocksRestart() {
        val gate = RimeStartupGate()
        val first = gate.begin()!!

        gate.destroy()

        assertFalse(gate.isCurrent(first))
        assertFalse(gate.complete(first))
        assertFalse(gate.fail(first))
        assertNull(gate.begin())
    }

    @Test
    fun healthProbeRequiresCandidateBeyondInputAndPreedit() {
        assertFalse(rimeProbeHasCandidate(emptyArray()))
        assertFalse(rimeProbeHasCandidate(arrayOf("ni", "ni")))
        assertFalse(rimeProbeHasCandidate(arrayOf("ni", "ni", "", "  ")))
        assertTrue(rimeProbeHasCandidate(arrayOf("ni", "ni", "你")))
    }

    @Test
    fun rimeDataRevisionTracksApkVersionCode() {
        assertEquals("apk-1", rimeDataRevision(null, 1))
        assertEquals("apk-42", rimeDataRevision(null, 42))
        assertEquals("apk-42", rimeDataRevision("not a hash", 42))
    }

    @Test
    fun rimeDataRevisionPrefersTheBundledContentHash() {
        val hash = "0123456789abcdef".repeat(4)
        // Same data in a newer APK: the copy on the phone stays valid.
        assertEquals("data-$hash", rimeDataRevision("$hash\n", 501))
        assertEquals("data-$hash", rimeDataRevision(hash, 601))
    }
}
