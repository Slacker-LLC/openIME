package llc.slacker.openime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityTest {

    @Test
    fun selectedImeRequiresAnExactPackageComponent() {
        assertTrue(
            matchesSelectedInputMethod(
                "llc.slacker.openime/llc.slacker.openime.LocalVoiceImeService",
                "llc.slacker.openime",
            ),
        )
        assertFalse(
            matchesSelectedInputMethod(
                "llc.slacker.openime.beta/llc.slacker.openime.LocalVoiceImeService",
                "llc.slacker.openime",
            ),
        )
        assertFalse(matchesSelectedInputMethod("llc.slacker.openime", "llc.slacker.openime"))
    }
}
