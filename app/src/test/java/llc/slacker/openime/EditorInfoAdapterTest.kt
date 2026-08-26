package llc.slacker.openime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorInfoAdapterTest {

    private fun info(inputType: Int) = EditorInfo().apply { this.inputType = inputType }

    @Test
    fun numberKindsUseDigits() {
        assertEquals(
            KeyboardMode.DIGITS,
            EditorInfoAdapter.defaultKeyboardMode(
                EditorInfoAdapter.kind(info(InputType.TYPE_CLASS_NUMBER)),
            ),
        )
        assertEquals(
            KeyboardMode.DIGITS,
            EditorInfoAdapter.defaultKeyboardMode(
                EditorInfoAdapter.kind(
                    info(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
                ),
            ),
        )
    }

    @Test
    fun phoneUsesDigits() {
        assertEquals(
            KeyboardMode.DIGITS,
            EditorInfoAdapter.defaultKeyboardMode(
                EditorInfoAdapter.kind(info(InputType.TYPE_CLASS_PHONE)),
            ),
        )
    }

    @Test
    fun emailAndUrlUseEnglish() {
        assertEquals(
            KeyboardMode.ENGLISH_26,
            EditorInfoAdapter.defaultKeyboardMode(
                EditorInfoAdapter.kind(
                    info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
                ),
            ),
        )
        assertEquals(
            KeyboardMode.ENGLISH_26,
            EditorInfoAdapter.defaultKeyboardMode(
                EditorInfoAdapter.kind(
                    info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
                ),
            ),
        )
    }

    @Test
    fun multilineStaysText() {
        assertEquals(
            KeyboardMode.PINYIN_26,
            EditorInfoAdapter.defaultKeyboardMode(
                EditorInfoAdapter.kind(
                    info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE),
                ),
            ),
        )
    }

    @Test
    fun passwordIsDetectedAndBlocksCandidates() {
        val kind = EditorInfoAdapter.kind(
            info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        )
        assertTrue(EditorInfoAdapter.isPassword(kind))
        assertFalse(EditorInfoAdapter.allowCandidates(kind))
    }
}
