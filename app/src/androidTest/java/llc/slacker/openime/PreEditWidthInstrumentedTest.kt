package llc.slacker.openime

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.keyboard.CompositionPolicy
import llc.slacker.openime.keyboard.ImeKeyboardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/**
 * The pre-edit keeps the reference's own half of the page
 * (`maxwidth="halfMaxWidth"`, `input_toolbar_typing.xml:7`) and scrolls inside
 * it rather than running the full width of the candidate row.
 */
@RunWith(AndroidJUnit4::class)
class PreEditWidthInstrumentedTest {
    private val listener = Proxy.newProxyInstance(
        ImeKeyboardView.Listener::class.java.classLoader,
        arrayOf(ImeKeyboardView.Listener::class.java),
    ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null } as ImeKeyboardView.Listener

    private fun withKeyboard(test: (DirectActivityHarness<DebugKeyboardActivity>, ImeKeyboardView) -> Unit) {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val keyboard = harness.awaitMain { activity ->
                ImeKeyboardView(activity, listener).also {
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(it)
                }
            }
            try {
                harness.awaitMain { if (keyboard.width > 0) true else null }
                test(harness, keyboard)
            } finally {
                harness.awaitMain {
                    keyboard.shutdown()
                    (keyboard.parent as? ViewGroup)?.removeView(keyboard)
                    true
                }
            }
        }
    }

    private fun tap(harness: DirectActivityHarness<DebugKeyboardActivity>, view: View) {
        val downTime = SystemClock.uptimeMillis()
        harness.awaitMain {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN,
                view.width / 2f, view.height / 2f, 0,
            )
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            true
        }
        harness.awaitMain {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                view.width / 2f, view.height / 2f, 0,
            )
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            true
        }
        SystemClock.sleep(150)
    }

    private fun type(harness: DirectActivityHarness<DebugKeyboardActivity>, keyboard: ImeKeyboardView, keys: String) {
        keys.forEach { letter ->
            val key = harness.awaitMain {
                keyboard.findViewWithTag<View>("key:$letter")?.takeIf { it.width > 0 }
            }
            assertNotNull("The $letter key must be on the surface", key)
            tap(harness, key!!)
        }
    }

    @Test
    fun thePreEditStopsAtHalfThePage() {
        withKeyboard { harness, keyboard ->
            harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
            type(harness, keyboard, "nihao")
            val zone = harness.awaitMain { keyboard.findViewWithTag<View>("ime_toolbar") }
            val editor = harness.awaitMain {
                keyboard.findViewWithTag<EditText>("pinyin-composition-editor")
                    ?.takeIf { it.text.isNotEmpty() }
            }
            assertNotNull("Typing pinyin must open the pre-edit", editor)
            val cap = CompositionPolicy.maxWidthPx(zone.width)
            assertEquals("The pre-edit keeps the reference's half of the row", cap, editor!!.width)
            assertEquals("The cap is the field's own limit too", cap, editor.maxWidth)
            assertTrue(
                "The other half of the row is left to the surface beside it",
                editor.right <= (zone.width / 2) + 2,
            )
            // The string scrolls inside the cap: it never widens the row.
            type(harness, keyboard, "shijiede")
            val longer = harness.awaitMain {
                keyboard.findViewWithTag<EditText>("pinyin-composition-editor")
                    ?.takeIf { it.text.length > 5 }?.width ?: -1
            }
            assertEquals("A longer string keeps the same cap", cap, longer)
        }
    }
}
