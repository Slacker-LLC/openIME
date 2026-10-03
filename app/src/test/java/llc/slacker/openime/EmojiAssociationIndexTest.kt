package llc.slacker.openime

import java.io.File
import llc.slacker.openime.candidate.EmojiAssociationIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiAssociationIndexTest {
    private val index = EmojiAssociationIndex.parse(
        sequenceOf(
            "# comment",
            "",
            "开心\t😊 😄 😁 🥳",
            "笑\t😄 😂",
            "猫\t🐱 😺",
            "生日快乐\t🎂 🎉",
            "快乐\t😄",
            "broken line without a tab",
            "\t🙂",
            "空\t",
        ),
    )

    @Test
    fun exactKeywordReturnsItsEmojiCappedAtThree() {
        assertEquals(listOf("😊", "😄", "😁"), index.emojiFor("开心"))
        assertEquals(listOf("😊"), index.emojiFor("开心", limit = 1))
    }

    @Test
    fun aPhraseEndingInAKeywordMatchesTheLongestKeyword() {
        assertEquals(listOf("😊", "😄", "😁"), index.emojiFor("今天好开心"))
        assertEquals(listOf("🎂", "🎉"), index.emojiFor("祝你生日快乐"))
        // 快乐 is shorter than 生日快乐 and must lose to it.
        assertEquals(listOf("😄"), index.emojiFor("祝你快乐"))
    }

    @Test
    fun oneCharacterKeywordsOnlyMatchExactly() {
        assertEquals(listOf("🐱", "😺"), index.emojiFor("猫"))
        assertTrue(index.emojiFor("我喜欢猫").isEmpty())
        assertTrue(index.emojiFor("好笑").isEmpty())
    }

    @Test
    fun keywordsInTheMiddleOrNoMatchGiveNothing() {
        assertTrue(index.emojiFor("开心果").isEmpty())
        assertTrue(index.emojiFor("你好").isEmpty())
        assertTrue(index.emojiFor("").isEmpty())
        assertTrue(index.emojiFor("   ").isEmpty())
    }

    @Test
    fun malformedLinesAreIgnored() {
        assertTrue(index.emojiFor("空").isEmpty())
        assertTrue(index.emojiFor("broken line without a tab").isEmpty())
    }

    @Test
    fun anEmptyIndexNeverMatches() {
        assertTrue(EmojiAssociationIndex.EMPTY.emojiFor("开心").isEmpty())
    }

    @Test
    fun theBundledTableIsWellFormedAndUsable() {
        val file = sequenceOf(File("."), File(".."))
            .map { File(it, "app/src/main/assets/emoji/keywords.tsv") }
            .first { it.isFile }
        val lines = file.readLines(Charsets.UTF_8)
        val rows = lines.filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("expected a substantial table, got ${rows.size}", rows.size >= 400)

        val keywords = mutableSetOf<String>()
        rows.forEach { row ->
            val parts = row.split('\t')
            assertEquals("one tab per row: $row", 2, parts.size)
            val keyword = parts[0]
            assertTrue("duplicate keyword $keyword", keywords.add(keyword))
            val emoji = parts[1].split(' ').filter { it.isNotEmpty() }
            assertTrue("$keyword needs 1..3 emoji", emoji.size in 1..3)
            assertEquals("$keyword has repeated emoji", emoji.size, emoji.toSet().size)
            emoji.forEach {
                assertTrue(
                    "$keyword -> $it is not an emoji",
                    it.codePoints().anyMatch { cp -> cp >= 0x2190 },
                )
            }
        }

        val real = EmojiAssociationIndex.parse(lines.asSequence())
        assertEquals("😊", real.emojiFor("开心").first())
        assertEquals("😊", real.emojiFor("今天真的好开心").first())
        assertTrue(real.emojiFor("我们").isEmpty())
    }
}
