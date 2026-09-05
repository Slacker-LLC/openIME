package llc.slacker.openime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationPolicyTest {
    @Test
    fun ordinaryTextAllowsPersonalization() {
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        assertTrue(PersonalizationPolicy.allow(info))
    }

    @Test
    fun explicitNoPersonalizedLearningFlagBlocksRecording() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        assertFalse(PersonalizationPolicy.allow(info))
    }

    @Test
    fun passwordsAndMissingEditorInfoBlockRecording() {
        val password = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        assertFalse(PersonalizationPolicy.allow(password))
        assertFalse(PersonalizationPolicy.allow(null))
    }
}
