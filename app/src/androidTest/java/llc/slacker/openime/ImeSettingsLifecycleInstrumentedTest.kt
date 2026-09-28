package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeSettingsLifecycleInstrumentedTest {

    @Test
    fun settingsActivityRecreatesWithItsPanelAndRendererLifecycleIntact() {
        DirectActivityHarness(ImeSettingsActivity::class.java).use { harness ->
            harness.launch()
            lateinit var originalActivity: ImeSettingsActivity
            harness.awaitMain { activity ->
                originalActivity = activity
                val keyboard = findKeyboard(activity.window.decorView)
                assertNotNull("Settings Activity must attach its keyboard renderer", keyboard)
                assertEquals(Panel.SETTINGS, keyboard!!.currentPanel())
                true
            }
            harness.awaitMain { activity ->
                activity.recreate()
                true
            }
            harness.awaitMain { activity ->
                if (activity === originalActivity) return@awaitMain null
                val keyboard = findKeyboard(activity.window.decorView)
                assertNotNull("Recreated settings Activity must attach a fresh renderer", keyboard)
                assertEquals(Panel.SETTINGS, keyboard!!.currentPanel())
                assertTrue("Settings renderer must remain attached after recreation", keyboard.isAttachedToWindow)
                true
            }
        }
    }

    private fun findKeyboard(root: View): ImeKeyboardView? {
        if (root is ImeKeyboardView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findKeyboard(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
