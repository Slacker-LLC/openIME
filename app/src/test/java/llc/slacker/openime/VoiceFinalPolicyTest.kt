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
    fun passwordFieldNeverReceivesVoiceText() {
        assertEquals(
            VoiceFinalPlan(false, false, false),
            VoiceFinalPolicy.resolve(
                passwordField = true,
                hadPartialComposition = false,
                autoCommit = true,
                finalText = "不应写入",
            ),
        )
    }
}
