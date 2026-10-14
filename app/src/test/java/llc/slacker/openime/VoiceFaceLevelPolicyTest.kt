package llc.slacker.openime

import llc.slacker.openime.voice.VoiceFaceLevelPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The character of the voice panel hops once per syllable it hears. These are the
 * rules that turn the level stream of the recognizer into those hops.
 */
class VoiceFaceLevelPolicyTest {
    @Test
    fun silenceNeverOpensASyllable() {
        val policy = VoiceFaceLevelPolicy()
        assertFalse(policy.onLevel(0f, 0L))
        assertFalse(policy.onLevel(0.02f, 32L))
        assertFalse(policy.onLevel(VoiceFaceLevelPolicy.FLOOR - 0.001f, 64L))
    }

    @Test
    fun speechFromSilenceOpensASyllable() {
        val policy = VoiceFaceLevelPolicy()
        assertFalse(policy.onLevel(0.01f, 0L))
        assertTrue(policy.onLevel(0.12f, 32L))
    }

    @Test
    fun aHeldVowelDoesNotHopAgain() {
        val policy = VoiceFaceLevelPolicy()
        assertTrue(policy.onLevel(0.12f, 0L))
        // A steady level is the same syllable, however long it is held.
        var now = 400L
        repeat(5) {
            assertFalse(policy.onLevel(0.12f, now))
            now += 400L
        }
    }

    @Test
    fun twoSyllablesHopTwice() {
        val policy = VoiceFaceLevelPolicy()
        assertTrue(policy.onLevel(0.12f, 0L))
        assertFalse(policy.onLevel(0.03f, 400L))
        assertTrue(policy.onLevel(0.14f, 800L))
    }

    @Test
    fun onsetsCloserThanTheIntervalAreDropped() {
        val policy = VoiceFaceLevelPolicy()
        assertTrue(policy.onLevel(0.12f, 0L))
        assertFalse(policy.onLevel(0.02f, 100L))
        // A second syllable 150 ms after the first is speech, but hopping that
        // fast would turn the character into a strobe.
        assertFalse(policy.onLevel(0.15f, 150L))
        policy.onLevel(0.02f, 200L)
        assertTrue(policy.onLevel(0.14f, VoiceFaceLevelPolicy.INTERVAL_MS))
    }

    @Test
    fun resetMakesTheNextLevelAnOnsetAgain() {
        val policy = VoiceFaceLevelPolicy()
        assertTrue(policy.onLevel(0.12f, 0L))
        policy.reset()
        assertTrue(policy.onLevel(0.12f, 100L))
    }

    @Test
    fun aLevelAboveOneIsClamped() {
        val policy = VoiceFaceLevelPolicy()
        assertTrue(policy.onLevel(2f, 0L))
        policy.reset()
        policy.onLevel(1f, 1_000L)
        // Clamped to 1, so the following full scale chunk is not a rise over it.
        assertFalse(policy.onLevel(4f, 2_000L))
    }
}
