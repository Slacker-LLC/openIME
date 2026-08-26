package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
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
}
