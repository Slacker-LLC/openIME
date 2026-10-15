package llc.slacker.openime

import android.content.ClipboardManager
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.setup.SetupUi
import llc.slacker.openime.voice.VoiceModelDownloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class VoiceModelActivityInstrumentedTest {
    @Test
    fun contentIsCenteredAndEveryCardSharesOneTextAndActionColumn() {
        DirectActivityHarness(VoiceModelActivity::class.java).use { harness ->
            harness.launch()
            val scroll = harness.awaitMain { activity ->
                val content = activity.window.decorView.findViewWithTag<LinearLayout>("voice-model-content")
                if (content == null || content.width == 0 || content.isLayoutRequested) null
                else content.parent as ScrollView
            }
            val fullWidth = harness.awaitMain { scroll.width }
            // Exercise relayout as well as the device's current width. Run this on a
            // tablet/landscape emulator too so the 600dp cap is exercised.
            for (width in listOf(fullWidth, fullWidth * 3 / 4, fullWidth)) {
                harness.awaitMain {
                    scroll.layoutParams = scroll.layoutParams.apply { this.width = width }
                    true
                }
                harness.awaitMain { activity ->
                    val content = scroll.findViewWithTag<LinearLayout>("voice-model-content")
                    if (scroll.width != width || content.isLayoutRequested || scroll.isLayoutRequested) {
                        return@awaitMain null
                    }
                    val availableWidth = scroll.width - scroll.paddingLeft - scroll.paddingRight
                    assertEquals(minOf(availableWidth, SetupUi.dp(activity, 600)), content.width)
                    assertTrue("Content has symmetric margins", abs(
                        (content.left - scroll.paddingLeft) -
                            (scroll.width - scroll.paddingRight - content.right),
                    ) <= 1)
                    val cards = (0 until content.childCount).map(content::getChildAt)
                        .filterIsInstance<LinearLayout>()
                        .filter { it.background != null }
                    assertTrue("Current model, hotwords, import and download cards exist", cards.size >= 6)
                    val pad = SetupUi.dp(activity, 16)
                    cards.forEach { card ->
                        assertEquals(pad, card.paddingLeft)
                        assertEquals(pad, card.paddingRight)
                        assertEquals(content.paddingLeft, card.left)
                        assertEquals(content.width - content.paddingRight, card.right)
                        for (index in 0 until card.childCount) {
                            val child = card.getChildAt(index)
                            assertEquals("Card text and actions start together", pad, child.left)
                            assertEquals("Card text and actions end together", card.width - pad, child.right)
                        }
                    }
                    true
                }
            }
        }
    }

    @Test
    fun eachDownloadButtonCopiesItsDisplayedOfficialAddress() {
        DirectActivityHarness(VoiceModelActivity::class.java).use { harness ->
            harness.launch()
            harness.awaitMain { activity -> if (activity.hasWindowFocus()) true else null }
            val originalClip = harness.awaitMain { activity ->
                activity.getSystemService(ClipboardManager::class.java)
            }.primaryClip
            try {
                for (model in VoiceModelDownloads.models) {
                    harness.awaitMain { activity ->
                        val card = activity.window.decorView.findViewWithTag<ViewGroup>("voice-model-download-${model.id}")
                            ?: return@awaitMain null
                        val url = card.findViewWithTag<TextView>("voice-model-url-${model.id}")
                        assertEquals(model.url, url.text.toString())
                        assertTrue("URL can also be selected", url.isTextSelectable)
                        val button = (0 until card.childCount).map(card::getChildAt).filterIsInstance<Button>().single()
                        assertTrue(button.performClick())
                        val copied = activity.getSystemService(ClipboardManager::class.java)
                            .primaryClip?.getItemAt(0)?.text?.toString()
                        assertEquals("Copies only the URL for the chosen model", model.url, copied)
                        true
                    }
                }
            } finally {
                harness.awaitMain { activity ->
                    val clipboard = activity.getSystemService(ClipboardManager::class.java)
                    if (originalClip != null) clipboard.setPrimaryClip(originalClip)
                    else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("test cleanup", ""))
                    true
                }
            }
        }
    }
}
