package llc.slacker.openime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAudioVoiceBackendTest {
    @Test
    fun pcmSpecIsExactlyTwentyMilliseconds() {
        assertEquals(320, LocalVoiceAudioSpec.CHUNK_SAMPLES)
        assertEquals(640, LocalVoiceAudioSpec.CHUNK_BYTES)
    }

    @Test
    fun ringBufferKeepsNewestSamplesWhenInferenceFallsBehind() {
        val ring = PcmRingBuffer(5)
        ring.offer(shortArrayOf(1, 2, 3, 4))
        ring.offer(shortArrayOf(5, 6, 7))

        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), ring.drain(10))
        assertEquals(2L, ring.droppedSamples)
    }

    @Test
    fun pcm16ConversionUsesNormalizedFloatRange() {
        val result = pcm16ToFloat(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE))
        assertEquals(-1f, result[0], 0.0001f)
        assertEquals(0f, result[1], 0.0001f)
        assertTrue(result[2] > 0.99f && result[2] <= 1f)
    }

    @Test
    fun closingOldStreamLeaseCannotReleaseNewStream() {
        val released = mutableListOf<String>()
        val old = VoiceStreamLease("stream-a", released::add)
        val current = VoiceStreamLease("stream-b", released::add)

        old.close()
        old.close()

        assertEquals(listOf("stream-a"), released)
        assertTrue(current.isClosed.not())
        assertEquals("stream-b", current.value)

        current.close()
        assertEquals(listOf("stream-a", "stream-b"), released)
    }
}
