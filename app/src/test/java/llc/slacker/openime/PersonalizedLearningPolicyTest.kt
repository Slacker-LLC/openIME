package llc.slacker.openime

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizedLearningPolicyTest {
    @Test fun ordinaryEditorAllowsLearning() {
        assertTrue(personalizedLearningAllowed(false, EditorInfo.IME_ACTION_DONE))
    }

    @Test fun privateFlagSurvivesOtherImeOptions() {
        assertFalse(personalizedLearningAllowed(false,
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_SEND or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI))
    }

    @Test fun passwordsAndMissingEditorsNeverLearn() {
        assertFalse(personalizedLearningAllowed(true, 0))
        assertFalse(personalizedLearningAllowed(false, null))
    }

    @Test fun switchingOutOfPrivateEditorRestoresLearning() {
        assertFalse(personalizedLearningAllowed(false, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertTrue(personalizedLearningAllowed(false, 0))
    }
}
