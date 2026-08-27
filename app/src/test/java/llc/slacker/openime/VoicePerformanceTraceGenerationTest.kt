package llc.slacker.openime

import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePerformanceTraceGenerationTest {

    @After
    fun tearDown() {
        VoicePerformanceTrace.resetForTest()
    }

    @Test
    fun staleFinishCannotEndNewerTrace() {
        val clock = AtomicLong(1_000L)
        val logs = mutableListOf<String>()
        VoicePerformanceTrace.resetForTest(
            clock = { clock.incrementAndGet() },
            logger = { logs.add(it) },
        )

        val sessionA = VoicePerformanceTrace.begin()
        val sessionB = VoicePerformanceTrace.begin()
        VoicePerformanceTrace.markFirstPartial(sessionA)
        VoicePerformanceTrace.markFirstPartial(sessionB)

        VoicePerformanceTrace.finish(sessionA, droppedPcmSamples = 7L, failed = true)

        assertFalse(VoicePerformanceTrace.isActiveForTest(sessionA))
        assertTrue(VoicePerformanceTrace.isActiveForTest(sessionB))

        // A's late callbacks are stale and must not mutate/remove B.
        VoicePerformanceTrace.markFirstDecode(sessionA)
        VoicePerformanceTrace.finish(sessionA, droppedPcmSamples = 999L, failed = true)
        assertTrue(VoicePerformanceTrace.isActiveForTest(sessionB))

        VoicePerformanceTrace.finish(sessionB, droppedPcmSamples = 0L, failed = true)
        assertFalse(VoicePerformanceTrace.isActiveForTest(sessionB))
        assertTrue(logs.any { it.contains("traceGeneration=${sessionA.generation}") })
        assertTrue(logs.any { it.contains("traceGeneration=${sessionB.generation}") })
    }

    @Test
    fun interleavedThreadsKeepTheirOwnGenerations() {
        val clock = AtomicLong(2_000L)
        val logs = Collections.synchronizedList(mutableListOf<String>())
        VoicePerformanceTrace.resetForTest(
            clock = { clock.incrementAndGet() },
            logger = { logs.add(it) },
        )

        val sessionA = VoicePerformanceTrace.begin()
        val sessionB = VoicePerformanceTrace.begin()

        val threadA = Thread {
            repeat(100) {
                VoicePerformanceTrace.markFirstPcm(sessionA)
                VoicePerformanceTrace.markFirstDecode(sessionA)
            }
            VoicePerformanceTrace.finish(sessionA, droppedPcmSamples = 11L, failed = true)
        }
        val threadB = Thread {
            repeat(100) {
                VoicePerformanceTrace.markFirstPcm(sessionB)
                VoicePerformanceTrace.markFirstPartial(sessionB)
            }
            VoicePerformanceTrace.finish(sessionB, droppedPcmSamples = 0L, failed = true)
        }

        threadA.start()
        threadB.start()
        threadA.join()
        threadB.join()

        assertTrue(VoicePerformanceTrace.activeGenerationsForTest().isEmpty())
        val joined = logs.joinToString("\n")
        assertTrue(joined.contains("traceGeneration=${sessionA.generation}"))
        assertTrue(joined.contains("droppedPcmSamples=11"))
        assertTrue(joined.contains("traceGeneration=${sessionB.generation}"))
        assertTrue(joined.contains("droppedPcmSamples=0"))
    }

    @Test
    fun abandoningOldSessionLeavesNewSessionActive() {
        val clock = AtomicLong(3_000L)
        VoicePerformanceTrace.resetForTest(
            clock = { clock.incrementAndGet() },
            logger = {},
        )

        val sessionA = VoicePerformanceTrace.begin()
        val sessionB = VoicePerformanceTrace.begin()
        VoicePerformanceTrace.abandon(sessionA)

        assertFalse(VoicePerformanceTrace.isActiveForTest(sessionA))
        assertTrue(VoicePerformanceTrace.isActiveForTest(sessionB))
    }
}
