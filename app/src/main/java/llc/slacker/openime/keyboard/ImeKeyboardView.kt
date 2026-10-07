package llc.slacker.openime.keyboard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import llc.slacker.openime.QuickPhraseEditActivity
import llc.slacker.openime.RailSymbolsActivity
import llc.slacker.openime.R
import llc.slacker.openime.candidate.CandidatePipeline
import llc.slacker.openime.candidate.CandidateResolver
import llc.slacker.openime.candidate.NineKeyLocalDecoder
import llc.slacker.openime.candidate.NineKeyPerformanceTrace
import llc.slacker.openime.candidate.NineKeyReading
import llc.slacker.openime.candidate.StrokeLexicon
import llc.slacker.openime.core.FuzzyRule
import llc.slacker.openime.core.ImeState
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.core.Panel
import llc.slacker.openime.core.LandscapeLayout
import llc.slacker.openime.core.ShiftState
import llc.slacker.openime.data.HapticStyle
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.data.KeySoundStyle
import llc.slacker.openime.data.QuickPhrase
import llc.slacker.openime.data.QuickPhraseRepository
import llc.slacker.openime.editor.EditorInfoAdapter
import llc.slacker.openime.editor.enterKeyPresentationFor
import llc.slacker.openime.floating.FloatingKeyboardController
import llc.slacker.openime.panel.ClipboardPanelController
import llc.slacker.openime.panel.EmojiCellFactory
import llc.slacker.openime.panel.ImePanelRenderer
import llc.slacker.openime.panel.PanelHeaderFactory
import llc.slacker.openime.panel.SettingsPanelController
import llc.slacker.openime.panel.TextEditorPanelController
import llc.slacker.openime.setup.SetupUi
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeDrawableFactory
import llc.slacker.openime.theme.ImeFocusRingPolicy
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeMotionTokens
import llc.slacker.openime.theme.ImeReferenceSizing
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeTheme
import llc.slacker.openime.theme.ImeTypographyTokens
import llc.slacker.openime.voice.InlineVoicePresenter
import llc.slacker.openime.voice.VoicePanelController
import llc.slacker.openime.voice.VoiceSessionHost
import llc.slacker.openime.widget.ImeKeyView

/**
 * Native IME top-level view. Visual baseline: the supplied preview.html prototype.
 * (390x296 仅作为设计基准：顶部固定节奏、48dp 按键高度和 6dp 行距；
 * 实际运行时按输入法窗口可用宽度用权重重排，宽屏限制最大内容宽度并居中。
 * 文本通道是 InputConnectionGateway。
 */
