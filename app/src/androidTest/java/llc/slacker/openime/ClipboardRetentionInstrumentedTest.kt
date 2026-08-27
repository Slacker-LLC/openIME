package llc.slacker.openime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardRetentionInstrumentedTest {

    @get:Rule
    val rule = ActivityScenarioRule(DebugKeyboardActivity::class.java)

    @Test
    fun clearButtonsMutatePersistentHistoryWithoutTouchingPinnedUntilRequested() {
        lateinit var keyboard: ImeKeyboardViewV2
        rule.scenario.onActivity { activity ->
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("test", ""))
            ClipboardHistoryRepository.clearAll(activity)
            ClipboardHistoryRepository.add(activity, "keep pinned")
            ClipboardHistoryRepository.togglePin(activity, "keep pinned")
            ClipboardHistoryRepository.add(activity, "remove normal")

            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            keyboard = ImeKeyboardViewV2(activity, NoopListener())
            content.addView(
                keyboard,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            keyboard.showPanel(Panel.CLIPBOARD)
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        rule.scenario.onActivity { activity ->
            val clearUnpinned = findTextView(keyboard, "清除未固定")
            assertNotNull(clearUnpinned)
            clearUnpinned!!.performClick()
            val remaining = ClipboardHistoryRepository.load(activity)
            assertEquals(listOf("keep pinned"), remaining.map { it.text })
            assertEquals(true, remaining.single().pinned)

            val clearAll = findTextView(keyboard, "清空全部")
            assertNotNull(clearAll)
            clearAll!!.performClick()
            assertEquals(emptyList<ClipboardEntry>(), ClipboardHistoryRepository.load(activity))
        }
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
