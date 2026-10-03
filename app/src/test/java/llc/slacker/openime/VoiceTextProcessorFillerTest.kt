package llc.slacker.openime

import llc.slacker.openime.voice.VoiceTextProcessingPolicy
import llc.slacker.openime.voice.VoiceTextProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTextProcessorFillerTest {
    private val strip = VoiceTextProcessingPolicy(
        autoTerminalPunctuation = false,
        stripFillers = true,
    )
    private val stripProse = VoiceTextProcessingPolicy(
        autoTerminalPunctuation = true,
        stripFillers = true,
    )
    private val asSpace = VoiceTextProcessingPolicy(
        autoTerminalPunctuation = true,
        punctuationAsSpace = true,
    )
    private val off = VoiceTextProcessingPolicy(autoTerminalPunctuation = false)

    private fun zh(raw: String, policy: VoiceTextProcessingPolicy) =
        VoiceTextProcessor.process(raw, "zh-CN", policy)

    private fun en(raw: String, policy: VoiceTextProcessingPolicy) =
        VoiceTextProcessor.process(raw, "en-US", policy)

    @Test
    fun hesitationsAreRemovedFromTheStartMiddleAndEndOfChineseText() {
        assertEquals("我觉得这个不错", zh("嗯我觉得这个不错", strip))
        assertEquals("我觉得这个不错", zh("我觉得呃这个不错", strip))
        assertEquals("我觉得这个不错", zh("我觉得 呃 这个不错", strip))
        assertEquals("我觉得这个不错", zh("我觉得这个不错嗯", strip))
        assertEquals("我觉得这个不错", zh("嗯嗯呃我觉得这个不错", strip))
    }

    @Test
    fun fillersStayWhenTheSwitchIsOff() {
        assertEquals("嗯我觉得这个不错", zh("嗯我觉得这个不错", off))
        assertEquals("um I think so", en("um I think so", off))
    }

    @Test
    fun anUtteranceThatIsOnlyAHesitationIsKept() {
        assertEquals("嗯", zh("嗯", strip))
        assertEquals("嗯嗯", zh("嗯嗯", strip))
        assertEquals("um", en("um", strip))
    }

    @Test
    fun realWordsContainingFillerCharactersSurvive() {
        assertEquals("他一直在打呃逆", zh("他一直在打呃逆", strip))
        assertEquals("额度不够", zh("额度不够", strip))
        assertEquals("总金额是五百", zh("总金额是五百", strip))
        // 额 only goes when the recogniser set it off as a word of its own.
        assertEquals("这个不错", zh("额 这个不错", strip))
    }

    @Test
    fun englishFillersAreRemovedOnlyAsWholeWords() {
        assertEquals("I think so", en("um I think so", strip))
        assertEquals("I think so", en("I uh think so", strip))
        assertEquals("I think so", en("I think so er", strip))
        assertEquals("I think so", en("Um, I think so", strip))
        assertEquals("humble summer", en("humble summer", strip))
        assertEquals("to err is human", en("to err is human", strip))
        assertEquals("the herd", en("the herd", strip))
    }

    @Test
    fun removingAFillerNextToPunctuationDoesNotLeaveStrayMarks() {
        assertEquals("你好，世界", zh("嗯 你好 逗号 世界", strip))
        assertEquals("你好，世界", zh("嗯，你好 逗号 世界", strip))
    }

    @Test
    fun automaticTerminalPunctuationStillAppliesAfterStripping() {
        assertEquals("我觉得这个不错。", zh("嗯我觉得这个不错", stripProse))
    }

    @Test
    fun punctuationBecomesSpacesBetweenClauses() {
        assertEquals("你好 世界", zh("你好 逗号 世界", asSpace))
        assertEquals("你好 世界 再见", zh("你好 逗号 世界 句号 再见", asSpace))
        assertEquals("你准备好了吗", zh("你准备好了吗", asSpace))
        assertEquals("今天天气很好", zh("今天天气很好", asSpace))
    }

    @Test
    fun trailingPunctuationIsDroppedNotReplacedByATrailingSpace() {
        assertEquals("你好", zh("你好句号", asSpace))
        assertEquals("你好", zh("你好 感叹号", asSpace))
    }

    @Test
    fun bracketsAndLatinNumbersAreLeftAlone() {
        assertEquals("价格 3.5 元", zh("价格 3.5 元", asSpace))
        assertEquals("见 a.b 网站", zh("见 a.b 网站", asSpace))
        assertEquals("（备注）", zh("左括号 备注 右括号", asSpace).replace(" ", ""))
    }

    @Test
    fun englishPunctuationBecomesSpacesToo() {
        assertEquals("hello world", en("hello comma world period", asSpace))
        assertEquals("hello world", en("hello world", asSpace))
    }

    @Test
    fun bothSwitchesCompose() {
        val both = VoiceTextProcessingPolicy(
            autoTerminalPunctuation = true,
            stripFillers = true,
            punctuationAsSpace = true,
        )
        assertEquals("你好 世界", zh("嗯 你好 逗号 呃 世界", both))
    }
}
