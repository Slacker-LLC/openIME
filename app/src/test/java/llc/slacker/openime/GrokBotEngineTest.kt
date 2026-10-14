package llc.slacker.openime

import com.lollipop.grokbot.GrokBotConfig
import com.lollipop.grokbot.GrokMood
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendored character is a port, so the engine behind it is checked here on
 * the JVM: it is pure Kotlin, and it has to produce a usable frame for every
 * mood the voice panel asks for.
 *
 * The renderer is not part of this test; it needs `android.graphics`, which a
 * unit test of this project has no implementation of.
 */
class GrokBotEngineTest {
    private val number = Regex("""-?\d+(\.\d+)?([eE][-+]?\d+)?""")

    @Test
    fun everyVoiceMoodPaintsAFiniteFrame() {
        val state = com.lollipop.grokbot.GrokBotState(GrokBotConfig())
        // The moods the voice panel drives, in the order a session uses them.
        val moods = listOf(
            GrokMood.IDLE, GrokMood.LOADING, GrokMood.LISTENING, GrokMood.THINKING,
            GrokMood.CELEBRATE, GrokMood.CONFUSED, GrokMood.SURPRISED, GrokMood.SHY,
            GrokMood.ALERTING,
        )
        var now = 0f
        var sawEyes = false
        moods.forEach { mood ->
            state.show(mood)
            // Two seconds of frames, which covers the entry springs of a mood.
            repeat(120) {
                now += 16f
                state.step(now)
            }
            val frame = state.engine.frame
            val body = frame.bodyPath
            assertTrue("$mood paints no body", body.isNotEmpty())
            assertTrue("$mood has an empty view box", frame.viewBox.size > 0f)
            assertFinite("$mood body", body)
            assertFinite("$mood overlay", frame.overlay.elements.joinToString(" ") { it.path.orEmpty() })
            // A blink or another overlay hides the eyes for a moment, so their
            // absence is only checked across the whole run of moods.
            sawEyes = sawEyes || frame.eyeFrame.visible.any { it }
            frame.eyeFrame.polys.forEachIndexed { eye, poly ->
                assertTrue("$mood leaves eye $eye empty", poly.isNotEmpty())
                assertFinite("$mood eye $eye", poly.joinToString(" "))
            }
        }
        assertTrue("the eyes never became visible", sawEyes)
    }

    @Test
    fun aListeningFaceLeavesTheStartupMoodBehind() {
        val state = com.lollipop.grokbot.GrokBotState(GrokBotConfig())
        state.step(16f)
        state.show(GrokMood.LISTENING)
        repeat(240) { state.step(16f * (it + 2)) }
        assertTrue(state.mood == GrokMood.LISTENING)
    }

    private fun assertFinite(what: String, text: String) {
        val broken = number.findAll(text)
            .map { it.value.toFloat() }
            .filter { !it.isFinite() }
            .toList()
        assertFalse("$what carries $broken", broken.isNotEmpty())
    }
}
