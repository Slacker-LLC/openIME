package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.voice.VoiceModelLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/** 26-key and nine-key bottom rows, the comma/period key and English letter case. */
@RunWith(AndroidJUnit4::class)
class KeyboardBottomRowInstrumentedTest {
    private class Recorder {
        val characters = mutableListOf<String>()
        val listener = Proxy.newProxyInstance(
            ImeKeyboardView.Listener::class.java.classLoader,
            arrayOf(ImeKeyboardView.Listener::class.java),
        ) { _, method, args ->
            when (method.name) {
                "voiceModelState" -> VoiceModelLifecycleState.COLD
                "onCharacter" -> { characters += args!![0] as String; null }
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

    @Test
    fun twentySixKeySpaceIsCenteredAndTheRowMirrors() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
        val space = settled(harness, keyboard, "key-space")
        val mode = settled(harness, keyboard, "key:mode")
        val punctuation = settled(harness, keyboard, "key-punctuation")
        val digits = settled(harness, keyboard, "key:123")
        val enter = settled(harness, keyboard, "key-enter")
        harness.awaitMain {
            val tolerance = 3f
            assertEquals(keyboard.width / 2f, space.centerX(keyboard), tolerance)
            assertEquals(mode.width.toFloat(), punctuation.width.toFloat(), tolerance)
            assertEquals(digits.width.toFloat(), enter.width.toFloat(), tolerance)
            assertTrue("the mode key must be narrower than the digits key", mode.width < digits.width)
            true
        }
    }

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
