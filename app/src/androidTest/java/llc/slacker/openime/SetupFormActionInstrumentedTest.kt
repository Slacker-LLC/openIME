package llc.slacker.openime

import android.content.Intent
import android.view.inputmethod.EditorInfo
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupFormActionInstrumentedTest {
    @Test
    fun symbolFormEditorActionsMoveFocusThroughTheForm() {
        DirectActivityHarness(SymbolManagerActivity::class.java).use { harness ->
            harness.launch()
            harness.awaitMain { activity ->
                val group = activity.findViewById<EditText>(R.id.custom_symbol_group_editor)
                val symbol = activity.findViewById<EditText>(R.id.custom_symbol_text_editor)
                group.onEditorAction(EditorInfo.IME_ACTION_NEXT)
                assertSame(symbol, activity.currentFocus)
                symbol.onEditorAction(EditorInfo.IME_ACTION_DONE)
            }
        }
    }

    @Test
    fun quickPhraseBlankContentDisablesAndDimsSaveUntilTextExists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, QuickPhraseEditActivity::class.java)
        DirectActivityHarness(QuickPhraseEditActivity::class.java).use { harness ->
            harness.launch()
            harness.awaitMain { activity ->
                val phrase = activity.findViewById<EditText>(R.id.quick_phrase_text_editor)
                val save = findButton(activity.window.decorView, "保存")
                    ?: error("save button missing")

                assertFalse("Blank quick phrase must not be saveable", save.isEnabled)
                assertTrue(
                    "Disabled save action must use the shared disabled alpha",
                    save.alpha == ImeSurfacePolicy.DISABLED_ALPHA,
                )
                assertTrue(
                    "Disabled save action must explain the missing content",
                    save.contentDescription.toString().contains("不可用"),
                )

                phrase.setText("可保存内容")

                assertTrue("Typing content must enable save immediately", save.isEnabled)
                assertTrue("Enabled save action must return to full opacity", save.alpha == 1f)
            }
        }
    }

    @Test
    fun quickPhraseFormEditorActionsMoveFocusThroughTheForm() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, QuickPhraseEditActivity::class.java)
            .putExtra(QuickPhraseEditActivity.EXTRA_TEXT, "测试常用语")
        DirectActivityHarness(QuickPhraseEditActivity::class.java).use { harness ->
            harness.launch(intent = intent)
            harness.awaitMain { activity ->
                val category = activity.findViewById<EditText>(R.id.quick_phrase_category_editor)
                val code = activity.findViewById<EditText>(R.id.quick_phrase_code_editor)
                val phrase = activity.findViewById<EditText>(R.id.quick_phrase_text_editor)
                category.onEditorAction(EditorInfo.IME_ACTION_NEXT)
                assertSame(code, activity.currentFocus)
                code.onEditorAction(EditorInfo.IME_ACTION_NEXT)
                assertSame(phrase, activity.currentFocus)
                phrase.onEditorAction(EditorInfo.IME_ACTION_DONE)
            }
        }
    }

    private fun findButton(root: View, label: String): Button? {
        if (root is Button && root.text.toString() == label) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findButton(root.getChildAt(index), label)?.let { return it }
            }
        }
        return null
    }

}
