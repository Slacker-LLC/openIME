package llc.slacker.openime

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugKeyboardActivityTest {

    private lateinit var harness: DirectActivityHarness<DebugKeyboardActivity>

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
    fun activityRendersKeyboard() {
        val shown = harness.awaitMain { activity ->
            activity.window.decorView.takeIf { it.isShown }
        }
        assertTrue(shown.isShown)
        assertTrue(
            harness.awaitMain { activity ->
                activity.findViewById<View>(android.R.id.content)?.takeIf { it.isShown }
            }.isShown,
        )
    }

    @Test
    fun keyboardContainsManyInteractiveNodes() {
        val counts = harness.awaitMain { activity ->
            var clickableViews = 0
            var textViews = 0
            fun walk(view: View) {
                if (view.isClickable && view !is ImeKeyboardView) clickableViews++
                if (view is TextView) textViews++
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) walk(view.getChildAt(index))
                }
            }
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@awaitMain null
            walk(root)
            if (clickableViews < 30 || textViews < 3) null else clickableViews to textViews
        }
        assertTrue("expected keyboard controls, got ${counts.first}", counts.first >= 30)
        assertTrue("expected text nodes, got ${counts.second}", counts.second >= 3)
    }

    @Test
    fun keyboardExposesRootAndLayoutTags() {
        val nodes = harness.awaitMain { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@awaitMain null
            val imeRoot = root.findViewWithTag<View>("ime_root") ?: return@awaitMain null
            if (imeRoot.width <= 0 || imeRoot.height <= 0) return@awaitMain null
            listOf(
                imeRoot,
                root.findViewWithTag("candidate-expand"),
                root.findViewWithTag("panel-overlay"),
                root.findViewWithTag("candidate-overlay"),
            ).takeIf { it.all { node -> node != null } }
        }
        nodes.forEach(::assertNotNull)
    }

    @Test
    fun backspaceSwipeUpDispatchesOneShotClear() {
        harness.awaitMain { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@awaitMain null
            val imeRoot = root.findViewWithTag<View>("ime_root") ?: return@awaitMain null
            val backspace = root.findViewWithTag<View>("key-backspace") ?: return@awaitMain null
            if (imeRoot.width <= 0 || backspace.width <= 0) return@awaitMain null

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
            true
        }

        val found = harness.awaitMain { activity ->
            var clearStatusFound = false
            fun walk(view: View) {
                if (view is TextView && view.text.toString() == "clear-all") clearStatusFound = true
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) walk(view.getChildAt(index))
                }
            }
            activity.findViewById<ViewGroup>(android.R.id.content)?.let(::walk)
            clearStatusFound.takeIf { it }
        }
        assertTrue("上滑松手必须只触发一次批量清空", found)
    }
}
