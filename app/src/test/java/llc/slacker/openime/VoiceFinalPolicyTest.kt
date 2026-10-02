package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceFinalPolicyTest {
    @Test
    fun finalOnlyCallbackSetsAndCommitsText() {
        assertEquals(
            VoiceFinalPlan(
                setFinalText = true,
                finishComposing = true,
                composingAfter = false,
            ),
            VoiceFinalPolicy.resolve(
                passwordField = false,
                hadPartialComposition = false,
                autoCommit = true,
                finalText = "这是只有终态的语音结果",
            ),
        )
    }

    @Test
    fun blankFinalCommitsExistingPartialWhenAutoCommitIsEnabled() {
        assertEquals(
            VoiceFinalPlan(
                setFinalText = false,
                finishComposing = true,
                composingAfter = false,
            ),
            VoiceFinalPolicy.resolve(
                passwordField = false,
                hadPartialComposition = true,
                autoCommit = true,
                finalText = "",
            ),
        )
    }

    @Test
    fun passwordFieldGetsTheFinalTextAsOneDirectCommit() {
        assertEquals(
            VoiceFinalPlan(
                setFinalText = false,
                finishComposing = false,
                composingAfter = false,
                commitDirect = true,
            ),
            VoiceFinalPolicy.resolve(
                passwordField = true,
                hadPartialComposition = false,
                autoCommit = true,
                finalText = "直接上屏",
            ),
        )
    }

    @Test
    fun passwordFieldWithBlankResultCommitsNothing() {
        assertEquals(
            VoiceFinalPlan(false, false, false, commitDirect = false),
            VoiceFinalPolicy.resolve(
                passwordField = true,
                hadPartialComposition = false,
                autoCommit = true,
                finalText = "  ",
            ),
        )
    }
}
