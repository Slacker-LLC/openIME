package llc.slacker.openime

import android.content.Intent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupFormActionInstrumentedTest {
    @Test
    fun symbolFormEditorActionsMoveFocusThroughTheForm() {
        ActivityScenario.launch(SymbolManagerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val group = activity.findViewById<EditText>(R.id.custom_symbol_group_editor)
                val symbol = activity.findViewById<EditText>(R.id.custom_symbol_text_editor)
                group.onEditorAction(EditorInfo.IME_ACTION_NEXT)
                assertSame(symbol, activity.currentFocus)
                symbol.onEditorAction(EditorInfo.IME_ACTION_DONE)
            }
        }
    }

    @Test
    fun quickPhraseFormEditorActionsMoveFocusThroughTheForm() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, QuickPhraseEditActivity::class.java)
            .putExtra(QuickPhraseEditActivity.EXTRA_TEXT, "测试常用语")
        ActivityScenario.launch<QuickPhraseEditActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val category = activity.findViewById<EditText>(R.id.quick_phrase_category_editor)
                val phrase = activity.findViewById<EditText>(R.id.quick_phrase_text_editor)
                category.onEditorAction(EditorInfo.IME_ACTION_NEXT)
                assertSame(phrase, activity.currentFocus)
                phrase.onEditorAction(EditorInfo.IME_ACTION_DONE)
            }
        }
    }
}
