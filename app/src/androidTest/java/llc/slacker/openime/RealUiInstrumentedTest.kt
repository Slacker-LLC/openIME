package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device UI test that deliberately avoids global-idle synchronization.
 * MIUI can keep window/Choreographer callbacks active while an IME-like view is
 * visible, so the test waits only for the concrete rendered UI condition.
 */
@RunWith(AndroidJUnit4::class)
class RealUiInstrumentedTest {

    @Test
    fun realDeviceUiRendersKeyboard() {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val result = harness.awaitMain { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@awaitMain null
                var imeRoot: View? = null
                var clickable = 0
                fun walk(view: View) {
                    if (view.isClickable && view !is ImeKeyboardView) clickable++
                    if (view.tag == "ime_root") imeRoot = view
                    if (view is ViewGroup) {
                        for (index in 0 until view.childCount) walk(view.getChildAt(index))
                    }
                }
                walk(root)
                if (imeRoot == null || clickable < 30) null else imeRoot to clickable
            }
            assertNotNull("ime_root missing", result.first)
            assertTrue("clickable controls missing: ${result.second}", result.second >= 30)
        }
    }
}
