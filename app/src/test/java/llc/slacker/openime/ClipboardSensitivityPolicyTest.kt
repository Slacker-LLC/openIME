package llc.slacker.openime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSensitivityPolicyTest {
    @Test
    fun sensitiveCompatibilityFlagBlocksPersistence() {
        assertTrue(
            ClipboardSensitivityPolicy.isSensitive { key ->
                key == "android.content.extra.IS_SENSITIVE"
            },
        )
    }

    @Test
    fun absentOrFalseFlagAllowsNormalClipboardHistory() {
        assertFalse(ClipboardSensitivityPolicy.isSensitive { false })
        assertFalse(
            ClipboardSensitivityPolicy.isSensitive { key ->
                key == "some.other.flag"
            },
        )
    }

    @Test
    fun malformedExtrasReaderFailsClosedToNormalBehavior() {
        assertFalse(
            ClipboardSensitivityPolicy.isSensitive {
                error("bad extras")
            },
        )
    }
}