open class ImeKeyboardView(
    context: Context,
    private val listener: Listener,
    private val standalonePanel: Boolean = false,
) : FrameLayout(context) {

    interface Listener : VoiceSessionHost {
        fun onModeChanged(mode: KeyboardMode)
        fun onPanelChanged(panel: Panel)
        fun onCharacter(char: String)
        fun onBackspace()
        fun onClearAll()
        fun onSpace()
        fun onFloatingKeyboardChanged(floating: Boolean)
        fun onFloatingKeyboardDragged(deltaX: Float, deltaY: Float)
        fun onVoiceToggle()
        fun onVoicePressChanged(pressed: Boolean) {
            if (pressed) onVoiceToggle()
        }
        fun onVoiceCommit() {}
        fun onEnter()
        fun onCompositionChanged(composition: String, candidates: List<String>)
        fun onNineKeyCompositionChanged(
            composition: String,
            digitBuffer: String,
            pinyinPaths: List<String>,
            candidates: List<String>,
        ) {
            onCompositionChanged(composition, candidates)
        }
        fun onCandidateSelected(candidate: String)
        fun onCandidateLongPressed(candidate: String) {}

        /**
         * An association ("联想") chip is rendered only *after* a candidate was
         * committed, i.e. when the composition is already empty. Routing those
         * clicks through [onCandidateSelected] is therefore always a no-op:
         * that path guards on a non-empty composition. Associations need their
         * own commit funnel that does not depend on a live composition.
         */
        fun onAssociationSelected(text: String) = Unit

        fun onCompositionBackspace()
        fun onThemeChanged(theme: ImeTheme)
        fun onAppearanceChanged(appearance: ImeAppearance)
        fun onShiftStateChanged(state: ShiftState)
        fun onCandidateExpanded(open: Boolean)
        fun onSymbolSelected(symbol: String)
        fun onEmojiSelected(emoji: String)
        fun onTextEdit(action: String)
        fun onSoundChanged(enabled: Boolean)
        fun onHapticChanged(enabled: Boolean)
        fun onPopupChanged(enabled: Boolean)
        fun onFuzzyChanged(enabled: Boolean)

        /** A 模糊音 pair was switched; the new set is already saved. */
        fun onFuzzyRulesChanged() {}
        fun onKeyboardHeightChanged(percent: Int) {}
        fun onHapticStrengthChanged(percent: Int) {}

        /** 震动手感 or 按键音效 changed; the new value is already saved. */
        fun onFeedbackStyleChanged() {}
        fun onFloatingStyleChanged(widthPercent: Int, opacityPercent: Int) {}
        fun onLandscapeLayoutChanged(layout: LandscapeLayout) {}
        fun onOpenAbout() {}
        fun onOpenDataManagement() {}
    }

    /** Visual class marker for white keys (nine/digits grid). */
    private val MARK_WHITE_KEY = 0x1F000001
    /** Visual class marker for the gray side/action column in nine-key layouts. */
    private val MARK_SIDE_KEY = 0x1F000002
    private val MARK_FUNCTION_KEY = 0x1F000003

    companion object {
        /** Scale differences below this are rounding noise, not a new geometry. */
        private const val SCALE_CHANGE_EPSILON = 0.01f
        private const val FLOATING_NAV_STRIP_DP = 28

        /** How often a deferred row rebuild re-checks whether the press ended. */
        private const val ROW_REBUILD_POLL_MS = 40L

        /** Slider drags preview the strength at most this often. */
        private const val STRENGTH_PREVIEW_INTERVAL_MS = 80L

        /** Long enough for SoundPool to decode a just-selected click. */
        private const val SOUND_PREVIEW_DELAY_MS = 120L
    }


    private val repeatHandler = Handler(Looper.getMainLooper())
    private val backspaceGestureController = BackspaceGestureController(
        toPx = ::dp,
        onDeleteOne = ::performBackspaceOnce,
        onClearAll = listener::onClearAll,
        onPressFeedback = ::feedback,
        onHapticFeedback = ::hapticFeedback,
        onGestureHint = { anchor, hint -> keyPopupController.showGestureHint(anchor, hint) },
        onHidePopup = ::hidePopup,
    )
    private val backspaceKeyFactory: BackspaceKeyFactory by lazy {
        BackspaceKeyFactory(
            context = context,
            toPx = ::dp,
            gestureController = backspaceGestureController,
            createBaseKey = { onTap ->
                key(
                    text = "",
                    func = true,
                    secondary = null,
                    mainTextSizeOverride = ImeTypographyTokens.BODY_SP,
                    iconRes = R.drawable.ic_backspace,
                    onTap = onTap,
                )
            },
            currentTokens = ::currentThemeTokens,
            onDeleteOne = ::performBackspaceOnce,
            onFeedback = ::feedback,
            onClearAll = listener::onClearAll,
            debugLogging = { debugLogging },
        )
    }
    private val spaceVoiceGestureController = SpaceVoiceGestureController(
        toPx = ::dp,
        canStartVoice = { true },
        onArmFeedback = ::hapticFeedback,
        onVoiceStart = { listener.onVoicePressChanged(true) },
        onVoiceStop = { listener.onVoicePressChanged(false) },
        onVoiceCancel = ::cancelVoiceGesture,
        onCancelPreviewChanged = { cancelling ->
            voicePanelController.setCancelPreview(cancelling)
        },
        onCursorStep = ::moveCursorFromSpace,
        onCursorModeChanged = ::lockBottomRowForCursorDrag,
    )

    /**
     * One horizontal step of a space-bar drag. While pinyin is being composed
     * the drag moves the pre-edit cursor (the same one a tap on the pre-edit
     * moves); otherwise it moves the cursor in the target text.
     */
    private fun moveCursorFromSpace(direction: Int) {
        val preedit = composition.text
        if (preedit != null && preedit.isNotEmpty()) {
            val next = (composition.selectionStart + direction).coerceIn(0, preedit.length)
            composition.setSelection(next)
        } else {
            listener.onTextEdit(if (direction < 0) "left" else "right")
        }
    }

    /**
     * The drag keeps the pointer on the space key, but a second finger or a
     * drifting thumb must not press the neighbouring keys of the bottom row.
     */
    private fun lockBottomRowForCursorDrag(spaceKey: View, locked: Boolean) {
        val row = spaceKey.parent as? ViewGroup ?: return
        for (index in 0 until row.childCount) {
            val sibling = row.getChildAt(index)
            if (sibling !== spaceKey && sibling is ImeKeyView) sibling.touchLocked = locked
        }
    }

    private val spaceVoiceKeyFactory: SpaceVoiceKeyFactory by lazy {
        SpaceVoiceKeyFactory(
            gestureController = spaceVoiceGestureController,
            createBaseKey = { label, onTap ->
                key(
                    text = label,
                    func = true,
                    secondary = null,
                    mainTextSizeOverride = ImeTypographyTokens.BODY_SP,
                    iconRes = R.drawable.ic_mic,
                    onTap = {
                        if (!insertIntoInlineEditor(" ")) onTap()
                    },
                )
            },
            canStartVoice = { true },
            markWhiteKey = { key -> key.setTag(MARK_WHITE_KEY, true) },
            onFeedback = ::feedback,
            onAccessibilityLongPress = {
                when {
                    voicePanelController.active -> stopVoiceFromSpace()
                    voicePanelController.pending -> cancelVoiceForManualInput()
                    else -> listener.onVoiceToggle()
                }
            },
        )
    }
    // Whether long-press alternate glyphs are shown as small corner hints.
    private var showSecondaryHints = true
    // Touch-coordinate trace logs are debug-only; they must never spam logcat
    // (or cost latency) in release builds.
    private val debugLogging: Boolean by lazy {
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private val candidateProvider = context as? CandidateResolver
    private var theme = ImeTheme.IOS
    private var appearance = ImeAppearance.SYSTEM
    private var mode = KeyboardMode.PINYIN_26
    // The mode whose key rows are currently built. setMode skips an expensive
    // removeAllViews()+rebuild when focus moves between fields without a layout
    // change.
    private var renderedMode: KeyboardMode? = null
    // Android drops a gesture the moment its view leaves the hierarchy, so a row
    // rebuild that lands while a key is held swallows that press (the space tap,
    // a letter, the backspace repeat). Remember the request and replay it once
    // the finger lifts instead of tearing the key out from under the gesture.
    private var pendingRowRebuild = false
    private val pendingRowRebuildPoll = object : Runnable {
        override fun run() {
            if (!pendingRowRebuild) return
            if (hasPressedKey()) {
                postDelayed(this, ROW_REBUILD_POLL_MS)
                return
            }
            pendingRowRebuild = false
            renderModeBody()
        }
    }
    // Only these configuration values change the derived row and IME heights.
    private var appliedOrientation = resources.configuration.orientation
    private var appliedScreenWidthDp = resources.configuration.screenWidthDp
    private var appliedFontScale = resources.configuration.fontScale
    private var appliedDensityDpi = resources.displayMetrics.densityDpi
    private var keyboardHeightPercent = ImeSettingsRepository.loadKeyboardHeightPercent(context)
    private var floatingWidthPercent = ImeSettingsRepository.loadFloatingWidthPercent(context)
    private var floatingOpacityPercent = ImeSettingsRepository.loadFloatingOpacityPercent(context)
    private var referenceScale = if (standalonePanel) 1f else ImeReferenceSizing.scale(context)
    private var floatingWindowMode = false
    /** Floating that the landscape setting started: it keeps the compact landscape rows. */
    private var floatingCompact = false
    private var layoutMetrics = buildLayoutMetrics()
    /** Landscape uses compact rows, except in floating mode, which keeps portrait size. */
    private fun buildLayoutMetrics() = KeyboardLayoutMetrics(
        landscape = appliedOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            (!floatingWindowMode || floatingCompact),
        fontScale = appliedFontScale,
        heightPercent = keyboardHeightPercent,
        availableWidthDp = resources.configuration.screenWidthDp,
    )
    private var lastTextMode = KeyboardMode.PINYIN_26
    private var preferredChineseMode = ImeSettingsRepository.loadPreferredChineseMode(context)
    protected var panel = Panel.NONE
    // Nested panel flows must be reversible. For example, Settings -> Fuzzy
    // settings should return to Settings instead of unexpectedly closing all
    // the way back to the keyboard.
    private val panelBackStack = mutableListOf<Panel>()
    fun currentPanel(): Panel = panel

    /** Persist the standalone settings panel's viewport across Activity recreation. */
    internal fun settingsScrollPosition(): Int =
        settingsPanelController.scrollPosition()

    internal fun restoreSettingsScrollPosition(scrollY: Int) {
        settingsPanelController.restoreScrollPosition(scrollY)
    }

    /** Refresh data owned by auxiliary editor Activities when they return. */
    internal fun refreshAuxiliaryContent() {
        when (panel) {
            Panel.CLIPBOARD -> renderClipboard(reusePanel = true)
            Panel.SYMBOLS -> panelRenderer.refreshCustomSymbols()
            else -> Unit
        }
        if (panel == Panel.NONE) {
            when (mode) {
                KeyboardMode.PINYIN_9, KeyboardMode.STROKE -> {
                    nineKeySymbolRailController?.refreshSymbols()
                    applyThemeToSubtree(this)
                }
                KeyboardMode.DIGITS -> {
                    numericKeyboardRenderer.refreshSymbols()
                    applyThemeToSubtree(this)
                }
                else -> Unit
            }
        }
    }

    private var shiftState = ShiftState.LOWERCASE
    private var soundEnabled = ImeSettingsRepository.loadSound(context)
    private var hapticEnabled = ImeSettingsRepository.loadHaptic(context)
    private val keyHaptics = KeyHaptics(context).apply {
        strengthPercent = ImeSettingsRepository.loadHapticStrengthPercent(context)
        style = HapticStyle.fromKey(ImeSettingsRepository.loadHapticStyle(context))
    }
    private var lastStrengthPreviewMs = 0L
    private val keySounds = KeySounds(context).apply {
        style = KeySoundStyle.fromKey(ImeSettingsRepository.loadKeySoundStyle(context))
    }
    private var popupEnabled = ImeSettingsRepository.loadPopup(context)
    private var fuzzyEnabled = ImeSettingsRepository.loadFuzzy(context)

    protected open fun onViewHierarchyRebuilt() = Unit

    private val pinyinBuffer = StringBuilder()
    private var lastNineDigits = ""

    /** A syllable the user fixed that is still the open tail; typing more digits seals it. */
    private var lockedNineTail: String? = null
    private var lastNineCandidates = emptyList<String>()
    private var lastNineSegmentPrefix = ""
    private var lastNinePinyinPaths = emptyList<String>()
    private var currentCandidates = emptyList<String>()
    private var voiceGestureSession = false
    // Floating mode changes only the IME window bounds. The keyboard surface
    // itself remains the same normal keyboard used in portrait mode.
    private val floatingKeyboardController: FloatingKeyboardController by lazy {
        FloatingKeyboardController(
            context = context,
            toPx = ::dp,
            mainDock = mainDock,
            canDrag = { panel == Panel.NONE },
            onDragBy = listener::onFloatingKeyboardDragged,
            onDock = { listener.onFloatingKeyboardChanged(false) },
        )
    }
    private var contentInsetPx = dp(0)
    private var navigationBottomInsetPx = 0
    private val themeApplier: ImeThemeApplier by lazy {
        ImeThemeApplier(
            toPx = ::dp,
            statefulRounded = ::statefulRounded,
            keyMainTextScale = { referenceScale },
            referenceScale = { referenceScale },
            toggleState = ::onState,
            isSideKey = { key ->
                key.getTag(MARK_SIDE_KEY) == true ||
                    (key.parent as? View)?.tag in
                    setOf("pinyin9-actions", "t9-actions", "digits-actions")
            },
            isFunctionKey = { key ->
                key.getTag(MARK_FUNCTION_KEY) == true
            },
            isWhiteKey = { key ->
                key.getTag(MARK_WHITE_KEY) == true
            },
        )
    }
    private val keyPopupController = KeyPopupController(
        host = this,
        dp = ::dp,
        contentInsetPx = { contentInsetPx },
        tokens = {
            theme.tokens(appearance, isNight())
        },
        rounded = { color, radius -> ImeDrawableFactory.rounded(color, radius) },
        statefulRounded = { normal, pressed, radius -> statefulRounded(normal, pressed, radius) },
        contrastText = ImeDrawableFactory::contrastText,
        feedback = ::feedback,
        hoverFeedback = ::hapticFeedback,
        onSymbolSelected = listener::onCharacter,
    )
    private val nineKeySegmentRepairController = NineKeySegmentRepairController(
        context = context,
        composition = { composition },
        isNineKeyActive = { mode == KeyboardMode.PINYIN_9 },
        listener = listener,
    )
    private var nineKeySymbolRailController: NineKeySymbolRailController? = null
    private val pinyin9Renderer: Pinyin9KeyboardRenderer by lazy {
        Pinyin9KeyboardRenderer(
            context = context,
            keyboardBody = keyboardBody,
            toPx = ::dp,
            keyRowHeightDp = ::keyRowHeightDp,
            nineGridHeightDp = ::nineGridHeightDp,
            nineBodyHeightDp = ::nineBodyHeightDp,
            doubleKeyHeightDp = ::doubleKeyHeightDp,
            createKey = { text, function, secondary, textSize, onTap ->
                key(
                    text = text,
                    func = function,
                    secondary = secondary,
                    mainTextSizeOverride = textSize,
                    onTap = onTap,
                )
            },
            createBackspaceKey = ::backspaceKey,
            createSpaceVoiceKey = { label, onTap ->
                spaceVoiceKey(label, white = true, onTap = onTap)
            },
            createSymbolRail = { requireNineKeySymbolRailController().buildRail() },
            markSideKey = { key -> key.setTag(MARK_SIDE_KEY, true) },
            markWhiteKey = { key -> key.setTag(MARK_WHITE_KEY, true) },
            onDigitKeyCreated = nineKeySegmentRepairController::bindDigitKey,
            onNineKey = ::onNineKey,
            onPinyinSegment = ::onPinyinSegment,
            onShowChoicePopup = ::showChoicePopup,
            onShowChoiceRows = ::showChoiceRows,
            swipeUpEnabled = { ImeSettingsRepository.loadSwipeUpDigits(context) },
            onCommitCharacter = ::commitKeyboardCharacter,
            onShowSymbols = { showPanel(Panel.SYMBOLS) },
            onDigits = { setMode(KeyboardMode.DIGITS) },
            onSpace = ::commitFirstCandidateOrSpace,
            onModeSwitch = ::cycleMode,
            onRetranslate = ::retype,
            onEnter = listener::onEnter,
        )
    }
    private val strokeRenderer: StrokeKeyboardRenderer by lazy {
        StrokeKeyboardRenderer(
            context = context,
            keyboardBody = keyboardBody,
            toPx = ::dp,
            keyRowHeightDp = ::keyRowHeightDp,
            gridHeightDp = ::nineGridHeightDp,
            bodyHeightDp = ::nineBodyHeightDp,
            createKey = { text, function, secondary, textSize, onTap ->
                key(
                    text = text,
                    func = function,
                    secondary = secondary,
                    mainTextSizeOverride = textSize,
                    onTap = onTap,
                )
            },
            createBackspaceKey = ::backspaceKey,
            createSpaceVoiceKey = { label, onTap ->
                spaceVoiceKey(label, white = true, onTap = onTap)
            },
            // The nine-key rail: stroke glyphs are not digits, so it stays on symbols.
            createSymbolRail = { requireNineKeySymbolRailController().buildRail() },
            markSideKey = { key -> key.setTag(MARK_SIDE_KEY, true) },
            markWhiteKey = { key -> key.setTag(MARK_WHITE_KEY, true) },
            onStroke = ::onStrokeKey,
            swipeUpEnabled = { ImeSettingsRepository.loadSwipeUpDigits(context) },
            onCommitCharacter = ::commitKeyboardCharacter,
            onShowSymbols = { showPanel(Panel.SYMBOLS) },
            onDigits = { setMode(KeyboardMode.DIGITS) },
            onSpace = ::commitFirstCandidateOrSpace,
            onModeSwitch = ::cycleMode,
            onRetype = ::retype,
            onEnter = listener::onEnter,
        )
    }
    private val numericKeyboardRenderer: NumericKeyboardRenderer by lazy {
        NumericKeyboardRenderer(
            context = context,
            keyboardBody = keyboardBody,
            toPx = ::dp,
            keyRowHeightDp = ::keyRowHeightDp,
            nineGridHeightDp = ::nineGridHeightDp,
            nineBodyHeightDp = ::nineBodyHeightDp,
            createKey = { text, function, textSize, onTap ->
                key(
                    text = text,
                    func = function,
                    secondary = null,
                    mainTextSizeOverride = textSize,
                    onTap = onTap,
                )
            },
            createBackspaceKey = ::backspaceKey,
            createSpaceVoiceKey = { label, onTap ->
                spaceVoiceKey(label, white = true, onTap = onTap)
            },
            markSideKey = { key -> key.setTag(MARK_SIDE_KEY, true) },
            markWhiteKey = { key -> key.setTag(MARK_WHITE_KEY, true) },
            onCommitCharacter = ::commitKeyboardCharacter,
            onFeedback = ::feedback,
            onShowSymbols = { showPanel(Panel.SYMBOLS) },
            onReturnToText = { setMode(lastTextMode) },
            onSpace = listener::onSpace,
            onEnter = listener::onEnter,
        )
    }
    private var systemBottomInsetPx = 0
    private val maxContentWidthDp = 600
    private val maxLandscapeContentWidthDp = 900
    // Portrait row height follows the available screen width. Landscape stays
    // compact; larger system fonts and the height preference grow the rows.
    private fun keyRowHeightDp(): Int = layoutMetrics.keyRowHeightDp
    private fun nineGridHeightDp(): Int = layoutMetrics.nineGridHeightDp
    private fun nineBodyHeightDp(): Int = layoutMetrics.nineBodyHeightDp
    private fun doubleKeyHeightDp(): Int = layoutMetrics.doubleKeyHeightDp
    private fun imeHeightDp(): Int = layoutMetrics.imeHeightDp + if (floatingWindowMode) 20 else 0

    /** The top zone is reserved at its composed height in every state. */
    private fun topZoneHeightDp(): Int = layoutMetrics.topZoneHeightDp
    private fun keyboardBodyHeightDp(): Int = layoutMetrics.keyboardBodyHeightDp
    private fun panelBodyHeightDp(): Int = layoutMetrics.panelBodyHeightDp
    private var syncingComposition = false
    private var passwordField = false
    private var inlineEditTarget: EditText? = null

    private lateinit var mainDock: LinearLayout
    private lateinit var keyboardHost: FrameLayout
    private lateinit var topZone: ImeTopZone
    private lateinit var candidateBarController: CandidateBarController
    private val toolbarRow: LinearLayout get() = topZone.toolbarRow
    private val composeZone: LinearLayout get() = topZone.composeZone
    private val composition: EditText get() = topZone.composition
    private val associationRow: LinearLayout get() = topZone.associationRow
    private val keyboardBody = LinearLayout(context)
    private val expandedPanel = LinearLayout(context)
    private val candidateOverlay = LinearLayout(context)
    private val emojiCellFactory: EmojiCellFactory by lazy {
        EmojiCellFactory(
            context = context,
            toPx = ::dp,
            onFeedback = ::feedback,
            onEmojiSelected = listener::onEmojiSelected,
        )
    }
    private val panelHeaderFactory: PanelHeaderFactory by lazy {
        PanelHeaderFactory(
            context = context,
            toPx = ::dp,
            previousPanel = { panelBackStack.lastOrNull() },
            onBack = ::closePanelToKeyboard,
            onFeedback = ::feedback,
            standalone = standalonePanel,
        )
    }
    private val panelRenderer: ImePanelRenderer by lazy {
        ImePanelRenderer(
            context = context,
            expandedPanel = expandedPanel,
            toPx = ::dp,
            panelBodyHeightPx = { dp(panelBodyHeightDp()) },
            imeHeightPx = { dp(imeHeightDp()) },
            createHeader = panelHeaderFactory::create,
            createKey = { text, function, textSize, onTap ->
                key(
                    text = text,
                    func = function,
                    secondary = null,
                    mainTextSizeOverride = textSize,
                    onTap = onTap,
                )
            },
            createPanelButton = ::button,
            createTitle = ::title,
            createEmojiCell = emojiCellFactory::create,
            gridCellParams = ::gridCellParams,
            currentMode = { mode },
            onModeSelected = { selected -> setMode(selected) },
            onShowPanel = ::showPanel,
            onEnableFloatingKeyboard = ::enableFloatingKeyboard,
            onOpenDataManagement = listener::onOpenDataManagement,
            onSymbolSelected = listener::onSymbolSelected,
            onCharacter = listener::onCharacter,
            onSpace = listener::onSpace,
            onFeedback = ::feedback,
            applyTheme = ::applyTheme,
            onHierarchyRebuilt = ::onViewHierarchyRebuilt,
        )
    }
    private val inlineVoicePresenter: InlineVoicePresenter by lazy {
        InlineVoicePresenter(
            handler = repeatHandler,
            toPx = ::dp,
            zone = { topZone.voiceInlineZone },
            icon = { topZone.voiceInlineIcon },
            status = { topZone.voiceInlineStatus },
            waves = { topZone.voiceInlineWaves },
            tokens = {
                theme.tokens(appearance, isNight())
            },
            isGestureSessionActive = { voiceGestureSession },
            isComposing = { composition.text?.isNotEmpty() == true },
            updateTopZone = ::updateTopZone,
        )
    }
    private val voicePanelController: VoicePanelController by lazy {
        VoicePanelController(
            context = context,
            expandedPanel = expandedPanel,
            toPx = ::dp,
            panelBodyHeightPx = { dp(panelBodyHeightDp()) },
            createHeader = panelHeaderFactory::create,
            createButton = ::button,
            listener = listener,
            isGestureSessionActive = { voiceGestureSession },
            onInlineState = { message, cancelling, error, rms ->
                inlineVoicePresenter.show(
                    message = message,
                    cancelling = cancelling,
                    error = error,
                    rms = rms,
                )
            },
            onHideInlineLater = { delayMs ->
                inlineVoicePresenter.hideLater(delayMs) {
                    !voicePanelController.active
                }
            },
            onFeedback = ::feedback,
            onSessionTerminal = {
                voiceGestureSession = false
                inlineVoicePresenter.stopPulse()
            },
        )
    }
    private val settingsPanelController: SettingsPanelController by lazy {
        SettingsPanelController(
            context = context,
            expandedPanel = expandedPanel,
            toPx = ::dp,
            createHeader = panelHeaderFactory::create,
            createSectionTitle = ::sectionTitle,
            createChipScroll = { labels, selected, onSelected -> panelRenderer.panelChipScroll(labels, selected, onSelected) },
            currentTheme = { theme },
            currentAppearance = { appearance },
            currentSound = { soundEnabled },
            currentHaptic = { hapticEnabled },
            currentHapticStrengthPercent = { keyHaptics.strengthPercent },
            currentHapticStyle = { keyHaptics.style },
            currentLandscapeLayout = { ImeSettingsRepository.loadLandscapeLayout(context) },
            currentKeySoundStyle = { keySounds.style },
            currentPopup = { popupEnabled },
            currentSwipeUpDigits = { ImeSettingsRepository.loadSwipeUpDigits(context) },
            currentExtraToggle = ::onState,
            currentFuzzy = { fuzzyEnabled },
            currentKeyboardHeightPercent = { keyboardHeightPercent },
            currentFloatingWidthPercent = { floatingWidthPercent },
            currentFloatingOpacityPercent = { floatingOpacityPercent },
            onThemeSelected = ::setTheme,
            onAppearanceSelected = { selected ->
                setAppearance(selected)
                listener.onAppearanceChanged(selected)
            },
            onToggleChanged = ::updateSettingToggle,
            onKeyboardHeightChanged = ::setKeyboardHeightPercent,
            onHapticStrengthChanged = ::setHapticStrengthPercent,
            onHapticStyleChanged = ::setHapticStyle,
            onLandscapeLayoutChanged = ::setLandscapeLayout,
            onKeySoundStyleChanged = ::setKeySoundStyle,
            onFloatingStyleChanged = ::setFloatingStyle,
            onShowFuzzySettings = { showPanel(Panel.FUZZY_SETTINGS) },
            onFuzzyRulesChanged = listener::onFuzzyRulesChanged,
            onOpenAbout = listener::onOpenAbout,
            onOpenDataManagement = listener::onOpenDataManagement,
            onFeedback = ::feedback,
            applyTheme = ::applyTheme,
            onHierarchyRebuilt = ::onViewHierarchyRebuilt,
        )
    }
    private val clipboardPanelController: ClipboardPanelController by lazy {
        ClipboardPanelController(
            context = context,
            expandedPanel = expandedPanel,
            toPx = ::dp,
            panelBodyHeightPx = { dp(panelBodyHeightDp()) },
            createHeader = panelHeaderFactory::create,
            createKey = { text, textSize, onTap ->
                key(
                    text = text,
                    func = false,
                    secondary = null,
                    mainTextSizeOverride = textSize,
                    onTap = onTap,
                )
            },
            createPanelButton = ::button,
            createSectionTitle = ::sectionTitle,
            createChipScroll = { labels, selected, onSelected -> panelRenderer.panelChipScroll(labels, selected, onSelected) },
            createVerticalScroll = panelRenderer::panelVerticalScroll,
            rememberVerticalScroll = panelRenderer::rememberPanelVerticalScroll,
            onCharacter = listener::onCharacter,
            onOpenQuickPhraseEditor = ::openQuickPhraseEditor,
            onFeedback = ::feedback,
            applyTheme = ::applyTheme,
            onHierarchyRebuilt = ::onViewHierarchyRebuilt,
            onContentLoaded = ::onClipboardContentLoaded,
            focusEntryPoint = ::focusPanelEntryPoint,
        )
    }
    private val textEditorPanelController: TextEditorPanelController by lazy {
        TextEditorPanelController(
            context = context,
            expandedPanel = expandedPanel,
            toPx = ::dp,
            panelBodyHeightPx = { dp(panelBodyHeightDp()) },
            createHeader = panelHeaderFactory::create,
            createKey = { text, textSize, onTap ->
                key(
                    text = text,
                    func = true,
                    secondary = null,
                    mainTextSizeOverride = textSize,
                    onTap = onTap,
                )
            },
            createPanelButton = ::button,
            isPasswordField = { passwordField },
            onTextEdit = listener::onTextEdit,
            onFeedback = ::feedback,
        )
    }
    private val pinyin26Renderer: Pinyin26KeyboardRenderer by lazy {
        Pinyin26KeyboardRenderer(
            context = context,
            keyboardBody = keyboardBody,
            toPx = ::dp,
            keyRowHeightDp = ::keyRowHeightDp,
            createKey = { text, function, secondary, textSize, iconRes, onTap ->
                key(
                    text = text,
                    func = function,
                    secondary = secondary,
                    mainTextSizeOverride = textSize,
                    iconRes = iconRes,
                    onTap = onTap,
                )
            },
            createBackspaceKey = ::backspaceKey,
            createSpaceVoiceKey = { label, onTap ->
                spaceVoiceKey(label, white = true, onTap = onTap)
            },
            onLetter = ::onKeyTapped,
            onPinyinSegment = ::onPinyinSegment,
            onShowChoicePopup = ::showChoicePopup,
            onCommitCharacter = ::commitKeyboardCharacter,
            hintsEnabled = { ImeSettingsRepository.loadLetterHints(context) },
            swipeUpEnabled = { ImeSettingsRepository.loadSwipeUpDigits(context) },
            onShift = ::cycleShift,
            onShiftLongPress = ::lockShift,
            onDigits = { setMode(KeyboardMode.DIGITS) },
            onModeSwitch = ::cycleMode,
            onSpace = listener::onSpace,
            onEnter = listener::onEnter,
        )
    }

    init {
        tag = "ime_root"
        // Some IME windows inherit the host's disabled sound-effect flag.
        // Keep the view channel enabled; the preference still gates feedback().
        isSoundEffectsEnabled = true
        val rootHeight = if (standalonePanel) {
            FrameLayout.LayoutParams.MATCH_PARENT
        } else {
            dp(imeHeightDp())
        }
        val dockHeight = if (standalonePanel) {
            FrameLayout.LayoutParams.MATCH_PARENT
        } else {
            dp(imeHeightDp())
        }
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            rootHeight,
        )
        mainDock = LinearLayout(context).apply {
            tag = "main-dock"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dockHeight,
            )
        }
        keyboardHost = FrameLayout(context).apply {
            tag = "keyboard-host"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(keyboardBodyHeightDp()),
            )
        }
        keyboardBody.orientation = LinearLayout.VERTICAL
        keyboardBody.tag = "keyboard-body"
        keyboardBody.setPadding(dp(0), dp(6), dp(0), dp(10))
        expandedPanel.orientation = LinearLayout.VERTICAL
        expandedPanel.tag = "panel-overlay"
        expandedPanel.visibility = View.GONE
        candidateOverlay.orientation = LinearLayout.VERTICAL
        candidateOverlay.tag = "candidate-overlay"
        candidateOverlay.visibility = View.GONE

        keyboardHost.addView(
            keyboardBody,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        keyboardHost.addView(
            candidateOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        mainDock.addView(
            floatingKeyboardController.handle,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)),
        )

        buildTopZone()
        mainDock.addView(keyboardHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(keyboardBodyHeightDp()),
        ))
        addView(
            mainDock,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                rootHeight,
            ),
        )
        addView(
            expandedPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                rootHeight,
            ),
        )
        renderModeBody()
        applyTheme()

        setOnApplyWindowInsetsListener { _, insets ->
            systemBottomInsetPx = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }.coerceAtMost(dp(32))
            val next = bottomInsetFor(systemBottomInsetPx)
            if (next != navigationBottomInsetPx) {
                navigationBottomInsetPx = next
                applyDynamicHeights()
                requestLayout()
            }
            updateResponsiveGeometry(width)
            insets
        }
    }

    /**
     * Docked, the keyboard sits above the system bar. A floating card gets a strip
     * of its own for the back and keyboard-switch buttons the system draws inside
     * the IME window, so they never cover the bottom row (Gboard does the same).
     */
    private fun bottomInsetFor(reportedPx: Int): Int {
        val clamped = ImeBottomInsetPolicy.clampInset(reportedPx, dp(32))
        return if (floatingWindowMode) maxOf(clamped, dp(FLOATING_NAV_STRIP_DP)) else clamped
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateResponsiveGeometry(width)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (appearance == ImeAppearance.SYSTEM) applyTheme()
        // Resize containers and rebuild rows so compact landscape heights / larger
        // font rows take effect. Unrelated configuration changes (locale, UI mode,
        // keyboard presence) do not alter the derived geometry, so they must not
        // tear down the key surface.
        val geometryChanged = newConfig.screenWidthDp != appliedScreenWidthDp ||
            newConfig.orientation != appliedOrientation ||
            newConfig.fontScale != appliedFontScale ||
            newConfig.densityDpi != appliedDensityDpi
        appliedScreenWidthDp = newConfig.screenWidthDp
        appliedOrientation = newConfig.orientation
        appliedFontScale = newConfig.fontScale
        appliedDensityDpi = newConfig.densityDpi
        layoutMetrics = buildLayoutMetrics()
        if (!geometryChanged) return
        // Do not yank the user out of an open panel.
        applyDynamicHeights()
        if (standalonePanel) return
        when {
            panel == Panel.NONE -> renderModeBody()
            panel == Panel.VOICE && (voicePanelController.active || voicePanelController.pending || voiceGestureSession) -> {
                // Rebuilding the voice panel would replace the closures that
                // own the active recognition session. Resize its body in place
                // and let the session continue without a visual reset.
                (expandedPanel.getChildAt(1)?.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                    params.height = dp(panelBodyHeightDp())
                    expandedPanel.getChildAt(1).layoutParams = params
                }
            }
            else -> renderPanel(panel)
        }
    }

    /** True while any key in this keyboard still owns a press. */
    private fun hasPressedKey(): Boolean {
        fun walk(view: View): Boolean {
            if (view.isPressed) return true
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (walk(view.getChildAt(index))) return true
                }
            }
            return false
        }
        // Space owns a physical touch stream through its touch listener. Some
        // Android versions clear View.isPressed while that stream is still
        // active, so a pending row rebuild must also respect the gesture owner.
        return spaceVoiceGestureController.trackingTouch || walk(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        shutdown()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (standalonePanel) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // The content box (padding, row widths) follows the width the parent
        // offers. Apply it here, before the children are measured: doing it from
        // onSizeChanged changes paddings of views laid out later in the same
        // pass and the framework then keeps their stale measurements.
        val offeredWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && offeredWidth > 0) {
            applyingGeometryInMeasure = true
            try {
                updateResponsiveGeometry(offeredWidth)
            } finally {
                applyingGeometryInMeasure = false
            }
        }
        val desiredHeight = dp(imeHeightDp())
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val baseHeight = when {
            mode == MeasureSpec.AT_MOST -> minOf(desiredHeight, size)
            mode == MeasureSpec.EXACTLY && size < desiredHeight -> size
            else -> desiredHeight
        }
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(baseHeight, MeasureSpec.EXACTLY),
        )
        if (navigationBottomInsetPx > 0) {
            val targetHeight = ImeBottomInsetPolicy.measuredHeight(
                baseHeightPx = measuredHeight,
                bottomInsetPx = navigationBottomInsetPx,
                measureMode = mode,
                measureSizePx = size,
            )
            if (targetHeight != measuredHeight) {
                setMeasuredDimension(measuredWidth, targetHeight)
            }
        }
    }

    /**
     * Keep the dock/host/panel heights aligned with the current orientation and
     * font scale. Construction already uses the current values; this resizes a
     * retained view on a configuration change before the rows are rebuilt.
     */
    private fun applyDynamicHeights() {
        if (standalonePanel) return
        if (::topZone.isInitialized) topZone.setLandscapeStrip(layoutMetrics.landscape)
        val totalPx = dp(imeHeightDp())
        val bodyPx = dp(keyboardBodyHeightDp())
        (layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.height != totalPx + navigationBottomInsetPx) {
                it.height = totalPx + navigationBottomInsetPx
                layoutParams = it
            }
        }
        (mainDock.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.height != totalPx) {
                it.height = totalPx
                mainDock.layoutParams = it
            }
        }
        (expandedPanel.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.height != totalPx) {
                it.height = totalPx
                expandedPanel.layoutParams = it
            }
        }
        (keyboardHost.layoutParams as? LinearLayout.LayoutParams)?.let {
            if (it.height != bodyPx) {
                it.height = bodyPx
                keyboardHost.layoutParams = it
            }
        }
    }

    /**
     * The 390dp prototype is a design reference only. Runtime content width is
     * derived from the measured IME width, with a 600dp maximum on tablets and
     * foldables. The bottom inset is added only when the system reports one so
     * the last row cannot sit underneath a gesture/navigation bar.
     */
    private fun updateResponsiveGeometry(measuredWidthPx: Int) {
        if (measuredWidthPx <= 0) return

        val nextScale = if (standalonePanel) 1f else ImeReferenceSizing.scale(context, measuredWidthPx, landscapeCompact = !floatingWindowMode || floatingCompact)
        // Configuration.screenWidthDp is a whole number while the measured width
        // is not (411dp vs 411.43dp on a 1080px / 420dpi screen), so the two
        // scales differ by up to 1/390 for the same window. That rounding noise
        // used to cross the old 0.001 threshold and rebuilt every key from
        // onSizeChanged, in the middle of the first layout pass. Only a real
        // change (floating window, rotation) rescales.
        if (kotlin.math.abs(nextScale - referenceScale) > SCALE_CHANGE_EPSILON) {
            val ratio = nextScale / referenceScale
            referenceScale = nextScale
            rescaleTopZone(topZone, ratio)
            applyDynamicHeights()
            if (!standalonePanel) {
                if (panel == Panel.NONE) renderModeBody() else renderPanel(panel)
            }
            applyTheme()
            // Rows rebuilt here are added after their parent was measured and
            // would stay at 0x0 (a blank keyboard on first show) otherwise.
            if (!applyingGeometryInMeasure) scheduleRelayout()
        }

        (mainDock.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.TOP
            mainDock.layoutParams = params
        }

        if (floatingKeyboardController.enabled) {
            // A configuration pass can briefly report the physical display
            // width before WindowManager applies the floating window bounds.
            // Keep the normal keyboard's content inset local to its window.
            contentInsetPx = dp(0)
            keyboardBody.setPadding(contentInsetPx, dp(6), contentInsetPx, dp(10))
            keyboardBody.findViewWithTag<View>("key-row-secondary")?.let { row ->
                // The portrait layout narrows this row to 90% of the full
                // display for optical centering. A floating window can be
                // narrower than the display, so that cached width would clip
                // the first and last keys. Let the row fill its local window.
                (row.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                    params.width = (measuredWidthPx * 0.9f).toInt()
                    params.gravity = Gravity.CENTER_HORIZONTAL
                    row.layoutParams = params
                }
            }
            expandedPanel.setPadding(contentInsetPx, 0, contentInsetPx, 0)
            candidateOverlay.setPadding(contentInsetPx, 0, contentInsetPx, 0)
            topZone.setContentInset(contentInsetPx)
            if (!applyingGeometryInMeasure) {
                requestLayout()
                scheduleRelayout()
            }
            return
        }
        val minimumInset = dp(0)
        val landscape = appliedOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val maxWidth = when {
            standalonePanel -> dp(maxContentWidthDp)
            // Landscape rows are short, so the width is what makes keys easy to hit.
            // Like Gboard and the Chinese keyboards, fill the screen up to a tablet cap
            // instead of shrinking the keys to the height-derived portrait width.
            landscape -> (maxLandscapeContentWidthDp * resources.displayMetrics.density).toInt()
            else -> (minOf(maxContentWidthDp.toFloat(), 390f * referenceScale) * resources.displayMetrics.density).toInt()
        }
        contentInsetPx = maxOf(minimumInset, (measuredWidthPx - maxWidth) / 2)
        keyboardBody.setPadding(
            contentInsetPx,
            dp(6),
            contentInsetPx,
            dp(10),
        )
        keyboardBody.findViewWithTag<View>("key-row-secondary")?.let { row ->
            val rowWidth = ((measuredWidthPx - contentInsetPx * 2) * 0.9f).toInt()
            val params = row.layoutParams as? LinearLayout.LayoutParams
            if (params != null) {
                params.gravity = Gravity.CENTER_HORIZONTAL
                if (params.width != rowWidth) params.width = rowWidth
                row.layoutParams = params
            }
        }
        expandedPanel.setPadding(contentInsetPx, 0, contentInsetPx, 0)
        candidateOverlay.setPadding(contentInsetPx, 0, contentInsetPx, 0)
        topZone.setContentInset(contentInsetPx)
        if (!applyingGeometryInMeasure) {
            requestLayout()
            scheduleRelayout()
        }
    }

    private var relayoutPosted = false
    private var applyingGeometryInMeasure = false

    /**
     * updateResponsiveGeometry runs from onSizeChanged, i.e. inside a layout
     * pass, and changes paddings and row widths of views that are laid out later
     * in that same pass. Their own requestLayout() calls are lost: each view
     * clears its force-layout flag when it finishes laying out, so the framework
     * sees no pending request and never re-measures them (the landscape keyboard
     * kept rows as wide as the whole window inside a clamped content box).
     * requestLayout() on the root is not enough either, it marks only the root
     * and its ancestors. So once the pass is over, flag the containers that
     * were changed.
     */
    private fun scheduleRelayout() {
        if (relayoutPosted) return
        relayoutPosted = true
        post {
            relayoutPosted = false
            keyboardBody.requestLayout()
            if (::topZone.isInitialized) topZone.requestLayout()
            expandedPanel.requestLayout()
            candidateOverlay.requestLayout()
            requestLayout()
            invalidate()
        }
    }

    private fun rescaleTopZone(view: View, ratio: Float) {
        view.layoutParams?.let { params ->
            if (params.width > 0) params.width = kotlin.math.round(params.width * ratio).toInt()
            if (params.height > 0) params.height = kotlin.math.round(params.height * ratio).toInt()
            if (params is ViewGroup.MarginLayoutParams) {
                params.setMargins((params.leftMargin * ratio).toInt(), (params.topMargin * ratio).toInt(), (params.rightMargin * ratio).toInt(), (params.bottomMargin * ratio).toInt())
            }
            view.layoutParams = params
        }
        view.minimumHeight = (view.minimumHeight * ratio).toInt()
        view.minimumWidth = (view.minimumWidth * ratio).toInt()
        view.setPadding((view.paddingLeft * ratio).toInt(), (view.paddingTop * ratio).toInt(), (view.paddingRight * ratio).toInt(), (view.paddingBottom * ratio).toInt())
        if (view is ViewGroup) for (i in 0 until view.childCount) rescaleTopZone(view.getChildAt(i), ratio)
    }

    /** Keep the top zone at one height so composing never relayouts the keyboard. */
    private fun buildTopZone() {
        topZone = ImeTopZone(
            context = context,
            toPx = ::dp,
            onFeedback = ::feedback,
            isCompositionSyncing = { syncingComposition },
            onCompositionEdited = ::onCompositionEdited,
            onKeyboardSelect = { showPanel(Panel.KEYBOARD_SELECT) },
            onClipboard = { showPanel(Panel.CLIPBOARD) },
            onEmoji = { showPanel(Panel.EMOJI) },
            onSymbols = { showPanel(Panel.SYMBOLS) },
            onTextEditor = { showPanel(Panel.TEXT_EDITOR) },
            onQuickPhrases = {
                clipboardPanelController.selectTab(1)
                showPanel(Panel.CLIPBOARD)
            },
            onHideKeyboard = ::hideKeyboard,
            onTools = { showPanel(Panel.TOOLS) },
            onExpandCandidates = {
                val open = !candidateBarController.expandedOpen
                candidateBarController.renderExpanded(
                    open = open,
                    candidates = currentCandidates,
                    compositionPreview = composition.text.toString(),
                )
                updateTopZone(composition.text?.isNotEmpty() == true)
                listener.onCandidateExpanded(open)
            },
            onUndo = { listener.onTextEdit("undo") },
            onAssociationDismiss = ::clearAssociationCandidates,
        )
        candidateBarController = CandidateBarController(
            context = context,
            row = topZone.candidateRow,
            scroll = topZone.candidateScroll,
            expandButton = topZone.candidateExpandButton,
            overlay = candidateOverlay,
            keyboardBody = keyboardBody,
            toPx = ::dp,
            keyRowHeightPx = { dp(keyRowHeightDp()) },
            createHeader = { panelHeaderFactory.create("候选字词") },
            createExpandedCandidate = { candidate ->
                key(candidate, false, null, 1f, ImeTypographyTokens.CANDIDATE_SP) {
                    listener.onCandidateSelected(candidate)
                }.apply {
                    allowTwoLineLabel()
                    contentDescription = "候选:$candidate"
                    setOnLongClickListener {
                        feedback()
                        listener.onCandidateLongPressed(candidate)
                        true
                    }
                }
            },
            createEmptyLabel = { title("暂无候选", small = true) },
            applyTheme = ::applyTheme,
            tokens = {
                theme.tokens(appearance, isNight())
            },
            statefulBackground = ::statefulRounded,
            onFeedback = ::feedback,
            onCandidateSelected = listener::onCandidateSelected,
            onCandidateLongPressed = listener::onCandidateLongPressed,
        )
        mainDock.addView(
            topZone,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(topZoneHeightDp()),
            ),
        )
        candidateBarController.syncExpandControl(
            hasCandidates = currentCandidates.isNotEmpty(),
        )
    }

    private fun hideKeyboard() {
        val service = context as? InputMethodService
        if (service != null) {
            service.requestHideSelf(0)
        } else {
            val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            manager?.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    fun cycleMode() {
        val next = when (mode) {
            KeyboardMode.PINYIN_26, KeyboardMode.PINYIN_9, KeyboardMode.STROKE -> KeyboardMode.ENGLISH_26
            KeyboardMode.ENGLISH_26 -> preferredChineseMode
            KeyboardMode.DIGITS -> lastTextMode
        }
        setMode(next)
    }

    fun setMode(newMode: KeyboardMode, notifyListener: Boolean = true) {
        // Choice popups live directly under the root, outside keyboardBody.
        // A mode switch can be triggered without a fresh touch (accessibility,
        // programmatic editor policy), so it must explicitly retire any popup
        // before rebuilding/removing its anchor.
        hidePopup()
        if (newMode != KeyboardMode.DIGITS) {
            lastTextMode = newMode
            if (newMode.isChineseLayout) {
                // Persist the 26-key/9-key/stroke choice so it survives process death.
                if (preferredChineseMode != newMode) {
                    ImeSettingsRepository.savePreferredChineseMode(context, newMode)
                }
                preferredChineseMode = newMode
            }
        }
        if (panel != Panel.NONE) dismissPanelForModeSwitch()
        val layoutChanged = mode != newMode
        if (
            layoutChanged &&
            (voiceGestureSession || voicePanelController.active || voicePanelController.pending)
        ) {
            // A mode change replaces the interaction surface. Inline/accessibility
            // voice can be active without a pressed space key, so do not let the
            // old recognition session continue behind the new keyboard mode.
            stopVoiceIfActive()
        }
        mode = newMode
        panelRenderer.syncSymbolCategoryForMode(newMode)
        clearAssociationCandidates()
        pinyinBuffer.clear()
        lastNineDigits = ""
        lastNineCandidates = emptyList()
        lastNineSegmentPrefix = ""
        lastNinePinyinPaths = emptyList()
        currentCandidates = emptyList()
        // Rebuild the key rows (and play the switch fade) only when the layout
        // actually changes. Re-focusing another field in the same mode now reuses
        // the existing rows instead of recreating ~150 views on every focus.
        if (layoutChanged || renderedMode != newMode) {
            keyboardBody.animate().cancel()
            keyboardBody.alpha = 0.96f
            renderModeBody()
            keyboardBody.animate().alpha(1f).setDuration(ImeMotionTokens.SURFACE_FADE_MS).start()
        }
        if (notifyListener) listener.onModeChanged(newMode)
    }

    fun showPanel(newPanel: Panel) {
        if (newPanel == Panel.NONE || newPanel == Panel.CANDIDATE_EXPANDED) return
        if (panel == Panel.CLIPBOARD && newPanel != Panel.CLIPBOARD) {
            clipboardPanelController.invalidatePendingLoad()
        }
        hidePopup()
        if (
            newPanel != Panel.VOICE &&
            (
                panel == Panel.VOICE ||
                    voiceGestureSession ||
                    voicePanelController.active ||
                    voicePanelController.pending
            )
        ) {
            // Inline voice normally lives while panel == NONE. Replacing the
            // keyboard surface with another panel must not leave that session
            // recording behind a hidden space key.
            stopVoiceIfActive()
        }
        if (panel != Panel.NONE && panel != newPanel) panelBackStack += panel
        panel = newPanel
        mainDock.visibility = View.GONE
        listener.onPanelChanged(newPanel)
        renderPanel(newPanel)
        animatePanelEntrance()
    }

    /** Enter floating mode from the tools page without replacing the keyboard. */
    private fun enableFloatingKeyboard() {
        hidePopup()
        if (panel != Panel.NONE) {
            if (panel == Panel.CLIPBOARD) clipboardPanelController.invalidatePendingLoad()
            stopVoiceIfActive()
            panelBackStack.clear()
            panel = Panel.NONE
            expandedPanel.animate().cancel()
            expandedPanel.visibility = View.GONE
            mainDock.visibility = View.VISIBLE
            keyboardBody.visibility = View.VISIBLE
            candidateOverlay.visibility = View.GONE
            listener.onPanelChanged(Panel.NONE)
        }
        floatingKeyboardController.setEnabled(true)
        listener.onFloatingKeyboardChanged(true)
    }

    /** Keep content geometry local when the service changes the window bounds. */
    fun setFloatingWindowMode(enabled: Boolean, compact: Boolean = false) {
        floatingWindowMode = enabled
        floatingCompact = enabled && compact
        navigationBottomInsetPx = bottomInsetFor(systemBottomInsetPx)
        floatingKeyboardController.setEnabled(enabled)
        topZone.setCompactToolbar(enabled)
        layoutMetrics = buildLayoutMetrics()
        renderedMode = null
        topZone.setLongPressDrag(if (enabled) floatingKeyboardController.dragController else null)
        applyDynamicHeights()
        // Floating landscape uses portrait rows, so the key surface must be rebuilt.
        if (!standalonePanel && panel == Panel.NONE) renderModeBody()
        if (enabled) {
            contentInsetPx = dp(0)
            keyboardBody.setPadding(contentInsetPx, dp(6), contentInsetPx, dp(10))
            expandedPanel.setPadding(contentInsetPx, 0, contentInsetPx, 0)
            candidateOverlay.setPadding(contentInsetPx, 0, contentInsetPx, 0)
            topZone.setContentInset(contentInsetPx)
        } else {
            updateTopZone(composition.text?.isNotEmpty() == true)
            if (width > 0) updateResponsiveGeometry(width)
        }
        applyTheme()
        requestLayout()
    }

    private fun dismissPanelForModeSwitch() {
        if (panel == Panel.NONE) return
        if (panel == Panel.CLIPBOARD) clipboardPanelController.invalidatePendingLoad()
        stopVoiceIfActive()
        panelBackStack.clear()
        panel = Panel.NONE
        expandedPanel.animate().cancel()
        expandedPanel.visibility = View.GONE
        mainDock.visibility = View.VISIBLE
        keyboardBody.visibility = View.VISIBLE
        candidateOverlay.visibility = View.GONE
        listener.onPanelChanged(Panel.NONE)
    }

    fun closePanelToKeyboard(): Boolean {
        if (candidateBarController.expandedOpen) {
            candidateBarController.renderExpanded(
                open = false,
                candidates = currentCandidates,
                compositionPreview = composition.text.toString(),
            )
            updateTopZone(composition.text?.isNotEmpty() == true)
            listener.onCandidateExpanded(false)
            return true
        }
        if (panel == Panel.NONE) return false
        if (panel == Panel.CLIPBOARD) clipboardPanelController.invalidatePendingLoad()
        if (panelBackStack.isNotEmpty()) {
            stopVoiceIfActive()
            panel = panelBackStack.removeAt(panelBackStack.lastIndex)
            listener.onPanelChanged(panel)
            renderPanel(panel)
            animatePanelEntrance(reverse = true)
            return true
        }
        stopVoiceIfActive()
        panel = Panel.NONE
        if (renderedMode != mode) {
            renderModeBody()
        }
        expandedPanel.animate().cancel()
        expandedPanel.visibility = View.GONE
        mainDock.animate().cancel()
        mainDock.visibility = View.VISIBLE
        keyboardBody.visibility = View.VISIBLE
        candidateOverlay.visibility = View.GONE
        mainDock.alpha = 0.96f
        mainDock.animate().alpha(1f).setDuration(ImeMotionTokens.SURFACE_FADE_MS).start()
        listener.onPanelChanged(Panel.NONE)
        return true
    }

    /** Subtle directional motion keeps panel navigation spatially legible. */
    private fun animatePanelEntrance(reverse: Boolean = false) {
        expandedPanel.animate().cancel()
        val direction = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
        val travel = if (reverse) -direction else direction
        expandedPanel.translationX = dp(12) * travel.toFloat()
        expandedPanel.alpha = 0.94f
        expandedPanel.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(ImeMotionTokens.STANDARD_TRANSITION_MS)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    fun renderState(state: ImeState) {
        val passwordStateChanged = passwordField != state.passwordField
        passwordField = state.passwordField
        if (passwordStateChanged) {
            // A recording started in the previous editor must not end in this one.
            if (voiceGestureSession || voicePanelController.active || voicePanelController.pending) {
                cancelVoiceForManualInput()
            }
            if (panel == Panel.TOOLS) {
                renderPanel(Panel.TOOLS)
            } else if (panel == Panel.TEXT_EDITOR) {
                // The same IME view can survive an editor switch. Rebuild the
                // open text-edit panel so clipboard actions reflect the new
                // password/privacy boundary immediately.
                renderPanel(Panel.TEXT_EDITOR)
            }
        }
        val sameComposition = composition.text.toString() == state.composition
        setCompositionText(
            state.composition,
            composition.selectionStart.takeIf { sameComposition && it >= 0 },
            composition.selectionEnd.takeIf { sameComposition && it >= 0 },
        )
        currentCandidates = state.candidates
        if (state.composition.isEmpty()) {
            pinyinBuffer.clear()
            lastNineDigits = ""
            lockedNineTail = null
            lastNineCandidates = emptyList()
            lastNineSegmentPrefix = ""
            lastNinePinyinPaths = emptyList()
            if (candidateBarController.expandedOpen) {
                candidateBarController.renderExpanded(
                    open = false,
                    candidates = currentCandidates,
                    compositionPreview = composition.text.toString(),
                )
                listener.onCandidateExpanded(false)
            }
        } else {
            pinyinBuffer.setLength(0)
            pinyinBuffer.append(state.composition)
            if (candidateBarController.expandedOpen) {
                candidateBarController.renderExpanded(
                    open = true,
                    candidates = currentCandidates,
                    compositionPreview = composition.text.toString(),
                )
            }
        }
        updateTopZone(state.composition.isNotEmpty())
        candidateBarController.render(
            candidates = currentCandidates,
            compositionPreview = composition.text.toString(),
            showCompositionWhenEmpty = composeZone.visibility == View.VISIBLE,
        )
        candidateBarController.syncExpandControl(
            hasCandidates = currentCandidates.isNotEmpty(),
        )
        syncEnterKeyPresentation(state.editorInfo?.imeOptions)
    }

    private fun syncEnterKeyPresentation(imeOptions: Int?) {
        val options = imeOptions ?: return
        val enter = findViewWithTag<ImeKeyView>("key-enter") ?: return
        val composing = composition.text?.isNotEmpty() == true
        val label = if (composing) "确定" else if (mode == KeyboardMode.PINYIN_9 || mode == KeyboardMode.STROKE || mode == KeyboardMode.DIGITS) "↵" else enterKeyPresentationFor(options).label
        enter.setMainText(label)
        enter.applyMainTextScale(referenceScale)
        enter.contentDescription = label
    }

    private var inlineAutofillController: InlineAutofillController? = null

    /** The autofill host, created on first use (Android 11+ only). */
    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private fun inlineAutofill(): InlineAutofillController? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        inlineAutofillController?.let { return it }
        lateinit var controller: InlineAutofillController
        controller = InlineAutofillController(context, context.mainExecutor) { chips ->
            if (::topZone.isInitialized) {
                topZone.setAutofillChips(chips, controller.chipSize.width, controller.chipSize.height)
            }
        }
        inlineAutofillController = controller
        return controller
    }

    /**
     * Hosts the autofill chips of one response (Android 11+). Returns false when
     * nothing can be shown, in which case the system falls back to its dropdown.
     */
    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    fun showInlineSuggestions(suggestions: List<android.view.inputmethod.InlineSuggestion>): Boolean =
        inlineAutofill()?.show(suggestions) ?: false

    fun clearInlineSuggestions() {
        // Nothing to clear until a response has been hosted.
        inlineAutofillController?.clear()
    }

    fun setAssociationCandidates(candidates: List<String>) {
        associationRow.removeAllViews()
        candidates.distinct().take(8).forEach { candidate ->
            associationRow.addView(
                TextView(context).apply {
                    text = candidate
                    textSize = ImeTypographyTokens.CANDIDATE_SP
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                    contentDescription = "联想:$candidate"
                    tag = "association-candidate"
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(12), 0, dp(12), 0)
                    setOnClickListener { feedback(); listener.onAssociationSelected(candidate) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(ImeGeometryTokens.TOUCH_TARGET_DP),
                ),
            )
        }
        topZone.showAssociations(candidates.isNotEmpty())
        applyAssociationTheme()
    }

    fun clearAssociationCandidates() {
        if (::topZone.isInitialized) { associationRow.removeAllViews(); topZone.showAssociations(false) }
    }

    fun setTheme(newTheme: ImeTheme) {
        // Called on every focus via reloadPersistedSettings(); avoid a full-tree
        // recolor when nothing changed.
        if (theme == newTheme) return
        theme = newTheme
        applyTheme()
        listener.onThemeChanged(newTheme)
    }

    private fun setFloatingStyle(widthPercent: Int, opacityPercent: Int) {
        val width = widthPercent.coerceIn(72, 96)
        val opacity = opacityPercent.coerceIn(82, 100)
        if (floatingWidthPercent == width && floatingOpacityPercent == opacity) return
        floatingWidthPercent = width
        floatingOpacityPercent = opacity
        ImeSettingsRepository.saveFloatingWidthPercent(context, width)
        ImeSettingsRepository.saveFloatingOpacityPercent(context, opacity)
        listener.onFloatingStyleChanged(width, opacity)
    }

    /** Save the new strength and play one click at it, so the slider is felt while dragged. */
    private fun setHapticStrengthPercent(percent: Int) {
        val bounded = percent.coerceIn(KeyHaptics.MIN_STRENGTH, KeyHaptics.MAX_STRENGTH)
        if (keyHaptics.strengthPercent == bounded) return
        keyHaptics.strengthPercent = bounded
        ImeSettingsRepository.saveHapticStrengthPercent(context, bounded)
        listener.onHapticStrengthChanged(bounded)
        val now = SystemClock.uptimeMillis()
        if (now - lastStrengthPreviewMs >= STRENGTH_PREVIEW_INTERVAL_MS) {
            lastStrengthPreviewMs = now
            keyHaptics.click(this)
        }
    }

    /** Save the 震动手感 choice and let it be felt once. */
    private fun setLandscapeLayout(next: LandscapeLayout) {
        if (ImeSettingsRepository.loadLandscapeLayout(context) == next) return
        ImeSettingsRepository.saveLandscapeLayout(context, next)
        listener.onLandscapeLayoutChanged(next)
    }

    private fun setHapticStyle(next: HapticStyle) {
        keyHaptics.style = next
        ImeSettingsRepository.saveHapticStyle(context, next.key)
        listener.onFeedbackStyleChanged()
        keyHaptics.click(this)
    }

    /** Save the 按键音效 choice and play it once, even while key sounds are off. */
    private fun setKeySoundStyle(next: KeySoundStyle) {
        keySounds.style = next
        ImeSettingsRepository.saveKeySoundStyle(context, next.key)
        listener.onFeedbackStyleChanged()
        // A freshly loaded sample decodes asynchronously; give it a moment.
        postDelayed({ keySounds.play() }, SOUND_PREVIEW_DELAY_MS)
    }

    private fun setKeyboardHeightPercent(percent: Int) {
        val bounded = percent.coerceIn(80, 120)
        if (keyboardHeightPercent == bounded) return
        keyboardHeightPercent = bounded
        ImeSettingsRepository.saveKeyboardHeightPercent(context, bounded)
        listener.onKeyboardHeightChanged(bounded)
        renderedMode = null
        layoutMetrics = buildLayoutMetrics()
        applyDynamicHeights()
        if (!standalonePanel && panel == Panel.NONE) {
            renderModeBody()
        } else {
            requestLayout()
        }
    }

    /**
     * Apply the complete persisted appearance in one render pass.
     *
     * The standalone settings page and the live IME can both refresh while a
     * panel is visible. Updating theme, appearance and height through separate
     * setters briefly mixed old and new tokens and rebuilt the whole subtree
     * several times. Keep the individual setters for user actions, but use
     * this atomic boundary whenever a persisted snapshot is loaded.
     */
    internal fun applyPersistedSettings(
        newTheme: ImeTheme,
        newAppearance: ImeAppearance,
        sound: Boolean,
        haptic: Boolean,
        popup: Boolean,
        fuzzy: Boolean,
    ) {
        val persistedHeight = ImeSettingsRepository.loadKeyboardHeightPercent(context)
        val persistedFloatingWidth = ImeSettingsRepository.loadFloatingWidthPercent(context)
        val persistedFloatingOpacity = ImeSettingsRepository.loadFloatingOpacityPercent(context)
        val floatingStyleChanged =
            floatingWidthPercent != persistedFloatingWidth ||
                floatingOpacityPercent != persistedFloatingOpacity
        val heightChanged = keyboardHeightPercent != persistedHeight
        val visualChanged = theme != newTheme || appearance != newAppearance

        theme = newTheme
        appearance = newAppearance
        soundEnabled = sound
        hapticEnabled = haptic
        popupEnabled = popup
        fuzzyEnabled = fuzzy
        keyHaptics.strengthPercent = ImeSettingsRepository.loadHapticStrengthPercent(context)
        keyHaptics.style = HapticStyle.fromKey(ImeSettingsRepository.loadHapticStyle(context))
        keySounds.style = KeySoundStyle.fromKey(ImeSettingsRepository.loadKeySoundStyle(context))
        if (heightChanged) {
            keyboardHeightPercent = persistedHeight
            renderedMode = null
            layoutMetrics = buildLayoutMetrics()
            applyDynamicHeights()
            if (!standalonePanel && panel == Panel.NONE) {
                renderModeBody()
            } else {
                requestLayout()
            }
            updateResponsiveGeometry(width)
        }
        if (floatingStyleChanged) {
            floatingWidthPercent = persistedFloatingWidth
            floatingOpacityPercent = persistedFloatingOpacity
            listener.onFloatingStyleChanged(
                floatingWidthPercent,
                floatingOpacityPercent,
            )
        }
        if (visualChanged) applyTheme()
    }

    fun isCandidateInteractionActive(): Boolean =
        ::candidateBarController.isInitialized &&
            candidateBarController.isInteractionActive()

    fun confirmCandidateDeletion(candidate: String, onConfirm: () -> Unit) {
        if (::candidateBarController.isInitialized) {
            candidateBarController.confirmCandidateDeletion(candidate, onConfirm)
        }
    }

    fun setAppearance(newAppearance: ImeAppearance) {
        if (appearance == newAppearance) return
        appearance = newAppearance
        applyTheme()
    }

    fun setSettings(sound: Boolean, haptic: Boolean, popup: Boolean) {
        soundEnabled = sound
        hapticEnabled = haptic
        popupEnabled = popup
    }

    fun setSettings(sound: Boolean, haptic: Boolean, popup: Boolean, fuzzy: Boolean) {
        soundEnabled = sound
        hapticEnabled = haptic
        popupEnabled = popup
        fuzzyEnabled = fuzzy
    }

    /**
     * Shift state is owned by this view, and nothing else could reset it. A
     * Caps Lock engaged in one app therefore survived into the next editor,
     * including password and banking fields. Called from onStartInput.
     */
    fun setShiftState(next: ShiftState) {
        if (shiftState == next) return
        shiftState = next
        listener.onShiftStateChanged(next)
        refreshEnglishShiftPresentation()
    }

    open fun shutdown() {
        keySounds.release()
        // The field these chips belong to is going away.
        clearInlineSuggestions()
        if (panel == Panel.CLIPBOARD) clipboardPanelController.invalidatePendingLoad()
        stopVoiceIfActive()
        // View.removeCallbacks(null) is a no-op; cancel the root-owned
        // named runnable explicitly. Gesture/controllers below invalidate their
        // own delayed work, while the posted voice-start lambda is guarded by
        // voiceGestureSession, which stopVoiceIfActive() cleared above.
        repeatHandler.removeCallbacksAndMessages(null)
        removeCallbacks(pendingRowRebuildPoll)
        backspaceGestureController.shutdown()
        spaceVoiceGestureController.shutdown()
        floatingKeyboardController.reset()
        pendingRowRebuild = false
        hidePopup()
    }

    internal fun isVoiceActive(): Boolean = voicePanelController.active

    internal fun findTestTarget(query: String): View? {
        findViewWithTag<View>(query)?.let { return it }
        // Replay scripts address letters independently of the visible shift/case.
        if (query.length == 1 && query[0].lowercaseChar() in 'a'..'z') {
            findViewWithTag<View>("key:${query.lowercase()}")?.let { return it }
        }
        fun deep(view: View): View? {
            val description = view.contentDescription?.toString()
            if (view.isClickable && (description == query || description?.substringBefore('，') == query)) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    deep(view.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
        return deep(this) ?: if (query == "确定" || query == "key:确定") {
            findViewWithTag<View>("key-enter")
        } else {
            null
        }
    }

    internal fun tapTestTarget(query: String): Boolean {
        val target = findTestTarget(query)
        return target?.performClick() == true
    }

    /**
     * Debug E2E entry that deliberately traverses the production backspace
     * gesture state machine. This verifies the arm threshold and atomic clear
     * callback instead of bypassing them with a direct editor mutation.
     */
    internal fun swipeClearForTest(): Boolean {
        val anchor = findTestTarget("key-backspace") ?: return false
        if (anchor.width <= 0 || anchor.height <= 0) return false
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val rawX = location[0] + anchor.width / 2f
        val rawY = location[1] + anchor.height / 2f
        backspaceGestureController.begin(anchor, pointerId = 0, rawX, rawY)
        backspaceGestureController.update(rawX, rawY - dp(BackspaceGestureController.CLEAR_ARM_DP + 8))
        backspaceGestureController.finish(commit = true)
        return true
    }

    /** Opens the production quick-phrase editor for one exact test phrase. */
    internal fun editQuickPhraseForTest(text: String): Boolean {
        val phrase = QuickPhraseRepository.load(context).firstOrNull { it.text == text } ?: return false
        openQuickPhraseEditor(phrase)
        return true
    }

    /** Clicks one exact phrase through the same listener as a real user tap. */
    internal fun useQuickPhraseForTest(text: String): Boolean {
        val phrase = QuickPhraseRepository.load(context).firstOrNull { it.text == text } ?: return false
        return findTestTarget("phrase:${phrase.id}")?.performClick() == true
    }

    /** Clicks the production delete action for one exact rendered test phrase. */
    internal fun deleteQuickPhraseForTest(text: String): Boolean {
        val phrase = QuickPhraseRepository.load(context).firstOrNull { it.text == text } ?: return false
        return findTestTarget("phrase-delete:${phrase.id}")?.performClick() == true
    }

    internal fun normalizedBoundsReport(): String {
        val origin = IntArray(2).also(::getLocationOnScreen)
        val out = StringBuilder("window=${origin[0]},${origin[1]},$width,$height\n")
        fun deep(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view.tag is String || (view.isClickable && !view.contentDescription.isNullOrEmpty())) {
                val normalized = NormalizedBounds.fromView(view, this)
                out.append(
                    "tag=${view.tag?.toString() ?: ""}|desc=${view.contentDescription ?: ""}",
                ).append('|')
                    .append(normalized.left).append(',')
                    .append(normalized.top).append(',')
                    .append(normalized.width).append(',')
                    .append(normalized.height).append('\n')
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) deep(view.getChildAt(i))
            }
        }
        deep(this)
        return out.toString()
    }

    private fun updateTopZone(composing: Boolean) {
        val topHeight = dp(topZoneHeightDp())
        topZone.minimumHeight = topHeight
        (topZone.layoutParams as? LinearLayout.LayoutParams)?.let {
            if (it.height != topHeight) {
                it.height = topHeight
                topZone.layoutParams = it
            }
        }
        (keyboardHost.layoutParams as? LinearLayout.LayoutParams)?.let {
            val bodyHeight = dp(keyboardBodyHeightDp())
            if (it.height != bodyHeight) {
                it.height = bodyHeight
                keyboardHost.layoutParams = it
            }
        }
        keyboardBody.setPadding(
            contentInsetPx,
            dp(6),
            contentInsetPx,
            dp(10),
        )
        val state = when {
            inlineVoicePresenter.active -> ImeTopZoneState.VOICE_INLINE
            candidateBarController.expandedOpen -> ImeTopZoneState.CANDIDATE_EXPANDED
            composing -> ImeTopZoneState.COMPOSING
            else -> ImeTopZoneState.IDLE
        }
        syncEnterKeyPresentation((context as? android.inputmethodservice.InputMethodService)?.currentInputEditorInfo?.imeOptions)
        findViewWithTag<View>("key-enter")?.let(::applyThemeToSubtree)
        topZone.renderState(
            state = state,
            showCompositionEditor = (composing || candidateBarController.expandedOpen) &&
                mode != KeyboardMode.ENGLISH_26,
        )
    }

    private fun renderModeBody() {
        // Never rebuild out from under a finger: the press would be lost silently.
        if (hasPressedKey()) {
            pendingRowRebuild = true
            removeCallbacks(pendingRowRebuildPoll)
            postDelayed(pendingRowRebuildPoll, ROW_REBUILD_POLL_MS)
            return
        }
        pendingRowRebuild = false
        // Corner hints default on for host-built surfaces such as 9-key.
        showSecondaryHints = true
        mainDock.visibility = View.VISIBLE
        keyboardBody.removeAllViews()
        keyboardBody.visibility = View.VISIBLE
        expandedPanel.visibility = View.GONE
        candidateOverlay.visibility = View.GONE
        candidateBarController.resetExpandedState()
        candidateBarController.syncExpandControl(currentCandidates.isNotEmpty())
        when (mode) {
            KeyboardMode.PINYIN_26 -> renderPinyin26()
            KeyboardMode.ENGLISH_26 -> renderEnglish26()
            KeyboardMode.PINYIN_9 -> renderPinyin9()
            KeyboardMode.STROKE -> renderStroke()
            KeyboardMode.DIGITS -> renderDigits()
        }
        updateTopZone(composition.text?.isNotEmpty() == true)
        if (width > 0) updateResponsiveGeometry(width)
        applyTheme()
        onViewHierarchyRebuilt()
        renderedMode = mode
        syncModeAccessibility()
    }

    /**
     * The compact "中/英" glyph is intentionally stable, but its action is
     * not: Chinese goes to English 26-key, English returns to the user's
     * preferred Chinese layout, and the digits page returns to text. Keep that
     * context available to TalkBack and hardware-keyboard focus without making
     * the visual key wider or adding another persistent label.
     */
    private fun syncModeAccessibility() {
        val modeKey = findViewWithTag<View>("key:mode") ?: return
        val target = when (mode) {
            KeyboardMode.PINYIN_26, KeyboardMode.PINYIN_9, KeyboardMode.STROKE -> "英文 26 键"
            KeyboardMode.ENGLISH_26 -> when (preferredChineseMode) {
                KeyboardMode.PINYIN_9 -> "中文九键"
                KeyboardMode.STROKE -> "笔画"
                else -> "中文 26 键"
            }
            KeyboardMode.DIGITS -> "文字键盘"
        }
        modeKey.contentDescription = when (mode) {
            KeyboardMode.DIGITS -> "返回文字键盘"
            else -> "中英切换，点击切换到$target"
        }
        if (Build.VERSION.SDK_INT >= 30) {
            modeKey.stateDescription = when (mode) {
                KeyboardMode.PINYIN_26 -> "当前中文 26 键"
                KeyboardMode.PINYIN_9 -> "当前中文九键"
                KeyboardMode.STROKE -> "当前笔画"
                KeyboardMode.ENGLISH_26 -> "当前英文 26 键"
                KeyboardMode.DIGITS -> "当前数字键盘"
            }
        }
    }

    private fun renderPinyin26() {
        pinyin26Renderer.render(
            english = false,
            shiftState = shiftState,
            enterLabel = enterKeyLabel(false),
        )
    }

    private fun renderEnglish26() {
        pinyin26Renderer.render(
            english = true,
            shiftState = shiftState,
            enterLabel = enterKeyLabel(true),
        )
    }

    /**
     * Resolve the Enter label from the bound editor at render time so the key is
     * correct on its first frame (V2 keeps an idempotent re-sync as a safety net).
     * Outside an InputMethodService host (settings/test) the legacy fallback is used.
     */
    private fun enterKeyLabel(english: Boolean, fallback: String? = null): String {
        val imeOptions = (context as? android.inputmethodservice.InputMethodService)
            ?.currentInputEditorInfo?.imeOptions
        if (imeOptions != null) return enterKeyPresentationFor(imeOptions).label
        return fallback ?: if (english) "Go" else "确定"
    }

    private fun renderPinyin9() {
        pinyin9Renderer.render(enterLabel = if (composition.text?.isNotEmpty() == true) "确定" else "↵")
    }

    private fun requireNineKeySymbolRailController(): NineKeySymbolRailController {
        return nineKeySymbolRailController ?: NineKeySymbolRailController(
            context = context,
            composition = composition,
            onCommit = listener::onCharacter,
            onFeedback = ::feedback,
            cellHeightDp = ::keyRowHeightDp,
            toPx = ::dp,
            onRailChanged = ::applyThemeToSubtree,
            onChooseReading = ::chooseNineKeyReading,
            fixedPrefix = ::nineKeyFixedPrefix,
            onEditSymbols = ::openRailSymbolEditor,
        ).also { nineKeySymbolRailController = it }
    }

    private fun renderStroke() {
        if (StrokeLexicon.current() == null) {
            Thread({ runCatching { StrokeLexicon.load(context) } }, "openime-stroke-table").apply {
                isDaemon = true
                start()
            }
        }
        strokeRenderer.render(enterLabel = if (composition.text?.isNotEmpty() == true) "确定" else "↵")
    }

    /**
     * 重输 clears what is being composed. It looks like the other function keys
     * at all times; with nothing composed a tap does nothing.
     */
    private fun retype() {
        if (composition.text?.isNotEmpty() == true) publishComposition("", emptyList())
    }

    /** One stroke (or 通配) typed at the pre-edit cursor. */
    private fun onStrokeKey(glyph: String) {
        clearAssociationCandidates()
        val (next, selection) = replaceCompositionSelection(glyph)
        publishComposition(next, candidatesForComposition(next), selection)
    }

    private fun renderDigits() {
        val info = (context as? android.inputmethodservice.InputMethodService)
            ?.currentInputEditorInfo
        numericKeyboardRenderer.render(
            editorKind = EditorInfoAdapter.kind(info),
            enterLabel = "↵",
        )
    }

    private fun commitFirstCandidateOrSpace() {
        if (composition.text.isNotEmpty() && currentCandidates.isNotEmpty()) {
            listener.onCandidateSelected(currentCandidates.first())
        } else {
            listener.onSpace()
        }
    }

    /**
     * Keep held gestures at the keyboard root. A finger can leave the original
     * key before ACTION_UP; voice release and one-shot clear must still finish
     * exactly once without falling back to a stream of synthetic taps.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            keyPopupController.hideIfOutside(event.x, event.y)
        }
        val handled = super.dispatchTouchEvent(event)
        // Slide-to-select: the finger that opened a long-press popup picks from it.
        if (keyPopupController.isShowing) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> keyPopupController.trackChoiceTouch(event.rawX, event.rawY, lifted = false)
                MotionEvent.ACTION_UP -> keyPopupController.trackChoiceTouch(event.rawX, event.rawY, lifted = true)
            }
        }
        trackShiftSlide(event)
        if (backspaceGestureController.active) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(backspaceGestureController.pointerId)
                    if (index >= 0) backspaceGestureController.update(
                        event.rawX + event.getX(index) - event.x,
                        event.rawY + event.getY(index) - event.y,
                    )
                }
                MotionEvent.ACTION_POINTER_UP -> if (
                    event.getPointerId(event.actionIndex) == backspaceGestureController.pointerId
                ) {
                    val index = event.actionIndex
                    backspaceGestureController.finish(
                        commit = true,
                        rawX = event.rawX + event.getX(index) - event.x,
                        rawY = event.rawY + event.getY(index) - event.y,
                    )
                }
                MotionEvent.ACTION_UP -> {
                    // The release position is part of the gesture: a quick flick
                    // can cross the threshold on the UP itself.
                    val index = event.findPointerIndex(backspaceGestureController.pointerId)
                        .takeIf { it >= 0 } ?: 0
                    backspaceGestureController.finish(
                        commit = true,
                        rawX = event.rawX + event.getX(index) - event.x,
                        rawY = event.rawY + event.getY(index) - event.y,
                    )
                }
                MotionEvent.ACTION_CANCEL -> {
                    Log.d("OpenIme", "bs touch CANCEL while gesture active (system or parent took the touch)")
                    backspaceGestureController.finish(commit = false)
                }
            }
        }
        if (spaceVoiceGestureController.active) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(spaceVoiceGestureController.pointerId)
                    if (index < 0) return handled
                    val pointerX = event.rawX + event.getX(index) - event.x
                    val pointerY = event.rawY + event.getY(index) - event.y
                    spaceVoiceGestureController.move(pointerX, pointerY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                    if (event.actionMasked == MotionEvent.ACTION_POINTER_UP &&
                        event.getPointerId(event.actionIndex) != spaceVoiceGestureController.pointerId
                    ) {
                        return handled
                    }
                    spaceVoiceGestureController.finish(
                        cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL,
                    )
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            nineKeySegmentRepairController.repairIfNeeded()
        }
        return handled
    }

    /** Tap commits the normal space/candidate action; long press starts voice. */
    private fun spaceVoiceKey(
        label: String = "空格",
        white: Boolean = false,
        onTap: () -> Unit,
    ): ImeKeyView =
        spaceVoiceKeyFactory.build(
            label = label,
            white = white,
            onTap = onTap,
        )

    /** Starts recording after the combined space key crosses the long-press threshold. */
    fun startVoiceFromSpace() {
        voiceGestureSession = true
        voicePanelController.lockLanguageForGesture()
        inlineVoicePresenter.invalidateGeneration()
        inlineVoicePresenter.show("正在准备麦克风…")
        inlineVoicePresenter.startPulse()
        // Let the in-place state row draw before model/session startup begins.
        post {
            if (voiceGestureSession) voicePanelController.start()
        }
    }

    /** Ends recording when the combined space key is released. */
    fun stopVoiceFromSpace() {
        if (!voiceGestureSession) return
        voiceGestureSession = false
        inlineVoicePresenter.stopPulse()
        inlineVoicePresenter.show("正在识别…")
        voicePanelController.stop()
    }

    private fun cancelVoiceGesture() {
        voiceGestureSession = false
        spaceVoiceGestureController.reset()
        inlineVoicePresenter.invalidateGeneration()
        inlineVoicePresenter.stopPulse()
        voicePanelController.cancel()
    }

    /** Revoke recognition ownership before the editor accepts manual input. */
    fun cancelVoiceForManualInput() {
        if (
            !voicePanelController.pending &&
            !voicePanelController.active &&
            !voiceGestureSession
        ) {
            return
        }
        cancelVoiceGesture()
        inlineVoicePresenter.hide()
    }

    private fun renderPanel(panel: Panel) {
        mainDock.visibility = View.GONE
        candidateOverlay.visibility = View.GONE
        keyboardBody.visibility = View.GONE
        expandedPanel.removeAllViews()
        expandedPanel.visibility = View.VISIBLE
        candidateBarController.resetExpandedState()
        voicePanelController.detachView()
        when (panel) {
            Panel.TOOLS -> panelRenderer.renderTools()
            Panel.KEYBOARD_SELECT -> panelRenderer.renderKeyboardSelect()
            Panel.SYMBOLS -> panelRenderer.renderSymbols()
            Panel.EMOJI -> panelRenderer.renderEmoji()
            Panel.HANDWRITING -> panelRenderer.renderHandwriting()
            Panel.VOICE -> voicePanelController.render()
            Panel.CLIPBOARD -> renderClipboard()
            Panel.TEXT_EDITOR -> textEditorPanelController.render()
            Panel.SETTINGS -> settingsPanelController.renderSettings()
            Panel.FUZZY_SETTINGS -> settingsPanelController.renderFuzzySettings()
            else -> closePanelToKeyboard()
        }
        applyTheme()
        onViewHierarchyRebuilt()
        focusPanelEntryPoint()
    }

    /** Keep hardware-keyboard focus inside the newly visible panel. */
    protected fun focusPanelEntryPoint() {
        expandedPanel.post {
            val entryPoint = expandedPanel.findViewWithTag<View>("key-panel-back") ?: return@post
            if (entryPoint.isShown && entryPoint.isFocusable) entryPoint.requestFocus()
        }
    }

    private fun stopVoiceIfActive() {
        val hadVoice =
            voicePanelController.active ||
                voicePanelController.pending ||
                voiceGestureSession
        voiceGestureSession = false
        voicePanelController.resetAndCancel()
        spaceVoiceGestureController.reset()
        inlineVoicePresenter.invalidateGeneration()
        inlineVoicePresenter.hide()
        if (hadVoice || panel == Panel.VOICE) {
            listener.onVoiceCancel()
        }
    }

    protected fun renderClipboard(reusePanel: Boolean = false) {
        inlineEditTarget = null
        clipboardPanelController.render(reusePanel)
    }

    /** Called on the UI thread after the asynchronous clipboard body is populated. */
    protected open fun onClipboardContentLoaded() = Unit

    private fun openRailSymbolEditor() {
        context.startActivity(
            Intent(context, RailSymbolsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun openQuickPhraseEditor(phrase: QuickPhrase?) {
        val intent = Intent(context, QuickPhraseEditActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(QuickPhraseEditActivity.EXTRA_ID, phrase?.id ?: 0L)
            .putExtra(QuickPhraseEditActivity.EXTRA_CATEGORY, phrase?.category.orEmpty())
            .putExtra(QuickPhraseEditActivity.EXTRA_TEXT, phrase?.text.orEmpty())
            .putExtra(QuickPhraseEditActivity.EXTRA_INPUT_CODE, phrase?.inputCode.orEmpty())
        context.startActivity(intent)
    }

    /** Keep copy/cut/paste honest as the target editor selection changes. */
    internal fun refreshTextEditAvailability(
        selectionAvailable: Boolean,
        clipboardAvailable: Boolean,
    ) {
        if (panel != Panel.TEXT_EDITOR) return
        textEditorPanelController.refreshAvailability(
            selectionAvailable = selectionAvailable,
            clipboardAvailable = clipboardAvailable,
        )
    }

    private fun sectionTitle(textValue: String): TextView = TextView(context).apply {
        text = textValue
        textSize = ImeTypographyTokens.SMALL_SP
        includeFontPadding = false
        setPadding(dp(16), dp(if (standalonePanel) 0 else 4), 0, dp(8))
        tag = "panel-section-title"
    }

    private fun onState(seed: String): Boolean = FuzzyRule.fromLabel(seed)
        // A 模糊音 pair: its own saved state (the theme paints the track from this).
        ?.let { it in ImeSettingsRepository.loadFuzzyRules(context) }
        ?: when (seed) {
        "按键音效" -> soundEnabled
        "触感震动" -> hapticEnabled
        "模糊音纠错", "启用模糊音" -> fuzzyEnabled
        "按键气泡" -> popupEnabled
        "上滑输入数字" -> ImeSettingsRepository.loadSwipeUpDigits(context)
        "数字和符号提示" -> ImeSettingsRepository.loadLetterHints(context)
        "表情联想" -> ImeSettingsRepository.loadEmojiAssociation(context)
        "语音去语气词" -> ImeSettingsRepository.loadVoiceStripFillers(context)
        "标点用空格代替" -> ImeSettingsRepository.loadVoicePunctuationAsSpace(context)
        else -> true
        }

    private fun updateSettingToggle(seed: String, enabled: Boolean) {
        when (seed) {
            "按键音效" -> {
                soundEnabled = enabled
                listener.onSoundChanged(enabled)
            }
            "触感震动" -> {
                hapticEnabled = enabled
                listener.onHapticChanged(enabled)
            }
            "模糊音纠错", "启用模糊音" -> {
                fuzzyEnabled = enabled
                listener.onFuzzyChanged(enabled)
            }
            "按键气泡" -> {
                popupEnabled = enabled
                listener.onPopupChanged(enabled)
            }
            // Read at gesture time, so it needs no listener round trip.
            "上滑输入数字" -> ImeSettingsRepository.saveSwipeUpDigits(context, enabled)
            "数字和符号提示" -> {
                ImeSettingsRepository.saveLetterHints(context, enabled)
                // Rebuilt when the keyboard is next shown (right away if it is).
                renderedMode = null
                if (panel == Panel.NONE) renderModeBody()
            }
            "表情联想" -> ImeSettingsRepository.saveEmojiAssociation(context, enabled)
            "语音去语气词" -> ImeSettingsRepository.saveVoiceStripFillers(context, enabled)
            "标点用空格代替" -> ImeSettingsRepository.saveVoicePunctuationAsSpace(context, enabled)
        }
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Route the keyboard's own keys into an inline quick-phrase editor. */
    fun insertIntoInlineEditor(text: String): Boolean {
        val target = inlineEditTarget?.takeIf { it.hasFocus() } ?: return false
        val start = target.selectionStart.coerceAtLeast(0).coerceAtMost(target.length())
        val end = target.selectionEnd.coerceAtLeast(start).coerceAtMost(target.length())
        target.text.replace(start, end, text)
        target.setSelection((start + text.length).coerceAtMost(target.length()))
        return true
    }

    /** Delete one code point from the active inline quick-phrase editor. */
    fun deleteInlineEditorChar(): Boolean {
        val target = inlineEditTarget?.takeIf { it.hasFocus() } ?: return false
        val start = target.selectionStart.coerceAtLeast(0).coerceAtMost(target.length())
        val end = target.selectionEnd.coerceAtLeast(start).coerceAtMost(target.length())
        if (start != end) {
            target.text.delete(start, end)
            target.setSelection(start)
            return true
        }
        if (start == 0) return true
        val previous = Character.offsetByCodePoints(target.text, start, -1)
        target.text.delete(previous, start)
        target.setSelection(previous)
        return true
    }

    private fun commitKeyboardCharacter(text: String) {
        if (!insertIntoInlineEditor(text)) listener.onCharacter(text)
    }

    private fun requireCandidateProvider(): CandidateResolver = requireNotNull(candidateProvider) {
        "Candidate-producing keyboard modes require a CandidateResolver host"
    }

    private fun onKeyTapped(base: String) {
        if (insertIntoInlineEditor(base)) return
        clearAssociationCandidates()
        if (mode == KeyboardMode.PINYIN_26) {
            val (py, selection) = replaceCompositionSelection(base)
            publishComposition(py, candidatesForComposition(py), selection)
        } else if (mode == KeyboardMode.ENGLISH_26) {
            val ch = if (shiftState != ShiftState.LOWERCASE) base.uppercase() else base
            val (py, selection) = replaceCompositionSelection(ch)
            publishComposition(py, candidatesForComposition(py), selection)
            if (shiftState == ShiftState.SHIFT_ONCE) {
                shiftState = ShiftState.LOWERCASE
                listener.onShiftStateChanged(shiftState)
                refreshEnglishShiftPresentation()
            }
        } else {
            listener.onCharacter(base)
        }
    }

    private fun onNineKey(num: String) {
        if (insertIntoInlineEditor(num)) return
        if (num == "0") {
            if (composition.text.isNotEmpty() && currentCandidates.isNotEmpty()) {
                listener.onCandidateSelected(currentCandidates.first())
            } else {
                listener.onSpace()
            }
            return
        }
        if (num == "*" || num == "#") {
            listener.onCharacter(num)
            return
        }
        if (mode == KeyboardMode.PINYIN_9) {
            sealLockedNineTail()
            val current = composition.text.toString()
            val rawStart = composition.selectionStart.takeIf { it >= 0 }?.coerceIn(0, current.length) ?: current.length
            val rawEnd = composition.selectionEnd.takeIf { it >= 0 }?.coerceIn(0, current.length) ?: rawStart
            val selStart = minOf(rawStart, rawEnd)
            val selEnd = maxOf(rawStart, rawEnd)

            val (prefix, suffix) = splitNineKeyText(current)
            if (selStart >= prefix.length) {
                val suffixStart = (selStart - prefix.length).coerceIn(0, suffix.length)
                val suffixEnd = (selEnd - prefix.length).coerceIn(0, suffix.length)
                val isAtEnd = (selStart == selEnd && selStart == current.length && prefix == lastNineSegmentPrefix && lastNineDigits.isNotEmpty())
                val suffixDigits = if (isAtEnd) {
                    lastNineDigits
                } else if (lastNineDigits.isNotEmpty() &&
                    lastNineDigits.length == nineKeyLetterCount(suffix, suffix.length) &&
                    prefix == lastNineSegmentPrefix
                ) {
                    lastNineDigits
                } else {
                    nineKeyDigitsOfTail(suffix) ?: lastNineDigits
                }
                // The tail may carry decoder dividers; cursor columns are text
                // positions, digits are letter positions.
                val insertPos = if (isAtEnd) {
                    suffixDigits.length
                } else {
                    nineKeyLetterCount(suffix, suffixStart).coerceAtMost(suffixDigits.length)
                }
                val deleteEnd = if (isAtEnd) {
                    suffixDigits.length
                } else {
                    nineKeyLetterCount(suffix, suffixEnd).coerceIn(insertPos, suffixDigits.length)
                }
                val newDigits = (suffixDigits.substring(0, insertPos) + num + suffixDigits.substring(deleteEnd)).take(64)
                lastNineSegmentPrefix = prefix
                val newCursor = if (isAtEnd) null else (prefix.length + suffixStart + 1)
                publishNineKeyDigits(newDigits, cursorPosition = newCursor)
            } else {
                val (nextText, newCursor) = replaceCompositionSelection(num)
                val newLastSpace = nextText.lastIndexOf(' ')
                val newPrefix = if (newLastSpace >= 0) nextText.substring(0, newLastSpace + 1) else ""
                val newSuffix = if (newLastSpace >= 0) nextText.substring(newLastSpace + 1) else nextText
                val newDigits = CandidatePipeline.nineKeyDigitsFor(newSuffix)
                if (newDigits != null) {
                    lastNineDigits = newDigits
                    lastNineSegmentPrefix = newPrefix
                } else {
                    lastNineDigits = ""
                    lastNineSegmentPrefix = nextText
                }
                lastNinePinyinPaths = emptyList()
                publishComposition(nextText, candidatesForComposition(nextText), newCursor)
            }
        } else {
            listener.onCharacter(num)
        }
    }

    /** Decode one bounded digit buffer without discarding alternate Pinyin paths. */
    private fun publishNineKeyDigits(
        digits: String,
        preferredSuffix: String? = null,
        cursorPosition: Int? = null,
        lockPreferred: Boolean = false,
    ) {
        lockedNineTail = if (lockPreferred) preferredSuffix?.lowercase()?.takeIf { it.isNotEmpty() } else null
        val resolveStartedAt = SystemClock.elapsedRealtimeNanos()
        val resolution = requireCandidateProvider().resolveNineKey(
            digits = digits,
            segmentPrefix = lastNineSegmentPrefix,
            preferredSuffix = preferredSuffix,
            fuzzy = fuzzyEnabled,
            lockPreferred = lockPreferred,
        )
        NineKeyPerformanceTrace.recordResolve(
            digitLength = digits.length,
            elapsedNs = SystemClock.elapsedRealtimeNanos() - resolveStartedAt,
            threadName = Thread.currentThread().name,
        )
        val preview = resolution.preview
        val pinyinPaths = resolution.pinyinPaths
        val candidates = resolution.candidates
        lastNineDigits = digits
        lastNinePinyinPaths = pinyinPaths
        lastNineCandidates = candidates
        val finalCursor = cursorPosition ?: preview.length
        setCompositionText(preview, finalCursor)
        pinyinBuffer.clear()
        pinyinBuffer.append(preview)
        currentCandidates = candidates
        updateTopZone(preview.isNotEmpty())
        candidateBarController.render(
            candidates = currentCandidates,
            compositionPreview = composition.text.toString(),
            showCompositionWhenEmpty = composeZone.visibility == View.VISIBLE,
        )
        listener.onNineKeyCompositionChanged(
            composition = preview,
            digitBuffer = digits,
            pinyinPaths = pinyinPaths,
            candidates = candidates,
        )
    }

    /**
     * Split the pre-edit text into the part the user fixed (a tapped syllable
     * or the segment key) and the still-open tail. Spaces the local decoder
     * puts between guessed syllables belong to the tail: treating them as fixed
     * boundaries froze guesses such as "woyi a m" and fed them to Rime.
     */
    private fun splitNineKeyText(text: String): Pair<String, String> {
        val fixed = lastNineSegmentPrefix
        if (fixed.isEmpty()) return "" to text
        if (text.startsWith(fixed)) return fixed to text.substring(fixed.length)
        val boundary = text.indexOfLast(::nineKeyIsDivider)
        return if (boundary >= 0) {
            text.substring(0, boundary + 1) to text.substring(boundary + 1)
        } else {
            "" to text
        }
    }

    /** Release the last fixed syllable of [prefix] back into open digits (keeping its spelling shown). */
    private fun unlockLastNineKeySyllable(prefix: String): Boolean {
        val trimmed = prefix.trimEnd(' ', '\'')
        val cut = trimmed.indexOfLast(::nineKeyIsDivider)
        val last = trimmed.substring(cut + 1)
        val digits = NineKeyLocalDecoder.digitsForPinyin(last) ?: return false
        lastNineSegmentPrefix = if (cut >= 0) trimmed.substring(0, cut + 1) else ""
        publishNineKeyDigits(digits, preferredSuffix = last)
        return true
    }

    private fun nineKeyIsDivider(ch: Char): Boolean = ch == ' ' || ch == '\''

    /**
     * More digits are coming after a syllable the user fixed: seal it with a
     * boundary so it stays fixed, and let the digits start a new open tail.
     */
    private fun sealLockedNineTail() {
        val locked = lockedNineTail ?: return
        lockedNineTail = null
        val current = composition.text.toString()
        val atEnd = composition.selectionStart.let { it < 0 || it == current.length } &&
            composition.selectionEnd.let { it < 0 || it == current.length }
        val (prefix, tail) = splitNineKeyText(current)
        if (!atEnd || tail != locked) return
        lastNineSegmentPrefix = "$prefix$tail'"
        lastNineDigits = ""
        setCompositionText(lastNineSegmentPrefix, lastNineSegmentPrefix.length)
    }

    /** Letters (not dividers) in the first [end] characters of [text]. */
    private fun nineKeyLetterCount(text: String, end: Int): Int =
        (0 until end.coerceIn(0, text.length)).count { !nineKeyIsDivider(text[it]) }

    /** T9 digits of an open tail, ignoring decoder-inserted dividers. */
    private fun nineKeyDigitsOfTail(tail: String): String? =
        CandidatePipeline.nineKeyDigitsFor(tail.filterNot(::nineKeyIsDivider))

    /**
     * Rime ranked [topCandidate] first for the current digits. Show that
     * word's own pinyin so the pre-edit text and the candidates agree, instead
     * of the local decoder's independent guess. Display-only: digits, the
     * fixed prefix and the native query are unchanged.
     */
    internal fun alignNineKeyPreview(expected: String, topCandidate: String) {
        if (mode != KeyboardMode.PINYIN_9 || composition.text.toString() != expected) return
        if (lockedNineTail != null) return
        val selection = composition.selectionStart
        if (selection >= 0 && selection != expected.length) return // user is editing mid-text
        val (prefix, tail) = splitNineKeyText(expected)
        val digits = lastNineDigits
        if (digits.isEmpty() || digits.length != nineKeyLetterCount(tail, tail.length)) return
        // The word also covers the fixed syllables in front; only its tail
        // characters spell the still-open digits.
        val fixedSyllables = prefix.split(' ', '\'').count { it.isNotEmpty() }
        val skipped = if (fixedSyllables == 0) 0 else {
            if (topCandidate.codePointCount(0, topCandidate.length) <= fixedSyllables) return
            topCandidate.offsetByCodePoints(0, fixedSyllables)
        }
        val syllables = candidateProvider
            ?.nineKeyPreviewFor(digits, topCandidate.substring(skipped))
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val aligned = prefix + syllables.joinToString("'")
        if (aligned == expected) return
        setCompositionText(aligned, aligned.length)
        pinyinBuffer.clear()
        pinyinBuffer.append(aligned)
        candidateBarController.render(
            candidates = currentCandidates,
            compositionPreview = aligned,
            showCompositionWhenEmpty = composeZone.visibility == View.VISIBLE,
        )
        listener.onNineKeyCompositionChanged(
            composition = aligned,
            digitBuffer = digits,
            pinyinPaths = lastNinePinyinPaths,
            candidates = currentCandidates,
        )
    }

    /**
     * A candidate spelling only the start of the input was committed; keep
     * typing on what is left. Nine-key leftovers can start with letters the
     * user had already fixed (they stay fixed) followed by open digits.
     */
    internal fun continueCompositionAfterPartial(remaining: String) {
        when (mode) {
            KeyboardMode.PINYIN_9 -> {
                val letters = remaining.takeWhile { it in 'a'..'z' || it == '\'' }
                val digits = remaining.substring(letters.length).filter { it in '2'..'9' }
                val fixed = letters.trim('\'')
                val prefix = if (fixed.isEmpty()) "" else "$fixed'"
                lastNineSegmentPrefix = prefix
                lastNineDigits = ""
                lastNinePinyinPaths = emptyList()
                if (digits.isNotEmpty()) {
                    publishNineKeyDigits(digits)
                } else if (prefix.isNotEmpty()) {
                    publishComposition(prefix, candidatesForComposition(prefix), prefix.length)
                }
            }
            KeyboardMode.PINYIN_26 -> {
                val text = remaining
                if (text.isNotBlank()) {
                    publishComposition(text, candidatesForComposition(text), text.length)
                }
            }
            else -> Unit
        }
    }

    /** The prefix the user has explicitly fixed, for the Pinyin rail. */
    internal fun nineKeyFixedPrefix(): String =
        lastNineSegmentPrefix.takeIf { composition.text.toString().startsWith(it) }.orEmpty()

    /**
     * The user tapped a reading in the left Pinyin rail. A tap fixes exactly
     * what the item shows: a whole reading (`ni'gao`) fixes every syllable,
     * a first syllable (`zhong`) fixes that one and the list moves on to the
     * next position. The candidates are re-queried with the fixed letters so
     * every word agrees with the choice.
     *
     * The last syllable of a whole reading stays the open tail (shown, and sent
     * to Rime as letters) rather than being sealed with a boundary; typing more
     * digits then seals it ([lockedNineTail]), so a fixed syllable is never lost.
     */
    internal fun chooseNineKeyReading(reading: NineKeyReading) {
        if (mode != KeyboardMode.PINYIN_9) return
        val syllables = reading.syllables.map { it.lowercase().trim() }.filter { it.isNotEmpty() }
        if (syllables.isEmpty()) return
        val (prefix, tail) = splitNineKeyText(composition.text.toString())
        val digits = lastNineDigits.ifEmpty { nineKeyDigitsOfTail(tail).orEmpty() }
        val spelled = StringBuilder()
        syllables.forEach { spelled.append(NineKeyLocalDecoder.digitsForPinyin(it) ?: return) }
        if (digits.isEmpty() || !digits.startsWith(spelled)) return

        // A bare initial (m, w, x ...) is a choice like any syllable: tapping it
        // fixes that letter, so the candidates start with it (m -> 么 吗 们).
        // Treated as a mere hint it changed nothing visible: Rime still read the
        // digit and the pre-edit snapped back to its top candidate (6 -> o).
        val rest = digits.substring(spelled.length)
        if (rest.isEmpty()) {
            val last = syllables.last()
            lastNineSegmentPrefix = prefix + syllables.dropLast(1).joinToString("") { "$it'" }
            publishNineKeyDigits(
                NineKeyLocalDecoder.digitsForPinyin(last).orEmpty(),
                preferredSuffix = last,
                lockPreferred = true,
            )
        } else {
            lastNineSegmentPrefix = prefix + syllables.joinToString("") { "$it'" }
            publishNineKeyDigits(rest)
        }
    }

    /** Insert an editable syllable boundary without committing the text. */
    private fun onPinyinSegment() {
        if (mode != KeyboardMode.PINYIN_26 && mode != KeyboardMode.PINYIN_9) return
        if (insertIntoInlineEditor(" ")) return
        val current = composition.text.toString()
        if (current.isBlank() || nineKeyIsDivider(current.last())) return
        if (mode == KeyboardMode.PINYIN_9) {
            lastNineDigits = ""
            lockedNineTail = null
            lastNinePinyinPaths = emptyList()
        }
        val (next, selection) = replaceCompositionSelection(if (mode == KeyboardMode.PINYIN_9) "'" else " ")
        publishComposition(next, candidatesForComposition(next), selection)
        if (mode == KeyboardMode.PINYIN_9) lastNineSegmentPrefix = next
    }

    private fun publishComposition(text: String, candidates: List<String>, selection: Int? = null) {
        setCompositionText(text, selection)
        pinyinBuffer.clear()
        pinyinBuffer.append(text)
        currentCandidates = candidates
        updateTopZone(text.isNotEmpty())
        candidateBarController.render(
            candidates = currentCandidates,
            compositionPreview = composition.text.toString(),
            showCompositionWhenEmpty = composeZone.visibility == View.VISIBLE,
        )
        listener.onCompositionChanged(text, candidates)
    }

    /** Keep the editable pre-edit field in sync without re-entering its watcher. */
    private fun setCompositionText(text: String, selection: Int? = null, selectionEnd: Int? = selection) {
        syncingComposition = true
        try {
            val changed = composition.text.toString() != text
            if (changed) composition.setText(text)
            val requested = selection ?: if (changed) text.length else composition.selectionStart.takeIf { it >= 0 } ?: text.length
            composition.setSelection(requested.coerceIn(0, text.length), (selectionEnd ?: requested).coerceIn(0, text.length))
        } finally {
            syncingComposition = false
        }
    }

    /** Recompute candidates after the user edits the visible Pinyin field. */
    private fun onCompositionEdited(text: String) {
        clearAssociationCandidates()
        pinyinBuffer.clear()
        pinyinBuffer.append(text)
        when (mode) {
            KeyboardMode.PINYIN_9 -> {
                val lastSpace = text.lastIndexOf(' ')
                val prefix = if (lastSpace >= 0) text.substring(0, lastSpace + 1) else ""
                val suffix = if (lastSpace >= 0) text.substring(lastSpace + 1) else text
                val suffixDigits = CandidatePipeline.nineKeyDigitsFor(suffix)
                if (suffixDigits != null) {
                    lastNineDigits = suffixDigits
                    lastNineSegmentPrefix = prefix
                } else {
                    lastNineDigits = ""
                    lastNineSegmentPrefix = text
                }
                lastNinePinyinPaths = emptyList()
            }
            else -> Unit
        }
        val candidates = candidatesForComposition(text)
        currentCandidates = candidates
        updateTopZone(text.isNotEmpty())
        candidateBarController.render(
            candidates = currentCandidates,
            compositionPreview = composition.text.toString(),
            showCompositionWhenEmpty = composeZone.visibility == View.VISIBLE,
        )
        listener.onCompositionChanged(text, candidates)
    }

    private fun candidatesForComposition(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return requireCandidateProvider().candidatesFor(mode, text, fuzzyEnabled)
    }

    private fun replaceCompositionSelection(insert: String): Pair<String, Int> {
        val current = composition.text.toString()
        val rawStart = composition.selectionStart.takeIf { it >= 0 }?.coerceIn(0, current.length)
            ?: current.length
        val rawEnd = composition.selectionEnd.takeIf { it >= 0 }?.coerceIn(0, current.length)
            ?: rawStart
        val start = minOf(rawStart, rawEnd)
        val end = maxOf(rawStart, rawEnd)
        return (current.substring(0, start) + insert + current.substring(end)) to (start + insert.length)
    }

    /** Delete at the visible pre-edit cursor; fall back to target-text deletion otherwise. */
    private fun deleteCompositionAtCursor(): Boolean {
        if (mode == KeyboardMode.PINYIN_9) {
            val current = composition.text.toString()
            if (current.isNotEmpty()) {
                val rawStart = if (composition.hasFocus()) {
                    composition.selectionStart.takeIf { it >= 0 }?.coerceIn(0, current.length) ?: current.length
                } else {
                    current.length
                }
                val rawEnd = if (composition.hasFocus()) {
                    composition.selectionEnd.takeIf { it >= 0 }?.coerceIn(0, current.length) ?: rawStart
                } else {
                    current.length
                }
                val start = minOf(rawStart, rawEnd)
                val end = maxOf(rawStart, rawEnd)

                val (prefix, suffix) = splitNineKeyText(current)
                if (start >= prefix.length) {
                    // Nothing left to delete after the fixed syllables: undo
                    // the last fix (tapped syllable or the segment key) and
                    // hand its digits back, rather than eating its letters.
                    if (suffix.isEmpty() && prefix.isNotEmpty() && start == end && start == current.length &&
                        unlockLastNineKeySyllable(prefix)
                    ) {
                        return true
                    }
                    val suffixStart = (start - prefix.length).coerceIn(0, suffix.length)
                    val suffixEnd = (end - prefix.length).coerceIn(0, suffix.length)
                    val suffixDigits = if (lastNineDigits.isNotEmpty() &&
                        lastNineDigits.length == nineKeyLetterCount(suffix, suffix.length) &&
                        prefix == lastNineSegmentPrefix
                    ) {
                        lastNineDigits
                    } else {
                        nineKeyDigitsOfTail(suffix)
                    }

                    if (suffixDigits != null && suffixDigits.isNotEmpty()) {
                        // Decoder dividers make text columns differ from digit
                        // columns; delete by letter position.
                        val digitStart = nineKeyLetterCount(suffix, suffixStart).coerceAtMost(suffixDigits.length)
                        val digitEnd = nineKeyLetterCount(suffix, suffixEnd).coerceIn(digitStart, suffixDigits.length)
                        val letters = suffix.filterNot(::nineKeyIsDivider)
                        val (nextDigits, newCursor, expectedSuffix) = if (suffixStart == suffixEnd) {
                            if (digitStart == 0) {
                                Triple(null, null, null)
                            } else {
                                val deleteIdx = digitStart - 1
                                val remDigits = suffixDigits.removeRange(deleteIdx, digitStart)
                                val remSuffix = if (letters.length >= digitStart) letters.removeRange(deleteIdx, digitStart) else null
                                Triple(remDigits, prefix.length + (suffixStart - 1).coerceAtLeast(0), remSuffix)
                            }
                        } else {
                            val remDigits = suffixDigits.removeRange(digitStart, digitEnd)
                            val remSuffix = if (letters.length >= digitEnd) letters.removeRange(digitStart, digitEnd) else null
                            Triple(remDigits, prefix.length + suffixStart, remSuffix)
                        }

                        if (nextDigits != null) {
                            lastNineSegmentPrefix = prefix
                            if (nextDigits.isNotEmpty()) {
                                publishNineKeyDigits(nextDigits, preferredSuffix = expectedSuffix, cursorPosition = newCursor)
                            } else {
                                lastNineDigits = ""
                                lastNinePinyinPaths = emptyList()
                                publishComposition(
                                    prefix,
                                    if (prefix.isEmpty()) emptyList() else candidatesForComposition(prefix),
                                    prefix.length,
                                )
                            }
                            return true
                        }
                    }
                }
            }
        }
        if (!composition.hasFocus() || composition.text.isEmpty()) return false
        val current = composition.text.toString()
        val rawStart = composition.selectionStart.coerceIn(0, current.length)
        val rawEnd = composition.selectionEnd.coerceIn(0, current.length)
        val start = minOf(rawStart, rawEnd)
        val end = maxOf(rawStart, rawEnd)
        val deleteStart = if (start == end) {
            if (start == 0) return true
            // Step back by one full Unicode code point, not by one UTF-16 unit.
            // Otherwise a backspace on an emoji / extension-B Han character
            // leaves an orphaned high surrogate behind and every later
            // candidate for this composition is computed from broken text.
            Character.offsetByCodePoints(current, start, -1).coerceAtLeast(0)
        } else {
            start
        }
        val next = current.removeRange(deleteStart, end)
        if (mode == KeyboardMode.PINYIN_9) {
            val lastSpace = next.lastIndexOf(' ')
            val prefix = if (lastSpace >= 0) next.substring(0, lastSpace + 1) else ""
            val suffix = if (lastSpace >= 0) next.substring(lastSpace + 1) else next
            val suffixDigits = CandidatePipeline.nineKeyDigitsFor(suffix)
            if (suffixDigits != null) {
                lastNineDigits = suffixDigits
                lastNineSegmentPrefix = prefix
            } else {
                lastNineDigits = ""
                lastNineSegmentPrefix = next
            }
            lastNinePinyinPaths = emptyList()
        }
        publishComposition(next, candidatesForComposition(next), deleteStart)
        return true
    }

    private var lastShiftTapAt = 0L
    private var shiftHeld = false
    private var shiftSliding = false
    private var shiftBeforeSlide = ShiftState.LOWERCASE

    private fun shiftKeyView(): ImeKeyView? = findViewWithTag("key-shift")
        ?: findViewWithTag("key-shift-active")
        ?: findViewWithTag("key-shift-caps")

    private fun rawContains(view: View, x: Float, y: Float): Boolean {
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return x >= at[0] && x < at[0] + view.width && y >= at[1] && y < at[1] + view.height
    }

    /**
     * Gboard's hold-Shift-and-slide: press Shift, slide onto a letter and lift to
     * type that one capital; the Shift state goes back to what it was. Lifting
     * anywhere else changes nothing.
     */
    private fun trackShiftSlide(event: MotionEvent) {
        if (mode != KeyboardMode.ENGLISH_26) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val shift = shiftKeyView()
                shiftHeld = shift != null && rawContains(shift, event.rawX, event.rawY)
                shiftSliding = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!shiftHeld || shiftSliding) return
                val shift = shiftKeyView() ?: return
                if (!rawContains(shift, event.rawX, event.rawY)) {
                    shiftSliding = true
                    shiftBeforeSlide = shiftState
                    if (shiftState == ShiftState.LOWERCASE) applyShift(ShiftState.SHIFT_ONCE)
                    hapticFeedback()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val sliding = shiftSliding
                shiftHeld = false
                shiftSliding = false
                if (!sliding) return
                val letter = "qwertyuiopasdfghjklzxcvbnm".firstOrNull { ch ->
                    findViewWithTag<ImeKeyView>("key:$ch")?.let { rawContains(it, event.rawX, event.rawY) } == true
                }
                if (letter != null && event.actionMasked == MotionEvent.ACTION_UP) {
                    onKeyTapped(letter.toString())
                    // One capital was typed: a lock stays, anything else ends lower case.
                    applyShift(
                        if (shiftBeforeSlide == ShiftState.CAPS_LOCK) ShiftState.CAPS_LOCK else ShiftState.LOWERCASE,
                    )
                } else {
                    applyShift(shiftBeforeSlide)
                }
            }
        }
    }

    private fun cycleShift() {
        val now = android.os.SystemClock.uptimeMillis()
        val since = if (lastShiftTapAt == 0L) null else now - lastShiftTapAt
        lastShiftTapAt = now
        applyShift(EnglishShiftPolicy.afterTap(shiftState, since))
    }

    /** Long press on Shift: lock Caps (or release it) without a second tap. */
    private fun lockShift() {
        lastShiftTapAt = 0L
        hapticFeedback()
        applyShift(EnglishShiftPolicy.afterLongPress(shiftState))
    }

    private fun applyShift(next: ShiftState) {
        shiftState = next
        listener.onShiftStateChanged(shiftState)
        refreshEnglishShiftPresentation()
    }

    /** Update only letter labels and the Shift key; do not rebuild the keyboard tree. */
    private fun refreshEnglishShiftPresentation() {
        if (mode != KeyboardMode.ENGLISH_26) return
        val uppercase = shiftState != ShiftState.LOWERCASE
        "qwertyuiopasdfghjklzxcvbnm".forEach { ch ->
            findViewWithTag<ImeKeyView>("key:$ch")?.setMainText(
                if (uppercase) ch.uppercaseChar().toString() else ch.toString(),
            )
        }
        val shift = findViewWithTag<ImeKeyView>("key-shift")
            ?: findViewWithTag<ImeKeyView>("key-shift-active")
            ?: findViewWithTag<ImeKeyView>("key-shift-caps")
        shift?.apply {
            tag = when (shiftState) {
                ShiftState.LOWERCASE -> "key-shift"
                ShiftState.SHIFT_ONCE -> "key-shift-active"
                ShiftState.CAPS_LOCK -> "key-shift-caps"
            }
            setIcon(EnglishShiftPolicy.icon(shiftState))
            contentDescription = when (shiftState) {
                ShiftState.LOWERCASE -> "大写"
                ShiftState.SHIFT_ONCE -> "大写一次"
                ShiftState.CAPS_LOCK -> "大写锁定"
            }
        }
        applyShiftTheme(shift)
    }

    /** Restyle only the Shift key after a label/icon change. */
    private fun applyShiftTheme(shift: ImeKeyView?) {
        val target = shift ?: return
        themeApplier.apply(target, currentThemeTokens())
    }

    private fun applyAssociationTheme() {
        if (!::topZone.isInitialized) return
        themeApplier.apply(associationRow, currentThemeTokens())
    }

    protected fun feedback() {
        if (hapticEnabled) keyHaptics.click(this)
        if (soundEnabled) keySounds.play()
    }

    /** Haptic-only confirmation (no key click sound), e.g. when voice arms. */
    private fun hapticFeedback() {
        // A gesture threshold gets the same crisp click; LONG_PRESS rings for too long.
        if (hapticEnabled) keyHaptics.click(this)
    }

    private fun key(
        text: String,
        func: Boolean,
        secondary: String?,
        weight: Float = 1f,
        mainTextSizeOverride: Float? = null,
        iconRes: Int = 0,
        onTap: () -> Unit,
    ): ImeKeyView {
        return ImeKeyView(
            context,
            text = text,
            secondary = if (func) null else secondary,
            iconRes = iconRes,
            mainTextSize = mainTextSizeOverride ?: if (func) {
                ImeTypographyTokens.BODY_SP
            } else {
                ImeTypographyTokens.KEY_LETTER_SP
            },
            fitMainText = func || text.length > 2,
            toPx = ::dp,
        ).apply {
            tag = "key:$text"
            setTag(MARK_FUNCTION_KEY, func)
            contentDescription = if (text.isNotEmpty()) text else if (iconRes != 0) "功能键" else " "
            if (!func && secondary != null && !showSecondaryHints) setSecondaryVisible(false)
            minimumHeight = dp(ImeGeometryTokens.TOUCH_TARGET_DP)
            setOnClickListener {
                if (!consumeTouchFeedback()) feedback()
                onTap()
            }
            run {
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            feedback()
                            isPressed = true
                            refreshDrawableState()
                            invalidate()
                            val activeText = (this as? ImeKeyView)?.currentMainText?.ifEmpty { text } ?: text
                            if (popupEnabled && !func && activeText.isNotEmpty()) showPopup(this, activeText)
                        }
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL,
                        -> if (!keyPopupController.consumeKeepAfterKeyUp()) {
                            hidePopup()
                        }
                    }
                    false
                }
            }
        }
    }

    private fun button(text: String, textSize: Float, func: Boolean): TextView = TextView(context).apply {
        this.text = text
        this.textSize = textSize
        contentDescription = text
        tag = "panel-button"
        gravity = Gravity.CENTER
        includeFontPadding = false
        minHeight = dp(ImeGeometryTokens.TOUCH_TARGET_DP)
        minimumHeight = dp(ImeGeometryTokens.TOUCH_TARGET_DP)
        isClickable = true
        isFocusable = true
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && isEnabled) feedback()
            false
        }
    }

    private fun performBackspaceOnce() {
        if (!deleteCompositionAtCursor()) listener.onBackspace()
    }

    private fun backspaceKey(): ImeKeyView =
        backspaceKeyFactory.build()

    private fun title(text: String, small: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = if (small) ImeTypographyTokens.BODY_SP else ImeTypographyTokens.TITLE_SP
        setPadding(0, 0, 0, dp(4))
    }

    private fun showPopup(anchor: View, char: String) {
        keyPopupController.show(anchor, char)
    }

    /** Horizontal long-press selector for symbols that share one key. */
    private fun showChoicePopup(anchor: View, choices: List<String>) {
        keyPopupController.showChoices(anchor, choices)
    }

    private fun showChoiceRows(anchor: View, rows: List<List<String>>) {
        keyPopupController.showChoiceRows(anchor, rows)
    }

    private fun hidePopup() {
        keyPopupController.hide()
    }

    // --- physical keyboard: the same entry points a tap on the soft key reaches ---

    /** Pinyin typing from a physical keyboard needs the plain 26-key surface: no panel, no voice. */
    internal fun hardwareAccepts(): Boolean =
        mode == KeyboardMode.PINYIN_26 && panel == Panel.NONE && !standalonePanel &&
            !voicePanelController.active && !voiceGestureSession

    internal fun hardwareIsComposing(): Boolean = composition.text.isNotEmpty()
    internal fun hardwareCandidateCount(): Int = currentCandidates.size
    internal fun hardwareLetter(char: Char) = onKeyTapped(char.toString())
    internal fun hardwareBackspace() = performBackspaceOnce()
    internal fun hardwareSpace() = commitFirstCandidateOrSpace()
    internal fun hardwareApostrophe() = onPinyinSegment()
    internal fun hardwareCancelComposition() = publishComposition("", emptyList())

    internal fun hardwareSelectCandidate(index: Int): Boolean {
        val candidate = currentCandidates.getOrNull(index) ?: return false
        listener.onCandidateSelected(candidate)
        return true
    }

    /**
     * The key preview is a permanent child that only toggles visibility, so
     * tests cannot detect it by counting children.
     */
    internal fun isKeyPopupShown(): Boolean = keyPopupController.isShowing

    /**
     * dp -> px exactly as the gesture thresholds compute it, i.e. including the
     * reference scale, so tests can drive gestures in the controllers' units.
     */
    internal fun scaledPx(dp: Int): Int = dp(dp)

    /** The preferences page paints with the app palette, the keyboard with key colours. */
    private fun currentThemeTokens(): ImeTheme.Tokens =
        if (standalonePanel) SetupUi.tokens(context) else theme.tokens(appearance, isNight())

    protected fun applyTheme() {
        val t = currentThemeTokens()
        if (floatingWindowMode && !standalonePanel) {
            // The window is transparent: the root is the card. Clipping to its rounded
            // outline rounds the toolbar, the keys and every panel opened over them.
            background = ImeDrawableFactory.rounded(
                t.keyboardBackground,
                dp(ImeGeometryTokens.CARD_RADIUS_DP),
                ImeSurfacePolicy.divider(t),
                dp(1).coerceAtLeast(1),
            )
            clipToOutline = true
        } else {
            setBackgroundColor(t.keyboardBackground)
            clipToOutline = false
        }
        mainDock.setBackgroundColor(t.keyboardBackground)
        keyboardBody.setBackgroundColor(t.keyboardBackground)
        topZone.setBackgroundColor(t.toolbarBackground)
        expandedPanel.setBackgroundColor(t.keyboardBackground)
        candidateOverlay.setBackgroundColor(t.expandedBackground)
        themeApplier.apply(this, t)
        composition.setTextColor(ImeSurfacePolicy.selectedText(t))
        topZone.candidateExpandButton.imageTintList = android.content.res.ColorStateList.valueOf(t.keyText)
        topZone.candidateEmojiButton.imageTintList = android.content.res.ColorStateList.valueOf(t.keySecondaryText)
        floatingKeyboardController.applyTheme(t)
        inlineVoicePresenter.refreshPalette()
    }

    /** Reuse the renderer's design tokens for views added by production decorators. */
    internal fun applyThemeToSubtree(target: View) {
        themeApplier.apply(target, currentThemeTokens())
    }

    private fun statefulRounded(normal: Int, pressed: Int, radius: Int): StateListDrawable =
        ImeDrawableFactory.statefulRounded(
            normal = normal,
            pressed = pressed,
            radiusPx = radius,
            focusStrokeColor = focusStroke(normal),
            focusStrokeWidthPx = dp(1),
        )

    private fun focusStroke(color: Int): Int {
        val background = if (Color.alpha(color) == 0) {
            theme.tokens(appearance, isNight()).keyboardBackground
        } else {
            color
        }
        return ImeFocusRingPolicy.resolve(background, theme.tokens(appearance, isNight()).primary)
    }

    private fun dp(value: Int): Int = kotlin.math.round(value * resources.displayMetrics.density * referenceScale).toInt()
    private fun gridCellParams(
        heightDp: Int,
        columns: Int,
        gapDp: Int,
    ): LinearLayout.LayoutParams {
        require(columns > 0)
        return LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply {
            marginStart = dp(gapDp) / 2
            marginEnd = dp(gapDp) / 2
        }
    }

}
