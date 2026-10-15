package llc.slacker.openime

import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.core.ImeState
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

/** Key caps keep their original uniform six-unit gap on every board. */
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
                test(harness, keyboard)
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
        fun pixels(margin: Float): Int = if (margin == 3f) kotlin.math.round(6 * unit).toInt() / 2 else Math.round(margin * unit)
        val insets = capInsets(key)
        assertEquals("$label keeps its left margin", pixels(left).toLong(), insets.left.toLong())
        assertEquals("$label keeps its top margin", pixels(top).toLong(), insets.top.toLong())
        assertEquals("$label keeps its right margin", pixels(right).toLong(), insets.right.toLong())
        assertEquals("$label keeps its bottom margin", pixels(bottom).toLong(), insets.bottom.toLong())
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
    fun theExpandedCandidatesPageKeepsTheReferenceSideInset() {
        withKeyboard { harness, keyboard ->
            val density = keyboard.resources.displayMetrics.density
            val scale = ImeReferenceSizing.scale(keyboard.context)
            val centering = maxOf(0, (keyboard.width - (minOf(600f, 390f * scale) * density).toInt()) / 2)
            // The page carries its own side figure whatever board opened it
            // (`cls_input_kbd_morecand_inset_adaptive`, `style.xml:731-733`).
            val expected = centering + Math.round(ImeGeometryTokens.MORE_CAND_SIDE_INSET_DP * density * scale)
            harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
            harness.awaitMain {
                keyboard.renderState(ImeState(composition = "nihao", candidates = listOf("你好", "您好")))
                true
            }
            harness.awaitMain { keyboard.findViewWithTag<View>("candidate-expand").performClick(); true }
            val overlay = harness.awaitMain {
                keyboard.findViewWithTag<ViewGroup>("candidate-overlay")
                    ?.takeIf { it.visibility == View.VISIBLE && it.paddingLeft > 0 }
            }
            assertNotNull("the expanded page opens", overlay)
            assertEquals(
                "the expanded page keeps the reference's side inset",
                expected.toLong(),
                overlay!!.paddingLeft.toLong(),
            )
            assertEquals("the expanded page insets both sides equally", overlay.paddingLeft, overlay.paddingRight)
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
            // The page is laid out on the frame after it opens, so wait for its
            // first key to have a size.
            val symbol = harness.awaitMain {
                allViews(keyboard)
                    .filterIsInstance<ImeKeyView>()
                    .firstOrNull {
                        it.isShown && it.width > 0 && it.background is InsetDrawable && capInsets(it).left > 0
                    }
            }
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
