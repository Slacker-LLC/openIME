package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelLifecycleRetryPolicyTest {

    @Test
    fun transientFailuresBackOffButBecomeRetryable() {
        val policy = VoicePreloadRetryPolicy(baseBackoffMs = 1_000L, maxBackoffMs = 4_000L)

        assertTrue(policy.canAttempt(0L))
        policy.recordFailure(permanent = false, nowMs = 100L)
        assertFalse(policy.canAttempt(1_099L))
        assertTrue(policy.canAttempt(1_100L))

        policy.recordFailure(permanent = false, nowMs = 1_100L)
        assertEquals(2_000L, policy.retryDelayMs(1_100L))
        assertFalse(policy.canAttempt(3_099L))
        assertTrue(policy.canAttempt(3_100L))

        policy.recordFailure(permanent = false, nowMs = 3_100L)
        assertEquals(4_000L, policy.retryDelayMs(3_100L))
        assertTrue(policy.canAttempt(7_100L))
    }

    @Test
    fun permanentFailureBlocksFurtherAttempts() {
        val policy = VoicePreloadRetryPolicy()

        policy.recordFailure(permanent = true, nowMs = 500L)

        assertFalse(policy.canAttempt(Long.MAX_VALUE - 1L))
        assertEquals(Long.MAX_VALUE, policy.retryDelayMs(500L))
    }

    @Test
    fun successClearsPreviousTransientFailureState() {
        val policy = VoicePreloadRetryPolicy()
        policy.recordFailure(permanent = false, nowMs = 100L)
        assertFalse(policy.canAttempt(500L))

        policy.recordSuccess()

        assertTrue(policy.canAttempt(500L))
        assertEquals(0L, policy.retryDelayMs(500L))
    }
}
