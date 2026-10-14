package llc.slacker.openime

import llc.slacker.openime.voice.VoiceHotwordProvider
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

    @Test
    fun keepsOnlyCjkTermsWithinTwoToSixteenCodePoints() {
        val oneCodePoint = "语"
        val seventeenCodePoints = "一二三四五六七八九十一二三四五六七"
        val encoded = VoiceHotwordProvider.encode(
            listOf(oneCodePoint, seventeenCodePoints, "语音识别", "openIME", "澎湃OS"),
        )
        assertEquals(17, seventeenCodePoints.codePoints().count())
        assertEquals("语音识别 :1.8/澎湃OS :1.8", encoded)
    }

    @Test
    fun stripsControlCharactersAndCollapsesWhitespace() {
        val encoded = VoiceHotwordProvider.encode(
            listOf("语音\t识别\u0000测试", "  澎湃  OS  "),
        )
        assertEquals("语音 识别 测试 :1.8/澎湃 OS :1.8", encoded)
    }

    @Test
    fun capsTheEncodedContextAtSixtyFourTerms() {
        val encoded = VoiceHotwordProvider.encode((1..80).map { "词${it}号" })
        assertEquals(64, encoded.split('/').size)
    }

    @Test
    fun parseLinesDropsBlankAndDuplicateLines() {
        assertEquals(
            listOf("澎湃OS", "语音识别", "好吧"),
            VoiceHotwordProvider.parseLines("澎湃OS\r\n  语音识别 \n\n澎湃OS\n好吧\n"),
        )
    }

    @Test
    fun parseLinesCapsAtSixtyFourTerms() {
        val text = (1..80).joinToString("\n") { "词${it}号" }
        assertEquals(64, VoiceHotwordProvider.parseLines(text).size)
    }
}
