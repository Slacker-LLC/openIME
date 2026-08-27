package llc.slacker.openime

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertNotNull

@RunWith(AndroidJUnit4::class)
class DebugKeyboardActivityTest {

    @get:Rule
    val rule = ActivityScenarioRule(DebugKeyboardActivity::class.java)

    @Test
    fun activityRendersKeyboard() {
        rule.scenario.onActivity { activity ->
            assertTrue(activity.window.decorView.isShown)
            assertTrue(activity.findViewById<View>(android.R.id.content)?.isShown == true)
        }
    }

    @Test
    fun keyboardContainsManyInteractiveNodes() {
        rule.scenario.onActivity { activity ->
            var clickableViews = 0
            var textViews = 0
            fun walk(view: View) {
                if (view.isClickable && view !is ImeKeyboardView) clickableViews++
                if (view is android.widget.TextView) textViews++
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) walk(view.getChildAt(i))
                }
            }
            activity.findViewById<ViewGroup>(android.R.id.content)?.let(::walk)
            assertTrue("expected keyboard controls, got $clickableViews", clickableViews >= 30)
            assertTrue("expected text nodes, got $textViews", textViews >= 3)
        }
    }

    @Test
    fun keyboardExposesRootAndLayoutTags() {
        rule.scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val imeRoot = root?.findViewWithTag<View>("ime_root")
            assertNotNull(imeRoot)
            assertTrue(imeRoot?.width ?: 0 > 0)
            assertTrue(imeRoot?.height ?: 0 > 0)
            assertNotNull(root?.findViewWithTag<View>("candidate-expand"))
            assertNotNull(root?.findViewWithTag<View>("panel-overlay"))
            assertNotNull(root?.findViewWithTag<View>("candidate-overlay"))
        }
    }

    @Test
    fun backspaceSwipeUpDispatchesOneShotClear() {
        rule.scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val imeRoot = requireNotNull(root.findViewWithTag<View>("ime_root"))
            val backspace = requireNotNull(root.findViewWithTag<View>("key-backspace"))

            val imeLocation = IntArray(2)
            val keyLocation = IntArray(2)
            imeRoot.getLocationOnScreen(imeLocation)
            backspace.getLocationOnScreen(keyLocation)
            val x = keyLocation[0] - imeLocation[0] + backspace.width / 2f
            val y = keyLocation[1] - imeLocation[1] + backspace.height / 2f
            val upY = y - activity.resources.displayMetrics.density * 48f
            val downTime = SystemClock.uptimeMillis()

            fun dispatch(action: Int, eventY: Float) {
                val event = MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    action,
                    x,
                    eventY,
                    0,
                )
                imeRoot.dispatchTouchEvent(event)
                event.recycle()
            }
            dispatch(MotionEvent.ACTION_DOWN, y)
            dispatch(MotionEvent.ACTION_MOVE, upY)
            dispatch(MotionEvent.ACTION_UP, upY)

            var clearStatusFound = false
            fun walk(view: View) {
                if (view is TextView && view.text.toString() == "clear-all") clearStatusFound = true
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) walk(view.getChildAt(index))
                }
            }
            walk(root)
            assertTrue("上滑松手必须只触发一次批量清空", clearStatusFound)
        }
    }
}
