package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextEditControlsInstrumentedTest {

    @get:Rule
    val rule = ActivityScenarioRule(DebugKeyboardActivity::class.java)

    @Test
    fun unsupportedTextEditControlsAreVisiblyDisabledNotClickable() {
        lateinit var keyboard: ImeKeyboardViewV2
        rule.scenario.onActivity { activity ->
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            keyboard = ImeKeyboardViewV2(activity, NoopListener())
            content.addView(
                keyboard,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            keyboard.showPanel(Panel.TEXT_EDITOR)
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        rule.scenario.onActivity {
            listOf("撤销", "▲", "▼").forEach { label ->
                val control = findInteractiveControl(keyboard, label)
                val labelView = findTextView(keyboard, label)
                assertNotNull("missing disabled text-edit control $label", control)
                assertNotNull("missing disabled text-edit label $label", labelView)
                assertFalse("$label must not remain clickable", control!!.isClickable)
                assertFalse("$label must expose disabled state", control.isEnabled)
                assertTrue("$label should look unavailable", labelView!!.alpha < 1f)
            }

            listOf("全选", "复制", "剪切", "粘贴", "◀", "▶").forEach { label ->
                val control = findInteractiveControl(keyboard, label)
                assertNotNull("missing supported text-edit control $label", control)
                assertTrue("$label should remain clickable", control!!.isClickable)
                assertTrue("$label should remain enabled", control.isEnabled)
            }
        }
    }

    private fun findInteractiveControl(root: View, label: String): View? {
        if (root is ImeKeyView && root.contentDescription?.toString() == label) return root
        if (root is TextView && root.text.toString() == label && root.parent !is ImeKeyView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findInteractiveControl(root.getChildAt(index), label)?.let { return it }
            }
        }
        return null
    }

    private fun findTextView(root: View, label: String): TextView? {
        if (root is TextView && root.text.toString() == label) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findTextView(root.getChildAt(index), label)?.let { return it }
            }
        }
        return null
    }

    private class NoopListener : ImeKeyboardViewV2.Listener {
        override fun onModeChanged(mode: KeyboardMode) = Unit
        override fun onPanelChanged(panel: Panel) = Unit
        override fun onCharacter(char: String) = Unit
        override fun onBackspace() = Unit
        override fun onClearAll() = Unit
        override fun onSpace() = Unit
        override fun onFloatingKeyboardChanged(floating: Boolean) = Unit
        override fun onFloatingKeyboardDragged(deltaX: Float, deltaY: Float) = Unit
        override fun onVoiceToggle() = Unit
        override fun onEnter() = Unit
        override fun onCompositionChanged(composition: String, candidates: List<String>) = Unit
        override fun onCandidateSelected(candidate: String) = Unit
        override fun onCompositionBackspace() = Unit
        override fun onThemeChanged(theme: ImeTheme) = Unit
        override fun onAppearanceChanged(appearance: ImeAppearance) = Unit
        override fun onShiftStateChanged(state: ShiftState) = Unit
        override fun onCandidateExpanded(open: Boolean) = Unit
        override fun onSymbolSelected(symbol: String) = Unit
        override fun onEmojiSelected(emoji: String) = Unit
        override fun onTextEdit(action: String) = Unit
        override fun onSoundChanged(enabled: Boolean) = Unit
        override fun onHapticChanged(enabled: Boolean) = Unit
        override fun onPopupChanged(enabled: Boolean) = Unit
        override fun onFuzzyChanged(enabled: Boolean) = Unit
        override fun onSkinChanged(opacity: Int, radius: Int, fontSize: Int, primaryColor: String) = Unit
    }
}
