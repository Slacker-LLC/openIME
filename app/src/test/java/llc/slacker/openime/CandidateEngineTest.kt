package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateEngineTest {

    private val engine = CandidateEngine()

    @Test
    fun nihaoProducesCandidates() {
        val candidates = engine.getCandidates("nihao")
        assertTrue("should include 你好", candidates.contains("你好"))
    }

    @Test
    fun zhongguoProducesCandidates() {
        assertTrue(engine.getCandidates("zhongguo").contains("中国"))
    }

    @Test
    fun shurufaProducesCandidates() {
        assertTrue(engine.getCandidates("shurufa").contains("输入法"))
    }

    @Test
    fun nineKeyPresetResolves() {
        val result = engine.get9KeyCandidates("64426")
        assertTrue(result.pinyins.contains("nihao"))
        assertTrue(result.candidates.contains("你好"))
    }

    @Test
    fun nineKeyCanDecodeRepeatedWordsWithoutCombinatorialExpansion() {
        val result = engine.get9KeyCandidates("6442664426")
        assertTrue(result.pinyins.contains("nihaonihao"))
        assertTrue(result.candidates.contains("你好你好"))
    }

    @Test
    fun longNineKeyStreamRemainsBoundedAndNeverReturnsRawDigits() {
        val result = engine.get9KeyCandidates("6".repeat(50))
        assertTrue("长九键串应在 50 个按键内返回", result.pinyins.isNotEmpty())
        assertTrue("候选不能把九键数字直接当汉字候选", result.candidates.none { it.any(Char::isDigit) })
    }

    @Test
    fun englishCompletionWorks() {
        assertTrue(engine.getEnglishCompletions("hel").contains("hello"))
    }

    @Test
    fun t9GoodResolvesToKnownWords() {
        val result = engine.getT9EnglishCandidates("4663")
        assertTrue("4663 should include good", result.contains("good"))
    }

    @Test
    fun fallbackReturnsPinyin() {
        assertEquals(listOf("xyzzy"), engine.getCandidates("xyzzy"))
    }

    @Test
    fun continuousPinyinGetsAWordSegmentedCandidate() {
        assertEquals(
            "你好输入法开发",
            engine.getCandidates("nihaoshurufakaifa").first(),
        )
    }

    @Test
    fun fuzzyPinyinReturnsZhVariant() {
        val candidates = engine.getCandidates("zi", fuzzy = true)
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.any { it == "之" || it == "知" || it == "只" || it == "支" })
    }

    @Test
    fun initialPinyinResolvesCommonPhrase() {
        assertTrue("首字母 nh 应包含 你好", engine.getCandidates("nh").contains("你好"))
    }

    @Test
    fun initialPinyinSupportsSingleInitial() {
        assertTrue("首字母 n 应包含 你", engine.getCandidates("n").contains("你"))
    }

    @Test
    fun allLettersCanAlsoBeReadAsInitialsWithoutRemovingFullPinyin() {
        val candidates = engine.getCandidates("xian")
        assertTrue("全拼候选先必须保留", candidates.contains("先"))
        assertTrue("西安也应作为词候选出现", candidates.contains("西安"))
    }

    @Test
    fun explicitSegmentationResolvesXiAn() {
        assertTrue("xi 分词 an 应得到西安", engine.getCandidates("xi an").contains("西安"))
        assertTrue("竖线边界也应可编辑识别", engine.getCandidates("xi|an").contains("西安"))
        assertTrue("分词短语也应支持普通首字母 xa", engine.getCandidates("xa").contains("西安"))
    }

    @Test
    fun userChoiceCanBeRankedBeforeBuiltInCandidates() {
        UserPhraseRepository.record("nihao", "自定义词")
        assertEquals("自定义词", engine.getCandidates("nihao").first())
        UserPhraseRepository.clear()
    }

    @Test
    fun chineseAssociationsReturnNextWords() {
        assertTrue(engine.getAssociations("你好").contains("呀"))
        assertTrue(engine.getAssociations("中国").contains("人"))
    }

    @Test
    fun externalLexiconFillsMissingCharacters() {
        val extended = CandidateEngine(mapOf("zai" to listOf("载")))
        assertTrue("外部字库应补齐载", extended.getCandidates("zai").contains("载"))
    }
}
