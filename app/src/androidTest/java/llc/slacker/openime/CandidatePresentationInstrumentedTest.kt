package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.core.ImeState
import llc.slacker.openime.keyboard.ImeKeyboardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CandidatePresentationInstrumentedTest {
    @Test
    fun asyncCandidateRefreshPreservesReversedPreeditSelection() {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            harness.awaitMain { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val keyboard = root.findViewWithTag<ImeKeyboardView>("ime_root") ?: return@awaitMain null
                if (keyboard.width == 0) return@awaitMain null
                val state = ImeState(composition = "nihao", candidates = listOf("你好"))
                keyboard.renderState(state)
                val editor = keyboard.findViewWithTag<EditText>("pinyin-composition-editor")
                editor.setSelection(4, 1)
                keyboard.renderState(state.copy(candidates = listOf("你好", "您好")))
                assertEquals(4, editor.selectionStart)
                assertEquals(1, editor.selectionEnd)
                true
            }
        }
    }

    @Test
    fun repeatedCandidateSnapshotPreservesViewsAndExpandedScrollContainer() {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            harness.awaitMain { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val keyboard = root.findViewWithTag<ImeKeyboardView>("ime_root") ?: return@awaitMain null
                if (keyboard.width == 0) return@awaitMain null
                val state = ImeState(composition = "nihao", candidates = listOf("你好", "这是一句用于验证布局的长候选词"))
                keyboard.renderState(state)
                val first = keyboard.findViewWithTag<View>("candidate-first-row")
                assertTrue(first.isFocusable)
                keyboard.findViewWithTag<View>("candidate-expand").performClick()
                val overlay = keyboard.findViewWithTag<ViewGroup>("candidate-overlay")
                assertEquals(View.GONE, keyboard.findViewWithTag<View>("keyboard-body").visibility)
                // The expanded overlay is the filter row over the list card.
                val card = overlay.findViewWithTag<View>("candidate-expanded-card")
                assertNotNull(card)
                keyboard.renderState(state)
                assertSame(first, keyboard.findViewWithTag<View>("candidate-first-row"))
                assertSame(card, overlay.findViewWithTag<View>("candidate-expanded-card"))

                // 单字 keeps single characters only; this list has none, so the
                // grid empties while the page stays open.
                filterChip(overlay, "单字").performClick()
                assertEquals(View.VISIBLE, overlay.visibility)
                assertNull(keyboard.findViewWithTag<View>("candidate-grid-first"))
                filterChip(overlay, "全部").performClick()
                assertNotNull(keyboard.findViewWithTag<View>("candidate-grid-first"))

                keyboard.renderState(ImeState())
                assertEquals(View.GONE, overlay.visibility)
                true
            }
        }
    }

    private fun filterChip(root: ViewGroup, label: String): View {
        val found = mutableListOf<View>()
        fun walk(view: View) {
            if (view.tag == "candidate-filter-chip" && (view as? TextView)?.text?.toString() == label) {
                found.add(view)
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(root)
        return found.single()
    }
}
