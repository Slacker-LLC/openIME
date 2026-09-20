package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupAccessibilityInstrumentedTest {

    @get:Rule
    val rule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun setupCardsExposeOneFocusableActionEach() {
        rule.scenario.onActivity { activity ->
            listOf(
                R.id.open_ime_settings,
                R.id.choose_ime,
                R.id.open_app_settings,
                R.id.voice_permission,
            ).forEach { id ->
                val card = activity.findViewById<ViewGroup>(id)
                assertTrue("Setup card must be clickable", card.isClickable)
                assertTrue("Setup card must be focusable", card.isFocusable)
                assertNotNull("Setup card must describe its current action", card.contentDescription)
                for (index in 0 until card.childCount) {
                    assertEquals(
                        "Setup card visuals must not create duplicate nodes",
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                        card.getChildAt(index).importantForAccessibility,
                    )
                }
            }
            assertTrue(
                "The completed enable step must remain actionable for revisiting system settings",
                activity.findViewById<View>(R.id.open_ime_settings).isEnabled,
            )
        }
    }
}
