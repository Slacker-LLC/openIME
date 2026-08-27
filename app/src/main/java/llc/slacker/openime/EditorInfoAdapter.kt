package llc.slacker.openime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Pure mappings from [EditorInfo] to IME behavior; unit-testable. */
object EditorInfoAdapter {

    enum class EditorKind {
        TEXT,
        NUMBER,
        DECIMAL,
        PHONE,
        EMAIL,
        URL,
        PASSWORD,
        MULTILINE,
        UNKNOWN,
    }

    fun kind(info: EditorInfo?): EditorKind {
        val t = info?.inputType ?: InputType.TYPE_NULL
        val cls = t and InputType.TYPE_MASK_CLASS
        val variation = t and InputType.TYPE_MASK_VARIATION
        return when {
            cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            ) -> EditorKind.PASSWORD
            cls == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> EditorKind.PASSWORD
            cls == InputType.TYPE_CLASS_PHONE -> EditorKind.PHONE
            cls == InputType.TYPE_CLASS_NUMBER -> {
                if ((t and InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0) EditorKind.DECIMAL
                else EditorKind.NUMBER
            }
            cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            ) -> EditorKind.EMAIL
            cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI ->
                EditorKind.URL
            cls == InputType.TYPE_CLASS_TEXT && (t and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ->
                EditorKind.MULTILINE
            cls == InputType.TYPE_CLASS_TEXT -> EditorKind.TEXT
            else -> EditorKind.UNKNOWN
        }
    }

    fun defaultKeyboardMode(kind: EditorKind): KeyboardMode = when (kind) {
        EditorKind.NUMBER,
        EditorKind.DECIMAL,
        EditorKind.PHONE,
        -> KeyboardMode.DIGITS
        EditorKind.EMAIL,
        EditorKind.URL,
        EditorKind.PASSWORD,
        -> KeyboardMode.ENGLISH_26
        else -> KeyboardMode.PINYIN_26
    }

    /**
     * Automatic sentence punctuation is only appropriate for prose-like text.
     * Structured fields and editors explicitly asking for no suggestions are
     * kept literal so ASR post-processing cannot mutate searches, identifiers,
     * addresses or command/code-style input.
     */
    fun allowNaturalLanguageVoicePunctuation(info: EditorInfo?): Boolean {
        if (info == null) return false
        when (kind(info)) {
            EditorKind.EMAIL,
            EditorKind.URL,
            EditorKind.PASSWORD,
            EditorKind.NUMBER,
            EditorKind.DECIMAL,
            EditorKind.PHONE,
            EditorKind.UNKNOWN,
            -> return false
            EditorKind.TEXT,
            EditorKind.MULTILINE,
            -> Unit
        }

        if ((info.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH) {
            return false
        }

        val inputType = info.inputType
        if ((inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (
            variation in setOf(
                InputType.TYPE_TEXT_VARIATION_FILTER,
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
            )
        ) {
            return false
        }
        return true
    }

    fun allowCandidates(kind: EditorKind): Boolean = kind != EditorKind.PASSWORD

    fun isPassword(kind: EditorKind): Boolean = kind == EditorKind.PASSWORD
}
