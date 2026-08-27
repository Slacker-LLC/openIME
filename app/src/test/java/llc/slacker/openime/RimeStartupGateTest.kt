package llc.slacker.openime

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
}
