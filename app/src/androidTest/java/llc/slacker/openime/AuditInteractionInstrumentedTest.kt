package llc.slacker.openime

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.core.ImeState
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.core.Panel
import llc.slacker.openime.data.CustomSymbolRepository
import llc.slacker.openime.data.QuickPhraseRepository
import llc.slacker.openime.floating.FloatingWindowController
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeTheme
import llc.slacker.openime.voice.VoiceModelLifecycleState
import llc.slacker.openime.voice.VoiceRecognitionEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class AuditInteractionInstrumentedTest {
    private class Recorder {
        lateinit var keyboard: ImeKeyboardView
        var events: VoiceRecognitionEvents? = null
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val characters = mutableListOf<String>()
        val fuzzyChanges = mutableListOf<Boolean>()
        val textEdits = mutableListOf<String>()
        var starts = 0
        var stops = 0
        var cancels = 0
        var spaces = 0
        var backspaces = 0
        var clears = 0
        val listener = Proxy.newProxyInstance(
            ImeKeyboardView.Listener::class.java.classLoader,
            arrayOf(ImeKeyboardView.Listener::class.java),
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
                "onVoicePartial" -> { partials.add(args!![0] as String); null }
                "onVoiceFinal" -> { finals.add(args!![0] as String); null }
                "onCharacter" -> { characters.add(args!![0] as String); null }
                "onVoiceToggle" -> { keyboard.startVoiceFromSpace(); null }
                "onVoicePressChanged" -> {
                    if (args!![0] == true) keyboard.startVoiceFromSpace()
                    else keyboard.stopVoiceFromSpace()
                    null
                }
                "onSpace" -> { spaces++; null }
                "onBackspace" -> { backspaces++; null }
                "onClearAll" -> { clears++; null }
                "onTextEdit" -> { textEdits.add(args!![0] as String); null }
                "onFloatingKeyboardChanged" -> {
                    keyboard.setFloatingWindowMode(args!![0] as Boolean)
                    null
                }
                "onFuzzyChanged" -> { fuzzyChanges.add(args!![0] as Boolean); null }
                // A proxy answers null for everything else; a primitive boolean
                // query must answer false instead.
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
    fun modeSwitchCancelsActiveVoiceAndRejectsLateFinal() = withKeyboard { harness, recorder, keyboard ->
        lateinit var staleEvents: VoiceRecognitionEvents
        harness.awaitMain {
            keyboard.startVoiceFromSpace()
            true
        }
        harness.awaitMain(timeoutMs = 2_000L) {
            recorder.events?.let {
                staleEvents = it
                true
            }
        }

        harness.awaitMain {
            keyboard.setMode(KeyboardMode.ENGLISH_26, notifyListener = false)
            assertEquals("Mode switch must cancel the active backend session", 1, recorder.cancels)
            assertFalse("Mode switch must leave no active voice presentation", keyboard.isVoiceActive())
            staleEvents.onFinal("stale after mode switch")
            true
        }
        harness.awaitMain {
            assertTrue("Late final from the cancelled mode must be ignored", recorder.finals.isEmpty())
            true
        }
    }

    @Test
    fun openingNonVoicePanelCancelsInlineVoiceAndRejectsLateFinal() = withKeyboard { harness, recorder, keyboard ->
        lateinit var staleEvents: VoiceRecognitionEvents
        harness.awaitMain {
            keyboard.startVoiceFromSpace()
            true
        }
        harness.awaitMain(timeoutMs = 2_000L) {
            recorder.events?.let {
                staleEvents = it
                true
            }
        }

        harness.awaitMain {
            keyboard.showPanel(Panel.EMOJI)
            assertEquals(Panel.EMOJI, keyboard.currentPanel())
            assertEquals("Opening another panel must cancel inline voice", 1, recorder.cancels)
            assertFalse(keyboard.isVoiceActive())
            staleEvents.onFinal("stale behind emoji")
            true
        }
        harness.awaitMain {
            assertTrue("Hidden inline voice must not commit after panel replacement", recorder.finals.isEmpty())
            true
        }
    }

    @Test
    fun releaseKeepsLateVoiceCallbacksUntilFinalResult() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain { keyboard.startVoiceFromSpace(); true }
        harness.awaitMain { if (recorder.events != null) true else null }
        harness.awaitMain {
            keyboard.stopVoiceFromSpace()
            recorder.events!!.onPartial("松手后的尾帧")
            recorder.events!!.onFinal("松手后的最终结果")
            true
        }
        harness.awaitMain {
            assertEquals("松手后的尾帧必须在释放后仍能进入最终识别流程", listOf("松手后的尾帧"), recorder.partials)
            assertEquals(listOf("松手后的最终结果"), recorder.finals)
            true
        }
    }

    @Test
    fun finalVoiceCallbackInvalidatesDuplicateAndLateCallbacks() = withKeyboard { harness, recorder, keyboard ->
        lateinit var events: VoiceRecognitionEvents
        harness.awaitMain {
            keyboard.showPanel(Panel.VOICE)
            keyboard.startVoiceFromSpace()
            true
        }
        harness.awaitMain(timeoutMs = 2_000L) {
            recorder.events?.let {
                events = it
                true
            }
        }
        harness.awaitMain {
            keyboard.stopVoiceFromSpace()
            events.onFinal("唯一最终结果")
            events.onFinal("重复最终结果")
            events.onError("迟到错误")
            events.onReady()
            true
        }
        harness.awaitMain {
            if (recorder.finals.size != 1) return@awaitMain null
            val status = keyboard.findViewWithTag<TextView>("voice-model-status")
            assertEquals(
                "Terminal final must commit exactly once",
                listOf("唯一最终结果"),
                recorder.finals,
            )
            assertTrue(
                "Late callbacks must not resurrect recognition UI after final",
                status.text.toString().contains("完成"),
            )
            assertFalse(keyboard.isVoiceActive())
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
    fun openEmojiPanelRebuildsItsResponsiveGridAfterFontScaleChanges() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.EMOJI)
            val before = keyboard.findViewWithTag<View>("emoji-scroll")
            assertNotNull("Emoji panel must expose its scroll surface", before)
            val next = Configuration(keyboard.resources.configuration).apply {
                fontScale = 1.15f
            }
            ImeKeyboardView::class.java.getDeclaredMethod(
                "onConfigurationChanged",
                Configuration::class.java,
            ).apply { isAccessible = true }.invoke(keyboard, next)
            val after = keyboard.findViewWithTag<View>("emoji-scroll")
            assertNotNull(after)
            assertTrue("Font-scale changes must rebuild the open emoji grid", before !== after)
            assertEquals(Panel.EMOJI, keyboard.currentPanel())
            true
        }
    }

    @Test
    fun nestedPanelBackReturnsToTheOriginatingSettingsPage() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.SETTINGS)
            keyboard.showPanel(Panel.FUZZY_SETTINGS)
            assertEquals(Panel.FUZZY_SETTINGS, keyboard.currentPanel())
            assertEquals(
                "返回设置",
                keyboard.findViewWithTag<View>("key-panel-back").contentDescription,
            )
            assertTrue(keyboard.closePanelToKeyboard())
            assertEquals("Back from a child panel must restore its parent", Panel.SETTINGS, keyboard.currentPanel())
            assertTrue(keyboard.closePanelToKeyboard())
            assertEquals(Panel.NONE, keyboard.currentPanel())
            true
        }
    }

    @Test
    fun quickPhrasePanelRefreshesEditedEntryAndCommitsCurrentText() = withKeyboard { harness, recorder, keyboard ->
        val original = "面板原文-${SystemClock.uptimeMillis()}"
        val edited = "面板编辑后-${SystemClock.uptimeMillis()}"
        var phraseId = 0L
        try {
            harness.awaitMain {
                val phrase = requireNotNull(
                    QuickPhraseRepository.upsert(
                        keyboard.context,
                        0L,
                        "测试分类",
                        original,
                    ),
                )
                phraseId = phrase.id
                keyboard.showPanel(Panel.CLIPBOARD)
                assertTrue(
                    "Clipboard panel must expose the quick-phrase tab",
                    keyboard.findTestTarget("常用语")!!.performClick(),
                )
                true
            }
            harness.awaitMain {
                val entry = keyboard.findViewWithTag<View>("phrase:$phraseId")
                    ?: return@awaitMain null
                assertTrue(entry.performClick())
                assertEquals(listOf(original), recorder.characters)

                QuickPhraseRepository.upsert(
                    keyboard.context,
                    phraseId,
                    "更新分类",
                    edited,
                )
                keyboard.refreshAuxiliaryContent()
                true
            }
            harness.awaitMain {
                val editedEntry = keyboard.findViewWithTag<View>("phrase:$phraseId")
                    ?: return@awaitMain null
                assertTrue(
                    "Refreshing auxiliary content must rebuild the row with edited text",
                    editedEntry.contentDescription.toString().contains(edited),
                )
                assertTrue(editedEntry.performClick())
                assertEquals(listOf(original, edited), recorder.characters)

                QuickPhraseRepository.remove(keyboard.context, phraseId)
                keyboard.refreshAuxiliaryContent()
                assertNull(
                    "Deleting then refreshing must remove the phrase row",
                    keyboard.findViewWithTag<View>("phrase:$phraseId"),
                )
                true
            }
        } finally {
            harness.awaitMain {
                if (phraseId > 0L) QuickPhraseRepository.remove(keyboard.context, phraseId)
                true
            }
        }
    }

    @Test
    fun networkEmoticonCategoryFiltersSymbolsInsteadOfOnlyHighlightingTab() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.SYMBOLS)
            val emoticonTab = keyboard.findTestTarget("网络颜文字")
            assertNotNull("Symbols must expose network emoticons as a real category", emoticonTab)
            assertTrue(emoticonTab!!.performClick())
            assertNotNull(
                "Selecting the category must render its contents",
                keyboard.findTestTarget("(｡◕‿◕｡)"),
            )

            assertTrue(keyboard.findTestTarget("特殊")!!.performClick())
            assertNotNull("Special shapes must remain in the special category", keyboard.findTestTarget("★"))
            assertNull(
                "Special category must not silently retain network emoticons",
                keyboard.findTestTarget("(｡◕‿◕｡)"),
            )
            true
        }
    }

    @Test
    fun emojiCategorySelectionReplacesTheRenderedGrid() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.EMOJI)
            assertNotNull("Default smiley category must render a smiley", keyboard.findTestTarget("😀"))
            assertTrue(keyboard.findTestTarget("手势")!!.performClick())
            assertNull("Switching category must replace, not append to, the old emoji grid", keyboard.findTestTarget("😀"))
            assertNotNull("People category must render skin-tone variants", keyboard.findTestTarget("👍🏿"))

            assertTrue(keyboard.findTestTarget("动物")!!.performClick())
            assertNotNull("Animal category must render its own content", keyboard.findTestTarget("🐶"))
            assertNull("Previous people grid must be removed", keyboard.findTestTarget("👍🏿"))
            true
        }
    }

    @Test
    fun majorKeyboardModesReuseTheSamePrimaryKeyHeight() = withKeyboard { harness, _, keyboard ->
        fun measuredHeight(mode: KeyboardMode, tag: String): Int {
            harness.awaitMain {
                keyboard.setMode(mode, notifyListener = false)
                true
            }
            return harness.awaitMain {
                val key = keyboard.findViewWithTag<View>(tag) ?: return@awaitMain null
                key.height.takeIf { it > 0 }
            }
        }

        val chinese26 = measuredHeight(KeyboardMode.PINYIN_26, "key:q")
        val english26 = measuredHeight(KeyboardMode.ENGLISH_26, "key:q")
        val chinese9 = measuredHeight(KeyboardMode.PINYIN_9, "key-9:2")
        val numeric = measuredHeight(KeyboardMode.DIGITS, "key:5")

        assertEquals("English 26 must reuse the Chinese 26 row geometry", chinese26, english26)
        assertEquals("Chinese 9 must reuse the primary key-row height", chinese26, chinese9)
        assertEquals("Numeric must reuse the primary key-row height", chinese26, numeric)
    }

    @Test
    fun settingsSlidersExposeCurrentValuesToTouchAndAccessibility() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.SETTINGS)
            val settings = keyboard.findViewWithTag<ViewGroup>("settings-panel")
            listOf("键盘高度", "浮动宽度", "浮动透明度").forEach { label ->
                val slider = settings.findViewWithTag<View>("settings-slider:$label")
                assertTrue("$label must keep a 48dp touch target", slider.minimumHeight >= keyboard.resources.displayMetrics.density * 48f)
                assertTrue("$label must expose its current value", slider.contentDescription.toString().contains(label))
            }
            true
        }
    }

    @Test
    fun settingsExposeEveryAppearanceAndKeepSelectionAccessible() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.SETTINGS)
            // The product ships one skin, so appearance (system / light / dark) is the picker.
            ImeAppearance.entries.forEach { appearance ->
                val option = keyboard.findTestTarget(appearance.label)
                assertTrue("${appearance.label} must be selectable from settings", option != null)
                assertTrue("${appearance.label} must be keyboard-focusable", option!!.isFocusable)
                assertTrue(
                    "${appearance.label} must expose a 48dp target",
                    option.minimumHeight >= keyboard.resources.displayMetrics.density * 48f,
                )
            }
            val light = keyboard.findTestTarget(ImeAppearance.LIGHT.label)
            assertTrue(light!!.performClick())
            assertTrue(
                "Selected appearance must expose its accessible state",
                keyboard.findTestTarget(ImeAppearance.LIGHT.label)!!.contentDescription.toString().contains("已选中"),
            )
            true
        }
    }

    @Test
    fun panelButtonsAreFocusableAndKeepTouchFeedbackTarget() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.TOOLS)
            val back = keyboard.findViewWithTag<View>("key-panel-back")
            assertTrue("Panel back must be keyboard-focusable", back.isFocusable)
            assertTrue("Panel back must keep a 48dp target", back.minimumHeight >= keyboard.resources.displayMetrics.density * 48f)
            val floatingEntry = keyboard.findViewWithTag<View>("tool:浮动键盘")
            assertNotNull("Tools must expose the floating keyboard action", floatingEntry)
            assertTrue("Tool actions must be keyboard-focusable", floatingEntry!!.isFocusable)
            assertTrue("Tool actions must keep a 48dp target", floatingEntry.minimumHeight >= keyboard.resources.displayMetrics.density * 48f)
            true
        }
    }

    @Test
    fun floatingDockStateDoesNotDependOnWindowAvailability() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            val controller = FloatingWindowController(
                resources = keyboard.resources,
                mainHandler = Handler(Looper.getMainLooper()),
                windowProvider = { null },
                keyboardHeightPx = { keyboard.measuredHeight.takeIf { it > 0 } },
                floatingWidthPercent = { 88 },
                floatingOpacityPercent = { 100 },
            )
            controller.enable()
            assertTrue(controller.enabled)

            controller.restore()

            assertFalse(
                "Docked state must be committed even when the IME Window has already disappeared",
                controller.enabled,
            )
            true
        }
    }

    @Test
    fun floatingWindowActionExposesDragAndDockSemantics() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.TOOLS)
            val floatingEntry = keyboard.findViewWithTag<View>("tool:浮动键盘")
            assertNotNull("Tools must expose the floating keyboard action", floatingEntry)
            assertTrue(floatingEntry!!.performClick())

            val dragHandle = keyboard.findViewWithTag<View>("floating-drag-handle")
            assertEquals("Floating action must return to the keyboard surface", Panel.NONE, keyboard.currentPanel())
            assertEquals(View.VISIBLE, dragHandle.visibility)
            assertTrue("Floating mode must enable drag", dragHandle.isEnabled)
            assertTrue("Floating drag handle must be focusable", dragHandle.isFocusable)
            assertTrue(dragHandle.contentDescription.toString().contains("点击贴底显示"))

            assertTrue("Floating handle must expose an explicit dock action", dragHandle.performClick())
            assertEquals(View.GONE, dragHandle.visibility)
            assertFalse("Docked keyboard must not expose a dead drag action", dragHandle.isEnabled)
            true
        }
    }

    @Test
    fun persistedSettingsSnapshotAppliesVisualAndInteractionStateTogether() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.applyPersistedSettings(
                newTheme = ImeTheme.IOS,
                newAppearance = ImeAppearance.LIGHT,
                sound = false,
                haptic = true,
                popup = true,
                fuzzy = true,
            )
            keyboard.showPanel(Panel.FUZZY_SETTINGS)
            val toggle = keyboard.findViewWithTag<View>("toggle")
            assertTrue(toggle.contentDescription.toString().contains("已开启"))
            // An enabled switch is painted with the accent, so this proves the
            // persisted appearance reached the theme, not just the settings text.
            assertEquals(
                ImeTheme.IOS.tokens(ImeAppearance.LIGHT).primary,
                switchTrackColor(toggle),
            )
            true
        }
    }

    @Test
    fun emptyCandidateOverflowActionIsDisabledUntilCandidatesExist() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            val expand = keyboard.findViewWithTag<View>("candidate-expand")
            assertTrue("Empty composition must not expose a dead overflow action", !expand.isEnabled)
            assertTrue(
                "Empty candidate overflow must use the shared disabled alpha",
                expand.alpha == ImeSurfacePolicy.DISABLED_ALPHA,
            )
            assertTrue(expand.contentDescription.toString().contains("暂无更多候选"))
            keyboard.renderState(ImeState(composition = "ni", candidates = listOf("你")))
            assertTrue("Candidate overflow must become available with a result", expand.isEnabled)
            true
        }
    }

    @Test
    fun candidateShortcutsExposePressedAndKeyboardFocusFeedback() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            // The reference design keeps one shortcut in the candidate bar: overflow.
            assertNull(
                "The candidate bar must not carry a second shortcut",
                keyboard.findViewWithTag<View>("candidate-emoji"),
            )
            val expand = keyboard.findViewWithTag<View>("candidate-expand")
            assertTrue("Candidate overflow shortcut must expose a stateful background", expand.background is StateListDrawable)
            assertTrue("Candidate overflow shortcut must be focusable", expand.isFocusable)
            true
        }
    }

    @Test
    fun toolbarActionsAreFocusableAndUseLocalizedLabels() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            val emoji = keyboard.findTestTarget("表情")
            assertTrue("Emoji toolbar action must be discoverable in Chinese", emoji != null)
            assertTrue("Toolbar actions must be keyboard-focusable", emoji!!.isFocusable)
            assertTrue("Toolbar actions must keep a 48dp target", emoji.minimumHeight >= keyboard.resources.displayMetrics.density * 48f)
            true
        }
    }

    @Test
    fun passwordFieldsKeepClipboardHistoryAvailable() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.renderState(ImeState(passwordField = true))
            val clipboard = keyboard.findViewWithTag<View>("clipboard-toolbar")
            assertTrue("Password fields use clipboard history like any other field", clipboard.isEnabled)
            keyboard.showPanel(Panel.TOOLS)
            assertTrue("Clipboard stays in tools for password fields", keyboard.findViewWithTag<View>("tool:剪贴板") != null)
            keyboard.showPanel(Panel.CLIPBOARD)
            assertEquals(Panel.CLIPBOARD, keyboard.currentPanel())
            true
        }
    }

    @Test
    fun switchingIntoPasswordFieldKeepsAnOpenClipboardPanel() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.renderState(ImeState(passwordField = false))
            keyboard.showPanel(Panel.CLIPBOARD)
            assertEquals(Panel.CLIPBOARD, keyboard.currentPanel())
            keyboard.renderState(ImeState(passwordField = true))
            assertEquals(Panel.CLIPBOARD, keyboard.currentPanel())
            true
        }
    }

    @Test
    fun passwordFieldsKeepTheVoiceGesture() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.renderState(ImeState(passwordField = true))
            val space = keyboard.findViewWithTag<View>("key-space")
            assertTrue("Password fields expose the voice long-press", space.isLongClickable)
            keyboard.showPanel(Panel.VOICE)
            assertEquals(Panel.VOICE, keyboard.currentPanel())
            true
        }
    }

    @Test
    fun toolCardsExposeOneAccessibleActionWithoutDuplicateLabels() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.TOOLS)
            val card = keyboard.findViewWithTag<View>("tool:表情")
            assertTrue("Tool card must be keyboard-focusable", card.isFocusable)
            // The column is the target; its icon tile mirrors the column's state.
            val tile = (card as ViewGroup).getChildAt(0)
            assertTrue("Tool tile must expose pressed and focus feedback", tile.background is StateListDrawable)
            assertTrue("Tool tile must follow the card's pressed state", tile.isDuplicateParentStateEnabled)
            assertEquals("表情", card.contentDescription)
            val label = card.getChildAt(1)
            assertTrue(
                "Visual tool label must not create a duplicate node",
                label.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            )
            true
        }
    }

    @Test
    fun punctuationAndDigitSymbolsAreKeyboardFocusable() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.setMode(KeyboardMode.PINYIN_9, notifyListener = false)
            listOf("，", "。", "？", "！").forEach { symbol ->
                val key = keyboard.findViewWithTag<View>("punct:$symbol")
                assertTrue("Punctuation $symbol must be focusable", key.isFocusable)
                assertTrue("Punctuation $symbol must remain clickable", key.isClickable)
                assertTrue("Punctuation $symbol must use the skin pressed state", key.background is StateListDrawable)
            }
            keyboard.setMode(KeyboardMode.DIGITS, notifyListener = false)
            listOf("%", "+", "−", "＊").forEach { symbol ->
                val key = keyboard.findViewWithTag<View>("digit-symbol:$symbol")
                assertTrue("Digit symbol $symbol must be focusable", key.isFocusable)
                assertTrue("Digit symbol $symbol must remain clickable", key.isClickable)
            }
            true
        }
    }

    @Test
    fun nineKeyAndDigitSymbolRailsScrollAndIncludeCustomSymbols() {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .targetContext
        val custom = CustomSymbolRepository.upsert(context, 0L, "测试", "⌘")
            ?: error("Unable to create the custom symbol fixture")
        // The nine-key rail is the user's own list (edited from its ＋), not the custom symbols.
        llc.slacker.openime.data.RailSymbolRepository.save(
            context,
            llc.slacker.openime.data.RailSymbolRepository.DEFAULT + "※",
        )
        try {
            withKeyboard { harness, _, keyboard ->
                harness.awaitMain {
                    keyboard.setMode(KeyboardMode.PINYIN_9, notifyListener = false)
                    true
                }
                harness.awaitMain {
                    val rail = keyboard.findViewWithTag<View>("nine-punct-stack")
                    if (rail !is ScrollView) return@awaitMain null
                    assertTrue("Nine-key symbols must advertise vertical scrolling", rail.contentDescription.toString().contains("上下滑动"))
                    assertNotNull("The user's rail symbols must appear in the nine-key rail", keyboard.findViewWithTag<View>("punct:※"))
                    assertNotNull("The nine-key rail ends in its ＋ editor cell", keyboard.findViewWithTag<View>("punct:add"))
                    assertEquals("Custom symbols stay in the symbol panel", null, keyboard.findViewWithTag<View>("punct:⌘"))
                    true
                }
                harness.awaitMain {
                    keyboard.setMode(KeyboardMode.DIGITS, notifyListener = false)
                    true
                }
                harness.awaitMain {
                    val rail = keyboard.findViewWithTag<View>("digits-symbol-scroll")
                    if (rail !is ScrollView) return@awaitMain null
                    assertTrue("Digit symbols must advertise vertical scrolling", rail.contentDescription.toString().contains("上下滑动"))
                    assertNotNull("Custom symbols must appear in the digit rail", keyboard.findViewWithTag<View>("digit-symbol:⌘"))
                    true
                }
            }
        } finally {
            CustomSymbolRepository.remove(context, custom.id)
            llc.slacker.openime.data.RailSymbolRepository.reset(context)
        }
    }

    @Test
    fun composingKeepsKeyboardGeometryStableAndEnglishUsesOneLine() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            val topZone = keyboard.findViewWithTag<View>("ime_toolbar")
            val keyboardHost = keyboard.findViewWithTag<View>("keyboard-host")
            val topBefore = topZone.layoutParams.height
            val bodyBefore = keyboardHost.layoutParams.height
            keyboard.setMode(KeyboardMode.ENGLISH_26, notifyListener = false)
            keyboard.renderState(ImeState(composition = "open", candidates = listOf("open", "openime")))
            assertEquals("Typing must not change the reserved top-zone height", topBefore, topZone.layoutParams.height)
            assertEquals("Typing must not shrink the keyboard body", bodyBefore, keyboardHost.layoutParams.height)
            assertEquals(View.GONE, keyboard.findViewWithTag<View>("pinyin-composition-editor").visibility)
            assertEquals(View.VISIBLE, keyboard.findViewWithTag<View>("candidate-field").visibility)
            assertEquals("open", keyboard.findViewWithTag<View>("candidate-first").let { (it as android.widget.TextView).text })
            true
        }
    }

    @Test
    fun settingToggleUsesOneFocusableStatefulRow() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.FUZZY_SETTINGS)
            val panel = keyboard.findViewWithTag<ViewGroup>("fuzzy-settings-panel")
            val row = panel.findViewWithTag<ViewGroup>("setting-row")
            val toggle = panel.findViewWithTag<View>("toggle")
            // The keyboard's compact rows carry no icon; the preferences page's do.
            val icon: View? = panel.findViewWithTag<View>("setting-icon")
            assertTrue("Settings row must be keyboard-focusable", row.isFocusable)
            assertTrue("Settings row must expose its current state", row.contentDescription.toString().contains("已关闭"))
            assertTrue("The visual switch must not create a duplicate accessibility node", toggle.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
            assertTrue(
                "Visual switch track must be wider than tall",
                ImeGeometryTokens.SWITCH_WIDTH_DP > ImeGeometryTokens.SWITCH_HEIGHT_DP,
            )
            assertTrue("Switch keeps a 48dp touch target", toggle.minimumHeight >= keyboard.resources.displayMetrics.density * 48f)
            if (icon != null) {
                assertTrue("Setting icon must remain decorative", icon.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO)
            }
            val offColor = switchTrackColor(toggle)
            assertTrue(row.performClick())
            assertTrue(row.contentDescription.toString().contains("已开启"))
            val onColor = switchTrackColor(toggle)
            assertTrue("Toggle background must follow its enabled state", offColor != onColor)
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
    fun backspaceExposesClearAllAsAnAccessibilityAction() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain {
            val key = keyboard.findViewWithTag<View>("key-backspace")
            val node = key.createAccessibilityNodeInfo()
            try {
                assertTrue(
                    "Backspace must expose the swipe-only clear action",
                    node.actionList.any { it.id == R.id.accessibility_clear_all },
                )
            } finally {
                node.recycle()
            }
            assertTrue(
                "Accessibility clear action must execute the same clear callback",
                key.performAccessibilityAction(R.id.accessibility_clear_all, null),
            )
            assertEquals(1, recorder.clears)
            true
        }
    }

    @Test
    fun voiceLanguageControlUpdatesItsAccessibleState() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.VOICE)
            val mandarin = keyboard.findTestTarget("语音语言：普通话")!!
            val english = keyboard.findTestTarget("语音语言：英文")!!
            assertTrue("Mandarin starts selected", mandarin.isSelected)
            assertFalse(english.isSelected)
            assertTrue(english.performClick())
            assertTrue("English must become selected", english.isSelected)
            assertFalse(mandarin.isSelected)
            keyboard.startVoiceFromSpace()
            assertFalse("Voice language must lock for the active session", english.isEnabled)
            assertFalse(mandarin.isEnabled)
            assertTrue(english.contentDescription.toString().contains("识别进行中不可切换"))
            keyboard.cancelVoiceForManualInput()
            assertTrue("Voice language must unlock after cancellation", english.isEnabled)
            assertTrue(mandarin.isEnabled)
            true
        }
    }

    @Test
    fun voiceErrorReleasesGestureLockAndAllowsRetry() = withKeyboard { harness, recorder, keyboard ->
        lateinit var failedEvents: VoiceRecognitionEvents
        // A locked control is deliberately not clickable, so it can only be
        // looked up before the lock engages.
        lateinit var language: View
        harness.awaitMain {
            keyboard.showPanel(Panel.VOICE)
            language = keyboard.findTestTarget("语音语言：普通话")!!
            keyboard.startVoiceFromSpace()
            assertFalse("Voice language must lock while recognition is starting", language.isEnabled)
            true
        }
        harness.awaitMain(timeoutMs = 2_000L) {
            recorder.events?.let {
                failedEvents = it
                true
            }
        }
        harness.awaitMain {
            failedEvents.onError("麦克风权限不可用")
            true
        }
        harness.awaitMain {
            if (!language.isEnabled) return@awaitMain null
            assertFalse("Terminal error must clear active voice state", keyboard.isVoiceActive())
            assertTrue("Terminal error must release the gesture-owned language lock", language.isEnabled)
            assertTrue("Language selection must work again after error", language.performClick())
            failedEvents.onFinal("错误后的迟到结果")
            true
        }
        harness.awaitMain {
            assertTrue(
                "A terminal error must reject a late final from the failed session",
                recorder.finals.isEmpty(),
            )
            keyboard.startVoiceFromSpace()
            true
        }
        harness.awaitMain(timeoutMs = 2_000L) {
            if (recorder.starts >= 2) true else null
        }
        harness.awaitMain {
            assertEquals("Voice must be retryable after a terminal error", 2, recorder.starts)
            keyboard.cancelVoiceForManualInput()
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
    fun voiceStatusChangesAreAvailableToAccessibilityServices() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.showPanel(Panel.VOICE)
            assertEquals(
                View.ACCESSIBILITY_LIVE_REGION_POLITE,
                keyboard.findViewWithTag<View>("voice-model-status").accessibilityLiveRegion,
            )
            assertEquals(
                View.ACCESSIBILITY_LIVE_REGION_POLITE,
                keyboard.findViewWithTag<View>("voice-transcript").accessibilityLiveRegion,
            )
            true
        }
    }

    @Test
    fun modeSwitchDismissesLongPressChoicePopup() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            // 1 is the nine-key segmentation key; its long press offers @ # /.
            keyboard.setMode(KeyboardMode.PINYIN_9, notifyListener = false)
            val segment = keyboard.findViewWithTag<View>("key-9:1")
            val baseline = keyboard.childCount
            assertTrue(segment.performLongClick())
            assertTrue("Long-press choice popup must be showing", keyboard.isKeyPopupShown())
            assertEquals("Long-press choice popup must attach to the root", baseline + 1, keyboard.childCount)

            keyboard.setMode(KeyboardMode.ENGLISH_26, notifyListener = false)

            assertFalse("Mode switch must retire popup whose anchor was rebuilt", keyboard.isKeyPopupShown())
            assertEquals("The retired popup must leave the root", baseline, keyboard.childCount)
            true
        }
    }

    @Test
    fun openingPanelDismissesOrdinaryKeyPopup() = withKeyboard { harness, _, keyboard ->
        lateinit var key: View
        var baseline = 0
        harness.awaitMain {
            keyboard.setSettings(sound = false, haptic = false, popup = true)
            keyboard.setMode(KeyboardMode.ENGLISH_26, notifyListener = false)
            key = keyboard.findViewWithTag<View>("key:q") ?: return@awaitMain null
            if (key.width == 0) return@awaitMain null
            baseline = keyboard.childCount
            touch(key, MotionEvent.ACTION_DOWN)
            assertTrue("Ordinary key preview must be showing", keyboard.isKeyPopupShown())

            keyboard.showPanel(Panel.EMOJI)

            assertFalse("Opening a panel must retire the transient key preview", keyboard.isKeyPopupShown())
            assertEquals("The preview is reused, not re-added to the root", baseline, keyboard.childCount)
            assertEquals(Panel.EMOJI, keyboard.currentPanel())
            touch(key, MotionEvent.ACTION_CANCEL)
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
            touch(key, MotionEvent.ACTION_DOWN)
            try {
                assertTrue("Enabled preview must actually appear", keyboard.isKeyPopupShown())
            } finally {
                touch(key, MotionEvent.ACTION_CANCEL)
            }
            assertFalse(keyboard.isKeyPopupShown())
            keyboard.setSettings(sound = false, haptic = false, popup = false)
            assertSame(key, keyboard.findViewWithTag<View>("key:q"))
            touch(key, MotionEvent.ACTION_DOWN)
            try {
                assertFalse("Existing key must respect the updated popup preference", keyboard.isKeyPopupShown())
            } finally {
                touch(key, MotionEvent.ACTION_CANCEL)
            }
            true
        }
    }

    @Test
    fun keyPopupUsesSharedProductGeometry() = withKeyboard { harness, _, keyboard ->
        harness.awaitMain {
            keyboard.setSettings(sound = false, haptic = false, popup = true)
            keyboard.setMode(KeyboardMode.DIGITS, notifyListener = false)
            true
        }
        harness.awaitMain {
            val key = keyboard.findViewWithTag<View>("key:5") ?: return@awaitMain null
            if (key.width == 0) return@awaitMain null
            touch(key, MotionEvent.ACTION_DOWN)
            try {
                assertTrue(keyboard.isKeyPopupShown())
                // The preview is brought to the front when it opens.
                val popup = keyboard.getChildAt(keyboard.childCount - 1)
                val expectedHeight = keyboard.scaledPx(ImeGeometryTokens.KEY_POPUP_HEIGHT_DP)
                assertEquals("Key popup height must use the shared product token", expectedHeight, popup.layoutParams.height)
                assertTrue(
                    "Wide keys must not produce a preview narrower than the source key",
                    popup.layoutParams.width >= key.width,
                )
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
    fun spaceHeldPastConfiguredLongPressStartsVoiceWithoutInsertingSpace() = withKeyboard { harness, recorder, keyboard ->
        val hold = ViewConfiguration.getLongPressTimeout().toLong() + 100L
        var released = false
        harness.awaitMain {
            val point = keyPoint(keyboard, "key-space")
            val downTime = SystemClock.uptimeMillis()
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(point))
            keyboard.postDelayed({
                pointers(keyboard, downTime, MotionEvent.ACTION_UP, listOf(point))
                released = true
            }, hold)
            true
        }
        harness.awaitMain { if (released) true else null }
        harness.awaitMain(timeoutMs = 10_000L) { if (recorder.starts > 0) true else null }
        harness.awaitMain {
            assertEquals("Configured long press must arm voice exactly once", 1, recorder.starts)
            assertEquals("Voice gesture must not also insert a space", 0, recorder.spaces)
            true
        }
    }

    @Test
    fun spaceJitterDoesNotEnterCursorModeAndHorizontalSwipeDoes() = withKeyboard { harness, recorder, keyboard ->
        // Gesture thresholds scale with the keyboard (reference scale), so the
        // drag is expressed in the same px: 18dp starts cursor mode, each 12dp
        // after that is one step. The 2dp margins keep px rounding out of it.
        val px = { dp: Int -> keyboard.scaledPx(dp).toFloat() }
        harness.awaitMain {
            val origin = keyPoint(keyboard, "key-space")
            var downTime = SystemClock.uptimeMillis()
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(origin))
            SystemClock.sleep(40L)
            pointers(
                keyboard,
                downTime,
                MotionEvent.ACTION_MOVE,
                listOf(origin.copy(x = origin.x + px(3))),
            )
            SystemClock.sleep(40L)
            pointers(keyboard, downTime, MotionEvent.ACTION_UP, listOf(origin.copy(x = origin.x + px(3))))
            assertTrue("A small jitter must not move the cursor", recorder.textEdits.isEmpty())
            val spacesAfterJitter = recorder.spaces

            val swipeOrigin = keyPoint(keyboard, "key-space")
            downTime = SystemClock.uptimeMillis()
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(swipeOrigin))
            var x = swipeOrigin.x
            fun dragBy(delta: Float, action: Int = MotionEvent.ACTION_MOVE) {
                x += delta
                pointers(keyboard, downTime, action, listOf(swipeOrigin.copy(x = x)))
            }
            dragBy(px(18) + px(2)) // crosses the cursor-mode threshold; no step yet
            dragBy(px(12) + px(2)) // first step right
            dragBy(px(12) + px(2)) // second step right
            dragBy(-(px(12) + px(6))) // reversing steps left
            dragBy(0f, MotionEvent.ACTION_UP)

            assertEquals(
                "A deliberate drag must move across characters and reverse direction",
                listOf("right", "right", "left"),
                recorder.textEdits,
            )
            assertEquals("Cursor movement must not commit a space", spacesAfterJitter, recorder.spaces)
            assertEquals("Cursor movement must not arm voice", 0, recorder.starts)
            true
        }
    }

    @Test
    fun spaceOwnerPointerUpBeforeLongPressCancelsPendingArmWithoutGhostSpace() = withKeyboard { harness, recorder, keyboard ->
        lateinit var owner: Finger
        lateinit var other: Finger
        var downTime = 0L
        harness.awaitMain {
            owner = keyPoint(keyboard, "key-space").copy(id = 7)
            other = owner.copy(id = 11, x = owner.x - 4f)
            downTime = SystemClock.uptimeMillis()
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(owner))
            pointers(
                keyboard,
                downTime,
                pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1),
                listOf(owner, other),
            )
            pointers(
                keyboard,
                downTime,
                pointerAction(MotionEvent.ACTION_POINTER_UP, 0),
                listOf(owner, other),
            )
            true
        }

        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150L)

        harness.awaitMain {
            pointers(keyboard, downTime, MotionEvent.ACTION_UP, listOf(other))
            assertEquals("Lifted owner must cancel the pending voice arm", 0, recorder.starts)
            assertEquals("The remaining finger must not synthesize a space click", 0, recorder.spaces)
            assertEquals(0, recorder.stops)
            true
        }
    }

    @Test
    fun activeSpaceVoiceOwnerPointerUpStopsOnceWithoutGhostSpace() = withKeyboard { harness, recorder, keyboard ->
        lateinit var owner: Finger
        lateinit var other: Finger
        var downTime = 0L
        harness.awaitMain {
            owner = keyPoint(keyboard, "key-space").copy(id = 7)
            other = owner.copy(id = 11, x = owner.x - 4f)
            downTime = SystemClock.uptimeMillis()
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(owner))
            pointers(
                keyboard,
                downTime,
                pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1),
                listOf(owner, other),
            )
            true
        }

        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100L)
        harness.awaitMain(timeoutMs = 2_000L) { if (recorder.starts == 1) true else null }

        harness.awaitMain {
            pointers(
                keyboard,
                downTime,
                pointerAction(MotionEvent.ACTION_POINTER_UP, 0),
                listOf(owner, other),
            )
            assertEquals("Owner release must stop voice exactly once", 1, recorder.stops)
            assertEquals(0, recorder.spaces)
            pointers(keyboard, downTime, MotionEvent.ACTION_UP, listOf(other))
            assertEquals("Remaining finger release must not insert a space", 0, recorder.spaces)
            assertEquals("Remaining finger release must not stop voice twice", 1, recorder.stops)
            true
        }
    }

    @Test
    fun rebuildWhileSpaceIsHeldDoesNotSwallowTheSpace() = withKeyboard { harness, recorder, keyboard ->
        var downTime = 0L
        var released = false
        var spaceViewSurvivedRebuildPoll = false
        lateinit var spaceKey: View
        val releaseDelayMs = minOf(
            100L,
            (ViewConfiguration.getLongPressTimeout().toLong() / 2L).coerceAtLeast(1L),
        )
        harness.awaitMain {
            val point = keyPoint(keyboard, "key-space")
            spaceKey = requireNotNull(keyboard.findViewWithTag("key-space"))
            downTime = SystemClock.uptimeMillis()
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(point))
            assertTrue("Space key must own the active press", spaceKey.isPressed)
            true
        }
        // A layout change (mode switch, configuration change, nine-key filter)
        // rebuilds the key rows. Android drops a gesture as soon as its view
        // leaves the hierarchy, so rebuilding while the key is held used to
        // detach it and swallow the release without a trace.
        harness.awaitMain {
            keyboard.setMode(KeyboardMode.ENGLISH_26, notifyListener = false)
            assertSame(
                "Changing layout must keep the pressed Space view attached until release",
                spaceKey,
                keyboard.findViewWithTag("key-space"),
            )
            // Give the deferred row rebuild poll several main-loop turns, but
            // release before Android's own long-click can consume this tap.
            keyboard.postDelayed({
                spaceViewSurvivedRebuildPoll =
                    keyboard.findViewWithTag<View>("key-space") === spaceKey
                val point = keyPoint(keyboard, "key-space")
                pointers(keyboard, downTime, MotionEvent.ACTION_UP, listOf(point))
                released = true
            }, releaseDelayMs)
            true
        }
        harness.awaitMain(timeoutMs = 3_000L) { if (released) true else null }
        harness.awaitMain {
            assertTrue(
                "The Space view must remain attached while the rebuild poll sees the active touch",
                spaceViewSurvivedRebuildPoll,
            )
            assertEquals("Space release must commit synchronously", 1, recorder.spaces)
            true
        }
        harness.awaitMain(timeoutMs = 3_000L) { if (recorder.spaces > 0) true else null }
        assertEquals("A rebuild during the press must not swallow the space", 1, recorder.spaces)
    }

    @Test
    fun backspaceTapDeletesExactlyOnceAndCancelDeletesNothing() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain {
            val owner = keyPoint(keyboard, "key-backspace")
            var downTime = SystemClock.uptimeMillis()
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(owner))
            pointers(keyboard, downTime, MotionEvent.ACTION_UP, listOf(owner))
            assertEquals("Backspace tap must delete exactly once", 1, recorder.backspaces)

            downTime = SystemClock.uptimeMillis()
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(owner))
            pointers(keyboard, downTime, MotionEvent.ACTION_CANCEL, listOf(owner))
            assertEquals("Cancelled backspace must not delete", 1, recorder.backspaces)
            true
        }
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100L)
        harness.awaitMain {
            assertEquals("Cancelled backspace must not leave repeat callbacks", 1, recorder.backspaces)
            assertEquals(0, recorder.clears)
            true
        }
    }

    @Test
    fun backspaceClearCommitsOnceAndCanBeDisarmedBeforeRelease() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain {
            val owner = keyPoint(keyboard, "key-backspace")
            var downTime = SystemClock.uptimeMillis()
            val clearPoint = owner.copy(y = owner.y - 44f * keyboard.resources.displayMetrics.density)

            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(owner))
            pointers(keyboard, downTime, MotionEvent.ACTION_MOVE, listOf(clearPoint))
            pointers(keyboard, downTime, MotionEvent.ACTION_UP, listOf(clearPoint))
            assertEquals("Armed clear gesture must clear exactly once", 1, recorder.clears)
            assertEquals("Clear gesture must not also delete one character", 0, recorder.backspaces)

            downTime = SystemClock.uptimeMillis()
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(owner))
            pointers(keyboard, downTime, MotionEvent.ACTION_MOVE, listOf(clearPoint))
            pointers(keyboard, downTime, MotionEvent.ACTION_MOVE, listOf(owner))
            pointers(keyboard, downTime, MotionEvent.ACTION_UP, listOf(owner))
            assertEquals("Returning below hysteresis must disarm clear", 1, recorder.clears)
            assertEquals("Disarmed gesture falls back to one backspace", 1, recorder.backspaces)
            true
        }
    }

    @Test
    fun backspaceLargeHorizontalDriftCannotArmClearAll() = withKeyboard { harness, recorder, keyboard ->
        harness.awaitMain {
            val owner = keyPoint(keyboard, "key-backspace")
            val downTime = SystemClock.uptimeMillis()
            val escaped = owner.copy(
                x = owner.x + 132f * keyboard.resources.displayMetrics.density,
                y = owner.y - 44f * keyboard.resources.displayMetrics.density,
            )
            pointers(keyboard, downTime, MotionEvent.ACTION_DOWN, listOf(owner))
            pointers(keyboard, downTime, MotionEvent.ACTION_MOVE, listOf(escaped))
            pointers(keyboard, downTime, MotionEvent.ACTION_UP, listOf(escaped))
            assertEquals("Horizontal escape must not trigger clear-all", 0, recorder.clears)
            assertEquals(1, recorder.backspaces)
            true
        }
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

    private fun keyPoint(keyboard: ImeKeyboardView, tag: String): Finger {
        val key = requireNotNull(keyboard.findViewWithTag<View>(tag))
        check(key.width > 0 && key.height > 0) { "Key $tag has not been laid out" }
        val rect = Rect(0, 0, key.width, key.height)
        keyboard.offsetDescendantRectToMyCoords(key, rect)
        return Finger(0, rect.exactCenterX(), rect.exactCenterY())
    }

    private fun pointerAction(action: Int, index: Int): Int =
        action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun pointers(keyboard: ImeKeyboardView, downTime: Long, action: Int, fingers: List<Finger>) {
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

    /** The switch track is painted inside its 48dp target by an inset wrapper. */
    private fun switchTrackColor(toggle: View): Int? =
        ((toggle.background as android.graphics.drawable.InsetDrawable).drawable as GradientDrawable).color?.defaultColor
}
