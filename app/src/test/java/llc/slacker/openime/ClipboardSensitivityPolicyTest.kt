package llc.slacker.openime

import android.view.inputmethod.EditorInfo
import llc.slacker.openime.data.ClipboardPrivacyPolicy
import llc.slacker.openime.data.ClipboardSensitivityPolicy
import llc.slacker.openime.editor.EditorInfoAdapter
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

    @Test
    fun passwordEditorsCanUsePersistentHistory() {
        assertTrue(
            ClipboardPrivacyPolicy.canUsePersistentHistory(
                EditorInfoAdapter.EditorKind.PASSWORD,
                imeOptions = 0,
            ),
        )
    }

    @Test
    fun noPersonalizedLearningEditorsCannotExposePersistentHistory() {
        assertFalse(
            ClipboardPrivacyPolicy.canUsePersistentHistory(
                EditorInfoAdapter.EditorKind.TEXT,
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ),
        )
    }

    @Test
    fun ordinaryTextEditorsCanUsePersistentHistory() {
        assertTrue(
            ClipboardPrivacyPolicy.canUsePersistentHistory(
                EditorInfoAdapter.EditorKind.TEXT,
                imeOptions = 0,
            ),
        )
    }
}
