package llc.slacker.openime.hotword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotwordParserTest {
    @Test
    fun readsHeadersWordsAndSkipsCommentsAndBlankLines() {
        val parsed = HotwordParser.parse(
            "﻿# title: 手游\n# description: 开黑用语\n# default: off\n\n打野\n# 注释\n 补刀 \n打野\n",
            fallbackTitle = "文件名",
        )
        assertEquals("手游", parsed.title)
        assertEquals("开黑用语", parsed.description)
        assertFalse(parsed.defaultEnabled)
        assertEquals(listOf("打野", "补刀"), parsed.words)
        assertEquals(0, parsed.rejectedLines)
    }

    @Test
    fun fallsBackToTheFileNameAndDefaultsToOn() {
        val parsed = HotwordParser.parse("打野\n", fallbackTitle = "我的词表")
        assertEquals("我的词表", parsed.title)
        assertTrue(parsed.defaultEnabled)
    }

    @Test
    fun rejectsWordsThatCannotBeMatchedBySound() {
        val parsed = HotwordParser.parse(
            listOf("打", "openIME", "澎湃OS", "一二三四五六七八九", "打 野", "打野").joinToString("\n"),
            fallbackTitle = "x",
        )
        assertEquals(listOf("打野"), parsed.words)
        assertEquals(5, parsed.rejectedLines)
    }

    @Test
    fun capsTheNumberOfWordsAndReportsIt() {
        val many = (0 until HotwordParser.MAX_WORDS + 5)
            .joinToString("\n") { i -> "我" + (0x4E00 + i).toChar() }
        val parsed = HotwordParser.parse(many, fallbackTitle = "x")
        assertEquals(HotwordParser.MAX_WORDS, parsed.words.size)
        assertTrue(parsed.truncated)
    }

    @Test
    fun renderedFormatParsesBackToTheSamePack() {
        val first = HotwordParser.parse("# title: 甲\n# description: 乙丙\n打野\n补刀\n", "x")
        val again = HotwordParser.parse(HotwordParser.render(first), "y")
        assertEquals(first.title, again.title)
        assertEquals(first.description, again.description)
        assertEquals(first.words, again.words)
    }
}
