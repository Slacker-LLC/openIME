package llc.slacker.openime

import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic high-frequency ownership races; no microphone or device is required. */
class VoiceLifecycleOwnershipStressTest {

    @After
    fun tearDown() {
        VoicePerformanceTrace.resetForTest()
    }

    @Test
    fun tenThousandStartCancelStartTraceCyclesNeverCrossGenerations() {
        val clock = AtomicLong(10_000L)
        VoicePerformanceTrace.resetForTest(
            clock = { clock.incrementAndGet() },
            logger = {},
        )

        repeat(10_000) {
            val stale = VoicePerformanceTrace.begin()
            val current = VoicePerformanceTrace.begin()

            VoicePerformanceTrace.markFirstPcm(stale)
            VoicePerformanceTrace.markFirstPcm(current)
            VoicePerformanceTrace.abandon(stale)
            VoicePerformanceTrace.finish(stale, droppedPcmSamples = 99L, failed = true)

            assertFalse(VoicePerformanceTrace.isActiveForTest(stale))
            assertTrue(VoicePerformanceTrace.isActiveForTest(current))

            VoicePerformanceTrace.finish(current, droppedPcmSamples = 0L, failed = true)
            assertFalse(VoicePerformanceTrace.isActiveForTest(current))
        }

        assertTrue(VoicePerformanceTrace.activeGenerationsForTest().isEmpty())
    }

    @Test
    fun tenThousandOverlappingRouteCyclesRestoreOnlyLatestOwner() {
        val ownership = VoiceRouteOwnership<Int>()
        var baselineVersion = 0
        var restoredCount = 0

        repeat(10_000) {
            val first = ownership.acquire { ++baselineVersion }
            val latest = ownership.acquire { error("overlap must reuse the first baseline") }

            assertTrue(first.firstOwner)
            assertFalse(latest.firstOwner)
            assertFalse(ownership.release(first.owner) { restoredCount++ })
            assertTrue(ownership.release(latest.owner) { restoredCount++ })
        }

        assertEquals(10_000, baselineVersion)
        assertEquals(10_000, restoredCount)
    }

    @Test
    fun pcmRingRemainsBoundedAcrossTenThousandCaptureChunks() {
        val chunk = ShortArray(LocalVoiceAudioSpec.CHUNK_SAMPLES) { it.toShort() }
        val ring = PcmRingBuffer(LocalVoiceAudioSpec.CHUNK_SAMPLES * 8)

        repeat(10_000) {
            ring.offer(chunk)
            if (it % 3 == 0) ring.drain(LocalVoiceAudioSpec.CHUNK_SAMPLES)
            assertTrue(ring.size <= LocalVoiceAudioSpec.CHUNK_SAMPLES * 8)
        }

        assertTrue(ring.droppedSamples > 0L)
        ring.clear()
        assertEquals(0, ring.size)
        assertEquals(0L, ring.droppedSamples)
    }
}
