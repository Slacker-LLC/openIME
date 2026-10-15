package llc.slacker.openime

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.core.Panel
import llc.slacker.openime.core.ShiftState
import llc.slacker.openime.data.ClipboardHistoryRepository
import llc.slacker.openime.data.QuickPhraseRepository
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 拆词 on the clipboard panel: the reference enters it with a long press on the
 * list (`clipboard_history_word_seg_tip`「长按列表可进入拆词」), selects words by
 * sliding across them (`word_segmentation_title`「滑动选词」) and files the run
 * into the quick phrases (`word_segmentation_add_to_common_phrase`
 * 「添加到常用语」, disabled until a run is selected —
 * `word_segmentation_add_to_common_phrase_bg_disabled`).
 */
@RunWith(AndroidJUnit4::class)
class ClipboardWordSegmentationInstrumentedTest {

    private lateinit var harness: DirectActivityHarness<DebugKeyboardActivity>

    /** 今天/天气/不错 are rows of the phrase table; 天气不错 is not. */
    private val fixture = "今天天气不错，iPhone 15"

    @Before
    fun launch() {
        harness = DirectActivityHarness(DebugKeyboardActivity::class.java)
        harness.launch()
    }

    @After
    fun close() {
        harness.close()
    }

    @Test
    fun aLongPressOpensTheSplitAndTheSlideFilesTheRun() {
        lateinit var keyboard: ImeKeyboardView
        harness.awaitMain { activity ->
            ClipboardHistoryRepository.clearAll(activity)
            ClipboardHistoryRepository.add(activity, fixture)
            keyboard = ImeKeyboardView(activity, NoopListener())
            activity.findViewById<ViewGroup>(android.R.id.content).addView(
                keyboard,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            keyboard.showPanel(Panel.CLIPBOARD)
            true
        }

        val card = harness.awaitMain {
            keyboard.findViewWithTag<View>("clip-card")?.takeIf { it.width > 0 }
        }
        harness.awaitMain { assertTrue("the clipboard card must take a long press", card.performLongClick()); true }

        // The split is built off the main thread (the word tables are read for
        // it), so the flow arrives a moment after the long press.
        val flow = harness.awaitMain {
            keyboard.findViewWithTag<ViewGroup>("word-seg-flow")?.takeIf { it.childCount > 0 }
        }
        harness.awaitMain {
            assertEquals(
                "the view carries the reference's own title",
                "滑动选词",
                keyboard.findViewWithTag<TextView>("panel-section-title")?.text?.toString(),
            )
            assertEquals(
                listOf("今天", "天气", "不错", "，", "iPhone", " ", "15"),
                (0 until flow.childCount).map { (flow.getChildAt(it) as TextView).text.toString() },
            )
            true
        }

        val add = keyboard.findViewWithTag<TextView>("word-seg-add")
        harness.awaitMain {
            assertNotNull(add)
            assertFalse("nothing is selected yet", add!!.isEnabled)
            assertEquals(ImeSurfacePolicy.DISABLED_ALPHA, add.alpha, 0.001f)
            true
        }

        // 滑动选词: press 今天, sweep to 不错, lift.
        val downTime = SystemClock.uptimeMillis()
        harness.awaitMain {
            send(flow, MotionEvent.ACTION_DOWN, downTime, centreX(flow, 0), centreY(flow, 0))
            send(flow, MotionEvent.ACTION_MOVE, downTime, centreX(flow, 1), centreY(flow, 1))
            send(flow, MotionEvent.ACTION_MOVE, downTime, centreX(flow, 2), centreY(flow, 2))
            send(flow, MotionEvent.ACTION_UP, downTime, centreX(flow, 2), centreY(flow, 2))
            true
        }
        harness.awaitMain {
            assertEquals("the run is the three words, not one", 1f, add!!.alpha, 0.001f)
            assertTrue(add.isEnabled)
            (0..2).forEach { index ->
                assertTrue(
                    "row $index must read as selected",
                    (flow.getChildAt(index).tag as String).startsWith("word-token-selected:"),
                )
            }
            assertEquals(
                "a separator is never part of the run",
                "word-token-plain:3",
                flow.getChildAt(3).tag as String,
            )
            true
        }

        harness.awaitMain { add!!.performClick(); true }
        harness.awaitMain { activity ->
            assertTrue(
                "the run must land in the quick phrases",
                QuickPhraseRepository.load(activity).any { it.text == "今天天气不错" },
            )
            assertEquals(
                "the panel lands on the list the phrase went into",
                "常用语",
                keyboard.findViewWithTag<TextView>("segment-selected")?.text?.toString(),
            )
            // Leave the store as it was: the phrase this test added is its own.
            QuickPhraseRepository.load(activity).filter { it.text == "今天天气不错" }
                .forEach { QuickPhraseRepository.remove(activity, it.id) }
            ClipboardHistoryRepository.clearAll(activity)
            keyboard.shutdown()
            true
        }
    }

    @Test
    fun aLongEntryWrapsAndTheSlideCrossesTheLines() {
        val long = "今天天气不错，".repeat(4).trimEnd('，')
        lateinit var keyboard: ImeKeyboardView
        harness.awaitMain { activity ->
            ClipboardHistoryRepository.clearAll(activity)
            ClipboardHistoryRepository.add(activity, long)
            keyboard = ImeKeyboardView(activity, NoopListener())
            activity.findViewById<ViewGroup>(android.R.id.content).addView(
                keyboard,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            keyboard.showPanel(Panel.CLIPBOARD)
            true
        }
        val card = harness.awaitMain {
            keyboard.findViewWithTag<View>("clip-card")?.takeIf { it.width > 0 }
        }
        harness.awaitMain { assertTrue(card.performLongClick()); true }
        val flow = harness.awaitMain {
            keyboard.findViewWithTag<ViewGroup>("word-seg-flow")?.takeIf { it.childCount > 0 }
        }

        val downTime = SystemClock.uptimeMillis()
        harness.awaitMain {
            val rows = (0 until flow.childCount).map { flow.getChildAt(it).top }.distinct().size
            assertTrue("a long text must wrap onto more than one line, got $rows", rows > 1)
            val last = flow.childCount - 1
            // 今天天气不错，今天天气不错，… — the last word is the final 不错.
            assertEquals("不错", (flow.getChildAt(last) as TextView).text.toString())
            send(flow, MotionEvent.ACTION_DOWN, downTime, centreX(flow, 0), centreY(flow, 0))
            // Sweep down the lines, the way a finger crosses them.
            (0..last).forEach { index ->
                send(flow, MotionEvent.ACTION_MOVE, downTime, centreX(flow, index), centreY(flow, index))
            }
            send(flow, MotionEvent.ACTION_UP, downTime, centreX(flow, last), centreY(flow, last))
            true
        }
        harness.awaitMain {
            val add = keyboard.findViewWithTag<TextView>("word-seg-add")
            assertNotNull(add)
            assertTrue("the whole text is one run", add!!.isEnabled)
            add.performClick()
            true
        }
        harness.awaitMain { activity ->
            assertTrue(
                "the whole text must be selectable across its lines",
                QuickPhraseRepository.load(activity).any { it.text == long },
            )
            QuickPhraseRepository.load(activity).filter { it.text == long }
                .forEach { QuickPhraseRepository.remove(activity, it.id) }
            ClipboardHistoryRepository.clearAll(activity)
            keyboard.shutdown()
            true
        }
    }

    private fun centreX(flow: ViewGroup, index: Int): Float {
        val child = flow.getChildAt(index)
        return (child.left + child.right) / 2f
    }

    private fun centreY(flow: ViewGroup, index: Int): Float {
        val child = flow.getChildAt(index)
        return (child.top + child.bottom) / 2f
    }

    private fun send(view: View, action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private class NoopListener : ImeKeyboardView.Listener {
        override fun onModeChanged(mode: KeyboardMode) = Unit
        override fun onPanelChanged(panel: Panel) = Unit
        override fun onCharacter(char: String) = Unit
        override fun onBackspace() = Unit
        override fun onClearAll() = Unit
        override fun onSpace() = Unit
        override fun onFloatingKeyboardChanged(floating: Boolean) = Unit
        override fun onFloatingKeyboardDragged(deltaX: Float, deltaY: Float) = Unit
        override fun onVoiceToggle() = Unit
        override fun onEnter() = Unit
        override fun onCompositionChanged(composition: String, candidates: List<String>) = Unit
        override fun onCandidateSelected(candidate: String) = Unit
        override fun onCompositionBackspace() = Unit
        override fun onThemeChanged(theme: ImeTheme) = Unit
        override fun onAppearanceChanged(appearance: ImeAppearance) = Unit
        override fun onShiftStateChanged(state: ShiftState) = Unit
        override fun onCandidateExpanded(open: Boolean) = Unit
        override fun onSymbolSelected(symbol: String) = Unit
        override fun onEmojiSelected(emoji: String) = Unit
        override fun onTextEdit(action: String) = Unit
        override fun onSoundChanged(enabled: Boolean) = Unit
        override fun onHapticChanged(enabled: Boolean) = Unit
        override fun onPopupChanged(enabled: Boolean) = Unit
        override fun onFuzzyChanged(enabled: Boolean) = Unit
    }
}
