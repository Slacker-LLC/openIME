package llc.slacker.openime

import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.core.Panel
import llc.slacker.openime.floating.FloatingWindowController
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.voice.VoiceModelLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/** Game-oriented floating keyboard: compact toolbar, long-press drag, portrait-sized landscape. */
@RunWith(AndroidJUnit4::class)
class FloatingGameKeyboardInstrumentedTest {
    private class Recorder {
        lateinit var keyboard: ImeKeyboardView
        val drags = mutableListOf<Pair<Float, Float>>()
        val floatingChanges = mutableListOf<Boolean>()
        val listener = Proxy.newProxyInstance(
            ImeKeyboardView.Listener::class.java.classLoader,
            arrayOf(ImeKeyboardView.Listener::class.java),
        ) { _, method, args ->
            when (method.name) {
                "voiceModelState" -> VoiceModelLifecycleState.COLD
                "onFloatingKeyboardChanged" -> {
                    floatingChanges += args!![0] as Boolean
                    keyboard.setFloatingWindowMode(args[0] as Boolean)
                    null
                }
                "onFloatingKeyboardDragged" -> {
                    drags += (args!![0] as Float) to (args[1] as Float)
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

    private fun View.visibleByTag(tag: String): Boolean =
        findViewWithTag<View>(tag)?.visibility == View.VISIBLE

    private fun View.toolbarIconByDescription(description: String): View? {
        val row = findViewWithTag<ViewGroup>("toolbar-row") ?: return null
        return (0 until row.childCount).map(row::getChildAt)
            .firstOrNull { it.contentDescription == description }
    }

    @Test
    fun floatingToolbarDropsTextEditAndUndoAndAddsQuickPhrases() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain {
            assertTrue("Docked toolbar keeps the clipboard", keyboard.visibleByTag("clipboard-toolbar"))
            assertTrue("Docked toolbar keeps text editing", keyboard.toolbarIconByDescription("文本编辑")!!.visibility == View.VISIBLE)
            assertTrue("Docked toolbar keeps undo", keyboard.visibleByTag("undo-toolbar"))
            assertFalse("Docked toolbar has no quick-phrase shortcut", keyboard.visibleByTag("quick-phrase-toolbar"))

            keyboard.setFloatingWindowMode(true)
            assertTrue("Floating toolbar exposes quick phrases", keyboard.visibleByTag("quick-phrase-toolbar"))
            assertFalse(keyboard.visibleByTag("clipboard-toolbar"))
            assertFalse(keyboard.visibleByTag("undo-toolbar"))
            assertEquals(View.GONE, keyboard.toolbarIconByDescription("文本编辑")!!.visibility)
            assertTrue("Emoji stays", keyboard.toolbarIconByDescription("表情")!!.visibility == View.VISIBLE)
            assertTrue("Keyboard switch stays", keyboard.visibleByTag("keyboard-selector"))

            keyboard.setFloatingWindowMode(false)
            assertTrue("Docking restores the full toolbar", keyboard.visibleByTag("clipboard-toolbar"))
            assertTrue(keyboard.toolbarIconByDescription("文本编辑")!!.visibility == View.VISIBLE)
            assertFalse(keyboard.visibleByTag("quick-phrase-toolbar"))
            true
        }
    }

    @Test
    fun quickPhraseShortcutOpensThePhraseTab() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.setFloatingWindowMode(true)
            assertTrue(keyboard.findViewWithTag<View>("quick-phrase-toolbar").performClick())
            assertEquals(Panel.CLIPBOARD, keyboard.currentPanel())
            assertNotNull(
                "The phrase tab shows its add button, not the clipboard refresh",
                keyboard.findViewWithTag<View>("quick-phrase-add"),
            )
            assertNull(keyboard.findViewWithTag<View>("clipboard-refresh"))
            true
        }
    }

