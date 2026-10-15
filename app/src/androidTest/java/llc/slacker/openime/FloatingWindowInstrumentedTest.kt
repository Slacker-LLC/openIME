package llc.slacker.openime

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.floating.FloatingWindowController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The floating card's window attributes: a drag only writes them when the card
 * really moves, because every write is a relayout of the window.
 */
@RunWith(AndroidJUnit4::class)
class FloatingWindowInstrumentedTest {
    @Test
    fun aDragOnlyWritesTheWindowWhenTheCardMoves() {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val logs = mutableListOf<String>()
            var opacityReads = 0
            val controller = harness.awaitMain { activity ->
                FloatingWindowController(
                    resources = activity.resources,
                    mainHandler = Handler(Looper.getMainLooper()),
                    windowProvider = { activity.window },
                    keyboardHeightPx = { 600 },
                    floatingWidthPercent = { 88 },
                    floatingOpacityPercent = { opacityReads++; 100 },
                    debugLog = { logs += it },
                ).also { it.enable() }
            }
            try {
                // enable() posts the first layout; wait for it to reach the window.
                harness.awaitMain { activity ->
                    if (activity.window.attributes.width > 0) true else null
                }
                logs.clear()
                val readsAfterEnable = opacityReads
                val startX = harness.awaitMain { harnessActivity -> harnessActivity.window.attributes.x }
                harness.awaitMain {
                    // Finger jitter: the delta truncates to 0, so the card does not
                    // move and the window must not be rewritten.
                    repeat(20) { controller.drag(0.4f, 0.4f) }
                    assertTrue(
                        "Sub-pixel drag samples must not write the window",
                        logs.none { it.startsWith(DRAG_LOG) },
                    )
                    assertEquals(
                        "A drag must not re-read the opacity from the settings",
                        readsAfterEnable,
                        opacityReads,
                    )
                    // A real move writes once, and the next sub-pixel sample again
                    // writes nothing.
                    controller.drag(50f, 30f)
                    assertEquals(
                        "A real move must write the window exactly once",
                        1,
                        logs.count { it.startsWith(DRAG_LOG) },
                    )
                    controller.drag(0.4f, 0f)
                    assertEquals(1, logs.count { it.startsWith(DRAG_LOG) })
                    true
                }
                harness.awaitMain { activity ->
                    assertTrue(
                        "The written position must be the dragged one ($startX -> ${activity.window.attributes.x})",
                        activity.window.attributes.x > startX,
                    )
                    true
                }
            } finally {
                controller.restore()
            }
        }
    }

    private companion object {
        const val DRAG_LOG = "floating-window-drag"
    }
}
