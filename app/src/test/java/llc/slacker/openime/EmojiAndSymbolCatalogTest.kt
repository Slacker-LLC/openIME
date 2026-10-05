package llc.slacker.openime

import llc.slacker.openime.core.EmojiCatalog
import llc.slacker.openime.core.ImeData
import llc.slacker.openime.core.SymbolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiAndSymbolCatalogTest {
    private fun asset(relative: String): File = sequenceOf(
        File("src/main/assets/$relative"),
        File("app/src/main/assets/$relative"),
    ).first { it.isFile }

    @Test
    fun curatedEmojiComeFirstThenTheCatalogAddsTheRest() {
        val merged = EmojiCatalog.merge(
            curated = linkedMapOf("笑脸" to listOf("😀", "❤️")),
            extra = mapOf("笑脸" to listOf("😀", "❤", "🫠"), "旗帜" to listOf("🏁")),
            drawable = { true },
        )
        // ❤ and ❤️ are one emoji; categories the panel has no tab for are ignored.
        assertEquals(listOf("😀", "❤️", "🫠"), merged["笑脸"])
        assertEquals(setOf("笑脸"), merged.keys)
    }

    @Test
    fun emojiTheFontCannotDrawAreLeftOut() {
        val merged = EmojiCatalog.merge(
            curated = linkedMapOf("笑脸" to listOf("😀")),
            extra = mapOf("笑脸" to listOf("🫩")),
            drawable = { it != "🫩" },
        )
        assertEquals(listOf("😀"), merged["笑脸"])
    }

    @Test
    fun theGeneratedCatalogCoversEveryPanelCategoryWithoutSkinTones() {
        val rows = asset("emoji/unicode_emoji.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val categories = rows.map { it.substringBefore('\t') }.toSet()
        assertEquals(ImeData.emojiByCategory.keys, categories)
        assertTrue("about 1900 base emoji, got ${rows.size}", rows.size in 1800..2400)
        val tones = (0x1F3FB..0x1F3FF).map { String(Character.toChars(it)) }
        assertTrue(rows.none { row -> tones.any { it in row } })
    }

    @Test
    fun everySymbolListIsFilledAndHasNoRepeats() {
        listOf(
            SymbolCatalog.CHINESE_EXTRA, SymbolCatalog.SPECIAL_EXTRA, SymbolCatalog.KAOMOJI_EXTRA,
            SymbolCatalog.PINYIN_TONES, SymbolCatalog.JAPANESE, SymbolCatalog.ZHUYIN, SymbolCatalog.BOX_DRAWING,
        ).forEach { list ->
            assertTrue(list.isNotEmpty())
            assertEquals("repeats in ${list.take(3)}", list.size, list.toSet().size)
            assertTrue(list.none { it.isBlank() })
        }
        assertEquals(SymbolCatalog.CATEGORIES.size, SymbolCatalog.CATEGORIES.toSet().size)
        assertTrue(SymbolCatalog.KAOMOJI_EXTRA.size + ImeData.symbols["网络颜文字"].orEmpty().size >= 100)
    }
}
