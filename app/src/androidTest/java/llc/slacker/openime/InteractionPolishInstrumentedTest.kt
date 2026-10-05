package llc.slacker.openime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.data.EmojiRecentRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InteractionPolishInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun cleanup() {
        EmojiRecentRepository.clear(context)
    }

    @Test
    fun emojiRecentIsMostRecentlyUsedAndDeduplicated() {
        // Start from no recents: earlier sessions on the device (or E2E runs) leave some.
        EmojiRecentRepository.clear(context)
        EmojiRecentRepository.record(context, "😀")
        EmojiRecentRepository.record(context, "😂")
        EmojiRecentRepository.record(context, "😀")
        assertEquals(listOf("😀", "😂"), EmojiRecentRepository.load(context))
    }
}
