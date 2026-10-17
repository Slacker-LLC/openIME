package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.keyboard.ProductionKeyPolicy
import llc.slacker.openime.voice.VoiceModelLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/** 26-key and nine-key bottom rows, the comma/period key and English letter case. */
@RunWith(AndroidJUnit4::class)
class KeyboardBottomRowInstrumentedTest {
    private class Recorder {
        val characters = mutableListOf<String>()
        var enters = 0
        var newlines = 0
        val listener = Proxy.newProxyInstance(
            ImeKeyboardView.Listener::class.java.classLoader,
            arrayOf(ImeKeyboardView.Listener::class.java),
        ) { _, method, args ->
            when (method.name) {
                "voiceModelState" -> VoiceModelLifecycleState.COLD
                "onCharacter" -> { characters += args!![0] as String; null }
                "onEnter" -> { enters++; null }
                "onEnterNewline" -> { newlines++; null }
                else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
            }
        } as ImeKeyboardView.Listener
    }

    private fun withKeyboard(test: (DirectActivityHarness<DebugKeyboardActivity>, Recorder, ImeKeyboardView) -> Any?) {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val recorder = Recorder()
            val keyboard = harness.awaitMain { activity ->
                ImeKeyboardView(activity, recorder.listener).also {
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(it)
                }
            }
            try {
                harness.awaitMain { if (keyboard.width > 0) true else null }
                test(harness, recorder, keyboard)
            } finally {
                harness.awaitMain {
                    keyboard.shutdown()
                    (keyboard.parent as? ViewGroup)?.removeView(keyboard)
                    true
                }
            }
        }
    }

    private fun View.centerX(root: View): Float {
        val loc = IntArray(2).also(::getLocationOnScreen)
        val rootLoc = IntArray(2).also(root::getLocationOnScreen)
        return loc[0] - rootLoc[0] + width / 2f
    }

    private fun settled(harness: DirectActivityHarness<DebugKeyboardActivity>, keyboard: ImeKeyboardView, tag: String): View =
        harness.awaitMain { keyboard.findViewWithTag<View>(tag)?.takeIf { it.width > 0 } }

    /** The original five keys keep their widths and the space bar stays centred. */
    @Test
    fun theBottomRowKeepsTheOriginalFiveKeyWidths() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
        val space = settled(harness, keyboard, "key-space")
        val weights = ProductionKeyPolicy.twentySixKeyBottomRowWeights()
        val shares = listOf(
            "key:123" to weights.leftOuter,
            "key-punctuation" to weights.leftInner,
            "key-space" to weights.space,
            "key:mode" to weights.rightInner,
            "key-enter" to weights.rightOuter,
        )
        harness.awaitMain {
            val row = space.parent as ViewGroup
            assertEquals("the bottom row has five keys", 5, row.childCount)
            assertNull(keyboard.findViewWithTag<View>("key-symbol-comma"))
            assertNull(keyboard.findViewWithTag<View>("key-symbol-period"))
            val unit = row.width / shares.sumOf { it.second.toDouble() }.toFloat()
            shares.forEach { (tag, weight) ->
                val key = keyboard.findViewWithTag<View>(tag)
                assertNotNull("$tag must be in the bottom row", key)
                assertEquals(
                    "$tag takes its share of the row",
                    weight * unit,
                    key!!.width.toFloat(),
                    2f,
                )
            }
            val before = shares.takeWhile { it.first != "key-space" }
                .sumOf { it.second.toDouble() }.toFloat()
            assertEquals(
                "the space bar stays centred",
                (before + weights.space / 2f) * unit,
                space.centerX(row),
                2f,
            )
            true
        }
    }

    @Test
    fun theSideColumnsKeepTheirOriginalKeysAndHeights() = withKeyboard { harness, _, keyboard ->
        listOf(KeyboardMode.PINYIN_9, KeyboardMode.STROKE).forEach { mode ->
            harness.awaitMain { keyboard.setMode(KeyboardMode.DIGITS); keyboard.setMode(mode); true }
            settled(harness, keyboard, "key-space")
            val ok = harness.awaitMain {
                val zero = keyboard.findViewWithTag<View>("key-9:0")
                if (mode == KeyboardMode.PINYIN_9) assertNotNull("nine-key keeps its separate zero", zero)
                else assertNull("stroke keeps its three-key side column", zero)
                val retype = keyboard.findViewWithTag<View>("key-retype")
                val enter = keyboard.findViewWithTag<View>("key-enter")
                val backspace = keyboard.findViewWithTag<View>("key-backspace")
                assertNotNull("$mode retype", retype)
                assertNotNull("$mode send", enter)
                assertNotNull("$mode delete", backspace)
                assertEquals("$mode delete matches retype", retype.height.toFloat(), backspace.height.toFloat(), 2f)
                assertEquals("$mode send keeps the same height", retype.height.toFloat(), enter.height.toFloat(), 2f)
                if (zero != null) assertEquals("zero matches the other keys", retype.height.toFloat(), zero.height.toFloat(), 2f)
                true
            }
            check(ok)
        }
    }

    @Test
    fun theIndentedLetterRowKeepsTheTopRowsKeyPitch() = withKeyboard { harness, _, keyboard ->
        listOf(KeyboardMode.PINYIN_26, KeyboardMode.ENGLISH_26).forEach { mode ->
            harness.awaitMain { keyboard.setMode(mode); true }
            settled(harness, keyboard, "key:q")
            harness.awaitMain<Boolean> {
                val q = keyboard.findViewWithTag<View>("key:q")
                val w = keyboard.findViewWithTag<View>("key:w")
                val a = keyboard.findViewWithTag<View>("key:a")
                "asdfghjkl".forEach { letter ->
                    val key = keyboard.findViewWithTag<View>("key:$letter")
                    assertEquals("$mode $letter keeps the top row's width", q.width.toFloat(), key.width.toFloat(), 2f)
                }
                assertEquals("$mode keeps the half-key indent", q.centerX(keyboard) + q.width / 2f, a.centerX(keyboard), 2f)
                val s = keyboard.findViewWithTag<View>("key:s")
                assertEquals("$mode keeps the top row's pitch", w.centerX(keyboard) - q.centerX(keyboard), s.centerX(keyboard) - a.centerX(keyboard), 2f)
                true
            }
        }
        true
    }

    /**
     * The reference's merged key carries the full stop in its own language and
     * the mark it hides in its corner: Chinese draws `text="，"` with
     * `ficon_text="。"` (`input_kbd_pinyin26.xml:145-152`), English keeps
     * the Beta10 comma-first order, so a
     * long press commits the corner mark in both.
     */
    @Test
    fun commaKeyTapsCommaAndLongPressesPeriodInBothLanguages() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
        val zh = settled(harness, keyboard, "key-punctuation")
        harness.awaitMain { assertTrue(zh.performClick()); assertTrue(zh.performLongClick()); true }
        assertEquals(listOf("，", "。"), recorder.characters.toList())

        recorder.characters.clear()
        harness.awaitMain { keyboard.setMode(KeyboardMode.ENGLISH_26); true }
        val en = harness.awaitMain { keyboard.findViewWithTag<View>("key-punctuation")?.takeIf { it.width > 0 } }
        harness.awaitMain { assertTrue(en.performClick()); assertTrue(en.performLongClick()); true }
        assertEquals(listOf(",", "."), recorder.characters.toList())
    }

    /**
     * 发送键的长按是换行：参考自己的设置文案就是这句（`enable_key_enter_sub_title`
     * 「长按「发送」可换行。开关支持App范围」）。只有「发送」这一面有长按——建立中的
     * 合成读「确定」，其余动作读它们自己的动作词，长按仍是各自那一次按压。
     */
    @Test
    fun theSendKeyLongPressesToALineBreak() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain {
            keyboard.editorOptionsOverride = EditorInfo.IME_ACTION_SEND
            // Rebuild the 26-key rows so the enter key is created under the send face.
            keyboard.setMode(KeyboardMode.PINYIN_9)
            keyboard.setMode(KeyboardMode.PINYIN_26)
            true
        }
        val send = settled(harness, keyboard, "key-enter")
        harness.awaitMain {
            assertEquals("发送，长按换行", send.contentDescription.toString())
            assertTrue("the send key must take a long press", send.performLongClick())
            assertEquals("a long press must not send", 0, recorder.enters)
            assertEquals(1, recorder.newlines)
            true
        }

        harness.awaitMain {
            keyboard.editorOptionsOverride = EditorInfo.IME_ACTION_SEARCH
            keyboard.setMode(KeyboardMode.ENGLISH_26)
            keyboard.setMode(KeyboardMode.PINYIN_26)
            true
        }
        val search = settled(harness, keyboard, "key-enter")
        harness.awaitMain {
            assertEquals("搜索", search.contentDescription.toString())
            assertFalse("only 发送 takes a long press", search.performLongClick())
            assertEquals(1, recorder.newlines)
            true
        }
    }

    @Test
    fun englishKeysShowLowercaseLetters() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.ENGLISH_26); true }
        harness.awaitMain {
            val q = keyboard.findViewWithTag<View>("key:q") ?: return@awaitMain null
            val labels = mutableListOf<String>()
            fun collect(view: View) {
                if (view is TextView) labels += view.text.toString()
                if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
            collect(q)
            assertTrue("English keys must show lowercase, got $labels", "q" in labels && "Q" !in labels)
            true
        }
    }

    @Test
    fun nineKeySpaceIsWiderThanTheSideKeys() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_9); true }
        val space = settled(harness, keyboard, "key-space")
        val mode = settled(harness, keyboard, "key:mode")
        val digits = settled(harness, keyboard, "key:123")
        harness.awaitMain {
            assertTrue("space ${space.width} must be wider than 2x the side keys ${digits.width}", space.width > digits.width * 2)
            assertEquals(digits.width.toFloat(), mode.width.toFloat(), 3f)
            true
        }
    }
}
