package llc.slacker.openime

import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.core.Panel
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeReferenceSizing
import llc.slacker.openime.voice.VoiceModelLifecycleState
import llc.slacker.openime.widget.ImeKeyView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/**
 * Every key cap is painted inside its cell by that key class's own margins: the
 * 26-key board's keys keep 2.25 units sideways and 4.75 up and down
 * (`cls_keyboard_button_margin`, `style.xml:289-290`), the nine-key family —
 * nine-key, stroke and the number page — 2.5 and 2.75
 * (`cls_keyboard9_button_margin`, `style.xml:373-374`), and the 26-key board's
 * two big side keys clear the letter column by 8 units on their inner side
 * (`cls_button_left_big`, `cls_button_right_big`, `style.xml:907-912`).
 */
@RunWith(AndroidJUnit4::class)
class KeyMarginsInstrumentedTest {
    private val listener = Proxy.newProxyInstance(
        ImeKeyboardView.Listener::class.java.classLoader,
        arrayOf(ImeKeyboardView.Listener::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "voiceModelState" -> VoiceModelLifecycleState.COLD
            else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
        }
    } as ImeKeyboardView.Listener

    private fun withKeyboard(test: (DirectActivityHarness<DebugKeyboardActivity>, ImeKeyboardView) -> Unit) {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val keyboard = harness.awaitMain { activity ->
                ImeKeyboardView(activity, listener).also {
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(it)
                }
            }
            try {
                harness.awaitMain { if (keyboard.width > 0) true else null }
                harness.awaitMain { test(harness, keyboard); true }
            } finally {
                harness.awaitMain {
                    keyboard.shutdown()
                    (keyboard.parent as? ViewGroup)?.removeView(keyboard)
                    true
                }
            }
        }
    }

    /** One reference unit in pixels, the measure `ImeKeyboardView.dp` uses. */
    private fun unitOf(keyboard: ImeKeyboardView): Float =
        keyboard.resources.displayMetrics.density * ImeReferenceSizing.scale(keyboard.context, keyboard.width)

    /** The margins the cap is painted with: the background reports its insets as padding. */
    private fun capInsets(key: View): Rect = Rect().also {
        (key.background as InsetDrawable).getPadding(it)
    }

    private fun assertMargins(
        keyboard: ImeKeyboardView,
        tag: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val key = keyboard.findViewWithTag<View>(tag)
        assertNotNull("$tag must be on the board", key)
        assertMargins(keyboard, key!!, tag, left, top, right, bottom)
    }

    private fun assertMargins(
        keyboard: ImeKeyboardView,
        key: View,
        label: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val unit = unitOf(keyboard)
        val insets = capInsets(key)
        assertEquals("$label keeps the reference's left margin", Math.round(left * unit).toLong(), insets.left.toLong())
        assertEquals("$label keeps the reference's top margin", Math.round(top * unit).toLong(), insets.top.toLong())
        assertEquals("$label keeps the reference's right margin", Math.round(right * unit).toLong(), insets.right.toLong())
        assertEquals("$label keeps the reference's bottom margin", Math.round(bottom * unit).toLong(), insets.bottom.toLong())
    }

    private fun allViews(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(allViews(root.getChildAt(index)))
        }
    }

    @Test
    fun theTwentySixKeyBoardKeepsTheReferenceKeyMargins() {
        withKeyboard { harness, keyboard ->
            harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
            val x = ImeGeometryTokens.KEY_MARGIN_X_DP
            val y = ImeGeometryTokens.KEY_MARGIN_Y_DP
            listOf("key-space", "key:123", "key:mode", "key-enter", "key-punctuation", "key:z").forEach { tag ->
                assertMargins(keyboard, tag, x, y, x, y)
            }
        }
    }

    @Test
    fun theBigSideKeysClearTheLetterColumnByTheirOwnMargin() {
        withKeyboard { harness, keyboard ->
            harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
            val inner = ImeGeometryTokens.KEY_INNER_MARGIN_DP
            val x = ImeGeometryTokens.KEY_MARGIN_X_DP
            val y = ImeGeometryTokens.KEY_MARGIN_Y_DP
            // 分词 on the left clears the letters to its right, 删除 on the right to its left.
            assertMargins(keyboard, "key-shift", x, y, inner, y)
            assertMargins(keyboard, "key-backspace", inner, y, x, y)
        }
    }

    @Test
    fun theNineKeyFamilyKeepsItsTighterMargin() {
        withKeyboard { harness, keyboard ->
            harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_9); true }
            val x = ImeGeometryTokens.NINE_KEY_MARGIN_X_DP
            val y = ImeGeometryTokens.NINE_KEY_MARGIN_Y_DP
            listOf("key-space", "key:123", "key:mode", "key-enter", "key-9:2").forEach { tag ->
                assertMargins(keyboard, tag, x, y, x, y)
            }
        }
    }

    @Test
    fun theStrokeAndNumberBoardsKeepTheNineKeyMargin() {
        withKeyboard { harness, keyboard ->
            val x = ImeGeometryTokens.NINE_KEY_MARGIN_X_DP
            val y = ImeGeometryTokens.NINE_KEY_MARGIN_Y_DP
            harness.awaitMain { keyboard.setMode(KeyboardMode.STROKE); true }
            assertMargins(keyboard, "key-space", x, y, x, y)
            harness.awaitMain { keyboard.setMode(KeyboardMode.DIGITS); true }
            assertMargins(keyboard, "key:5", x, y, x, y)
        }
    }

    @Test
    fun aPanelKeepsTheKeyMarginWhateverBoardOpenedIt() {
        withKeyboard { harness, keyboard ->
            harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_9); true }
            harness.awaitMain {
                keyboard.showPanel(Panel.SYMBOLS)
                true
            }
            // A panel's keys are the reference's own 26-key key whatever board
            // it was opened from (`cls_symbol_keyboard_button`, `style.xml:1332`).
            val symbol = allViews(keyboard)
                .filterIsInstance<ImeKeyView>()
                .firstOrNull { it.isShown && it.width > 0 && capInsets(it).left > 0 }
            assertNotNull("the symbol page carries keys", symbol)
            assertMargins(
                keyboard,
                symbol!!,
                "a symbol page key",
                ImeGeometryTokens.KEY_MARGIN_X_DP,
                ImeGeometryTokens.KEY_MARGIN_Y_DP,
                ImeGeometryTokens.KEY_MARGIN_X_DP,
                ImeGeometryTokens.KEY_MARGIN_Y_DP,
            )
        }
    }
}
