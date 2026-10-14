package llc.slacker.openime

import llc.slacker.openime.candidate.CandidatePreviewPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidatePreviewPolicyTest {
    @Test
    fun rawPinyinEchoIsNotShownWhileWaitingForRime() {
        assertEquals(emptyList<String>(), CandidatePreviewPolicy.chineseOnly(listOf("xyzzy")))
    }

    @Test
    fun pinyinEchoIsFilteredEvenWhenPreviewArrivesBeforeRimeReady() {
        // immediateCandidates 使用同一过滤器处理 Rime 未就绪帧。
        assertEquals(emptyList<String>(), CandidatePreviewPolicy.chineseOnly(listOf("nihao", "xyz")))
        assertEquals(listOf("你好"), CandidatePreviewPolicy.chineseOnly(listOf("nihao", "你好")))
    }

    @Test
    fun chineseCandidatesSurviveAndKeepTheirOrder() {
        assertEquals(
            listOf("你好", "泥"),
            CandidatePreviewPolicy.chineseOnly(listOf("nihao", "你好", "ni", "泥")),
        )
    }

    @Test
    fun mixedTextWithAHanCharacterIsKept() {
        assertEquals(listOf("A股"), CandidatePreviewPolicy.chineseOnly(listOf("A股", "agu")))
    }

    @Test
    fun latinLettersWithAccentsAreNotChinese() {
        // 旧实现用 code > 0x7f 会把 é 误判为汉字。
        assertEquals(emptyList<String>(), CandidatePreviewPolicy.chineseOnly(listOf("café", "naïve")))
    }

    @Test
    fun fullWidthPunctuationAndSymbolsAreNotChinese() {
        // 全角标点 U+FF0C 等 code > 0x7f，但不是汉字。
        assertEquals(emptyList<String>(), CandidatePreviewPolicy.chineseOnly(listOf("，", "！？", "…")))
    }

    @Test
    fun cjkExtensionBCharacterIsChinese() {
        // U+20000 是扩展区 B，是代理对，必须按码点而不是 char 判定。
        val extB = String(Character.toChars(0x20000))
        assertTrue(CandidatePreviewPolicy.containsHan(extB))
        assertEquals(listOf(extB), CandidatePreviewPolicy.chineseOnly(listOf("xx", extB)))
    }

    @Test
    fun compatibilityIdeographIsChinese() {
        // U+F900 CJK 兼容表意文字。
        assertTrue(CandidatePreviewPolicy.containsHan("\uF900"))
    }

    @Test
    fun kanaAndHangulAreNotHan() {
        assertFalse(CandidatePreviewPolicy.containsHan("ひらがな"))
        assertFalse(CandidatePreviewPolicy.containsHan("한글"))
    }
}
