package llc.slacker.openime

import llc.slacker.openime.voice.StreamingModelKind
import llc.slacker.openime.voice.VadSpeechTracker
import llc.slacker.openime.voice.VoiceEndpointPolicy
import llc.slacker.openime.voice.decodingFor
import llc.slacker.openime.voice.hotwordModelingUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDecodingPolicyTest {
    @Test
    fun transducerAlwaysUsesModifiedBeamSearch() {
        assertEquals(
            "modified_beam_search",
            decodingFor(StreamingModelKind.TRANSDUCER, hasHotwords = false),
        )
        assertEquals(
            "modified_beam_search",
            decodingFor(StreamingModelKind.TRANSDUCER, hasHotwords = true),
        )
    }

    @Test
    fun otherKindsKeepGreedySearchEvenWithHotwords() {
        val others = StreamingModelKind.entries.filter { it != StreamingModelKind.TRANSDUCER }
        for (kind in others) {
            assertEquals(kind.id, "greedy_search", decodingFor(kind, hasHotwords = false))
            assertEquals(kind.id, "greedy_search", decodingFor(kind, hasHotwords = true))
        }
    }

    @Test
    fun hotwordModelingUnitAdmitsOnlyUnitsTheNativeEncoderSupports() {
        // 原生 EncodeHotwords 遇到不支持的 modelingUnit 会直接结束进程，必须先准入。
        assertEquals("cjkchar", hotwordModelingUnit("", hasBpeVocab = false))
        assertEquals("cjkchar", hotwordModelingUnit("cjkchar", hasBpeVocab = false))
        assertEquals("bpe", hotwordModelingUnit("bpe", hasBpeVocab = true))
        assertEquals("cjkchar+bpe", hotwordModelingUnit("cjkchar+bpe", hasBpeVocab = true))
        assertNull(hotwordModelingUnit("bpe", hasBpeVocab = false))
        assertNull(hotwordModelingUnit("cjkchar+bpe", hasBpeVocab = false))
        assertNull(hotwordModelingUnit("whisper", hasBpeVocab = true))
    }

    @Test
    fun noVadKeepsTheOriginalTailBehavior() {
        assertTrue(VoiceEndpointPolicy.tailPaddingNeeded(vadActive = false, sawSpeech = false))
        assertTrue(VoiceEndpointPolicy.tailPaddingNeeded(vadActive = false, sawSpeech = true))
        assertTrue(VadSpeechTracker().tailPaddingNeeded())
    }

    @Test
    fun vadSkipsTailPaddingOnlyWhenNoSpeechWasEverSeen() {
        assertFalse(VoiceEndpointPolicy.tailPaddingNeeded(vadActive = true, sawSpeech = false))
        assertTrue(VoiceEndpointPolicy.tailPaddingNeeded(vadActive = true, sawSpeech = true))
    }

    @Test
    fun trackerFollowsSpeechSilenceAndFailure() {
        val tracker = VadSpeechTracker()
        tracker.observe(false)
        assertTrue(tracker.isVadActive)
        assertFalse(tracker.hasSpeech)
        assertFalse(tracker.tailPaddingNeeded())

        repeat(3) { tracker.observe(true) }
        assertTrue(tracker.hasSpeech)
        assertTrue(tracker.tailPaddingNeeded())
        assertFalse(tracker.speechEnded())

        repeat(VadSpeechTracker.DEFAULT_MIN_SILENCE_CHUNKS) { tracker.observe(false) }
        assertTrue(tracker.speechEnded())

        // VAD 中途失败：立刻退回原有端点行为。
        tracker.observe(null)
        assertFalse(tracker.isVadActive)
        assertFalse(tracker.speechEnded())
        assertTrue(tracker.tailPaddingNeeded())
    }

    @Test
    fun silenceBeforeAnySpeechIsNotAnEndpoint() {
        val tracker = VadSpeechTracker()
        repeat(100) { tracker.observe(false) }
        assertFalse(tracker.hasSpeech)
        assertFalse(tracker.speechEnded())
    }
}
