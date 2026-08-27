package llc.slacker.openime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTextProcessorContextTest {

    private val prose = VoiceTextProcessingPolicy(autoTerminalPunctuation = true)
    private val literal = VoiceTextProcessingPolicy(autoTerminalPunctuation = false)

    private fun editor(inputType: Int, action: Int = EditorInfo.IME_ACTION_NONE) =
        EditorInfo().apply {
            this.inputType = inputType
            imeOptions = action
        }

    @Test
    fun normalChineseProseKeepsAutomaticTerminalPunctuation() {
        assertEquals("今天天气很好。", VoiceTextProcessor.process("今天天气很好", "zh-CN", prose))
        assertEquals("你准备好了吗？", VoiceTextProcessor.process("你准备好了吗", "zh-CN", prose))
    }

    @Test
    fun literalSearchStyleContextDoesNotAppendTerminalPunctuation() {
        assertEquals("附近咖啡店", VoiceTextProcessor.process("附近咖啡店", "zh-CN", literal))
    }

    @Test
    fun punctuationWordInsideOrdinaryChineseTextIsNotBlindlyReplaced() {
        assertEquals(
            "我喜欢句号这个名字。",
            VoiceTextProcessor.process("我喜欢句号这个名字", "zh-CN", prose),
        )
    }

    @Test
    fun explicitStandaloneOrPauseDelimitedPunctuationStillWorks() {
        assertEquals("。", VoiceTextProcessor.process("句号", "zh-CN", literal))
        assertEquals(
            "你好，世界。",
            VoiceTextProcessor.process("你好 逗号 世界", "zh-CN", prose),
        )
        assertEquals("你好。", VoiceTextProcessor.process("你好句号", "zh-CN", literal))
    }

    @Test
    fun editorPolicyAllowsOnlyProseLikeContexts() {
        assertTrue(
            EditorInfoAdapter.allowNaturalLanguageVoicePunctuation(
                editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL),
            ),
        )
        assertTrue(
            EditorInfoAdapter.allowNaturalLanguageVoicePunctuation(
                editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            ),
        )
        assertFalse(
            EditorInfoAdapter.allowNaturalLanguageVoicePunctuation(
                editor(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
                    EditorInfo.IME_ACTION_SEARCH,
                ),
            ),
        )
        assertFalse(
            EditorInfoAdapter.allowNaturalLanguageVoicePunctuation(
                editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS),
            ),
        )
        assertFalse(
            EditorInfoAdapter.allowNaturalLanguageVoicePunctuation(
                editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            ),
        )
        assertFalse(
            EditorInfoAdapter.allowNaturalLanguageVoicePunctuation(
                editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER),
            ),
        )
        assertFalse(
            EditorInfoAdapter.allowNaturalLanguageVoicePunctuation(
                editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
            ),
        )
    }
}
