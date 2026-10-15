package llc.slacker.openime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.keyboard.QuickInputRowPolicy
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeReferenceSizing
import llc.slacker.openime.theme.ImeTheme
import llc.slacker.openime.theme.ImeTypographyTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun withClipboard(
        text: String,
        test: (DirectActivityHarness<DebugKeyboardActivity>, ImeKeyboardView, (String) -> Unit) -> Unit,
    ) {
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
                    // The quick-input rows belong to a real editor session;
                    // this view has none, so it declares the options an editor
                    // without the no-personalisation flag would give it.
                    keyboard.editorOptionsOverride = EditorInfo.IME_ACTION_NONE
                    true
                }
                copy(text)
                harness.awaitMain { keyboard.refreshSuggestions(); true }
                // Let the row place itself: the assertions measure a laid-out glyph.
                harness.awaitMain {
                    val icon = keyboard.findViewWithTag<View>("suggestion-icon")
                    if ((icon?.height ?: 0) > 0) true else null
                }
                test(harness, keyboard, copy)
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
        withClipboard("hello from the clipboard") { harness, keyboard, _ ->
            val row = harness.awaitMain { keyboard.findViewWithTag<View>("suggestion-row") }
            assertNotNull("A copied text must offer a row", row)
            val label = harness.awaitMain { keyboard.findViewWithTag<TextView>("suggestion-text") }
            assertNotNull("The row carries the text itself", label)
            // Every text outside a key cap is drawn at the reference scale's
            // size, so the row reads at the candidate font as drawn.
            assertEquals(
                "The quick-input text reads at the candidate font",
                ImeTypographyTokens.CANDIDATE_SP *
                    ImeReferenceSizing.scale(keyboard.context, keyboard.width),
                harness.awaitMain { label!!.textSize / keyboard.resources.displayMetrics.scaledDensity },
                0.5f,
            )
            assertNotNull(
                "and the glyph that says where it came from",
                harness.awaitMain { keyboard.findViewWithTag<View>("suggestion-icon") },
            )
            val badge = harness.awaitMain { keyboard.findViewWithTag<View>("suggestion-icon")!! }
            assertEquals(
                "The glyph rides at `fi_toolbar_tip_superscript` against the candidate font",
                (label!!.textSize * QuickInputRowPolicy.BADGE_FONT_RATIO + 0.5f).toInt().toLong(),
                badge.height.toLong(),
            )
            assertTrue(
                "as a superscript above the text, not a centred icon",
                badge.top - row!!.top < row.height / 2,
            )
            val tokens = ImeTheme.IOS.tokens(ImeAppearance.LIGHT)
            assertEquals(
                "`canditem_text` over the row (`input_toolbar_quickinput.xml:40`)",
                tokens.candidateText,
                label.currentTextColor,
            )
            val close = harness.awaitMain { keyboard.findViewWithTag<View>("autofill-close") }
            assertNotNull("The row ends with the reference's close key", close)
            assertEquals(View.VISIBLE, harness.awaitMain { close!!.visibility })
            assertFalse(
                "The strip's back control belongs to the system's chips",
                harness.awaitMain {
                    keyboard.findViewWithTag<View>("association-back")?.visibility == View.VISIBLE
                },
            )
        }
    }

    @Test
    fun tappingTheRowTypesTheTextAndTheCloseKeyDropsIt() {
        withClipboard("123456") { harness, keyboard, copy ->
            characters.clear()
            harness.awaitMain {
                keyboard.findViewWithTag<View>("suggestion-row")!!.performClick()
                true
            }
            assertTrue("A tap types the copied text", characters.contains("123456"))
            assertTrue(
                "The row goes away with the tap",
                harness.awaitMain { keyboard.findViewWithTag<View>("suggestion-row") == null },
            )
            // A later copy is a new suggestion, so the row is back.
            copy("654321")
            harness.awaitMain { keyboard.refreshSuggestions(); true }
            assertNotNull(
                "A fresh copy brings the row back",
                harness.awaitMain { keyboard.findViewWithTag<View>("suggestion-row") },
            )
            harness.awaitMain {
                keyboard.findViewWithTag<View>("autofill-close")!!.performClick()
                true
            }
            assertTrue(
                "The close key drops the row for this text",
                harness.awaitMain { keyboard.findViewWithTag<View>("suggestion-row") == null },
            )
            harness.awaitMain { keyboard.refreshSuggestions(); true }
            assertTrue(
                "and keeps it away until the clipboard changes",
                harness.awaitMain { keyboard.findViewWithTag<View>("suggestion-row") == null },
            )
        }
    }
}
