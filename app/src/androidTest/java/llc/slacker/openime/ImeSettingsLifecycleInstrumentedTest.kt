package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
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
        ActivityScenario.launch(ImeSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val keyboard = findKeyboard(activity.window.decorView)
                assertNotNull("Settings Activity must attach its keyboard renderer", keyboard)
                assertEquals(Panel.SETTINGS, keyboard!!.currentPanel())
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val keyboard = findKeyboard(activity.window.decorView)
                assertNotNull("Recreated settings Activity must attach a fresh renderer", keyboard)
                assertEquals(Panel.SETTINGS, keyboard!!.currentPanel())
                assertTrue("Settings renderer must remain attached after recreation", keyboard.isAttachedToWindow)
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
