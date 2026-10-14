package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.core.Panel
import llc.slacker.openime.data.SymbolRecentRepository
import llc.slacker.openime.keyboard.ImeKeyboardView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/**
 * The symbol board's toolbar strip: the symbols the user has typed, as the
 * reference lists them (`input_toolbar_quickinput.xml:4-18`).
 */
@RunWith(AndroidJUnit4::class)
class SymbolHistoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val symbols = mutableListOf<String>()

    @After
    fun cleanup() {
        SymbolRecentRepository.clear(context)
    }

    private val listener = Proxy.newProxyInstance(
        ImeKeyboardView.Listener::class.java.classLoader,
        arrayOf(ImeKeyboardView.Listener::class.java),
    ) { _, method, args ->
        when (method.name) {
            "onSymbolSelected" -> { symbols.add(args!![0] as String); null }
            "onCharacter" -> { symbols.add(args!![0] as String); null }
            else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
        }
    } as ImeKeyboardView.Listener

    private fun withKeyboard(test: (ImeKeyboardView) -> Unit) {
        // The history each test records before this call is the input it arranges;
        // the @After cleanup empties the store between tests instead.
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val keyboard = harness.awaitMain { activity ->
                ImeKeyboardView(activity, listener).also {
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(it)
                }
            }
            try {
                harness.awaitMain { if (keyboard.width > 0) true else null }
                harness.awaitMain { test(keyboard); true }
            } finally {
                harness.awaitMain {
                    keyboard.shutdown()
                    (keyboard.parent as? ViewGroup)?.removeView(keyboard)
                    true
                }
            }
        }
    }

    @Test
    fun theSymbolBoardListsTheSymbolsTheUserTyped() {
        SymbolRecentRepository.record(context, "★")
        SymbolRecentRepository.record(context, "→")
        withKeyboard { keyboard ->
            keyboard.showPanel(Panel.SYMBOLS)
            val strip = keyboard.findViewWithTag<View>("recent-symbol-strip")
            assertNotNull("The symbol board must carry the history strip", strip)
            assertEquals(View.VISIBLE, strip!!.visibility)
            // The row is the reference's own: lead inset, list, split, gap, close
            // and tail (`input_toolbar_quickinput.xml:6-15`).
            val row = keyboard.findViewWithTag<View>("recent-symbol-row") as ViewGroup
            assertEquals(
                "Newest symbol first",
                listOf("→", "★"),
                (0 until row.childCount).map { row.getChildAt(it).contentDescription.toString().substringAfter(":") },
            )
            listOf("recent-symbol-lead", "recent-symbol-split", "recent-symbol-gap", "recent-symbol-close", "recent-symbol-tail")
                .forEach { assertNotNull("$it must be part of the strip", keyboard.findViewWithTag<View>(it)) }
        }
    }

    @Test
    fun tappingAHistoryCellCommitsThroughTheSymbolPath() {
        SymbolRecentRepository.record(context, "★")
        withKeyboard { keyboard ->
            keyboard.showPanel(Panel.SYMBOLS)
            assertTrue(
                "The cell must commit the symbol it shows",
                keyboard.findTestTarget("最近符号:★")!!.performClick(),
            )
            assertEquals(listOf("★"), symbols)
        }
    }

    @Test
    fun theCloseKeyEndsTheStripUntilTheBoardIsOpenedAgain() {
        SymbolRecentRepository.record(context, "★")
        withKeyboard { keyboard ->
            keyboard.showPanel(Panel.SYMBOLS)
            val strip = keyboard.findViewWithTag<View>("recent-symbol-strip")!!
            assertEquals(View.VISIBLE, strip.visibility)
            assertTrue(keyboard.findViewWithTag<View>("recent-symbol-close")!!.performClick())
            assertEquals("The close key ends the strip", View.GONE, strip.visibility)
            assertNotNull(
                "The board itself stays open",
                keyboard.findViewWithTag<View>("symbol-strip-row"),
            )
            keyboard.showPanel(Panel.SYMBOLS)
            assertEquals("Opening the board again shows the strip", View.VISIBLE, strip.visibility)
        }
    }

    @Test
    fun otherPanelsNeverCarryTheHistoryStrip() {
        SymbolRecentRepository.record(context, "★")
        withKeyboard { keyboard ->
            keyboard.showPanel(Panel.SYMBOLS)
            keyboard.showPanel(Panel.EMOJI)
            assertEquals(
                "The strip belongs to the symbol board's toolbar only",
                View.GONE,
                keyboard.findViewWithTag<View>("recent-symbol-strip")!!.visibility,
            )
        }
    }

    @Test
    fun theHistoryKeepsTheNewestFirstWithoutDuplicates() {
        SymbolRecentRepository.clear(context)
        SymbolRecentRepository.record(context, "★")
        SymbolRecentRepository.record(context, "→")
        SymbolRecentRepository.record(context, "★")
        assertEquals(listOf("★", "→"), SymbolRecentRepository.load(context))
    }
}