    private fun touch(view: View, action: Int, downTime: Long, x: Float, y: Float): Boolean {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        return try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun longPressOnTopZoneDragsTheFloatingKeyboardWithoutDockingOrClicking() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain { keyboard.setFloatingWindowMode(true); true }
        val topZone = harness.awaitMain { keyboard.findViewWithTag<View>("ime_toolbar") }
        val downTime = SystemClock.uptimeMillis()
        // Press on an icon, so a leaked click would be visible as a panel opening.
        val (x, y) = harness.awaitMain {
            val icon = keyboard.findViewWithTag<View>("quick-phrase-toolbar")
            val loc = IntArray(2).also { icon.getLocationInWindow(it) }
            val zoneLoc = IntArray(2).also { topZone.getLocationInWindow(it) }
            (loc[0] - zoneLoc[0] + icon.width / 2f) to (loc[1] - zoneLoc[1] + icon.height / 2f)
        }
        harness.awaitMain { touch(topZone, MotionEvent.ACTION_DOWN, downTime, x, y); true }
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 250L)
        harness.awaitMain {
            touch(topZone, MotionEvent.ACTION_MOVE, downTime, x + 30f, y + 20f)
            touch(topZone, MotionEvent.ACTION_MOVE, downTime, x + 90f, y + 60f)
            touch(topZone, MotionEvent.ACTION_UP, downTime, x + 90f, y + 60f)
            true
        }
        harness.awaitMain {
            assertTrue("Long-press drag must move the window", recorder.drags.isNotEmpty())
            val totalX = recorder.drags.sumOf { it.first.toDouble() }
            val totalY = recorder.drags.sumOf { it.second.toDouble() }
            assertEquals("Drag deltas follow the finger after the grab point", 60.0, totalX, 1.0)
            assertEquals(40.0, totalY, 1.0)
            assertTrue("Releasing a drag must not dock the keyboard", recorder.floatingChanges.isEmpty())
            assertEquals("A drag on an icon must not open its panel", Panel.NONE, keyboard.currentPanel())
            true
        }
    }

    @Test
    fun shortTapOnTopZoneStillClicksAndDoesNotDrag() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain { keyboard.setFloatingWindowMode(true); true }
        harness.awaitMain {
            assertTrue(keyboard.findViewWithTag<View>("quick-phrase-toolbar").performClick())
            assertTrue(recorder.drags.isEmpty())
            assertEquals(Panel.CLIPBOARD, keyboard.currentPanel())
            true
        }
    }

    @Test
    fun floatingWindowUsesPortraitWidthOnLandscapeScreen() = withKeyboard { harness, _, keyboard ->
        val controller = harness.awaitMain { activity ->
            val real = keyboard.resources
            val landscape = DisplayMetrics().apply {
                setTo(real.displayMetrics)
                // A landscape phone: the long side is the width.
                widthPixels = maxOf(real.displayMetrics.widthPixels, real.displayMetrics.heightPixels)
                heightPixels = minOf(real.displayMetrics.widthPixels, real.displayMetrics.heightPixels)
            }
            @Suppress("DEPRECATION")
            val resources = Resources(real.assets, landscape, real.configuration)
            FloatingWindowController(
                resources = resources,
                mainHandler = Handler(Looper.getMainLooper()),
                windowProvider = { activity.window },
                keyboardHeightPx = { 600 },
                floatingWidthPercent = { 88 },
                floatingOpacityPercent = { 100 },
            ).also { it.enable() }
        }
        try {
            harness.awaitMain { activity ->
                val metrics = activity.resources.displayMetrics
                val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
                val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
                val expected = minOf((shortSide * 0.88f).toInt(), (420 * metrics.density).toInt())
                val width = activity.window.attributes.width
                // scheduleLayout runs on a posted message; poll until it applied.
                if (width != expected) return@awaitMain null
                assertTrue(
                    "Landscape floating width ($width) must follow the portrait width, not the $longSide px long side",
                    width < longSide * 0.6f,
                )
                true
            }
        } finally {
            controller.restore()
        }
    }
}
