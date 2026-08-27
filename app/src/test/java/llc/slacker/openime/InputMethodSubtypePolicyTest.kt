package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Test

class InputMethodSubtypePolicyTest {

    @Test
    fun englishSubtypeDrivesOrdinaryTextToEnglish() {
        assertEquals(
            KeyboardMode.ENGLISH_26,
            InputMethodSubtypePolicy.defaultKeyboardMode(
                EditorInfoAdapter.EditorKind.TEXT,
                "en_US",
            ),
        )
        assertEquals(
            KeyboardMode.ENGLISH_26,
            InputMethodSubtypePolicy.defaultKeyboardMode(
                EditorInfoAdapter.EditorKind.MULTILINE,
                "en-US",
            ),
        )
    }

    @Test
    fun chineseSubtypeDrivesOrdinaryTextToPinyin() {
        assertEquals(
            KeyboardMode.PINYIN_26,
            InputMethodSubtypePolicy.defaultKeyboardMode(
                EditorInfoAdapter.EditorKind.TEXT,
                "zh_CN",
            ),
        )
        assertEquals(
            KeyboardMode.PINYIN_26,
            InputMethodSubtypePolicy.defaultKeyboardMode(
                EditorInfoAdapter.EditorKind.UNKNOWN,
                "zh-CN",
            ),
        )
    }

    @Test
    fun editorSafetyModesOverrideSubtypeLanguage() {
        listOf(
            EditorInfoAdapter.EditorKind.EMAIL,
            EditorInfoAdapter.EditorKind.URL,
            EditorInfoAdapter.EditorKind.PASSWORD,
        ).forEach { kind ->
            assertEquals(
                KeyboardMode.ENGLISH_26,
                InputMethodSubtypePolicy.defaultKeyboardMode(kind, "zh_CN"),
            )
        }
        listOf(
            EditorInfoAdapter.EditorKind.NUMBER,
            EditorInfoAdapter.EditorKind.DECIMAL,
            EditorInfoAdapter.EditorKind.PHONE,
        ).forEach { kind ->
            assertEquals(
                KeyboardMode.DIGITS,
                InputMethodSubtypePolicy.defaultKeyboardMode(kind, "en_US"),
            )
        }
    }

    @Test
    fun unknownSubtypeKeepsExistingChineseDefault() {
        assertEquals(
            KeyboardMode.PINYIN_26,
            InputMethodSubtypePolicy.defaultKeyboardMode(
                EditorInfoAdapter.EditorKind.TEXT,
                null,
            ),
        )
    }
}
