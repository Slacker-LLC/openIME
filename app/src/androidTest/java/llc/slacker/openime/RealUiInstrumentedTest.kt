package llc.slacker.openime

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device UI test that does not rely on ActivityScenarioRule.
 * Some MIUI builds block ActivityScenario startup, so this uses the
 * instrumentation's direct launch path.
 */
@RunWith(AndroidJUnit4::class)
class RealUiInstrumentedTest {

    @Test
    fun realDeviceUiRendersKeyboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, DebugKeyboardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = instrumentation.startActivitySync(intent) as Activity
        try {
            var root: View? = null
            var clickable = 0
            instrumentation.runOnMainSync {
                root = activity.findViewById(android.R.id.content)
                var imeRoot: View? = null
                fun walk(view: View) {
                    if (view.isClickable && view !is ImeKeyboardView) clickable++
                    if (view.tag == "ime_root") imeRoot = view
                    if (view is ViewGroup) {
                        for (i in 0 until view.childCount) walk(view.getChildAt(i))
                    }
                }
                root?.let(::walk)
                assertNotNull("ime_root missing", imeRoot)
            }
            assertTrue("clickable controls missing: $clickable", clickable >= 30)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
