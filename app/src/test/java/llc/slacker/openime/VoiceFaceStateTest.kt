package llc.slacker.openime

import llc.slacker.openime.voice.VoiceFaceState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The voice panel's character is its status display, so every step of a voice
 * session has to be recognisable on it: two states that show the same mood would
 * be two states the user cannot tell apart.
 */
class VoiceFaceStateTest {
    @Test
    fun everyStateShowsItsOwnMood() {
        val byMood = VoiceFaceState.entries.groupBy { it.mood }
        val shared = byMood.filterValues { it.size > 1 }
            .map { (mood, states) -> "$mood: ${states.joinToString()}" }
        assertEquals("states that show the same mood: $shared", emptyList<String>(), shared)
    }
}
