package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
                val scroll = overlay.getChildAt(1) as ScrollView
                keyboard.renderState(state)
                assertSame(first, keyboard.findViewWithTag<View>("candidate-first-row"))
                assertSame(scroll, overlay.getChildAt(1))
                keyboard.renderState(ImeState())
                assertEquals(View.GONE, overlay.visibility)
                true
            }
        }
    }
}
