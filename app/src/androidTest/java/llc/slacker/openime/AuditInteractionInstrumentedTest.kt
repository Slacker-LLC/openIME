package llc.slacker.openime

import android.os.SystemClock
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class AuditInteractionInstrumentedTest {
    private class Recorder {
        lateinit var keyboard: ImeKeyboardViewV2
        var events: VoiceRecognitionEvents? = null
        val finals = mutableListOf<String>()
        val fuzzyChanges = mutableListOf<Boolean>()
        var starts = 0
        var stops = 0
        var cancels = 0
        var spaces = 0
        var backspaces = 0
        var clears = 0
        val listener = Proxy.newProxyInstance(
            ImeKeyboardViewV2.Listener::class.java.classLoader,
            arrayOf(ImeKeyboardViewV2.Listener::class.java),
        ) { _, method, args ->
            when (method.name) {
                "voiceModelState" -> VoiceModelLifecycleState.COLD
                "startVoiceRecognition" -> {
                    starts++
                    events = args!![1] as VoiceRecognitionEvents
                    null
                }
                "stopVoiceRecognition" -> { stops++; null }
                "cancelVoiceRecognition" -> { cancels++; null }
                "onVoiceFinal" -> { finals.add(args!![0] as String); null }
                "onVoiceToggle" -> { keyboard.startVoiceFromSpace(); null }
                "onVoicePressChanged" -> {
                    if (args!![0] == true) keyboard.startVoiceFromSpace()
                    else keyboard.stopVoiceFromSpace()
                    null
                }
                "onSpace" -> { spaces++; null }
                "onBackspace" -> { backspaces++; null }
                "onClearAll" -> { clears++; null }
                "onFuzzyChanged" -> { fuzzyChanges.add(args!![0] as Boolean); null }
                else -> null
            }
        } as ImeKeyboardViewV2.Listener
    }

    private fun withKeyboard(
        test: (DirectActivityHarness<DebugKeyboardActivity>, Recorder, ImeKeyboardViewV2) -> Any?,
    ) {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val recorder = Recorder()
            val keyboard = harness.awaitMain { activity ->
                ImeKeyboardViewV2(activity, recorder.listener).also {
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

    @Test
    fun queuedFinalAfterManualInputCancellationCannotCommit() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain { keyboard.startVoiceFromSpace(); true }
        harness.awaitMain {
            val events = recorder.events ?: return@awaitMain null
            // Queue onFinal's UI work, then invalidate the session in the same main-thread turn.
            events.onFinal("stale result after manual input")
            keyboard.cancelVoiceForManualInput()
            true
        }
        harness.awaitMain {
            assertTrue("The cancelled session must not commit its queued final", recorder.finals.isEmpty())
            assertTrue("Manual input must cancel the backend session", recorder.cancels > 0)
            true
        }
    }

    @Test
    fun accessibilitySpaceLongClickStartsVoiceAndSecondLongClickStops() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain {
            val space = keyboard.findViewWithTag<View>("key-space")
            assertTrue("Space must handle the accessibility long-click action", space.performLongClick())
            true
        }
        harness.awaitMain { if (recorder.events != null) true else null }
        harness.awaitMain {
            assertEquals(1, recorder.starts)
            assertTrue(keyboard.findViewWithTag<View>("key-space").performLongClick())
            assertEquals("Second long-click must stop recording", 1, recorder.stops)
            assertEquals("Long-click must not insert a space", 0, recorder.spaces)
            assertEquals("Second long-click must not start another session", 1, recorder.starts)
            true
        }
    }

    @Test
    fun fuzzySettingsAliasTogglesOnAndOff() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain {
            keyboard.setSettings(sound = false, haptic = false, popup = true, fuzzy = false)
            keyboard.showPanel(Panel.FUZZY_SETTINGS)
            val panel = keyboard.findViewWithTag<ViewGroup>("fuzzy-settings-panel")
            val toggle = panel.findViewWithTag<View>("toggle")
            assertEquals("启用模糊音，已关闭", toggle.contentDescription.toString())
            assertTrue(toggle.performClick())
            assertEquals(listOf(true), recorder.fuzzyChanges)
            assertTrue(toggle.performClick())
            assertEquals(listOf(true, false), recorder.fuzzyChanges)
            true
        }
    }

    @Test
    fun nestedPanelBackReturnsToTheOriginatingSettingsPage() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.SETTINGS)
            keyboard.showPanel(Panel.FUZZY_SETTINGS)
            assertEquals(Panel.FUZZY_SETTINGS, keyboard.currentPanel())
            assertTrue(keyboard.closePanelToKeyboard())
            assertEquals("Back from a child panel must restore its parent", Panel.SETTINGS, keyboard.currentPanel())
            assertTrue(keyboard.closePanelToKeyboard())
            assertEquals(Panel.NONE, keyboard.currentPanel())
            true
        }
    }

    @Test
    fun settingsSlidersExposeCurrentValuesToTouchAndAccessibility() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.SETTINGS)
            val settings = keyboard.findViewWithTag<ViewGroup>("settings-panel")
            listOf("圆角", "不透明度", "按键字号").forEach { label ->
                val slider = settings.findViewWithTag<View>("settings-slider:$label")
                assertTrue("$label must keep a 48dp touch target", slider.minimumHeight >= keyboard.resources.displayMetrics.density * 48f)
                assertTrue("$label must expose its current value", slider.contentDescription.toString().contains(label))
            }
            true
        }
    }

    @Test
    fun panelButtonsAreFocusableAndKeepTouchFeedbackTarget() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.GAMING)
            val back = keyboard.findViewWithTag<View>("key-panel-back")
            assertTrue("Panel back must be keyboard-focusable", back.isFocusable)
            assertTrue("Panel back must keep a 48dp target", back.minimumHeight >= keyboard.resources.displayMetrics.density * 48f)
            val button = keyboard.findViewWithTag<View>("panel-button")
            assertTrue("Panel actions must be keyboard-focusable", button.isFocusable)
            assertTrue("Panel actions must keep a 48dp target", button.minimumHeight >= keyboard.resources.displayMetrics.density * 48f)
            true
        }
    }

    @Test
    fun emptyCandidateOverflowActionIsDisabledUntilCandidatesExist() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            val expand = keyboard.findViewWithTag<View>("candidate-expand")
            assertTrue("Empty composition must not expose a dead overflow action", !expand.isEnabled)
            assertTrue(expand.contentDescription.toString().contains("暂无更多候选"))
            keyboard.renderState(ImeState(composition = "ni", candidates = listOf("你")))
            assertTrue("Candidate overflow must become available with a result", expand.isEnabled)
            true
        }
    }

    @Test
    fun textEditorSpacerCellsAreNotExposedAsActions() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.TEXT_EDITOR)
            val spacer = keyboard.findViewWithTag<View>("textedit-spacer")
            assertTrue("Text editor layout spacers must not be clickable", !spacer.isClickable)
            assertTrue("Text editor layout spacers must not receive focus", !spacer.isFocusable)
            true
        }
    }

    @Test
    fun voiceLanguageButtonUpdatesItsAccessibleState() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.VOICE)
            val language = keyboard.findViewWithTag<View>("voice-language")
            assertTrue(language.contentDescription.toString().contains("普通话"))
            assertTrue(language.performClick())
            assertTrue(language.contentDescription.toString().contains("英文"))
            true
        }
    }

    @Test
    fun voiceControlsDescribeTheActiveGesture() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.VOICE)
            keyboard.startVoiceFromSpace()
            true
        }
        harness.awaitMain {
            val mic = keyboard.findViewWithTag<View>("voice-mic")
            assertTrue(mic.contentDescription.toString().contains("松开空格结束"))
            val hint = keyboard.findViewWithTag<View>("voice-gesture-hint")
            assertTrue(hint.contentDescription.toString().contains("上滑取消"))
            keyboard.cancelVoiceForManualInput()
            true
        }
    }

    @Test
    fun disablingPopupAffectsExistingKeyWithoutRebuilding() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.setSettings(sound = false, haptic = false, popup = true)
            keyboard.setMode(KeyboardMode.ENGLISH_26)
            true
        }
        harness.awaitMain {
            val key = keyboard.findViewWithTag<View>("key:q") ?: return@awaitMain null
            if (key.width == 0) return@awaitMain null
            val baseline = keyboard.childCount
            touch(key, MotionEvent.ACTION_DOWN)
            try {
                assertEquals("Enabled preview must actually appear", baseline + 1, keyboard.childCount)
            } finally {
                touch(key, MotionEvent.ACTION_CANCEL)
            }
            assertEquals(baseline, keyboard.childCount)
            keyboard.setSettings(sound = false, haptic = false, popup = false)
            assertSame(key, keyboard.findViewWithTag<View>("key:q"))
            touch(key, MotionEvent.ACTION_DOWN)
            try {
                assertEquals("Existing key must respect the updated popup preference", baseline, keyboard.childCount)
            } finally {
                touch(key, MotionEvent.ACTION_CANCEL)
            }
            true
        }
    }

    private fun touch(view: View, action: Int) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, view.width / 2f, view.height / 2f, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun spaceHeldPastLegacyThresholdButBeforeSystemTimeoutStillTypesSpace() = withKeyboard { harness, recorder, keyboard ->
        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        assumeTrue("Requires a system timeout greater than the legacy 150 ms threshold", timeout > 200L)
        val hold = 150L + (timeout - 150L) / 2L
        var released = false
        var elapsed = 0L
        harness.awaitMain {
            val point = keyPoint(keyboard, "key-space")
            val downTime = SystemClock.uptimeMillis()
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(point))
            keyboard.postDelayed({
                elapsed = SystemClock.uptimeMillis() - downTime
                pointers(keyboard, downTime, MotionEvent.ACTION_UP, listOf(point))
                released = true
            }, hold)
            true
        }
        harness.awaitMain { if (released) true else null }
        // View delivers a tap through a posted Runnable, so the space can reach
        // the listener on a later main-loop turn than the release callback. The
        // assertion below used to run first and read zero on a loaded device.
        val typed = runCatching {
            harness.awaitMain(timeoutMs = 2_000L) { if (recorder.spaces > 0) true else null }
        }.isSuccess
        harness.awaitMain {
            assumeTrue("Main-thread scheduling missed the pre-timeout release window", elapsed in 151L until timeout)
            assertTrue("A sub-long-press release must still type a space", typed)
            assertEquals(1, recorder.spaces)
            assertEquals(0, recorder.starts)
            true
        }
        SystemClock.sleep(timeout + 100L)
        harness.awaitMain {
            assertEquals("The adapter must cancel its deferred voice start", 0, recorder.starts)
            assertEquals(1, recorder.spaces)
            true
        }
    }

    @Test
    fun rebuildWhileSpaceIsHeldDoesNotSwallowTheSpace() = withKeyboard { harness, recorder, keyboard ->
        var downTime = 0L
        harness.awaitMain {
            val point = keyPoint(keyboard, "key-space")
            downTime = SystemClock.uptimeMillis()
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(point))
            true
        }
        // A layout change (mode switch, configuration change, nine-key filter)
        // rebuilds the key rows. Android drops a gesture as soon as its view
        // leaves the hierarchy, so rebuilding while the key is held used to
        // detach it and swallow the release without a trace.
        harness.awaitMain {
            keyboard.setMode(KeyboardMode.ENGLISH_26, notifyListener = false)
            true
        }
        harness.awaitMain {
            val point = keyPoint(keyboard, "key-space")
            pointers(keyboard, downTime, MotionEvent.ACTION_UP, listOf(point))
            true
        }
        harness.awaitMain(timeoutMs = 3_000L) { if (recorder.spaces > 0) true else null }
        assertEquals("A rebuild during the press must not swallow the space", 1, recorder.spaces)
    }

    @Test
    fun backspaceRepeatResumesAfterUpwardDriftReturnsBelowEightDp() = withKeyboard { harness, recorder, keyboard ->
        lateinit var owner: Finger
        var downTime = 0L
        var beforePause = 0
        harness.awaitMain {
            owner = keyPoint(keyboard, "key-backspace")
            downTime = SystemClock.uptimeMillis()
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(owner))
            true
        }
        harness.awaitMain { if (recorder.backspaces > 0) true else null }
        harness.awaitMain {
            beforePause = recorder.backspaces
            val drift = owner.copy(y = owner.y - 12f * keyboard.resources.displayMetrics.density)
            pointers(keyboard, downTime, MotionEvent.ACTION_MOVE, listOf(drift))
            true
        }
        SystemClock.sleep(180L)
        harness.awaitMain {
            assertEquals("Upward drift must suspend repeat before clear is armed", beforePause, recorder.backspaces)
            pointers(keyboard, downTime, MotionEvent.ACTION_MOVE, listOf(owner))
            true
        }
        harness.awaitMain(timeoutMs = 3_000L) { if (recorder.backspaces > beforePause) true else null }
        var afterRelease = 0
        harness.awaitMain {
            afterRelease = recorder.backspaces
            pointers(keyboard, downTime, MotionEvent.ACTION_UP, listOf(owner))
            assertEquals("Release after repeat must not add a single delete", afterRelease, recorder.backspaces)
            assertEquals(0, recorder.clears)
            true
        }
        SystemClock.sleep(180L)
        harness.awaitMain { assertEquals(afterRelease, recorder.backspaces); true }
    }

    @Test
    fun backspaceTracksOwnerAcrossPointerReorderingAndIgnoresOtherFingerRelease() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain {
            val owner = keyPoint(keyboard, "key-backspace").copy(id = 7)
            val other = owner.copy(id = 11, x = owner.x - 4f)
            val downTime = SystemClock.uptimeMillis()
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(owner))
            pointers(keyboard, downTime, pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), listOf(owner, other))
            val raisedOwner = owner.copy(y = owner.y - 44f * keyboard.resources.displayMetrics.density)
            // Owner is now index 1: consulting rawY/index 0 would miss its clear gesture.
            pointers(keyboard, downTime, MotionEvent.ACTION_MOVE, listOf(other, raisedOwner))
            pointers(keyboard, downTime, pointerAction(MotionEvent.ACTION_POINTER_UP, 0), listOf(other, raisedOwner))
            assertEquals("Releasing a non-owner must not finish the clear", 0, recorder.clears)
            assertEquals(0, recorder.backspaces)
            pointers(keyboard, downTime, MotionEvent.ACTION_UP, listOf(raisedOwner))
            assertEquals("Owner release commits exactly one clear", 1, recorder.clears)
            assertEquals(0, recorder.backspaces)
            true
        }
    }

    @Test
    fun backspaceOwnerPointerUpFinishesOnceWhileOtherFingerRemainsDown() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain {
            val owner = keyPoint(keyboard, "key-backspace").copy(id = 7)
            val other = owner.copy(id = 11, x = owner.x - 4f)
            val downTime = SystemClock.uptimeMillis()
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(owner))
            pointers(keyboard, downTime, pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), listOf(owner, other))
            pointers(keyboard, downTime, pointerAction(MotionEvent.ACTION_POINTER_UP, 0), listOf(owner, other))
            assertEquals("Owner POINTER_UP must finish the tap immediately", 1, recorder.backspaces)
            pointers(keyboard, downTime, MotionEvent.ACTION_UP, listOf(other))
            assertEquals("Remaining finger must not commit another delete", 1, recorder.backspaces)
            assertEquals(0, recorder.clears)
            true
        }
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100L)
        harness.awaitMain { assertEquals(1, recorder.backspaces); true }
    }

    private data class Finger(val id: Int, val x: Float, val y: Float)

    private fun keyPoint(keyboard: ImeKeyboardViewV2, tag: String): Finger {
        val key = requireNotNull(keyboard.findViewWithTag<View>(tag))
        check(key.width > 0 && key.height > 0) { "Key $tag has not been laid out" }
        val rect = Rect(0, 0, key.width, key.height)
        keyboard.offsetDescendantRectToMyCoords(key, rect)
        return Finger(0, rect.exactCenterX(), rect.exactCenterY())
    }

    private fun pointerAction(action: Int, index: Int): Int =
        action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun pointers(keyboard: ImeKeyboardViewV2, downTime: Long, action: Int, fingers: List<Finger>) {
        val properties = fingers.map { finger ->
            MotionEvent.PointerProperties().apply {
                id = finger.id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coordinates = fingers.map { finger ->
            MotionEvent.PointerCoords().apply {
                x = finger.x
                y = finger.y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, fingers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            keyboard.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
