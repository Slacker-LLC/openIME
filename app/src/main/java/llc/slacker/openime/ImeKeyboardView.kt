package llc.slacker.openime

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

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

    interface Listener {
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
        /** Kept for compatibility; production voice always commits on release of space. */
        fun onVoiceSessionStarted(autoCommitOnFinal: Boolean) {}
        fun onVoicePartial(text: String) {}
        fun onVoiceFinal(text: String) {}
        fun onVoiceError(message: String) {}
        fun onVoiceCommit() {}
        fun onVoiceCancel() {}
        fun voiceModelState(): VoiceModelLifecycleState = VoiceModelLifecycleState.COLD
        fun startVoiceRecognition(languageTag: String, events: VoiceRecognitionEvents) {
            events.onError("本地语音服务未连接")
        }
        fun stopVoiceRecognition() {}
        fun cancelVoiceRecognition() {}
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
        fun onSkinChanged(opacity: Int, radius: Int, fontSize: Int, primaryColor: String) {}
    }

    /** Visual class marker for white keys (nine/digits grid). */
    private val MARK_WHITE_KEY = 0x1F000001
    /** Visual class marker for the gray side/action column in nine-key layouts. */
    private val MARK_SIDE_KEY = 0x1F000002
    private val MARK_FUNCTION_KEY = 0x1F000003

    companion object {
        /** How often a deferred row rebuild re-checks whether the press ended. */
        private const val ROW_REBUILD_POLL_MS = 40L
    }


    private val repeatHandler = Handler(Looper.getMainLooper())
    private val backspaceGestureController = BackspaceGestureController(
        toPx = ::dp,
        onDeleteOne = ::performBackspaceOnce,
        onClearAll = listener::onClearAll,
        onPressFeedback = ::feedback,
        onHapticFeedback = ::hapticFeedback,
        onShowClearPopup = { anchor -> showPopup(anchor, "清空") },
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
                    mainTextSizeOverride = 15f,
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
        canStartVoice = { voiceAllowed },
        onArmFeedback = ::hapticFeedback,
        onVoiceStart = { listener.onVoicePressChanged(true) },
        onVoiceStop = { listener.onVoicePressChanged(false) },
        onVoiceCancel = ::cancelVoiceGesture,
        onCancelPreviewChanged = { cancelling ->
            voicePanelController.setCancelPreview(cancelling)
        },
    )
    private val spaceVoiceKeyFactory: SpaceVoiceKeyFactory by lazy {
        SpaceVoiceKeyFactory(
            gestureController = spaceVoiceGestureController,
            createBaseKey = { label, onTap ->
                key(
                    text = label,
                    func = true,
                    secondary = null,
                    mainTextSizeOverride = 14f,
                    iconRes = R.drawable.ic_mic,
                    onTap = {
                        if (!insertIntoInlineEditor(" ")) onTap()
                    },
                )
            },
            canStartVoice = { voiceAllowed },
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
    private var appliedFontScale = resources.configuration.fontScale
    private var appliedDensityDpi = resources.displayMetrics.densityDpi
    private var layoutMetrics = KeyboardLayoutMetrics(
        landscape = appliedOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
        fontScale = appliedFontScale,
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
                KeyboardMode.PINYIN_9 -> {
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
    private var soundEnabled = true
    private var hapticEnabled = true
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private var popupEnabled = false
    private var fuzzyEnabled = false
    private var skinRadius = ImeSettingsRepository.loadSkinRadius(context)
    private var skinOpacity = ImeSettingsRepository.loadSkinOpacity(context)
    private var skinFontSize = ImeSettingsRepository.loadSkinFont(context)
    private var skinPrimaryColor = ImeSettingsRepository.loadSkinColor(context)

    protected open fun onViewHierarchyRebuilt() = Unit

    private val pinyinBuffer = StringBuilder()
    private var lastNineDigits = ""
    private var lastNineCandidates = emptyList<String>()
    private var lastNineSegmentPrefix = ""
    private var lastNinePinyinPaths = emptyList<String>()
    private var currentCandidates = emptyList<String>()
    private var voiceAllowed = true
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
    private var contentInsetPx = dp(5)
    private var navigationBottomInsetPx = 0
    private val themeApplier: ImeThemeApplier by lazy {
        ImeThemeApplier(
            toPx = ::dp,
            statefulRounded = ::statefulRounded,
            keyMainTextScale = ::skinFontScale,
            skinRadiusPx = { dp(skinRadius) },
            skinOpacity = { skinOpacity },
            skinPrimaryColor = { skinPrimaryColor },
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
            theme.tokens(
                appearance,
                isNight(),
                AccentPalette.parse(skinPrimaryColor),
            )
        },
        rounded = { color, radius -> ImeDrawableFactory.rounded(color, radius) },
        statefulRounded = { normal, pressed, radius -> statefulRounded(normal, pressed, radius) },
        contrastText = ImeDrawableFactory::contrastText,
        feedback = ::feedback,
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
            onCommitCharacter = ::commitKeyboardCharacter,
            onShowSymbols = { showPanel(Panel.SYMBOLS) },
            onDigits = { setMode(KeyboardMode.DIGITS) },
            onSpace = ::commitFirstCandidateOrSpace,
            onModeSwitch = ::cycleMode,
            onRetranslate = { publishComposition("", emptyList()) },
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
    // Portrait keeps the historical 296dp total. Landscape uses a compact
    // keyboard, and key rows grow with the system font scale so sp labels are
    // never clipped inside a fixed-height key.
    private fun keyRowHeightDp(): Int = layoutMetrics.keyRowHeightDp
    private fun nineGridHeightDp(): Int = layoutMetrics.nineGridHeightDp
    private fun nineBodyHeightDp(): Int = layoutMetrics.nineBodyHeightDp
    private fun doubleKeyHeightDp(): Int = layoutMetrics.doubleKeyHeightDp
    private fun imeHeightDp(): Int = layoutMetrics.imeHeightDp

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
            isPasswordField = { passwordField },
            onModeSelected = { selected -> setMode(selected) },
            onShowPanel = ::showPanel,
            onEnableFloatingKeyboard = ::enableFloatingKeyboard,
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
                theme.tokens(
                    appearance,
                    isNight(),
                    AccentPalette.parse(skinPrimaryColor),
                )
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
            createChipScroll = panelRenderer::panelChipScroll,
            currentTheme = { theme },
            currentAppearance = { appearance },
            currentSound = { soundEnabled },
            currentHaptic = { hapticEnabled },
            currentPopup = { popupEnabled },
            currentFuzzy = { fuzzyEnabled },
            currentSkinOpacity = { skinOpacity },
            currentSkinRadius = { skinRadius },
            currentSkinFontSize = { skinFontSize },
            currentSkinColor = { skinPrimaryColor },
            onThemeSelected = ::setTheme,
            onAppearanceSelected = { selected ->
                setAppearance(selected)
                listener.onAppearanceChanged(selected)
            },
            onToggleChanged = ::updateSettingToggle,
            onSkinChanged = { opacity, radius, fontSize, color ->
                skinOpacity = opacity
                skinRadius = radius
                skinFontSize = fontSize
                skinPrimaryColor = AccentPalette.normalize(color)
                listener.onSkinChanged(
                    skinOpacity,
                    skinRadius,
                    skinFontSize,
                    skinPrimaryColor,
                )
                applyTheme()
            },
            onShowFuzzySettings = { showPanel(Panel.FUZZY_SETTINGS) },
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
            createChipScroll = panelRenderer::panelChipScroll,
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
            onShift = ::cycleShift,
            onDigits = { setMode(KeyboardMode.DIGITS) },
            onModeSwitch = ::cycleMode,
            onSpace = listener::onSpace,
            onEnter = listener::onEnter,
        )
    }

    init {
        tag = "ime_root"
        setOnApplyWindowInsetsListener { _, insets ->
            val reported = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            val next = ImeBottomInsetPolicy.clampInset(reported, dp(32))
            if (next != navigationBottomInsetPx) {
                navigationBottomInsetPx = next
                requestLayout()
            }
            insets
        }
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
        keyboardBody.setPadding(dp(5), dp(6), dp(5), dp(16))
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
        keyboardHost.addView(
            floatingKeyboardController.handle,
            FrameLayout.LayoutParams(dp(ImeGeometryTokens.TOUCH_TARGET_DP), dp(24)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(4)
            },
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
            updateResponsiveGeometry(width)
            insets
        }
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
        val geometryChanged = newConfig.orientation != appliedOrientation ||
            newConfig.fontScale != appliedFontScale ||
            newConfig.densityDpi != appliedDensityDpi
        appliedOrientation = newConfig.orientation
        appliedFontScale = newConfig.fontScale
        appliedDensityDpi = newConfig.densityDpi
        layoutMetrics = KeyboardLayoutMetrics(
            landscape = appliedOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
            fontScale = appliedFontScale,
        )
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
        return walk(this)
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
        val totalPx = dp(imeHeightDp())
        val bodyPx = dp(keyboardBodyHeightDp())
        (layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.height != totalPx) {
                it.height = totalPx
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
     * The 390dp prototype is a design reference only. Runtime geometry is
     * derived from the measured IME width, with a 600dp maximum on tablets and
     * foldables. The bottom inset is added only when the system reports one so
     * the last row cannot sit underneath a gesture/navigation bar.
     */
    private fun updateResponsiveGeometry(measuredWidthPx: Int) {
        if (measuredWidthPx <= 0) return
        if (floatingKeyboardController.enabled) {
            // A configuration pass can briefly report the physical display
            // width before WindowManager applies the floating window bounds.
            // Keep the normal keyboard's content inset local to its window.
            contentInsetPx = dp(5)
            keyboardBody.setPadding(contentInsetPx, dp(6), contentInsetPx, dp(16))
            keyboardBody.findViewWithTag<View>("key-row-secondary")?.let { row ->
                // The portrait layout narrows this row to 90% of the full
                // display for optical centering. A floating window can be
                // narrower than the display, so that cached width would clip
                // the first and last keys. Let the row fill its local window.
                (row.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                    params.width = LinearLayout.LayoutParams.MATCH_PARENT
                    params.gravity = Gravity.NO_GRAVITY
                    row.layoutParams = params
                }
            }
            expandedPanel.setPadding(contentInsetPx, 0, contentInsetPx, 0)
            candidateOverlay.setPadding(contentInsetPx, 0, contentInsetPx, 0)
            topZone.setContentInset(contentInsetPx)
            requestLayout()
            return
        }
        val minimumInset = dp(5)
        val maxWidth = dp(maxContentWidthDp)
        contentInsetPx = maxOf(minimumInset, (measuredWidthPx - maxWidth) / 2)
        keyboardBody.setPadding(
            contentInsetPx,
            dp(6),
            contentInsetPx,
            dp(16),
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
        requestLayout()
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
                key(candidate, false, null, 1f, 15f) {
                    listener.onCandidateSelected(candidate)
                }.apply {
                    allowTwoLineLabel()
                    contentDescription = "候选:$candidate"
                }
            },
            createEmptyLabel = { title("暂无候选", small = true) },
            applyTheme = ::applyTheme,
            tokens = {
                theme.tokens(
                    appearance,
                    isNight(),
                    AccentPalette.parse(skinPrimaryColor),
                )
            },
            statefulBackground = ::statefulRounded,
            onFeedback = ::feedback,
            onCandidateSelected = listener::onCandidateSelected,
        )
        mainDock.addView(
            topZone,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64),
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
            KeyboardMode.PINYIN_26, KeyboardMode.PINYIN_9 -> KeyboardMode.ENGLISH_26
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
            if (newMode == KeyboardMode.PINYIN_26 || newMode == KeyboardMode.PINYIN_9) {
                // Persist the 26/9-key choice so it survives process death.
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
        if (passwordField && newPanel in setOf(Panel.CLIPBOARD, Panel.VOICE)) return
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
    fun setFloatingWindowMode(enabled: Boolean) {
        floatingKeyboardController.setEnabled(enabled)
        if (enabled) {
            contentInsetPx = dp(5)
            keyboardBody.setPadding(contentInsetPx, dp(6), contentInsetPx, dp(16))
            expandedPanel.setPadding(contentInsetPx, 0, contentInsetPx, 0)
            candidateOverlay.setPadding(contentInsetPx, 0, contentInsetPx, 0)
            topZone.setContentInset(contentInsetPx)
        } else {
            updateTopZone(composition.text?.isNotEmpty() == true)
            if (width > 0) updateResponsiveGeometry(width)
        }
        floatingKeyboardController.applyTheme(currentThemeTokens())
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
        voiceAllowed = !passwordField
        if (passwordStateChanged) {
            if (!voiceAllowed && (voiceGestureSession || voicePanelController.active || voicePanelController.pending)) {
                cancelVoiceForManualInput()
            }
            if (passwordField && panel == Panel.CLIPBOARD) {
                // A retained IME view can survive a focus change into a
                // password field. Persistent history must disappear
                // immediately instead of remaining visible until the user
                // manually backs out of the panel.
                closePanelToKeyboard()
            } else if (panel == Panel.TOOLS) {
                renderPanel(Panel.TOOLS)
            } else if (panel == Panel.TEXT_EDITOR) {
                // The same IME view can survive an editor switch. Rebuild the
                // open text-edit panel so clipboard actions reflect the new
                // password/privacy boundary immediately.
                renderPanel(Panel.TEXT_EDITOR)
            }
            syncSensitiveToolbar()
            syncSensitiveVoice()
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
        val label = enterKeyPresentationFor(options).label
        enter.setMainText(label)
        enter.contentDescription = label
    }

    /** Keep sensitive editors from exposing persistent clipboard history. */
    private fun syncSensitiveToolbar() {
        val clipboardButton = toolbarRow.findViewWithTag<View>("clipboard-toolbar") ?: return
        val available = !passwordField
        clipboardButton.isEnabled = available
        clipboardButton.isClickable = available
        clipboardButton.alpha = if (available) 1f else 0.38f
        clipboardButton.contentDescription = if (available) {
            "剪贴板"
        } else {
            "剪贴板，密码输入中不可用"
        }
        if (Build.VERSION.SDK_INT >= 30) {
            clipboardButton.stateDescription = if (available) "可用" else "密码输入中不可用"
        }
    }

    /** Password editors keep ordinary space input but remove the recording gesture. */
    private fun syncSensitiveVoice() {
        val space = findViewWithTag<View>("key-space") ?: return
        space.isLongClickable = voiceAllowed
        space.contentDescription = if (voiceAllowed) {
            "空格，点击空格，长按语音输入"
        } else {
            "空格，密码输入中语音不可用"
        }
        if (Build.VERSION.SDK_INT >= 30) {
            space.stateDescription = if (voiceAllowed) "可长按语音" else "语音不可用"
        }
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
                    setPadding(dp(8), 0, dp(8), 0)
                    setOnClickListener { feedback(); listener.onAssociationSelected(candidate) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(ImeGeometryTokens.TOUCH_TARGET_DP),
                ).apply { marginEnd = dp(5) },
            )
        }
        applyAssociationTheme()
    }

    fun clearAssociationCandidates() {
        if (::topZone.isInitialized) associationRow.removeAllViews()
    }

    fun setTheme(newTheme: ImeTheme) {
        // Called on every focus via reloadPersistedSettings(); avoid a full-tree
        // recolor when nothing changed.
        if (theme == newTheme) return
        theme = newTheme
        applyTheme()
        listener.onThemeChanged(newTheme)
    }

    /**
     * Apply the complete persisted appearance in one render pass.
     *
     * The standalone settings page and the live IME can both refresh while a
     * panel is visible. Updating theme, appearance and skin through separate
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
        opacity: Int,
        radius: Int,
        fontSize: Int,
        primaryColor: String,
    ) {
        val normalizedColor = AccentPalette.normalize(primaryColor)
        val visualChanged = theme != newTheme ||
            appearance != newAppearance ||
            skinOpacity != opacity ||
            skinRadius != radius ||
            skinFontSize != fontSize ||
            skinPrimaryColor != normalizedColor

        theme = newTheme
        appearance = newAppearance
        soundEnabled = sound
        hapticEnabled = haptic
        popupEnabled = popup
        fuzzyEnabled = fuzzy
        skinOpacity = opacity
        skinRadius = radius
        skinFontSize = fontSize
        skinPrimaryColor = normalizedColor
        if (visualChanged) applyTheme()
    }

    fun setAppearance(newAppearance: ImeAppearance) {
        if (appearance == newAppearance) return
        appearance = newAppearance
        applyTheme()
    }

    /** Apply persisted visual settings when another entry point changed them. */
    fun setSkin(opacity: Int, radius: Int, fontSize: Int, primaryColor: String) {
        val normalizedColor = AccentPalette.normalize(primaryColor)
        if (skinOpacity == opacity &&
            skinRadius == radius &&
            skinFontSize == fontSize &&
            skinPrimaryColor == normalizedColor
        ) {
            return
        }
        skinOpacity = opacity
        skinRadius = radius
        skinFontSize = fontSize
        skinPrimaryColor = normalizedColor
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
        backspaceGestureController.begin(anchor, pointerId = 0, rawX, rawY) { }
        backspaceGestureController.update(rawX, rawY - dp(48))
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
        val out = StringBuilder()
        fun deep(view: View) {
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
            dp(16),
        )
        val state = when {
            inlineVoicePresenter.active -> ImeTopZoneState.VOICE_INLINE
            candidateBarController.expandedOpen -> ImeTopZoneState.CANDIDATE_EXPANDED
            composing -> ImeTopZoneState.COMPOSING
            else -> ImeTopZoneState.IDLE
        }
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
            KeyboardMode.DIGITS -> renderDigits()
        }
        updateTopZone(composition.text?.isNotEmpty() == true)
        if (width > 0) updateResponsiveGeometry(width)
        applyTheme()
        onViewHierarchyRebuilt()
        renderedMode = mode
        syncSensitiveVoice()
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
            KeyboardMode.PINYIN_26, KeyboardMode.PINYIN_9 -> "英文 26 键"
            KeyboardMode.ENGLISH_26 -> if (preferredChineseMode == KeyboardMode.PINYIN_9) "中文九键" else "中文 26 键"
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
        pinyin9Renderer.render(enterLabel = enterKeyLabel(false))
    }

    private fun requireNineKeySymbolRailController(): NineKeySymbolRailController {
        return nineKeySymbolRailController ?: NineKeySymbolRailController(
            context = context,
            composition = composition,
            onCommit = listener::onCharacter,
            onFeedback = ::feedback,
        ).also { nineKeySymbolRailController = it }
    }

    private fun renderDigits() {
        val info = (context as? android.inputmethodservice.InputMethodService)
            ?.currentInputEditorInfo
        numericKeyboardRenderer.render(
            editorKind = EditorInfoAdapter.kind(info),
            enterLabel = enterKeyLabel(false, "换行"),
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
                    backspaceGestureController.finish(commit = true)
                }
                MotionEvent.ACTION_UP -> backspaceGestureController.finish(commit = true)
                MotionEvent.ACTION_CANCEL -> backspaceGestureController.finish(commit = false)
            }
        }
        if (spaceVoiceGestureController.active) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(spaceVoiceGestureController.pointerId)
                    if (index < 0) return handled
                    val pointerY = event.rawY + event.getY(index) - event.y
                    spaceVoiceGestureController.move(pointerY)
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
        if (!voiceAllowed) return
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

    private fun openQuickPhraseEditor(phrase: QuickPhrase?) {
        val intent = Intent(context, QuickPhraseEditActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(QuickPhraseEditActivity.EXTRA_ID, phrase?.id ?: 0L)
            .putExtra(QuickPhraseEditActivity.EXTRA_CATEGORY, phrase?.category.orEmpty())
            .putExtra(QuickPhraseEditActivity.EXTRA_TEXT, phrase?.text.orEmpty())
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
        textSize = 12f
        includeFontPadding = false
        setPadding(dp(4), dp(2), 0, dp(8))
        tag = "panel-section-title"
    }

    private fun onState(seed: String): Boolean = when (seed) {
        "按键音效" -> soundEnabled
        "触感震动" -> hapticEnabled
        "模糊音纠错", "启用模糊音" -> fuzzyEnabled
        "按键气泡" -> popupEnabled
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
            val current = composition.text.toString()
            val rawStart = composition.selectionStart.takeIf { it >= 0 }?.coerceIn(0, current.length) ?: current.length
            val rawEnd = composition.selectionEnd.takeIf { it >= 0 }?.coerceIn(0, current.length) ?: rawStart
            val selStart = minOf(rawStart, rawEnd)
            val selEnd = maxOf(rawStart, rawEnd)

            val lastSpace = current.lastIndexOf(' ')
            if (selStart > lastSpace) {
                val prefix = if (lastSpace >= 0) current.substring(0, lastSpace + 1) else ""
                val suffix = if (lastSpace >= 0) current.substring(lastSpace + 1) else current
                val suffixStart = (selStart - prefix.length).coerceIn(0, suffix.length)
                val suffixEnd = (selEnd - prefix.length).coerceIn(0, suffix.length)
                val isAtEnd = (selStart == selEnd && selStart == current.length && prefix == lastNineSegmentPrefix && lastNineDigits.isNotEmpty())
                val suffixDigits = if (isAtEnd) {
                    lastNineDigits
                } else if (lastNineDigits.isNotEmpty() && lastNineDigits.length == suffix.length && prefix == lastNineSegmentPrefix) {
                    lastNineDigits
                } else {
                    CandidatePipeline.nineKeyDigitsFor(suffix) ?: lastNineDigits
                }
                val insertPos = if (isAtEnd) suffixDigits.length else suffixStart
                val deleteEnd = if (isAtEnd) suffixDigits.length else suffixEnd
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
    ) {
        val resolution = requireCandidateProvider().resolveNineKey(
            digits = digits,
            segmentPrefix = lastNineSegmentPrefix,
            preferredSuffix = preferredSuffix,
            fuzzy = fuzzyEnabled,
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

    /** Insert an editable syllable boundary without committing the text. */
    private fun onPinyinSegment() {
        if (mode != KeyboardMode.PINYIN_26 && mode != KeyboardMode.PINYIN_9) return
        if (insertIntoInlineEditor(" ")) return
        val current = composition.text.toString()
        if (current.isBlank() || current.endsWith(' ')) return
        if (mode == KeyboardMode.PINYIN_9) {
            lastNineDigits = ""
            lastNinePinyinPaths = emptyList()
        }
        val (next, selection) = replaceCompositionSelection(" ")
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

                val lastSpace = current.lastIndexOf(' ')
                if (start > lastSpace) {
                    val prefix = if (lastSpace >= 0) current.substring(0, lastSpace + 1) else ""
                    val suffix = if (lastSpace >= 0) current.substring(lastSpace + 1) else current
                    val suffixStart = (start - prefix.length).coerceIn(0, suffix.length)
                    val suffixEnd = (end - prefix.length).coerceIn(0, suffix.length)
                    val suffixDigits = if (lastNineDigits.isNotEmpty() && lastNineDigits.length == suffix.length && prefix == lastNineSegmentPrefix) {
                        lastNineDigits
                    } else {
                        CandidatePipeline.nineKeyDigitsFor(suffix)
                    }

                    if (suffixDigits != null && suffixDigits.isNotEmpty()) {
                        val (nextDigits, newCursor, expectedSuffix) = if (suffixStart == suffixEnd) {
                            if (suffixStart == 0) {
                                Triple(null, null, null)
                            } else {
                                val deleteIdx = suffixStart - 1
                                val remDigits = suffixDigits.removeRange(deleteIdx, suffixStart)
                                val remSuffix = if (suffix.length >= suffixStart) suffix.removeRange(deleteIdx, suffixStart) else null
                                Triple(remDigits, prefix.length + deleteIdx, remSuffix)
                            }
                        } else {
                            val remDigits = suffixDigits.removeRange(suffixStart, suffixEnd)
                            val remSuffix = if (suffix.length >= suffixEnd) suffix.removeRange(suffixStart, suffixEnd) else null
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

    private fun cycleShift() {
        shiftState = when (shiftState) {
            ShiftState.LOWERCASE -> ShiftState.SHIFT_ONCE
            ShiftState.SHIFT_ONCE -> ShiftState.CAPS_LOCK
            ShiftState.CAPS_LOCK -> ShiftState.LOWERCASE
        }
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
            setIcon(if (shiftState == ShiftState.CAPS_LOCK) R.drawable.ic_caps_lock else R.drawable.ic_shift)
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
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        themeApplier.apply(target, theme.tokens(appearance, night, AccentPalette.parse(skinPrimaryColor)))
    }

    private fun applyAssociationTheme() {
        if (!::topZone.isInitialized) return
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        themeApplier.apply(associationRow, theme.tokens(appearance, night, AccentPalette.parse(skinPrimaryColor)))
    }

    protected fun feedback() {
        if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (soundEnabled) {
            // View effects can be disabled by an IME host window even when the
            // app preference is on. Use the system keypress channel directly;
            // the view channel remains the fallback for standalone previews.
            runCatching {
                if (audioManager != null) {
                    audioManager?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
                } else {
                    isSoundEffectsEnabled = true
                    playSoundEffect(SoundEffectConstants.CLICK)
                }
            }
        }
    }

    /** Haptic-only confirmation (no key click sound), e.g. when voice arms. */
    private fun hapticFeedback() {
        if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** Key main-text size is scaled around the 17sp default from the skin font slider. */
    private fun skinFontScale(): Float = skinFontSize / 17f.coerceAtLeast(1f)

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
            mainTextSize = mainTextSizeOverride ?: (if (func) 15f else 20f),
            fitMainText = func || text.length > 2,
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
        textSize = if (small) 12f else 16f
        setPadding(0, 0, 0, dp(4))
    }

    private fun showPopup(anchor: View, char: String) {
        keyPopupController.show(anchor, char)
    }

    /** Horizontal long-press selector for symbols that share one key. */
    private fun showChoicePopup(anchor: View, choices: List<String>) {
        keyPopupController.showChoices(anchor, choices)
    }

    private fun hidePopup() {
        keyPopupController.hide()
    }

    private fun currentThemeTokens(): ImeTheme.Tokens =
        theme.tokens(
            appearance,
            isNight(),
            AccentPalette.parse(skinPrimaryColor),
        )

    protected fun applyTheme() {
        val t = currentThemeTokens()
        setBackgroundColor(t.keyboardBackground)
        mainDock.setBackgroundColor(t.expandedBackground)
        keyboardBody.setBackgroundColor(t.keyboardBackground)
        topZone.setBackgroundColor(t.toolbarBackground)
        expandedPanel.setBackgroundColor(t.expandedBackground)
        candidateOverlay.setBackgroundColor(t.expandedBackground)
        themeApplier.apply(this, t)
        composition.setTextColor(t.keySecondaryText)
        topZone.candidateExpandButton.setTextColor(t.keySecondaryText)
        topZone.candidateEmojiButton.setTextColor(t.keySecondaryText)
        floatingKeyboardController.applyTheme(t)
        inlineVoicePresenter.refreshPalette()
    }

    /** Reuse the renderer's design tokens for views added by production decorators. */
    internal fun applyThemeToSubtree(target: View) {
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        themeApplier.apply(target, theme.tokens(appearance, night, AccentPalette.parse(skinPrimaryColor)))
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
            theme.tokens(
                appearance,
                isNight(),
                AccentPalette.parse(skinPrimaryColor),
            ).keyboardBackground
        } else {
            color
        }
        return ImeFocusRingPolicy.resolve(background, AccentPalette.parse(skinPrimaryColor))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun gridCellParams(
        heightDp: Int,
        columns: Int,
        gapDp: Int,
    ): LinearLayout.LayoutParams {
        val available = (width - contentInsetPx * 2 - dp(20)).coerceAtLeast(0)
        val gap = dp(gapDp)
        val cellWidth = if (width > 0) {
            ((available - gap * (columns - 1)) / columns).coerceAtLeast(dp(1))
        } else {
            0
        }
        return if (width > 0) {
            LinearLayout.LayoutParams(cellWidth, dp(heightDp)).apply { marginEnd = gap }
        } else {
            LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply { marginEnd = gap }
        }
    }

}
