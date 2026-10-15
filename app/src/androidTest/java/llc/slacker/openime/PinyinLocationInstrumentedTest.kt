package llc.slacker.openime

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.core.PinyinLocation
import llc.slacker.openime.keyboard.ImeKeyboardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/**
 * 「输入拼音显示」的两个取值在键盘这一侧的表现：候选栏那一档键盘顶上留预编辑
 * 行，输入框那一档不留——拼音交给应用自己的输入框去显示，候选行照旧。
 */
@RunWith(AndroidJUnit4::class)
class PinyinLocationInstrumentedTest {
    private val listener = Proxy.newProxyInstance(
        ImeKeyboardView.Listener::class.java.classLoader,
        arrayOf(ImeKeyboardView.Listener::class.java),
    ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null } as ImeKeyboardView.Listener

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
                    keyboard.setPinyinLocation(PinyinLocation.CANDIDATE_BAR)
                    keyboard.shutdown()
                    (keyboard.parent as? ViewGroup)?.removeView(keyboard)
                    true
                }
            }
        }
    }

    private fun tap(harness: DirectActivityHarness<DebugKeyboardActivity>, view: View) {
        val downTime = SystemClock.uptimeMillis()
        harness.awaitMain {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN,
                view.width / 2f, view.height / 2f, 0,
            )
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            true
        }
        harness.awaitMain {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                view.width / 2f, view.height / 2f, 0,
            )
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            true
        }
        SystemClock.sleep(150)
    }

    private fun type(harness: DirectActivityHarness<DebugKeyboardActivity>, keyboard: ImeKeyboardView, keys: String) {
        keys.forEach { letter ->
            val key = harness.awaitMain {
                keyboard.findViewWithTag<View>("key:$letter")?.takeIf { it.width > 0 }
            }
            assertNotNull("The $letter key must be on the surface", key)
            tap(harness, key!!)
        }
    }

    /** (预编辑行是否显示着字, 行里的字)。 */
    private fun probeEditor(keyboard: ImeKeyboardView): Pair<Boolean, String> {
        val editor = keyboard.findViewWithTag<EditText>("pinyin-composition-editor")
        val text = editor?.text?.toString().orEmpty()
        val showing = editor != null && editor.visibility == View.VISIBLE && text.isNotEmpty()
        return showing to text
    }

    private fun awaitEditorText(
        harness: DirectActivityHarness<DebugKeyboardActivity>,
        keyboard: ImeKeyboardView,
        expected: String,
    ): String? = harness.awaitMain {
        probeEditor(keyboard).takeIf { it.first && it.second == expected }?.second
    }

    private fun editorStateNow(
        harness: DirectActivityHarness<DebugKeyboardActivity>,
        keyboard: ImeKeyboardView,
    ): Pair<Boolean, String> = harness.awaitMain { probeEditor(keyboard) }

    @Test
    fun theEditorRowCarriesThePinyinOnlyInTheCandidateBarMode() {
        withKeyboard { harness, keyboard ->
            harness.awaitMain { keyboard.setMode(KeyboardMode.PINYIN_26); true }
            type(harness, keyboard, "nihao")
            assertEquals("候选栏：预编辑行显示拼音", "nihao", awaitEditorText(harness, keyboard, "nihao"))

            harness.awaitMain { keyboard.setPinyinLocation(PinyinLocation.INPUT_BOX); true }
            val (showing, _) = editorStateNow(harness, keyboard)
            assertFalse("输入框：键盘上不再重复这一行", showing)
            val zone = harness.awaitMain { keyboard.findViewWithTag<View>("ime_toolbar") }
            assertTrue("候选行本身照旧占着位置", zone.height > 0)

            harness.awaitMain { keyboard.setPinyinLocation(PinyinLocation.CANDIDATE_BAR); true }
            assertEquals("切回候选栏，拼音回到键盘上", "nihao", awaitEditorText(harness, keyboard, "nihao"))
        }
    }

    @Test
    fun theSettingSurvivesARestartOfTheSurface() {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val first = harness.awaitMain { activity ->
                ImeKeyboardView(activity, listener).also {
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(it)
                }
            }
            harness.awaitMain { if (first.width > 0) true else null }
            harness.awaitMain {
                first.setMode(KeyboardMode.PINYIN_26)
                first.setPinyinLocation(PinyinLocation.INPUT_BOX)
                true
            }
            harness.awaitMain {
                first.shutdown()
                (first.parent as? ViewGroup)?.removeView(first)
                true
            }

            val second = harness.awaitMain { activity ->
                ImeKeyboardView(activity, listener).also {
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(it)
                }
            }
            try {
                harness.awaitMain { if (second.width > 0) true else null }
                harness.awaitMain { second.setMode(KeyboardMode.PINYIN_26); true }
                type(harness, second, "nihao")
                val (showing, _) = editorStateNow(harness, second)
                assertFalse("重新建一次键盘，设置还在", showing)
                // 打的字没丢：把它切回候选栏就能看见。
                harness.awaitMain { second.setPinyinLocation(PinyinLocation.CANDIDATE_BAR); true }
                assertEquals("nihao", awaitEditorText(harness, second, "nihao"))
            } finally {
                harness.awaitMain {
                    second.setPinyinLocation(PinyinLocation.CANDIDATE_BAR)
                    second.shutdown()
                    (second.parent as? ViewGroup)?.removeView(second)
                    true
                }
            }
        }
    }
}
