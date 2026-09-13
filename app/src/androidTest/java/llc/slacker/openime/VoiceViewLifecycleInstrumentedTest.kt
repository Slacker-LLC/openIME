package llc.slacker.openime

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class VoiceViewLifecycleInstrumentedTest {
    private class Recorder {
        var events: VoiceRecognitionEvents? = null
        var finals = 0
        var presses = 0
        val listener = Proxy.newProxyInstance(
            ImeKeyboardViewV2.Listener::class.java.classLoader,
            arrayOf(ImeKeyboardViewV2.Listener::class.java),
        ) { _, method, args ->
            when (method.name) {
                "voiceModelState" -> VoiceModelLifecycleState.COLD
                "startVoiceRecognition" -> { events = args!![1] as VoiceRecognitionEvents; null }
                "onVoiceFinal" -> { finals++; null }
                "onVoicePressChanged" -> { if (args!![0] == true) presses++; null }
                else -> null
            }
        } as ImeKeyboardViewV2.Listener
    }

    @Test
    fun queuedFinalCannotCommitAfterShutdown() {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val recorder = Recorder()
            lateinit var keyboard: ImeKeyboardViewV2
            harness.awaitMain { activity ->
                keyboard = ImeKeyboardViewV2(activity, recorder.listener)
                activity.findViewById<ViewGroup>(android.R.id.content).addView(keyboard)
                keyboard.startVoiceFromSpace()
                true
            }
            harness.awaitMain {
                val events = recorder.events ?: return@awaitMain null
                events.onFinal("stale test result")
                keyboard.shutdown()
                true
            }
            harness.awaitMain {
                assertEquals(0, recorder.finals)
                true
            }
        }
    }

    @Test
    fun detachingBeforeLongPressCancelsBothVoiceTimers() {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val recorder = Recorder()
            lateinit var keyboard: ImeKeyboardViewV2
            harness.awaitMain { activity ->
                keyboard = ImeKeyboardViewV2(activity, recorder.listener)
                activity.findViewById<ViewGroup>(android.R.id.content).addView(keyboard)
                true
            }
            harness.awaitMain {
                val space = keyboard.findViewWithTag<View>("key-space") ?: return@awaitMain null
                if (space.width == 0) return@awaitMain null
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, space.width / 2f, space.height / 2f, 0)
                space.dispatchTouchEvent(down)
                down.recycle()
                (keyboard.parent as ViewGroup).removeView(keyboard)
                true
            }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 250L)
            harness.awaitMain {
                assertEquals(0, recorder.presses)
                true
            }
        }
    }
}
