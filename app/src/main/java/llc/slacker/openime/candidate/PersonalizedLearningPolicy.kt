package llc.slacker.openime.candidate

import android.view.inputmethod.EditorInfo

internal fun personalizedLearningAllowed(passwordField: Boolean, imeOptions: Int?): Boolean =
    !passwordField && imeOptions != null &&
        imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
