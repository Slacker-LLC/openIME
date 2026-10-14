package llc.slacker.openime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeTheme
import llc.slacker.openime.theme.ImeTypographyTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/**
 * The clipboard and message suggestions the app builds for itself wear the
 * reference's quick-input rows (`input_toolbar_quickinput.xml:20-51`): flat
 * text at the candidate font with the glyph that says where it came from, and
 * the reference's own close key at the row's end.
 */
@RunWith(AndroidJUnit4::class)
class QuickInputRowInstrumentedTest {
    private val characters = mutableListOf<String>()
    private val listener = Proxy.newProxyInstance(
        ImeKeyboardView.Listener::class.java.classLoader,
        arrayOf(ImeKeyboardView.Listener::class.java),
    ) { _, method, args ->
        if (method.name == "onCharacter") characters += args!![0] as String
        if (method.returnType == java.lang.Boolean.TYPE) false else null
    } as ImeKeyboardView.Listener

    private fun withClipboard(text: String, test: (ImeKeyboardView, (String) -> Unit) -> Unit) {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val keyboard = harness.awaitMain { activity ->
                ImeKeyboardView(activity, listener).also {
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(it)
                }
            }
            try {
                harness.awaitMain { if (keyboard.width > 0) true else null }
                val clipboard = harness.awaitMain {
                    keyboard.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                }
                val copy: (String) -> Unit = { copied ->
                    harness.awaitMain {
                        clipboard.setPrimaryClip(ClipData.newPlainText("openime-test", copied))
                        true
                    }
                    Unit
                }
                harness.awaitMain {
                    keyboard.applyPersistedSettings(
                        newTheme = ImeTheme.IOS,
                        newAppearance = ImeAppearance.LIGHT,
                        sound = false,
                        haptic = true,
                        popup = true,
                        fuzzy = false,
                    )
                    true
                }
                copy(text)
                harness.awaitMain { keyboard.refreshSuggestions(); true }
                harness.awaitMain { test(keyboard, copy); true }
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
    fun theClipboardSuggestionIsTheReferencesOwnRow() {
        withClipboard("hello from the clipboard") { keyboard, _ ->
            val row = keyboard.findViewWithTag<View>("suggestion-row")
            assertNotNull("A copied text must offer a row", row)
            val label = keyboard.findViewWithTag<TextView>("suggestion-text")
            assertNotNull("The row carries the text itself", label)
            assertEquals(
                "The quick-input text reads at the candidate font",
                ImeTypographyTokens.CANDIDATE_SP,
                label!!.textSize / keyboard.resources.displayMetrics.scaledDensity,
                0.5f,
            )
            assertNotNull("and the glyph that says where it came from", keyboard.findViewWithTag<View>("suggestion-icon"))
            val tokens = ImeTheme.IOS.tokens(ImeAppearance.LIGHT)
            assertEquals(
                "`canditem_text` over the row (`input_toolbar_quickinput.xml:40`)",
                tokens.candidateText,
                label.currentTextColor,
            )
            val close = keyboard.findViewWithTag<View>("autofill-close")
            assertNotNull("The row ends with the reference's close key", close)
            assertEquals(View.VISIBLE, close.visibility)
            assertEquals(
                "The strip's back control belongs to the system's chips",
                View.GONE,
                keyboard.findViewWithTag<View>("association-back")?.visibility,
            )
        }
    }

    @Test
    fun tappingTheRowTypesTheTextAndTheCloseKeyDropsIt() {
        withClipboard("123456", { keyboard, copy ->
            characters.clear()
            keyboard.findViewWithTag<View>("suggestion-row")!!.performClick()
            assertTrue("A tap types the copied text", characters.contains("123456"))
            assertNull(
                "The row goes away with the tap",
                keyboard.findViewWithTag<View>("suggestion-row"),
            )
            // A later copy is a new suggestion, so the row is back.
            copy("654321")
            keyboard.refreshSuggestions()
            assertNotNull(
                "A fresh copy brings the row back",
                keyboard.findViewWithTag<View>("suggestion-row"),
            )
            keyboard.findViewWithTag<View>("autofill-close")!!.performClick()
            assertNull(
                "The close key drops the row for this text",
                keyboard.findViewWithTag<View>("suggestion-row"),
            )
            keyboard.refreshSuggestions()
            assertNull(
                "and keeps it away until the clipboard changes",
                keyboard.findViewWithTag<View>("suggestion-row"),
            )
        })
    }
}
