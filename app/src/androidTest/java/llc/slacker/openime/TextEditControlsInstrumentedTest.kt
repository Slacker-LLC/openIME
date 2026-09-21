package llc.slacker.openime

import android.os.Build
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
    fun textEditorExposesUndoAndVerticalCursorControls() {
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
                assertNotNull("missing supported text-edit control $label", control)
                assertTrue("$label should remain clickable", control!!.isClickable)
                assertTrue("$label should remain enabled", control.isEnabled)
            }

            listOf("全选", "复制", "剪切", "粘贴", "◀", "▶").forEach { label ->
                val control = findInteractiveControl(keyboard, label)
                assertNotNull("missing supported text-edit control $label", control)
                assertTrue("$label should remain clickable", control!!.isClickable)
                assertTrue("$label should remain enabled", control.isEnabled)
            }
        }
    }

    @Test
    fun passwordFieldsDisableClipboardActionsBeforeTheUserCanTriggerADeadAction() {
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
            keyboard.renderState(ImeState(passwordField = false))
            keyboard.showPanel(Panel.TEXT_EDITOR)
            keyboard.renderState(ImeState(passwordField = true))
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        rule.scenario.onActivity {
            listOf("全选", "复制", "剪切", "粘贴").forEach { label ->
                val control = findInteractiveControl(keyboard, label)
                assertNotNull("password editor must still show $label", control)
                assertFalse("password $label must not remain clickable", control!!.isClickable)
                assertFalse("password $label must expose an enabled state", control.isEnabled)
                assertTrue("password $label should look unavailable", control.alpha < 1f)
                if (Build.VERSION.SDK_INT >= 30) {
                    assertTrue(
                        "password $label must explain why it is unavailable",
                        control.stateDescription?.toString()?.contains("密码输入中不可用") == true,
                    )
                }
            }
            assertTrue("cursor movement must remain available", findInteractiveControl(keyboard, "◀")!!.isEnabled)
        }
    }

    @Test
    fun editorActionsReflectSelectionAndClipboardAvailability() {
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
            keyboard.refreshTextEditAvailability(
                selectionAvailable = false,
                clipboardAvailable = false,
            )
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        rule.scenario.onActivity {
            listOf("copy", "cut", "paste").forEach { action ->
                val control = findTextEditAction(keyboard, action)
                assertNotNull("missing dynamic text-edit control $action", control)
                assertFalse("$action must be disabled without its prerequisite", control!!.isEnabled)
                val explained = if (Build.VERSION.SDK_INT >= 30) {
                    control.stateDescription?.toString()?.isNotEmpty() == true
                } else {
                    control.contentDescription?.toString()?.contains("不可用") == true
                }
                assertTrue("$action should explain why it is unavailable", explained)
            }
            val selectAll = findTextEditAction(keyboard, "select-all")
            assertNotNull("missing select-all control", selectAll)
            assertTrue("select-all should remain available", selectAll!!.isEnabled)
        }
    }

    private fun findTextEditAction(root: View, action: String): View? {
        if (root.tag == "textedit-action:$action") return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findTextEditAction(root.getChildAt(index), action)?.let { return it }
            }
        }
        return null
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
