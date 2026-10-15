package llc.slacker.openime

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.voice.VoiceModelLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/**
 * Docked 「键盘调节」: long-press the toolbar row, then drag up or down to set the
 * keyboard height. The drag previews on every sample but writes the height once.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardHeightDragInstrumentedTest {
    private class Recorder {
        lateinit var keyboard: ImeKeyboardView
        /** Heights the keyboard reported as committed, in order. */
        val heights = mutableListOf<Int>()
        val listener = Proxy.newProxyInstance(
            ImeKeyboardView.Listener::class.java.classLoader,
            arrayOf(ImeKeyboardView.Listener::class.java),
        ) { _, method, args ->
            when (method.name) {
                "voiceModelState" -> VoiceModelLifecycleState.COLD
                "onKeyboardHeightChanged" -> { heights += args!![0] as Int; null }
                "onFloatingKeyboardChanged" -> {
                    keyboard.setFloatingWindowMode(args!![0] as Boolean)
                    null
                }
                else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
            }
        } as ImeKeyboardView.Listener
    }

    private fun withKeyboard(
        test: (DirectActivityHarness<DebugKeyboardActivity>, Recorder, ImeKeyboardView) -> Any?,
    ) {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val recorder = Recorder()
            val keyboard = harness.awaitMain { activity ->
                ImeKeyboardView(activity, recorder.listener).also {
                    recorder.keyboard = it
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

    private fun touch(view: View, action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun theHeightDragPreviewsEverySampleAndCommitsOnce() = withKeyboard { harness, recorder, keyboard ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = harness.awaitMain {
            ImeSettingsRepository.loadKeyboardHeightPercent(context, landscape = false)
        }
        // Drag away from the clamp ceiling, so every sample counts and the
        // direction of the expected change is known.
        val step = if (start > 130) 20f else -20f
        val samples = 12
        val downTime = SystemClock.uptimeMillis()
        val zone = harness.awaitMain { keyboard.findViewWithTag<View>("ime_toolbar") }
        // Raw touch coordinates are screen coordinates ("the original location of
        // the event on the screen, before it had been adjusted for the containing
        // window", MotionEvent.getRawX), while the IME window does not sit at the
        // screen origin: the press belongs at the row's own screen position, which
        // is what ImeTopZone.isInsideToolbar gates the docked drag on. Synthetic
        // events carry raw == x/y, so this test dispatches to the zone itself and
        // presses where the row is on screen.
        val (pressX, pressY) = harness.awaitMain {
            val row = requireNotNull(keyboard.findViewWithTag<View>("toolbar-row"))
            check(row.height > 0) { "The toolbar row has not been laid out" }
            val location = IntArray(2).also { row.getLocationOnScreen(it) }
            (location[0] + row.width / 2f) to (location[1] + row.height / 2f)
        }
        val startHeight = harness.awaitMain { keyboard.desiredWindowHeightPx() }
        val firstKey = harness.awaitMain { keyboard.findViewWithTag<View>("key:a") }
        harness.awaitMain { touch(zone, MotionEvent.ACTION_DOWN, downTime, pressX, pressY); true }
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 250L)
        val previewHeight = harness.awaitMain {
            var y = pressY
            repeat(samples) {
                y += step
                touch(zone, MotionEvent.ACTION_MOVE, downTime, pressX, y)
            }
            assertTrue(
                "Dragging the toolbar must resize the keyboard while the finger moves",
                keyboard.desiredWindowHeightPx() != startHeight,
            )
            // One main-thread turn, [samples] samples: the preview coalesces the
            // rebuild onto the next frame instead of rebuilding per sample.
            assertSame(
                "A drag must not rebuild the key rows sample by sample",
                firstKey,
                keyboard.findViewWithTag("key:a"),
            )
            assertTrue("A drag must not report a height before it ends", recorder.heights.isEmpty())
            assertEquals(
                "A drag must not persist a height before it ends",
                start,
                ImeSettingsRepository.loadKeyboardHeightPercent(context, landscape = false),
            )
            checkNotNull(keyboard.desiredWindowHeightPx())
        }
        harness.awaitMain {
            touch(zone, MotionEvent.ACTION_UP, downTime, pressX, pressY + step * samples)
            true
        }
        harness.awaitMain {
            assertEquals("Releasing the drag reports the height exactly once", 1, recorder.heights.size)
            val committed = recorder.heights.single()
            assertEquals(
                "Releasing the drag persists the height it reports",
                committed,
                ImeSettingsRepository.loadKeyboardHeightPercent(context, landscape = false),
            )
            assertTrue(
                "The drag must move the height the way the finger went ($start -> $committed)",
                if (step < 0) committed > start else committed < start,
            )
            assertEquals(
                "The height that is persisted must be the one on screen",
                previewHeight,
                keyboard.desiredWindowHeightPx(),
            )
            true
        }
    }
}
