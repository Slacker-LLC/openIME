package llc.slacker.openime

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.voice.VoiceModelLifecycleState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/** Nine-key long press (letters and digit) and the swipe-up digit shortcut. */
@RunWith(AndroidJUnit4::class)
class NineKeyGesturesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class Recorder {
        val characters = mutableListOf<String>()
        val listener = Proxy.newProxyInstance(
            ImeKeyboardView.Listener::class.java.classLoader,
            arrayOf(ImeKeyboardView.Listener::class.java),
        ) { _, method, args ->
            when (method.name) {
                "voiceModelState" -> VoiceModelLifecycleState.COLD
                "onCharacter" -> { characters += args!![0] as String; null }
                else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
            }
        } as ImeKeyboardView.Listener
    }

    @After
    fun restoreSetting() {
        ImeSettingsRepository.saveSwipeUpDigits(context, true)
    }

    private fun withNineKey(test: (DirectActivityHarness<DebugKeyboardActivity>, Recorder, ImeKeyboardView) -> Any?) {
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
                harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_9); true }
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

    private fun key(harness: DirectActivityHarness<DebugKeyboardActivity>, keyboard: ImeKeyboardView, digit: String): View =
        harness.awaitMain { keyboard.findViewWithTag<View>("key-9:$digit")?.takeIf { it.width > 0 } }

    private fun send(view: View, action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun swipeUp(harness: DirectActivityHarness<DebugKeyboardActivity>, view: View, distancePx: Float) {
        val downTime = SystemClock.uptimeMillis()
        val x = view.width / 2f
        val y = view.height / 2f
        harness.awaitMain {
            send(view, MotionEvent.ACTION_DOWN, downTime, x, y)
            send(view, MotionEvent.ACTION_MOVE, downTime, x, y - distancePx / 2)
            send(view, MotionEvent.ACTION_MOVE, downTime, x, y - distancePx)
            send(view, MotionEvent.ACTION_UP, downTime, x, y - distancePx)
            true
        }
    }

    @Test
    fun swipeUpTypesTheDigitOnceAndNothingElse() = withNineKey { harness, recorder, keyboard ->
        val five = key(harness, keyboard, "5")
        swipeUp(harness, five, distancePx = five.height * 1.2f)
        harness.awaitMain { true }
        assertEquals(listOf("5"), recorder.characters.toList())
        val composition = harness.awaitMain { keyboard.findViewWithTag<TextView>("pinyin-composition-editor")?.text?.toString().orEmpty() }
        assertEquals("a swipe must not also start a composition", "", composition)
    }

    @Test
    fun aShortUpwardWobbleIsStillATap() = withNineKey { harness, recorder, keyboard ->
        val five = key(harness, keyboard, "5")
        swipeUp(harness, five, distancePx = 4f)
        assertTrue("a tap must not commit a digit", recorder.characters.isEmpty())
    }

    @Test
    fun theSwipeCanBeSwitchedOff() = withNineKey { harness, recorder, keyboard ->
        ImeSettingsRepository.saveSwipeUpDigits(context, false)
        val five = key(harness, keyboard, "5")
        swipeUp(harness, five, distancePx = five.height * 3f)
        assertTrue("with the setting off nothing is committed", recorder.characters.isEmpty())
    }

    @Test
    fun longPressOffersTheDigitAndBothCasesOfTheLetters() = withNineKey { harness, recorder, keyboard ->
        val two = key(harness, keyboard, "2")
        assertTrue(harness.awaitMain { two.performLongClick() })
        val labels = harness.awaitMain {
            val found = mutableListOf<String>()
            fun collect(view: View) {
                if (view is TextView && view.contentDescription?.startsWith("输入") == true) {
                    found += view.text.toString()
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
            collect(keyboard)
            found.takeIf { it.isNotEmpty() }
        }
        assertEquals(listOf("2", "a", "b", "c", "A", "B", "C"), labels)

        harness.awaitMain {
            fun find(view: View, description: String): View? {
                if (view.contentDescription == description) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), description)?.let { return it }
                return null
            }
            val upperB = find(keyboard, "输入B")
            assertNotNull(upperB)
            upperB!!.performClick()
            true
        }
        assertEquals(listOf("B"), recorder.characters.toList())
        // Long-press timing sanity: the platform default is what drives this popup.
        assertTrue(ViewConfiguration.getLongPressTimeout() > 0)
    }
}
