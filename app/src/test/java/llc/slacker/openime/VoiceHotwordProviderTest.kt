package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceHotwordProviderTest {
    @Test
    fun encodesDistinctChineseAndMixedTerms() {
        val encoded = VoiceHotwordProvider.encode(
            listOf("澎湃OS", "语音识别", "澎湃OS", "openIME"),
        )
        assertTrue(encoded.contains("澎湃OS :1.8"))
        assertTrue(encoded.contains("语音识别 :1.8"))
        assertFalse(encoded.contains("openIME"))
        assertEquals(1, Regex("澎湃OS").findAll(encoded).count())
    }

    @Test
    fun stripsHotwordSeparatorsAndRejectsOversizedPhrases() {
        val encoded = VoiceHotwordProvider.encode(
            listOf("语音/识别:测试", "这是一个明显超过十六个字符因此不应进入热词的固定句子"),
        )
        assertEquals("语音 识别 测试 :1.8", encoded)
    }
}
