package llc.slacker.openime

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.core.Panel
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.voice.VoiceModelLifecycleState
import llc.slacker.openime.widget.ImeKeyView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The space-bar cursor drag (with the bottom row locked while it runs), the
 * digits and symbols printed on the letter keys (typed by swipe up or long
 * press), and the settings toggles added in 0.0.3-beta.1.
 */
@RunWith(AndroidJUnit4::class)
class SpaceCursorAndLetterHintsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class Recorder {
        val characters = mutableListOf<String>()
        val textEdits = mutableListOf<String>()
        var spaces = 0
        val listener = Proxy.newProxyInstance(
            ImeKeyboardView.Listener::class.java.classLoader,
            arrayOf(ImeKeyboardView.Listener::class.java),
        ) { _, method, args ->
            when (method.name) {
                "voiceModelState" -> VoiceModelLifecycleState.COLD
                "onCharacter" -> { characters += args!![0] as String; null }
                "onTextEdit" -> { textEdits += args!![0] as String; null }
                "onSpace" -> { spaces++; null }
                else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
            }
        } as ImeKeyboardView.Listener
    }

    @After
    fun restoreSettings() {
        ImeSettingsRepository.saveLetterHints(context, true)
        ImeSettingsRepository.saveSwipeUpDigits(context, true)
        ImeSettingsRepository.saveEmojiAssociation(context, true)
        ImeSettingsRepository.saveVoiceStripFillers(context, true)
        ImeSettingsRepository.saveVoicePunctuationAsSpace(context, false)
    }

    private fun withKeyboard(
        test: (DirectActivityHarness<DebugKeyboardActivity>, Recorder, ImeKeyboardView) -> Any?,
    ) {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val recorder = Recorder()
            val keyboard = harness.awaitMain { activity ->
                ImeKeyboardView(activity, recorder.listener).also {
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(it)
                }
            }
            try {
                harness.awaitMain { if (keyboard.width > 0) true else null }
                test(harness, recorder, keyboard)
            } finally {
                harness.awaitMain {
                    keyboard.shutdown()
                    (keyboard.parent as? ViewGroup)?.removeView(keyboard)
                    true
                }
            }
        }
    }

    private fun settled(
        harness: DirectActivityHarness<DebugKeyboardActivity>,
        keyboard: ImeKeyboardView,
        tag: String,
    ): View = harness.awaitMain { keyboard.findViewWithTag<View>(tag)?.takeIf { it.width > 0 } }

    private fun send(view: View, action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun tap(harness: DirectActivityHarness<DebugKeyboardActivity>, view: View) {
        val downTime = SystemClock.uptimeMillis()
        val sent = harness.awaitMain {
            send(view, MotionEvent.ACTION_DOWN, downTime, view.width / 2f, view.height / 2f)
            send(view, MotionEvent.ACTION_UP, downTime, view.width / 2f, view.height / 2f)
            true
        }
        check(sent)
        // View posts the click after ACTION_UP; let it run before the caller looks.
        SystemClock.sleep(150)
        check(harness.awaitMain { true })
    }

    private class Drag(val view: View, val downTime: Long, val y: Float, var x: Float)

    private fun dragStart(harness: DirectActivityHarness<DebugKeyboardActivity>, view: View): Drag {
        val drag = Drag(view, SystemClock.uptimeMillis(), view.height / 2f, view.width / 2f)
        harness.awaitMain {
            send(view, MotionEvent.ACTION_DOWN, drag.downTime, drag.x, drag.y)
            true
        }
        return drag
    }

    /** Moves the pointer [dxDp] to the right (negative: left), in small steps like a finger. */
    private fun dragBy(harness: DirectActivityHarness<DebugKeyboardActivity>, drag: Drag, dxDp: Int) {
        val density = context.resources.displayMetrics.density
        val steps = 20
        val stepPx = dxDp * density / steps
        repeat(steps) {
            drag.x += stepPx
            val moved = harness.awaitMain {
                send(drag.view, MotionEvent.ACTION_MOVE, drag.downTime, drag.x, drag.y)
                true
            }
            check(moved)
        }
    }

    private fun dragEnd(harness: DirectActivityHarness<DebugKeyboardActivity>, drag: Drag) {
        harness.awaitMain {
            send(drag.view, MotionEvent.ACTION_UP, drag.downTime, drag.x, drag.y)
            true
        }
    }

    private fun neighbours(space: View): List<ImeKeyView> {
        val row = space.parent as ViewGroup
        return (0 until row.childCount).map(row::getChildAt).filter { it !== space }.filterIsInstance<ImeKeyView>()
    }

    // ----- space-bar cursor drag -------------------------------------------------

    @Test
    fun draggingTheSpaceBarMovesTheCursorOneStepPerTwelveDp() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
        val space = settled(harness, keyboard, "key-space")
        val drag = dragStart(harness, space)
        dragBy(harness, drag, 18 + 12 * 4)
        dragEnd(harness, drag)
        assertTrue("expected right steps, got ${recorder.textEdits}", recorder.textEdits.size in 3..5)
        assertTrue(recorder.textEdits.all { it == "right" })
        assertEquals("a drag is not a space", 0, recorder.spaces)

        val back = dragStart(harness, space)
        dragBy(harness, back, -(18 + 12 * 3 + 6))
        dragEnd(harness, back)
        assertTrue(recorder.textEdits.takeLast(2).all { it == "left" })
        assertEquals(0, recorder.spaces)
    }

    @Test
    fun theRestOfTheBottomRowIsLockedWhileDraggingAndFreeAfterwards() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
        val space = settled(harness, keyboard, "key-space")
        val others = harness.awaitMain { neighbours(space).takeIf { it.size >= 3 } }
        harness.awaitMain { assertTrue(others.none { it.touchLocked }); true }

        val drag = dragStart(harness, space)
        dragBy(harness, drag, 60)
        harness.awaitMain {
            assertTrue("every neighbour must be locked", others.all { it.touchLocked })
            assertTrue("locked keys are drawn dimmed", others.all { it.alpha < 0.5f })
            true
        }

        // A tap on a locked key (a second finger) does nothing.
        val punctuation = settled(harness, keyboard, "key-punctuation")
        tap(harness, punctuation)
        assertTrue("locked key must not type: ${recorder.characters}", recorder.characters.isEmpty())

        dragEnd(harness, drag)
        harness.awaitMain {
            assertTrue(others.none { it.touchLocked })
            assertTrue(others.all { it.alpha == 1f })
            true
        }
        tap(harness, punctuation)
        assertEquals("the key works again after the drag", 1, recorder.characters.size)
    }

    @Test
    fun aPlainTapOnSpaceStaysASpaceAndLocksNothing() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
        val space = settled(harness, keyboard, "key-space")
        val others = harness.awaitMain { neighbours(space) }
        tap(harness, space)
        assertEquals(1, recorder.spaces)
        assertTrue(recorder.textEdits.isEmpty())
        assertTrue(others.none { it.touchLocked })
    }

    @Test
    fun aSmallWobbleIsStillATapNotACursorDrag() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
        val space = settled(harness, keyboard, "key-space")
        val others = harness.awaitMain { neighbours(space) }
        val drag = dragStart(harness, space)
        dragBy(harness, drag, 8)
        harness.awaitMain { assertTrue(others.none { it.touchLocked }); true }
        dragEnd(harness, drag)
        assertTrue(recorder.textEdits.isEmpty())
    }

    @Test
    fun theLockAlsoWorksOnTheNineKeyAndDigitKeyboards() = withKeyboard { harness, recorder, keyboard ->
        listOf(KeyboardMode.PINYIN_9, KeyboardMode.DIGITS).forEach { mode ->
            harness.awaitMain { keyboard.setMode(mode); true }
            val space = settled(harness, keyboard, "key-space")
            val others = harness.awaitMain { neighbours(space).takeIf { it.isNotEmpty() } }
            recorder.textEdits.clear()
            val drag = dragStart(harness, space)
            dragBy(harness, drag, 50)
            harness.awaitMain { assertTrue("$mode", others.all { it.touchLocked }); true }
            dragEnd(harness, drag)
            harness.awaitMain { assertTrue("$mode", others.none { it.touchLocked }); true }
            assertTrue("$mode: ${recorder.textEdits}", recorder.textEdits.isNotEmpty())
        }
    }

    @Test
    fun whileComposingTheDragMovesThePinyinCursorNotTheTargetText() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
        tap(harness, settled(harness, keyboard, "key:n"))
        tap(harness, settled(harness, keyboard, "key:i"))
        tap(harness, settled(harness, keyboard, "key:h"))
        val editor = harness.awaitMain {
            keyboard.findViewWithTag<EditText>("pinyin-composition-editor")?.takeIf { it.text.length == 3 }
        }
        val before = harness.awaitMain { editor.selectionStart }
        val space = settled(harness, keyboard, "key-space")
        val drag = dragStart(harness, space)
        dragBy(harness, drag, -(18 + 12 * 2))
        dragEnd(harness, drag)
        val after = harness.awaitMain { editor.selectionStart }
        assertTrue("pinyin cursor $before -> $after", after < before)
        assertTrue("the target text must not move: ${recorder.textEdits}", recorder.textEdits.isEmpty())
        assertEquals("the pinyin itself is untouched", "nih", editor.text.toString())
    }

    @Test
    fun theLockIsReleasedIfTheKeyboardIsRebuiltMidDrag() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
        val space = settled(harness, keyboard, "key-space")
        val drag = dragStart(harness, space)
        dragBy(harness, drag, 60)
        // Android cancels the gesture (for example the window is hidden).
        harness.awaitMain {
            send(space, MotionEvent.ACTION_CANCEL, drag.downTime, drag.x, drag.y)
            true
        }
        harness.awaitMain {
            assertTrue(neighbours(space).none { it.touchLocked })
            true
        }
    }

    // ----- digits and symbols on the letter keys ------------------------------------

    private fun texts(view: View): List<String> {
        val found = mutableListOf<String>()
        fun walk(v: View) {
            if (v is android.widget.TextView && v.text.isNotEmpty()) found += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return found
    }

    private fun swipeUp(harness: DirectActivityHarness<DebugKeyboardActivity>, view: View) {
        val downTime = SystemClock.uptimeMillis()
        val x = view.width / 2f
        val y = view.height / 2f
        val distance = view.height * 1.2f
        val ok = harness.awaitMain {
            send(view, MotionEvent.ACTION_DOWN, downTime, x, y)
            send(view, MotionEvent.ACTION_MOVE, downTime, x, y - distance / 2)
            send(view, MotionEvent.ACTION_MOVE, downTime, x, y - distance)
            send(view, MotionEvent.ACTION_UP, downTime, x, y - distance)
            true
        }
        check(ok)
        SystemClock.sleep(150)
        check(harness.awaitMain { true })
    }

    private val chineseHints = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5", 'y' to "6", 'u' to "7", 'i' to "8",
        'o' to "9", 'p' to "0", 'a' to "~", 's' to "！", 'd' to "@", 'f' to "#", 'g' to "￥", 'h' to "%",
        'j' to "&", 'k' to "*", 'l' to "？", 'z' to "（", 'x' to "）", 'c' to "-", 'v' to "_",
        'b' to "：", 'n' to "；", 'm' to "/",
    )
    private val englishHints = chineseHints + mapOf(
        's' to "!", 'g' to "$", 'l' to "?", 'z' to "(", 'x' to ")", 'b' to ":", 'n' to ";",
    )

    @Test
    fun noExtraRowIsAddedAndTheKeyboardKeepsItsFourRows() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
        settled(harness, keyboard, "key-space")
        harness.awaitMain {
            assertNull(keyboard.findViewWithTag<View>("key-row-numbers"))
            val body = (keyboard.findViewWithTag<View>("key-space").parent as View).parent as ViewGroup
            assertEquals("four key rows", 4, body.childCount)
            true
        }
    }

    @Test
    fun everyLetterKeyShowsItsHintInBothLanguages() = withKeyboard { harness, _, keyboard ->
        mapOf(KeyboardMode.PINYIN_26 to chineseHints, KeyboardMode.ENGLISH_26 to englishHints)
            .forEach { (mode, expected) ->
                harness.awaitMain { keyboard.setMode(KeyboardMode.DIGITS); keyboard.setMode(mode); true }
                settled(harness, keyboard, "key:q")
                val checked = harness.awaitMain {
                    expected.forEach { (letter, hint) ->
                        val key = keyboard.findViewWithTag<View>("key:$letter")
                        assertNotNull("$mode $letter", key)
                        assertTrue("$mode $letter shows $hint in ${texts(key)}", hint in texts(key))
                    }
                    true
                }
                check(checked)
            }
    }

    @Test
    fun swipeUpTypesTheHintOnceAndStartsNoComposition() = withKeyboard { harness, recorder, keyboard ->
        listOf(KeyboardMode.PINYIN_26 to chineseHints, KeyboardMode.ENGLISH_26 to englishHints)
            .forEach { (mode, hints) ->
                harness.awaitMain { keyboard.setMode(KeyboardMode.DIGITS); keyboard.setMode(mode); true }
                recorder.characters.clear()
                val letters = listOf('q', 'p', 'a', 's', 'g', 'l', 'z', 'm')
                letters.forEach { swipeUp(harness, settled(harness, keyboard, "key:$it"), ) }
                assertEquals(mode.name, letters.map { hints.getValue(it) }, recorder.characters.toList())
                val composing = harness.awaitMain {
                    keyboard.findViewWithTag<EditText>("pinyin-composition-editor")?.text?.toString().orEmpty()
                }
                assertEquals("a swipe must not also start a composition", "", composing)
            }
    }

    @Test
    fun longPressTypesTheSameHint() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
        listOf('w', 'd', 'k', 'v').forEach { letter ->
            val key = settled(harness, keyboard, "key:$letter")
            assertTrue(harness.awaitMain { key.performLongClick() })
        }
        assertEquals(listOf("2", "@", "*", "_"), recorder.characters.toList())
    }

    @Test
    fun aPlainTapStillTypesTheLetter() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.ENGLISH_26); true }
        tap(harness, settled(harness, keyboard, "key:q"))
        assertTrue("a tap types the letter, not the hint: ${recorder.characters}", "1" !in recorder.characters)
    }

    @Test
    fun theSwipeSwitchTurnsOffSwipeButNotTheHints() = withKeyboard { harness, recorder, keyboard ->
        ImeSettingsRepository.saveSwipeUpDigits(context, false)
        harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
        val q = settled(harness, keyboard, "key:q")
        swipeUp(harness, q)
        assertTrue("swipe is off: ${recorder.characters}", recorder.characters.isEmpty())
        harness.awaitMain { assertTrue("1" in texts(q)); true }
        assertTrue(harness.awaitMain { q.performLongClick() })
        assertEquals("long press still works", listOf("1"), recorder.characters.toList())
    }

    @Test
    fun turningTheHintsOffRemovesThemAndTheirGestures() = withKeyboard { harness, recorder, keyboard ->
        ImeSettingsRepository.saveLetterHints(context, false)
        harness.awaitMain { keyboard.setMode(KeyboardMode.DIGITS); keyboard.setMode(KeyboardMode.PINYIN_26); true }
        val q = settled(harness, keyboard, "key:q")
        harness.awaitMain {
            assertEquals(listOf("Q"), texts(q))
            assertEquals(listOf("S"), texts(keyboard.findViewWithTag<View>("key:s")))
            true
        }
        swipeUp(harness, q)
        harness.awaitMain { q.performLongClick(); true }
        assertTrue("no hint, no digit: ${recorder.characters}", recorder.characters.isEmpty())
    }

    @Test
    fun turningTheHintsOffFromSettingsRebuildsTheKeyboard() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
        settled(harness, keyboard, "key-space")
        harness.awaitMain { keyboard.showPanel(Panel.SETTINGS); true }
        val flipped = harness.awaitMain {
            val toggle = keyboard.findToggle("数字和符号提示")
            assertEquals("数字和符号提示，已开启", toggle.contentDescription.toString())
            assertTrue(toggle.performClick())
            true
        }
        check(flipped)
        assertFalse(ImeSettingsRepository.loadLetterHints(context))
        harness.awaitMain { keyboard.closePanelToKeyboard(); true }
        val q = settled(harness, keyboard, "key:q")
        harness.awaitMain { assertEquals(listOf("Q"), texts(q)); true }
    }

    // ----- new settings toggles ----------------------------------------------------

    @Test
    fun theNewTogglesAreListedAndPersist() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain { keyboard.showPanel(Panel.SETTINGS); true }
        val expected = mapOf(
            "数字和符号提示" to ("开启" to { ImeSettingsRepository.loadLetterHints(context) }),
            "表情联想" to ("开启" to { ImeSettingsRepository.loadEmojiAssociation(context) }),
            "语音去语气词" to ("开启" to { ImeSettingsRepository.loadVoiceStripFillers(context) }),
            "标点用空格代替" to ("关闭" to { ImeSettingsRepository.loadVoicePunctuationAsSpace(context) }),
        )
        expected.forEach { (label, state) ->
            val (initial, read) = state
            val flipped = harness.awaitMain {
                val toggle = keyboard.findToggle(label)
                assertEquals("$label，已$initial", toggle.contentDescription.toString())
                val before = read()
                assertTrue(toggle.performClick())
                assertEquals("$label must flip", !before, read())
                assertTrue(toggle.performClick())
                assertEquals("$label must flip back", before, read())
                true
            }
            check(flipped)
        }
    }

    private fun View.findToggle(label: String): View {
        var found: View? = null
        fun walk(view: View) {
            if (found != null) return
            if (view.tag == "toggle" && view.contentDescription?.startsWith("$label，") == true) found = view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(this)
        return found ?: error("no toggle for $label")
    }
}
