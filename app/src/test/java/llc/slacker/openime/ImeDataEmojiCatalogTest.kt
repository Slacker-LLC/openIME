package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeDataEmojiCatalogTest {

    @Test
    fun catalogCoversEveryProductEmojiCategory() {
        assertEquals(
            listOf(
                "笑脸",
                "人物/手势",
                "动物/自然",
                "食物/饮品",
                "活动",
                "旅行/地点",
                "物品",
                "符号",
                "旗帜",
            ),
            ImeData.emojiByCategory.keys.toList(),
        )
        assertTrue(ImeData.emojiByCategory.values.all { it.isNotEmpty() })
    }

    @Test
    fun peopleCatalogIncludesAllFiveSkinToneModifiers() {
        val people = ImeData.emojiByCategory.getValue("人物/手势")
        assertTrue(
            people.containsAll(
                listOf("👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿"),
            ),
        )
    }

    @Test
    fun standardCategoriesExposeRepresentativeDailyUseEmoji() {
        assertTrue("🐶" in ImeData.emojiByCategory.getValue("动物/自然"))
        assertTrue("🍜" in ImeData.emojiByCategory.getValue("食物/饮品"))
        assertTrue("⚽" in ImeData.emojiByCategory.getValue("活动"))
        assertTrue("✈️" in ImeData.emojiByCategory.getValue("旅行/地点"))
        assertTrue("📱" in ImeData.emojiByCategory.getValue("物品"))
        assertTrue("✅" in ImeData.emojiByCategory.getValue("符号"))
        assertTrue("🇨🇳" in ImeData.emojiByCategory.getValue("旗帜"))
    }
}
