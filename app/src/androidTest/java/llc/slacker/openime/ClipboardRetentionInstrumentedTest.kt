package llc.slacker.openime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardRetentionInstrumentedTest {

    private lateinit var harness: DirectActivityHarness<DebugKeyboardActivity>

    @Before
    fun launch() {
        harness = DirectActivityHarness(DebugKeyboardActivity::class.java)
        harness.launch()
    }

    @After
    fun close() {
        harness.close()
    }

    @Test
    fun sensitiveSystemClipIsNeverCapturedIntoHistory() {
        harness.awaitMain { activity ->
            ClipboardHistoryRepository.clearAll(activity)
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("sensitive fixture", "synthetic test secret")
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
            clipboard.setPrimaryClip(clip)
            assertFalse(ClipboardHistoryRepository.capturePrimary(activity))
            assertEquals(emptyList<ClipboardEntry>(), ClipboardHistoryRepository.load(activity))
            val connection = BaseInputConnection(View(activity), true)
            val gateway = InputConnectionGateway(activity, { connection })
            // Android 12+ may redact a sensitive clip when it is read back
            // through ClipboardManager. Exercise the gateway with the exact
            // immutable snapshot instead of depending on that platform detail.
            assertEquals("synthetic test secret", gateway.pasteClipSnapshot(clip) { pasted ->
                // A new system clip must not change the sensitivity of the one pasted.
                clipboard.setPrimaryClip(ClipData.newPlainText("new", "ordinary replacement"))
                ClipboardHistoryRepository.captureClip(activity, pasted)
            })
            assertEquals("synthetic test secret", connection.editable.toString())
            assertEquals(emptyList<ClipboardEntry>(), ClipboardHistoryRepository.load(activity))
            clipboard.setPrimaryClip(ClipData.newPlainText("test cleanup", ""))
        }
    }

    @Test
    fun leavingClipboardBeforePostedLoadDoesNotCaptureStaleClip() {
        lateinit var keyboard: ImeKeyboardView
        harness.awaitMain { activity ->
            ClipboardHistoryRepository.clearAll(activity)
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("stale clipboard fixture", "must not capture after leaving panel"),
            )

            keyboard = ImeKeyboardView(activity, NoopListener())
            activity.findViewById<ViewGroup>(android.R.id.content).addView(
                keyboard,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            // Both calls happen in one main-thread turn. The clipboard loader is
            // only posted by the first call, so the second panel must invalidate
            // its surface before that task is allowed to read/capture the clip.
            keyboard.showPanel(Panel.CLIPBOARD)
            keyboard.showPanel(Panel.SETTINGS)
        }

        harness.awaitMain { true }
        repeat(10) {
            Thread.sleep(30)
            harness.awaitMain { Unit }
        }

        harness.awaitMain { activity ->
            assertEquals(
                "A detached clipboard surface must not persist a stale queued capture",
                emptyList<ClipboardEntry>(),
                ClipboardHistoryRepository.load(activity),
            )
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("test cleanup", ""))
            keyboard.shutdown()
        }
    }

    @Test
    fun closingClipboardBeforePostedLoadDoesNotCaptureStaleClip() {
        lateinit var keyboard: ImeKeyboardView
        harness.awaitMain { activity ->
            ClipboardHistoryRepository.clearAll(activity)
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("closed clipboard fixture", "must not capture after closing panel"),
            )

            keyboard = ImeKeyboardView(activity, NoopListener())
            activity.findViewById<ViewGroup>(android.R.id.content).addView(
                keyboard,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            keyboard.showPanel(Panel.CLIPBOARD)
            assertTrue("Clipboard panel must close back to the keyboard", keyboard.closePanelToKeyboard())
        }

        harness.awaitMain { true }
        repeat(10) {
            Thread.sleep(30)
            harness.awaitMain { Unit }
        }

        harness.awaitMain { activity ->
            assertEquals(
                "Closing the clipboard panel must invalidate its queued capture",
                emptyList<ClipboardEntry>(),
                ClipboardHistoryRepository.load(activity),
            )
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("test cleanup", ""))
            keyboard.shutdown()
        }
    }

    @Test
    fun clearButtonsMutatePersistentHistoryWithoutTouchingPinnedUntilRequested() {
        lateinit var keyboard: ImeKeyboardView
        harness.awaitMain { activity ->
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("test", ""))
            ClipboardHistoryRepository.clearAll(activity)
            ClipboardHistoryRepository.add(activity, "keep pinned")
            ClipboardHistoryRepository.togglePin(activity, "keep pinned")
            ClipboardHistoryRepository.add(activity, "remove normal")

            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            keyboard = ImeKeyboardView(activity, NoopListener())
            content.addView(
                keyboard,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            keyboard.showPanel(Panel.CLIPBOARD)
        }

        val clearUnpinned = harness.awaitMain { findTextView(keyboard, "清除未固定") }
        harness.awaitMain { activity ->
            val minimumTarget = (48 * activity.resources.displayMetrics.density).toInt()
            assertTrue("clear-unpinned must keep a 48dp target", clearUnpinned.minimumHeight >= minimumTarget)
            assertTrue(clearUnpinned.contentDescription.toString().contains("保留已固定内容"))
            clearUnpinned.performClick()
            val remaining = ClipboardHistoryRepository.load(activity)
            assertEquals(listOf("keep pinned"), remaining.map { it.text })
            assertEquals(true, remaining.single().pinned)
        }

        var clearAll: TextView? = null
        repeat(20) {
            harness.awaitMain { true }
            harness.awaitMain { clearAll = findTextView(keyboard, "清空全部") }
            if (clearAll != null) return@repeat
            Thread.sleep(50)
        }
        harness.awaitMain { activity ->
            val minimumTarget = (48 * activity.resources.displayMetrics.density).toInt()
            assertNotNull("Retention actions must return after the pruned history reloads", clearAll)
            assertTrue("clear-all must keep a 48dp target", clearAll!!.minimumHeight >= minimumTarget)
            assertTrue(clearAll!!.contentDescription.toString().contains("删除全部剪贴历史"))
            clearAll!!.performClick()
            assertEquals(
                "A destructive clear must wait for confirmation",
                listOf("keep pinned"),
                ClipboardHistoryRepository.load(activity).map { it.text },
            )
        }
        harness.awaitMain { activity ->
            val confirm = keyboard.findViewWithTag<View>("clipboard-clear-confirm")
            assertNotNull("Inline confirmation must expose a clear action", confirm)
            assertTrue("Inline confirmation must be keyboard-focusable", confirm!!.isFocusable)
            assertTrue(confirm.contentDescription.toString().contains("确认清空全部剪贴历史"))
            assertTrue(confirm.performClick())
            assertEquals(emptyList<ClipboardEntry>(), ClipboardHistoryRepository.load(activity))
        }

        var emptyState: TextView? = null
        repeat(20) {
            harness.awaitMain { true }
            harness.awaitMain {
                emptyState = findTextView(keyboard, "暂无剪贴历史；复制文本后重新打开这里即可看到。")
            }
            if (emptyState != null) return@repeat
            Thread.sleep(50)
        }
        assertNotNull("Clearing all history must expose an empty state", emptyState)
        harness.awaitMain { activity ->
            val refresh = findTextView(keyboard, "重新读取")
            assertNotNull("Empty clipboard state must offer an immediate refresh action", refresh)
            assertTrue(refresh!!.contentDescription.toString().contains("重新读取剪贴板"))
        }
    }

    @Test
    fun clipboardCardTapUsesTheDisplayedEntry() {
        lateinit var keyboard: ImeKeyboardView
        lateinit var listener: NoopListener
        harness.awaitMain { activity ->
            ClipboardHistoryRepository.clearAll(activity)
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("test", ""))
            ClipboardHistoryRepository.add(activity, "tap this entry")
            listener = NoopListener()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            keyboard = ImeKeyboardView(activity, listener)
            content.addView(
                keyboard,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            keyboard.showPanel(Panel.CLIPBOARD)
            assertNull(
                "Retention actions must wait until clipboard history has loaded",
                findTextView(keyboard, "清空全部"),
            )
        }

        var card: View? = null
        repeat(20) {
            harness.awaitMain { true }
            harness.awaitMain { card = keyboard.findViewWithTag("clip-card") }
            if (card != null) return@repeat
            Thread.sleep(50)
        }
        assertNotNull("clipboard entry card must render", card)
        assertTrue("clipboard entry card must be a primary action", card!!.isClickable)
        assertTrue(card!!.contentDescription.toString().contains("tap this entry"))
        assertTrue(card!!.contentDescription.toString().contains("点击使用"))

        harness.awaitMain {
            assertTrue(card!!.performClick())
            assertEquals("tap this entry", listener.lastCharacter)
        }
    }

    @Test
    fun openingClipboardCapturesPrimaryClipBeforeAddingRetentionActions() {
        lateinit var keyboard: ImeKeyboardView
        harness.awaitMain(timeoutMs = 10_000L) { activity ->
            if (activity.hasWindowFocus()) true else null
        }
        harness.awaitMain { activity ->
            ClipboardHistoryRepository.clearAll(activity)
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("capture on open", "captured on open"))
            assertEquals(
                "The foreground activity must be able to read the clip used by this capture test",
                "captured on open",
                clipboard.primaryClip?.getItemAt(0)?.text?.toString(),
            )
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            keyboard = ImeKeyboardView(activity, NoopListener())
            content.addView(
                keyboard,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            keyboard.showPanel(Panel.CLIPBOARD)
        }

        var card: View? = null
        var clearAll: TextView? = null
        harness.awaitMain(timeoutMs = 15_000L) {
            card = keyboard.findViewWithTag("clip-card")
            clearAll = findTextView(keyboard, "清空全部")
            if (card != null && clearAll != null) true else null
        }
        assertNotNull("opening the clipboard must capture the current clip", card)
        assertNotNull("retention actions must appear after async capture", clearAll)
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

    private class NoopListener : ImeKeyboardView.Listener {
        var lastCharacter: String? = null

        override fun onModeChanged(mode: KeyboardMode) = Unit
        override fun onPanelChanged(panel: Panel) = Unit
        override fun onCharacter(char: String) { lastCharacter = char }
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
