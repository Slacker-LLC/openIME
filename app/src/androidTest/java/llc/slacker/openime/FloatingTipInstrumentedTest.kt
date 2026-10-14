package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.keyboard.ToolbarTipPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/**
 * The floating keyboard meets the user with the reference's tip banner in the
 * toolbar row (`input_toolbar_tip.xml:44-49`), whose exit is our own handle tap
 * rather than the reference's drag to the bottom edge.
 */
@RunWith(AndroidJUnit4::class)
class FloatingTipInstrumentedTest {
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
                harness.awaitMain { test(harness, keyboard); true }
            } finally {
                harness.awaitMain {
                    keyboard.shutdown()
                    (keyboard.parent as? ViewGroup)?.removeView(keyboard)
                    true
                }
            }
        }
    }

    private fun tip(keyboard: ImeKeyboardView): View? = keyboard.findViewWithTag("toolbar-tip")

    /** Floats the keyboard and lets the banner take its place in the row. */
    private fun showFloatingTip(harness: DirectActivityHarness<DebugKeyboardActivity>, keyboard: ImeKeyboardView) {
        harness.awaitMain { keyboard.setFloatingWindowMode(true); true }
        harness.awaitMain { if ((tip(keyboard)?.width ?: 0) > 0) true else null }
    }

    @Test
    fun theFloatingKeyboardOpensWithTheTipBanner() {
        withKeyboard { harness, keyboard ->
            assertEquals(View.GONE, tip(keyboard)?.visibility)
            showFloatingTip(harness, keyboard)
            assertEquals(View.VISIBLE, tip(keyboard)?.visibility)
            val label = keyboard.findViewWithTag<TextView>("toolbar-tip-label")
            assertNotNull("The banner says what the handle does", label)
            assertTrue(
                "Our tip names our own way back to the docked keyboard",
                label.text.toString().contains("把手"),
            )
            assertNotNull("The banner keeps the reference's round close key", keyboard.findViewWithTag<View>("toolbar-tip-close"))
        }
    }

    @Test
    fun theCloseKeySettlesTheTipForTheStayAfloat() {
        withKeyboard { _, keyboard ->
            keyboard.setFloatingWindowMode(true)
            keyboard.findViewWithTag<View>("toolbar-tip-close")!!.performClick()
            assertEquals(View.GONE, tip(keyboard)?.visibility)
            // A later floating update keeps it away: the user has read it.
            keyboard.setFloatingWindowMode(true, compact = true)
            assertEquals(View.GONE, tip(keyboard)?.visibility)
            // Docking and floating again shows it once more.
            keyboard.setFloatingWindowMode(false)
            keyboard.setFloatingWindowMode(true)
            assertEquals(View.VISIBLE, tip(keyboard)?.visibility)
        }
    }

    @Test
    fun theTipTakesTheRowButNotTheCollectKey() {
        withKeyboard { harness, keyboard ->
            showFloatingTip(harness, keyboard)
            assertEquals(View.VISIBLE, tip(keyboard)?.visibility)
            val hide = keyboard.findViewWithTag<View>("keyboard-hide")
            assertNotNull("The collect-the-keyboard key stays reachable", hide)
            assertEquals(View.VISIBLE, hide.visibility)
            assertTrue(
                "The banner is wider than a toolbar icon: it takes the row",
                tip(keyboard)!!.width > hide.width,
            )
        }
    }

    @Test
    fun theBannerKeepsTheReferencesOwnProportions() {
        withKeyboard { harness, keyboard ->
            showFloatingTip(harness, keyboard)
            val strip = tip(keyboard)!!
            val row = keyboard.findViewWithTag<View>("toolbar-row")!!
            val density = keyboard.resources.displayMetrics.density
            assertEquals(
                "The container keeps its own 3.333% padding",
                (row.width * ToolbarTipPolicy.CONTAINER_PAD_FRACTION).toInt().toLong(),
                strip.paddingLeft.toLong(),
            )
            val lead = keyboard.findViewWithTag<View>("toolbar-tip-lead")!!
            assertEquals(
                "`width_weight=\"0.03571\"` before the label",
                (row.width * ToolbarTipPolicy.LEAD_WEIGHT).toInt().toLong(),
                lead.width.toLong(),
            )
            val close = keyboard.findViewWithTag<View>("toolbar-tip-close")!!
            assertEquals(
                "The close key keeps the 48dp touch target",
                (48 * density).toInt().toLong(),
                close.width.toLong(),
            )
        }
    }
}
