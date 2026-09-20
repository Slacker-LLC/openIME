package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Test

class FluentEmojiAssetRepositoryTest {
    @Test
    fun assetFilenameUsesFullUnicodeSequence() {
        assertEquals("1f600.png", FluentEmojiAssetRepository.fileNameFor("😀"))
        assertEquals("263a_fe0f.png", FluentEmojiAssetRepository.fileNameFor("☺️"))
        assertEquals("1f636_200d_1f32b_fe0f.png", FluentEmojiAssetRepository.fileNameFor("😶‍🌫️"))
    }
}
