package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDataArchiveTest {
    private val settings = ArchiveSettings(
        theme = ImeTheme.IOS.name,
        appearance = ImeAppearance.SYSTEM.name,
        sound = true,
        haptic = false,
        popup = true,
        fuzzy = false,
        skinOpacity = 92,
        skinRadius = 10,
        skinFont = 18,
        skinColor = "#1D9BF0",
        preferredChineseMode = KeyboardMode.PINYIN_9.name,
        handedness = ImeHandedness.LEFT.name,
        keyboardHeightPercent = 104,
        floatingWidthPercent = 84,
        floatingOpacityPercent = 96,
    )

    @Test
    fun jsonRoundTripPreservesAllExportedSections() {
        val archive = UserDataArchive(
            quickPhrases = listOf(
                ArchiveQuickPhrase("工作", "收到，我稍后处理。", "sh"),
            ),
            customSymbols = listOf(
                ArchiveCustomSymbol("箭头", "⇢", pinned = true),
            ),
            userPhrases = listOf(
                ArchiveUserPhrase("ni hao", "你好呀", frequency = 7, lastUsed = 1234L),
            ),
            settings = settings,
            rimeUserDictionaries = listOf(
                RimeUserDictionaryArchive("luna_pinyin", "# Rime user dictionary\n"),
            ),
        )

        val decoded = UserDataArchiveCodec.decode(UserDataArchiveCodec.encode(archive))

        assertEquals(archive, decoded)
    }

    @Test
    fun decodeDropsRimeDictionaryNamesThatCanEscapeTheUserDataDirectory() {
        val archive = UserDataArchive(
            quickPhrases = emptyList(),
            customSymbols = emptyList(),
            userPhrases = emptyList(),
            settings = settings,
            rimeUserDictionaries = listOf(
                RimeUserDictionaryArchive("luna_pinyin", "safe"),
                RimeUserDictionaryArchive("luna_pinyin", "duplicate"),
                RimeUserDictionaryArchive("../outside", "unsafe"),
                RimeUserDictionaryArchive("nested/path", "unsafe"),
                RimeUserDictionaryArchive("nested\\path", "unsafe"),
            ),
        )

        val decoded = UserDataArchiveCodec.decode(UserDataArchiveCodec.encode(archive))

        assertEquals(
            listOf("luna_pinyin"),
            decoded.rimeUserDictionaries.map { it.name },
        )
        assertTrue(UserDataArchiveCodec.isSafeRimeDictionaryName("luna_pinyin"))
        assertFalse(UserDataArchiveCodec.isSafeRimeDictionaryName("../outside"))
    }

    @Test
    fun mergeAddsNewRecordsWithoutDroppingExistingRecords() {
        val existing = UserDataArchive(
            quickPhrases = listOf(
                ArchiveQuickPhrase("常用", "原有常用语", "old"),
            ),
            customSymbols = listOf(
                ArchiveCustomSymbol("常用", "→", pinned = false),
            ),
            userPhrases = listOf(
                ArchiveUserPhrase("ni", "你", frequency = 5, lastUsed = 100L),
            ),
            settings = settings,
        )
        val incoming = UserDataArchive(
            quickPhrases = listOf(
                ArchiveQuickPhrase("常用", "原有常用语", "old"),
                ArchiveQuickPhrase("工作", "新增常用语", "new"),
            ),
            customSymbols = listOf(
                ArchiveCustomSymbol("常用", "→", pinned = true),
                ArchiveCustomSymbol("数学", "≈", pinned = false),
            ),
            userPhrases = listOf(
                ArchiveUserPhrase("ni", "你", frequency = 9, lastUsed = 200L),
                ArchiveUserPhrase("hao", "好", frequency = 3, lastUsed = 300L),
            ),
            settings = settings.copy(sound = false),
        )

        val preview = UserDataArchiveMerger.preview(existing, incoming)
        val merged = UserDataArchiveMerger.merge(existing, incoming)

        assertEquals(1, preview.quickPhrasesToAdd)
        assertEquals(1, preview.customSymbolsToAdd)
        assertEquals(1, preview.userPhrasesToAdd)
        assertTrue(preview.settingsWillChange)
        assertEquals(2, merged.quickPhrases.size)
        assertEquals(2, merged.customSymbols.size)
        assertEquals(2, merged.userPhrases.size)
        assertTrue(merged.quickPhrases.any { it.text == "原有常用语" })
        assertTrue(merged.customSymbols.any { it.symbol == "→" })
        val learned = merged.userPhrases.single { it.code == "ni" && it.text == "你" }
        assertEquals(9, learned.frequency)
        assertEquals(200L, learned.lastUsed)
        assertFalse(merged.settings.sound)
    }
}
