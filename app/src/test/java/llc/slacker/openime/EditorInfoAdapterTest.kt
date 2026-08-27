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
    fun emailAndUrlAreClassifiedExplicitlyAndUseEnglish() {
        val email = EditorInfoAdapter.kind(
            info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        )
        val webEmail = EditorInfoAdapter.kind(
            info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS),
        )
        val url = EditorInfoAdapter.kind(
            info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
        )

        assertEquals(EditorInfoAdapter.EditorKind.EMAIL, email)
        assertEquals(EditorInfoAdapter.EditorKind.EMAIL, webEmail)
        assertEquals(EditorInfoAdapter.EditorKind.URL, url)
        assertFalse(EditorInfoAdapter.isPassword(email))
        assertFalse(EditorInfoAdapter.isPassword(webEmail))
        assertFalse(EditorInfoAdapter.isPassword(url))
        assertEquals(KeyboardMode.ENGLISH_26, EditorInfoAdapter.defaultKeyboardMode(email))
        assertEquals(KeyboardMode.ENGLISH_26, EditorInfoAdapter.defaultKeyboardMode(url))
    }

    @Test
    fun ordinaryTextVariationsAreNotPasswords() {
        listOf(
            InputType.TYPE_TEXT_VARIATION_NORMAL,
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
        ).forEach { variation ->
            val kind = EditorInfoAdapter.kind(info(InputType.TYPE_CLASS_TEXT or variation))
            assertFalse("variation=$variation must not be password", EditorInfoAdapter.isPassword(kind))
        }
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
    fun allPasswordVariationsAreDetectedAndBlockCandidates() {
        val textPasswords = listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        textPasswords.forEach { variation ->
            val kind = EditorInfoAdapter.kind(info(InputType.TYPE_CLASS_TEXT or variation))
            assertTrue("variation=$variation must be password", EditorInfoAdapter.isPassword(kind))
            assertFalse(EditorInfoAdapter.allowCandidates(kind))
        }

        val numberPassword = EditorInfoAdapter.kind(
            info(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
        )
        assertTrue(EditorInfoAdapter.isPassword(numberPassword))
        assertFalse(EditorInfoAdapter.allowCandidates(numberPassword))
    }
}
