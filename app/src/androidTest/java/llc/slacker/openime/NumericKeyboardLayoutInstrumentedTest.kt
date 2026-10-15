package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.editor.EditorInfoAdapter
import llc.slacker.openime.keyboard.NumericKeyboardRenderer
import llc.slacker.openime.widget.ImeKeyView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The original numeric arrangement must survive phone-field remapping. */
@RunWith(AndroidJUnit4::class)
class NumericKeyboardLayoutInstrumentedTest {
    @Test
    fun phoneKeepsTheOriginalPositionsAndCommitsAllDialerSymbols() {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            harness.awaitMain { context ->
                val committed = mutableListOf<String>()
                var spaces = 0
                val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                fun key(text: String, onTap: () -> Unit) = ImeKeyView(context, text).apply {
                    setOnClickListener { onTap() }
                }
                NumericKeyboardRenderer(
                    context = context,
                    keyboardBody = body,
                    toPx = { it },
                    keyRowHeightDp = { 54 },
                    nineBodyHeightDp = { 216 },
                    createKey = { text, _, _, onTap -> key(text, onTap) },
                    createBackspaceKey = { key("删除") {}.apply { tag = "key-backspace" } },
                    createSpaceVoiceKey = { text, onTap -> key(text, onTap).apply { tag = "key-space" } },
                    markSideKey = {},
                    markWhiteKey = {},
                    markAccentKey = {},
                    onCommitCharacter = { committed += it },
                    onFeedback = {},
                    onShowSymbols = {},
                    onReturnToText = {},
                    onSpace = { spaces++ },
                    onEnter = {},
                ).render(EditorInfoAdapter.EditorKind.PHONE, "确定")

                fun find(tag: String) = body.findViewWithTag<ImeKeyView>(tag)
                val star = find("key-space")
                val zero = find("key:0")
                val back = find("key:mode")
                val bottom = star.parent as ViewGroup
                assertEquals(back, bottom.getChildAt(0))
                assertEquals(star, bottom.getChildAt(1))
                assertEquals(zero, bottom.getChildAt(2))
                assertEquals("*", star.currentMainText)
                assertEquals("+", find("key:.").currentMainText)
                assertEquals("#", find("key:@").currentMainText)
                assertTrue(star.performClick())
                assertTrue(find("key:.").performClick())
                assertTrue(find("key:@").performClick())
                assertEquals(listOf("*", "+", "#"), committed)
                assertEquals(0, spaces)

                val actions = body.findViewWithTag<LinearLayout>("digits-actions")
                assertEquals(4, actions.childCount)
                assertEquals(
                    listOf("key-backspace", "key:.", "key:@", "key-enter"),
                    (0 until actions.childCount).map { actions.getChildAt(it).tag },
                )
                assertTrue((0 until actions.childCount).all { actions.getChildAt(it).layoutParams.height == 54 })
                val grid = body.findViewWithTag<LinearLayout>("digits-grid")
                assertEquals(3, grid.childCount)
                assertEquals(
                    listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")),
                    (0 until grid.childCount).map { rowIndex ->
                        val row = grid.getChildAt(rowIndex) as ViewGroup
                        (0 until row.childCount).map { (row.getChildAt(it) as ImeKeyView).currentMainText }
                    },
                )
                assertEquals(View.GONE, body.findViewWithTag<View>("digits-symbol-scroll").visibility)
                true
            }
        }
    }
}
