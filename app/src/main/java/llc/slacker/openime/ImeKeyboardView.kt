package llc.slacker.openime

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.graphics.Paint
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors

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
        /** Compiled once. [applyThemeRecursive] walks ~150 nodes per pass. */
        private val DIGITS_ONLY = Regex("[0-9]+")

        /**
         * Emoji cells used to decode their PNG from assets inline on the UI
         * thread: 23-40 synchronous decodes every time the panel opened or
         * switched category, with no reuse and no recycling. Cache by asset
         * path so the cost is paid once per process, not once per render.
         */
        private const val EMOJI_CACHE_BYTES = 4 * 1024 * 1024
        private const val CANDIDATE_STRIP_LIMIT = 24
        /** How often a deferred row rebuild re-checks whether the press ended. */
        private const val ROW_REBUILD_POLL_MS = 40L
        private val emojiBitmaps = object : LruCache<String, Bitmap>(EMOJI_CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
        private val emojiDecodeExecutor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "openime-emoji-decode").apply { isDaemon = true }
        }
        private val emojiMainHandler = Handler(Looper.getMainLooper())
        private val emojiDecodeLock = Any()
        private val emojiDecodeWaiters = mutableMapOf<String, MutableList<(Bitmap) -> Unit>>()

        private fun requestEmojiBitmap(context: Context, assetPath: String, onReady: (Bitmap) -> Unit) {
            emojiBitmaps.get(assetPath)?.let { cached ->
                onReady(cached)
                return
            }
            val shouldDecode = synchronized(emojiDecodeLock) {
                emojiBitmaps.get(assetPath)?.let { cached ->
                    emojiMainHandler.post { onReady(cached) }
                    return@synchronized false
                }
                val waiters = emojiDecodeWaiters[assetPath]
                if (waiters != null) {
                    waiters += onReady
                    false
                } else {
                    emojiDecodeWaiters[assetPath] = mutableListOf(onReady)
                    true
                }
            }
            if (!shouldDecode) return

            val appContext = context.applicationContext
            emojiDecodeExecutor.execute {
                val bitmap = runCatching {
                    appContext.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                val waiters = synchronized(emojiDecodeLock) {
                    if (bitmap != null) emojiBitmaps.put(assetPath, bitmap)
                    emojiDecodeWaiters.remove(assetPath).orEmpty()
                }
                if (bitmap != null && waiters.isNotEmpty()) {
                    emojiMainHandler.post { waiters.forEach { it(bitmap) } }
                }
            }
        }
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
    private val spaceVoiceGestureController = SpaceVoiceGestureController(
        toPx = ::dp,
        canStartVoice = { voiceAllowed },
        onArmFeedback = ::hapticFeedback,
        onVoiceStart = { listener.onVoicePressChanged(true) },
        onVoiceStop = { listener.onVoicePressChanged(false) },
        onVoiceCancel = {
            voiceCancelAction?.invoke() ?: cancelVoiceGesture()
        },
        onCancelPreviewChanged = { cancelling ->
            voiceCancelPreviewAction?.invoke(cancelling)
        },
    )
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
    internal fun settingsScrollPosition(): Int {
        return (expandedPanel.findViewWithTag<ScrollView>("settings-scroll")?.scrollY ?: settingsScrollY)
            .coerceAtLeast(0)
    }

    internal fun restoreSettingsScrollPosition(scrollY: Int) {
        settingsScrollY = scrollY.coerceAtLeast(0)
        expandedPanel.findViewWithTag<ScrollView>("settings-scroll")?.let { scroll ->
            scroll.post {
                scroll.scrollTo(0, settingsScrollY)
            }
        }
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
    private var currentItems: List<String>? = null
    private var candidateExpandedOpen = false
    private var voiceEventGeneration = 0L
    private var renderedExpandedCandidates: List<String>? = null
    private var renderedExpandedComposition: String? = null
    private var clipboardTab = 0
    // Guards async clipboard loads so a stale background result can't render over a newer panel.
    private var clipboardLoadGen = 0
    private var voiceLanguageIndex = 0
    private var toolPage = 0
    private var settingsScrollY = 0
    private var voiceActive = false
    private var voicePending = false
    private var voiceStopRequested = false
    private var voiceAllowed = true
    private var inlineVoicePaletteColor: Int? = null
    private var voiceStartAction: (() -> Unit)? = null
    private var voiceStopAction: (() -> Unit)? = null
    private var voiceCancelAction: (() -> Unit)? = null
    private var voiceCancelPreviewAction: ((Boolean) -> Unit)? = null
    private var voiceGestureSession = false
    private var voiceInlineActive = false
    private var voiceInlineCancel = false
    private var voiceInlineError = false
    private var voiceInlineGeneration = 0L
    private var voiceInlineHasLiveRms = false
    private var voiceInlinePulseFrame = 0
    private val voiceInlinePulseAction = object : Runnable {
        override fun run() {
            if (!voiceInlineActive || !voiceGestureSession || voiceInlineHasLiveRms) return
            voiceInlineWaves.forEachIndexed { index, bar ->
                val phase = (voiceInlinePulseFrame + index * 2) % 12
                val distance = kotlin.math.abs(phase - 6)
                val params = bar.layoutParams
                params.height = dp((7 + (6 - distance) * 3).coerceIn(7, 25))
                bar.layoutParams = params
            }
            voiceInlinePulseFrame = (voiceInlinePulseFrame + 1) % 12
            repeatHandler.postDelayed(this, 72L)
        }
    }
    // Floating mode changes only the IME window bounds. The keyboard surface
    // itself remains the same normal keyboard used in portrait mode.
    private var floatingWindowMode = false
    private val floatingDragController = FloatingDragController(
        toPx = ::dp,
        onDragBy = listener::onFloatingKeyboardDragged,
        onDock = { floatingDragHandle.performClick() },
    )
    private var contentInsetPx = dp(5)
    private var navigationBottomInsetPx = 0
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
        contrastText = ::contrastText,
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
    private val candidateField: LinearLayout get() = topZone.candidateField
    private val associationRow: LinearLayout get() = topZone.associationRow
    private val candidateExpandBtn: TextView get() = topZone.candidateExpandButton
    private val candidateEmojiBtn: TextView get() = topZone.candidateEmojiButton
    private val voiceInlineZone: LinearLayout get() = topZone.voiceInlineZone
    private val voiceInlineIcon: ImageView get() = topZone.voiceInlineIcon
    private val voiceInlineStatus: TextView get() = topZone.voiceInlineStatus
    private val voiceInlineWaves: List<View> get() = topZone.voiceInlineWaves
    private lateinit var floatingDragHandle: View
    private val keyboardBody = LinearLayout(context)
    private val expandedPanel = LinearLayout(context)
    private val candidateOverlay = LinearLayout(context)
    private val panelRenderer: ImePanelRenderer by lazy {
        ImePanelRenderer(
            context = context,
            expandedPanel = expandedPanel,
            toPx = ::dp,
            panelBodyHeightPx = { dp(panelBodyHeightDp()) },
            imeHeightPx = { dp(imeHeightDp()) },
            createHeader = ::panelHead,
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
            createEmojiCell = ::emojiCell,
            gridCellParams = ::gridCellParams,
            currentMode = { mode },
            isPasswordField = { passwordField },
            onModeSelected = { selected -> setMode(selected) },
            onShowPanel = ::showPanel,
            onEnableFloatingKeyboard = ::enableFloatingKeyboard,
            onSymbolSelected = listener::onSymbolSelected,
            onFeedback = ::feedback,
            applyTheme = ::applyTheme,
            onHierarchyRebuilt = ::onViewHierarchyRebuilt,
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
    private var nineTapKey = ""
    private var nineTapIndex = 0
    private val nineTapReset = Runnable {
        nineTapKey = ""
        nineTapIndex = 0
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
        floatingDragHandle = FloatingDragHandleView(context).apply {
            tag = "floating-drag-handle"
            contentDescription = "拖动浮动键盘，点击贴底显示"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (floatingWindowMode) listener.onFloatingKeyboardChanged(false)
            }
            setOnTouchListener { _, event -> handleFloatingDragTouch(event) }
        }
        keyboardHost.addView(
            floatingDragHandle,
            FrameLayout.LayoutParams(dp(48), dp(24)).apply {
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
        prepareVoiceController()
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

    private fun handleFloatingDragTouch(event: MotionEvent): Boolean {
        if (!floatingWindowMode || panel != Panel.NONE) return false
        return floatingDragController.onTouch(event)
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
            panel == Panel.VOICE && (voiceActive || voicePending || voiceGestureSession) -> {
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
        if (floatingWindowMode) {
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
                val open = candidateOverlay.visibility == View.GONE
                renderExpanded(open)
                listener.onCandidateExpanded(open)
            },
        )
        candidateBarController = CandidateBarController(
            context = context,
            row = topZone.candidateRow,
            scroll = topZone.candidateScroll,
            expandButton = topZone.candidateExpandButton,
            toPx = ::dp,
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
            expandedOpen = candidateExpandedOpen,
            hasCandidates = currentCandidates.isNotEmpty(),
        )
    }

    private inner class FloatingDragHandleView(context: Context) : View(context) {
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            style = Paint.Style.FILL
        }

        fun setDotColor(color: Int) {
            dotPaint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = dp(2)
            val gapX = dp(7)
            val gapY = dp(7)
            val startX = width / 2f - gapX
            val startY = height / 2f - gapY / 2f
            for (row in 0..1) {
                for (column in 0..2) {
                    canvas.drawCircle(
                        startX + column * gapX,
                        startY + row * gapY,
                        radius.toFloat(),
                        dotPaint,
                    )
                }
            }
        }
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
        repeatHandler.removeCallbacks(nineTapReset)
        nineTapReset.run()
        val layoutChanged = mode != newMode
        mode = newMode
        panelRenderer.syncSymbolCategoryForMode(newMode)
        clearAssociationCandidates()
        pinyinBuffer.clear()
        lastNineDigits = ""
        lastNineCandidates = emptyList()
        lastNineSegmentPrefix = ""
        lastNinePinyinPaths = emptyList()
        currentCandidates = emptyList()
        currentItems = emptyList()
        // Rebuild the key rows (and play the switch fade) only when the layout
        // actually changes. Re-focusing another field in the same mode now reuses
        // the existing rows instead of recreating ~150 views on every focus.
        if (layoutChanged || renderedMode != newMode) {
            keyboardBody.animate().cancel()
            keyboardBody.alpha = 0.96f
            renderModeBody()
            keyboardBody.animate().alpha(1f).setDuration(100L).start()
        }
        if (notifyListener) listener.onModeChanged(newMode)
    }

    fun showPanel(newPanel: Panel) {
        if (newPanel == Panel.NONE || newPanel == Panel.CANDIDATE_EXPANDED) return
        if (passwordField && newPanel in setOf(Panel.CLIPBOARD, Panel.VOICE)) return
        hidePopup()
        if (panel == Panel.VOICE && newPanel != Panel.VOICE) stopVoiceIfActive()
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
        floatingWindowMode = true
        listener.onFloatingKeyboardChanged(true)
    }

    /** Keep content geometry local when the service changes the window bounds. */
    fun setFloatingWindowMode(enabled: Boolean) {
        floatingWindowMode = enabled
        floatingDragHandle.isEnabled = enabled
        floatingDragHandle.isFocusable = enabled
        floatingDragHandle.contentDescription = if (enabled) {
            "拖动浮动键盘，点击贴底显示"
        } else {
            "浮动键盘未启用"
        }
        if (Build.VERSION.SDK_INT >= 30) {
            floatingDragHandle.stateDescription = if (enabled) "可拖动，点击可贴底显示" else "不可用"
        }
        if (enabled) {
            contentInsetPx = dp(5)
            keyboardBody.setPadding(contentInsetPx, dp(6), contentInsetPx, dp(16))
            expandedPanel.setPadding(contentInsetPx, 0, contentInsetPx, 0)
            candidateOverlay.setPadding(contentInsetPx, 0, contentInsetPx, 0)
            topZone.setContentInset(contentInsetPx)
            floatingDragHandle.visibility = View.VISIBLE
        } else {
            floatingDragController.reset()
            floatingDragHandle.visibility = View.GONE
            updateTopZone(composition.text?.isNotEmpty() == true)
            if (width > 0) updateResponsiveGeometry(width)
        }
        applyFloatingChromeTheme()
        requestLayout()
    }

    private fun dismissPanelForModeSwitch() {
        if (panel == Panel.NONE) return
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
        if (candidateExpandedOpen) {
            renderExpanded(false)
            listener.onCandidateExpanded(false)
            return true
        }
        if (panel == Panel.NONE) return false
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
        mainDock.animate().alpha(1f).setDuration(100L).start()
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
            .setDuration(160L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    fun renderState(state: ImeState) {
        val passwordStateChanged = passwordField != state.passwordField
        passwordField = state.passwordField
        voiceAllowed = !passwordField
        if (passwordStateChanged) {
            if (!voiceAllowed && (voiceGestureSession || voiceActive || voicePending)) {
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
        currentItems = state.candidates
        currentCandidates = state.candidates
        if (state.composition.isEmpty()) {
            pinyinBuffer.clear()
            lastNineDigits = ""
            lastNineCandidates = emptyList()
            lastNineSegmentPrefix = ""
            lastNinePinyinPaths = emptyList()
            if (candidateExpandedOpen) {
                renderExpanded(false)
                listener.onCandidateExpanded(false)
            }
        } else {
            pinyinBuffer.setLength(0)
            pinyinBuffer.append(state.composition)
            if (candidateExpandedOpen) {
                renderExpanded(true)
            }
        }
        updateTopZone(state.composition.isNotEmpty())
        candidateBarController.render(
            candidates = currentCandidates,
            compositionPreview = composition.text.toString(),
            showCompositionWhenEmpty = composeZone.visibility == View.VISIBLE,
        )
        candidateBarController.syncExpandControl(
            expandedOpen = candidateExpandedOpen,
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
                    textSize = 12f
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
                    dp(48),
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
        stopVoiceIfActive()
        // Drop every pending callback, not just the repeat one. A surviving
        // backspace/voice runnable fires after the editor changed and would
        // delete or compose into whichever InputConnection is current then.
        repeatHandler.removeCallbacksAndMessages(null)
        removeCallbacks(null)
        backspaceGestureController.shutdown()
        spaceVoiceGestureController.shutdown()
        floatingDragController.reset()
        pendingRowRebuild = false
        voiceInlineActive = false
        voiceInlineCancel = false
        voiceInlineError = false
        voiceStopRequested = false
        hidePopup()
    }

    internal fun isVoiceActive(): Boolean = voiceActive

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
            voiceInlineActive -> ImeTopZoneState.VOICE_INLINE
            candidateExpandedOpen -> ImeTopZoneState.CANDIDATE_EXPANDED
            composing -> ImeTopZoneState.COMPOSING
            else -> ImeTopZoneState.IDLE
        }
        topZone.renderState(
            state = state,
            showCompositionEditor = (composing || candidateExpandedOpen) &&
                mode != KeyboardMode.ENGLISH_26,
        )
    }

    private fun conciseVoiceError(message: String): String = when {
        message.contains("模型") -> "语音不可用 · 请检查本地模型"
        message.contains("麦克风") || message.contains("权限") -> "语音不可用 · 请检查麦克风权限"
        else -> "语音失败 · 长按空格重试"
    }

    private fun showInlineVoiceState(
        message: String,
        cancelling: Boolean = false,
        error: Boolean = false,
        rms: Float? = null,
    ) {
        voiceInlineActive = true
        voiceInlineCancel = cancelling
        voiceInlineError = error
        if (voiceInlineStatus.text.toString() != message) {
            voiceInlineStatus.text = message
            voiceInlineZone.contentDescription = message
        }
        if (rms != null) {
            voiceInlineHasLiveRms = true
            repeatHandler.removeCallbacks(voiceInlinePulseAction)
            val strength = (rms * 9f).coerceIn(0.08f, 1f)
            voiceInlineWaves.forEachIndexed { index, bar ->
                val shape = if (index in 2..3) 1f else if (index in 1..4) 0.72f else 0.48f
                val params = bar.layoutParams
                val height = dp((6f + 22f * strength * shape).toInt().coerceIn(6, 28))
                if (params.height != height) {
                    params.height = height
                    bar.layoutParams = params
                }
            }
        }
        applyInlineVoicePalette()
        updateTopZone(false)
    }

    private fun startInlineVoicePulse() {
        voiceInlineHasLiveRms = false
        voiceInlinePulseFrame = 0
        repeatHandler.removeCallbacks(voiceInlinePulseAction)
        repeatHandler.post(voiceInlinePulseAction)
    }

    private fun stopInlineVoicePulse() {
        repeatHandler.removeCallbacks(voiceInlinePulseAction)
    }

    private fun applyInlineVoicePalette() {
        if (!::topZone.isInitialized) return
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val tokens = theme.tokens(appearance, night, AccentPalette.parse(skinPrimaryColor))
        val backgroundColor = if (voiceInlineCancel || voiceInlineError) {
            tokens.destructive
        } else {
            tokens.primary
        }
        if (inlineVoicePaletteColor == backgroundColor) return
        inlineVoicePaletteColor = backgroundColor
        voiceInlineZone.background = ImeDrawableFactory.rounded(
            backgroundColor,
            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
        )
        val foregroundColor = ImeDrawableFactory.contrastText(backgroundColor)
        voiceInlineIcon.imageTintList = ColorStateList.valueOf(foregroundColor)
        voiceInlineStatus.setTextColor(foregroundColor)
        voiceInlineWaves.forEach {
            it.background = ImeDrawableFactory.rounded(foregroundColor, dp(ImeGeometryTokens.PILL_RADIUS_DP))
        }
    }

    private fun hideInlineVoiceState() {
        stopInlineVoicePulse()
        voiceInlineActive = false
        voiceInlineCancel = false
        voiceInlineError = false
        updateTopZone(composition.text?.isNotEmpty() == true)
    }

    private fun hideInlineVoiceStateLater(delayMs: Long) {
        val generation = voiceInlineGeneration
        postDelayed({
            if (generation == voiceInlineGeneration && !voiceActive) hideInlineVoiceState()
        }, delayMs)
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
        candidateExpandedOpen = false
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
    ): ImeKeyView = key(
        label,
        true,
        null,
        1f,
        14f,
        iconRes = R.drawable.ic_mic,
        onTap = { if (!insertIntoInlineEditor(" ")) onTap() },
    ).apply {
        tag = "key-space"
        contentDescription = "$label，点击空格，长按语音输入"
        setOnLongClickListener {
            if (!voiceAllowed) return@setOnLongClickListener true
            // A physical touch is timed by SpaceVoiceGestureController. Android
            // may dispatch the View long-click callback at the same configured
            // timeout, so consume it here to avoid starting voice twice.
            if (spaceVoiceGestureController.trackingTouch) return@setOnLongClickListener true
            // Accessibility actions do not deliver a touch DOWN/UP sequence.
            when {
                voiceActive -> stopVoiceFromSpace()
                voicePending -> cancelVoiceForManualInput()
                else -> listener.onVoiceToggle()
            }
            true
        }
        if (white) setTag(MARK_WHITE_KEY, true)
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    feedback()
                    spaceVoiceGestureController.begin(
                        anchor = view,
                        pointerId = event.getPointerId(event.actionIndex),
                        rawY = event.rawY,
                    )
                    false
                }
                MotionEvent.ACTION_MOVE -> spaceVoiceGestureController.move(event.rawY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    spaceVoiceGestureController.finish(
                        cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL,
                    )
                }
                else -> false
            }
        }
    }

    /** Starts recording after the combined space key crosses the long-press threshold. */
    fun startVoiceFromSpace() {
        if (!voiceAllowed) return
        prepareVoiceController()
        voiceGestureSession = true
        lockVoiceLanguageForGesture()
        voiceInlineGeneration++
        showInlineVoiceState("正在准备麦克风…")
        startInlineVoicePulse()
        // Let the in-place state row draw before model/session startup begins.
        post {
            if (voiceGestureSession) voiceStartAction?.invoke()
        }
    }

    /** Lock language selection as soon as a voice gesture starts, before model startup is posted. */
    private fun lockVoiceLanguageForGesture() {
        val language = expandedPanel.findViewWithTag<View>("voice-language") ?: return
        language.isEnabled = false
        language.isClickable = false
        language.alpha = 0.52f
        language.contentDescription = "语音语言：${if (voiceLanguageIndex == 0) "普通话" else "英文"}，识别进行中不可切换"
        if (Build.VERSION.SDK_INT >= 30) {
            language.stateDescription = "当前${if (voiceLanguageIndex == 0) "普通话" else "英文"}，识别进行中不可切换"
        }
    }

    /** Ends recording when the combined space key is released. */
    fun stopVoiceFromSpace() {
        if (!voiceGestureSession) return
        voiceGestureSession = false
        stopInlineVoicePulse()
        showInlineVoiceState("正在识别…")
        voiceStopAction?.invoke()
        // Keep progress visible until a terminal callback or explicit cancel.
    }

    private fun cancelVoiceGesture() {
        voicePending = false
        voiceGestureSession = false
        voiceActive = false
        voiceStopRequested = false
        spaceVoiceGestureController.reset()
        voiceInlineGeneration++
        stopInlineVoicePulse()
        showInlineVoiceState("已取消")
        hideInlineVoiceStateLater(260L)
        listener.cancelVoiceRecognition()
        listener.onVoiceCancel()
    }

    /** Revoke recognition ownership before the editor accepts manual input. */
    fun cancelVoiceForManualInput() {
        if (!voicePending && !voiceActive && !voiceGestureSession) return
        voiceCancelAction?.invoke() ?: cancelVoiceGesture()
        hideInlineVoiceState()
    }

    private fun renderPanel(panel: Panel) {
        mainDock.visibility = View.GONE
        candidateOverlay.visibility = View.GONE
        keyboardBody.visibility = View.GONE
        expandedPanel.removeAllViews()
        expandedPanel.visibility = View.VISIBLE
        candidateExpandedOpen = false
        when (panel) {
            Panel.TOOLS -> panelRenderer.renderTools()
            Panel.KEYBOARD_SELECT -> panelRenderer.renderKeyboardSelect()
            Panel.SYMBOLS -> panelRenderer.renderSymbols()
            Panel.EMOJI -> panelRenderer.renderEmoji()
            Panel.HANDWRITING -> renderHandwriting()
            Panel.VOICE -> renderVoice()
            Panel.CLIPBOARD -> renderClipboard()
            Panel.TEXT_EDITOR -> renderTextEditor()
            Panel.SETTINGS -> renderSettings()
            Panel.FUZZY_SETTINGS -> renderFuzzySettings()
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

    /** Build the voice controller while it remains hidden, so a space gesture does not relayout the IME. */
    private fun prepareVoiceController() {
        if (voiceStartAction != null) return
        expandedPanel.removeAllViews()
        expandedPanel.visibility = View.GONE
        renderVoice()
        expandedPanel.visibility = View.GONE
        applyTheme()
    }

    private fun panelHead(name: String): LinearLayout {
        val backTarget = panelBackStack.lastOrNull()?.let(::panelTitle) ?: "键盘"
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            minimumHeight = dp(48)
            tag = "panel-head"
        }
        nav.addView(
            ImageView(context).apply {
                tag = "key-panel-back"
                setImageResource(R.drawable.ic_arrow_back)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                isFocusable = true
                minimumHeight = dp(48)
                minimumWidth = dp(48)
                contentDescription = "返回$backTarget"
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) feedback()
                    false
                }
                setOnClickListener {
                    feedback()
                    closePanelToKeyboard()
                }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        nav.addView(TextView(context).apply {
            text = name
            textSize = 13f
            setPadding(dp(8), 0, 0, 0)
            tag = "panel-title"
        }, wrapParams())
        return nav
    }

    private fun panelTitle(value: Panel): String = when (value) {
        Panel.TOOLS -> "更多"
        Panel.KEYBOARD_SELECT -> "切换键盘"
        Panel.SYMBOLS -> "符号"
        Panel.EMOJI -> "表情"
        Panel.HANDWRITING -> "手写输入"
        Panel.VOICE -> "语音"
        Panel.CLIPBOARD -> "剪贴板"
        Panel.TEXT_EDITOR -> "文本编辑"
        Panel.SETTINGS -> "设置"
        Panel.FUZZY_SETTINGS -> "模糊音纠错"
        Panel.NONE, Panel.CANDIDATE_EXPANDED -> "键盘"
    }

    private fun addPanelHead(name: String) {
        expandedPanel.addView(panelHead(name), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
    }

    private fun renderHandwriting() {
        addPanelHead("手写输入")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val candRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        candRow.addView(title("在下方区域落笔手写...", small = true), wrapParams())
        body.addView(candRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { bottomMargin = dp(7) })
        var undoButton: ImeKeyView? = null
        var clearButton: ImeKeyView? = null
        fun refreshStrokeActions(hasStrokes: Boolean) {
            listOf(
                undoButton to "撤销",
                clearButton to "清空",
            ).forEach { (button, label) ->
                button ?: return@forEach
                button.isEnabled = hasStrokes
                button.alpha = if (hasStrokes) 1f else 0.42f
                button.contentDescription = if (hasStrokes) label else "$label（暂无笔画）"
                if (Build.VERSION.SDK_INT >= 30) {
                    button.stateDescription = if (hasStrokes) "可用" else "不可用"
                }
            }
        }
        val pad = HandwritingPadView(context) { strokes ->
            refreshStrokeActions(strokes.isNotEmpty())
            candRow.removeAllViews()
            val result = UnavailableHandwritingProvider.recognize(strokes)
            if (result is HandwritingResult.NotConfigured) {
                candRow.addView(title("当前未配置手写识别引擎", small = true), wrapParams())
            } else {
                (result as? HandwritingResult.Success)?.candidates?.forEach { c ->
                    candRow.addView(key(c, false, null, 1f, 15f) { listener.onCharacter(c) }, wrapParams())
                }
            }
        }
        pad.tag = "handwriting-canvas"
        body.addView(pad, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(140),
        ).apply { bottomMargin = dp(7) })
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        undoButton = key("撤销", true, null, 1f, 13f) { pad.undo() }
        clearButton = key("清空", true, null, 1f, 13f) { pad.clear() }
        actions.addView(undoButton!!, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(clearButton!!, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(key("空格", true, null, 1f, 13f) { listener.onSpace() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        refreshStrokeActions(false)
        body.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
        val handwritingScroll = panelRenderer.panelVerticalScroll(body, "handwriting-scroll")
        panelRenderer.rememberPanelVerticalScroll(handwritingScroll, "handwriting")
        expandedPanel.addView(
            handwritingScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(panelBodyHeightDp()),
            ),
        )
    }

    private fun renderVoice() {
        addPanelHead("语音输入")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val initialModelState = listener.voiceModelState()
        val modelReady = initialModelState in setOf(
            VoiceModelLifecycleState.HOT,
            VoiceModelLifecycleState.RECORDING,
            VoiceModelLifecycleState.COOLDOWN,
        )
        val modelStatus = TextView(context).apply {
            text = if (modelReady) {
                "离线模型已就绪 · 音频不出设备"
            } else {
                "离线模型后台准备中 · 未启用联网识别"
            }
            textSize = 11f
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            tag = "voice-model-status"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(modelStatus, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(22),
        ))
        val transcript = TextView(context).apply {
            text = "只需长按空格；松开自动上屏，上滑取消"
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(12), 0)
            tag = "voice-transcript"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(transcript, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        ))
        val waveBar = LinearLayout(context).apply {
            tag = "voice-waveform"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val waves = (0 until 10).map { _ ->
            View(context).apply {
                tag = "voice-wave-bar"
                layoutParams = LinearLayout.LayoutParams(dp(4), dp(12))
            }
        }
        waves.forEach { waveBar.addView(it, LinearLayout.LayoutParams(dp(4), dp(12)).apply {
            marginEnd = dp(5)
        }) }
        body.addView(waveBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        ))
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        // The bundled model is bilingual Mandarin + English. Do not expose
        // dialect buttons that the packaged model cannot actually recognize.
        val languages = listOf("普通话" to "zh-CN", "英文" to "en-US")
        var recognizedText = ""
        var voiceCancelled = false
        var cancelPreview = false
        var modelPrepared = false
        val langButton = button(languages[voiceLanguageIndex].first, 13f, true).apply {
            tag = "voice-language"
            contentDescription = "语音语言：${languages[voiceLanguageIndex].first}，点击切换"
            if (Build.VERSION.SDK_INT >= 30) {
                stateDescription = languages[voiceLanguageIndex].first
            }
            setOnClickListener {
                feedback()
                voiceLanguageIndex = (voiceLanguageIndex + 1) % languages.size
                val selectedLanguage = languages[voiceLanguageIndex].first
                text = selectedLanguage
                contentDescription = "语音语言：$selectedLanguage，点击切换"
                if (Build.VERSION.SDK_INT >= 30) stateDescription = selectedLanguage
            }
        }
        fun refreshLanguageControl() {
            val selectedLanguage = languages[voiceLanguageIndex].first
            val locked = voiceGestureSession || voiceActive || voicePending
            langButton.isEnabled = !locked
            langButton.isClickable = !locked
            langButton.alpha = if (locked) 0.52f else 1f
            langButton.contentDescription = if (locked) {
                "语音语言：$selectedLanguage，识别进行中不可切换"
            } else {
                "语音语言：$selectedLanguage，点击切换"
            }
            if (Build.VERSION.SDK_INT >= 30) {
                langButton.stateDescription = if (locked) {
                    "当前$selectedLanguage，识别进行中不可切换"
                } else {
                    "当前$selectedLanguage"
                }
            }
        }
        refreshLanguageControl()
        controls.addView(langButton, LinearLayout.LayoutParams(0, dp(ImeGeometryTokens.VOICE_CONTROL_HEIGHT_DP), 1f))
        val micButton = button("🎤", 18f, false).apply {
            tag = "voice-mic"
            isEnabled = false
            contentDescription = "语音状态，当前未开始，仅支持长按空格启动"
        }
        controls.addView(
            micButton,
            LinearLayout.LayoutParams(
                dp(ImeGeometryTokens.VOICE_CONTROL_HEIGHT_DP),
                dp(ImeGeometryTokens.VOICE_CONTROL_HEIGHT_DP),
            ),
        )
        val gestureHint = button("长按空格开始", 13f, true).apply {
            tag = "voice-gesture-hint"
            isEnabled = false
            contentDescription = "长按空格开始语音，松开自动上屏，上滑取消"
        }
        controls.addView(gestureHint, LinearLayout.LayoutParams(0, dp(ImeGeometryTokens.VOICE_CONTROL_HEIGHT_DP), 1f))
        fun setMicState(icon: String, description: String) {
            micButton.text = icon
            micButton.contentDescription = description
        }
        fun setGestureHint(label: String, description: String) {
            gestureHint.text = label
            gestureHint.contentDescription = description
        }
        body.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(ImeGeometryTokens.VOICE_CONTROL_HEIGHT_DP),
        ))
        expandedPanel.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(panelBodyHeightDp()),
        ))
        fun startVoice() {
            if (voiceActive) return
            val eventGeneration = ++voiceEventGeneration
            recognizedText = ""
            voiceCancelled = false
            cancelPreview = false
            modelPrepared = false
            voiceActive = true
            voicePending = true
            voiceStopRequested = false
            refreshLanguageControl()
            showInlineVoiceState("正在准备麦克风…")
            setMicState("⏹", "语音输入进行中，松开空格结束，上滑取消")
            setGestureHint("松开空格上屏 · 上滑取消", "松开空格结束语音并自动上屏，上滑取消")
            modelStatus.text = "正在使用离线模型 · 音频不出设备"
            transcript.text = "正在聆听… 松开空格结束"
            listener.onVoiceSessionStarted(true)
            listener.startVoiceRecognition(languages[voiceLanguageIndex].second, object : VoiceRecognitionEvents {
                private val rmsQueued = java.util.concurrent.atomic.AtomicBoolean(false)
                @Volatile private var latestRms = 0f
                override fun onPartial(text: String) {
                    // AudioRecord inference callbacks arrive from the voice
                    // worker thread; keep view and InputConnection mutations
                    // on the IME main thread.
                    post {
                        if (eventGeneration != voiceEventGeneration) return@post
                        if (!voiceActive || voiceCancelled) return@post
                        if (cancelPreview) return@post
                        modelPrepared = true
                        if (text.isNotBlank()) recognizedText = text
                        transcript.text = text
                        if (voiceStopRequested) {
                            modelStatus.text = "正在整理识别结果…"
                            setMicState("⏹", "正在整理语音识别结果，请稍候")
                            setGestureHint("整理识别结果…", "正在整理语音识别结果，请稍候")
                            showInlineVoiceState("正在识别…")
                        } else {
                            modelStatus.text = "正在聆听 · 松开空格结束"
                            setMicState("⏹", "语音输入进行中，松开空格结束，上滑取消")
                            setGestureHint("松开空格上屏 · 上滑取消", "松开空格结束语音并自动上屏，上滑取消")
                            showInlineVoiceState(text.ifBlank { "正在聆听…" })
                        }
                        listener.onVoicePartial(text)
                    }
                }
                override fun onFinal(text: String) {
                    post {
                        if (eventGeneration != voiceEventGeneration) return@post
                        if (voiceCancelled || cancelPreview) return@post
                        if (text.isNotBlank()) recognizedText = text
                        transcript.text = text
                        setMicState("🎤", "语音状态，已完成识别，仅支持长按空格启动")
                        setGestureHint("长按空格开始", "长按空格开始语音，松开自动上屏，上滑取消")
                        voiceActive = false
                        voicePending = false
                        voiceStopRequested = false
                        refreshLanguageControl()
                        modelStatus.text = "离线识别完成 · 已自动上屏"
                        listener.onVoiceFinal(text)
                        showInlineVoiceState(if (text.isBlank()) "没有识别到语音" else "已上屏")
                        hideInlineVoiceStateLater(if (text.isBlank()) 900L else 280L)
                    }
                }
                override fun onRms(rms: Float) {
                    latestRms = rms
                    if (!rmsQueued.compareAndSet(false, true)) return
                    postDelayed({
                        rmsQueued.set(false)
                        if (eventGeneration != voiceEventGeneration) return@postDelayed
                        if (!voiceActive || voiceStopRequested || voiceCancelled || cancelPreview) return@postDelayed
                        val level = latestRms
                        val h = dp((8 + (level * 4f).coerceIn(0f, 52f)).toInt())
                        if (waveBar.isShown) waves.forEach { bar ->
                            if (bar.layoutParams.height != h) {
                                bar.layoutParams = bar.layoutParams.apply { height = h }
                            }
                        }
                        showInlineVoiceState(
                            if (modelPrepared) "正在聆听…" else "正在录音 · 模型准备中…",
                            rms = level,
                        )
                    }, 32L)
                }
                override fun onError(message: String) {
                    post {
                        if (eventGeneration != voiceEventGeneration) return@post
                        if (voiceCancelled) return@post
                        recognizedText = ""
                        transcript.text = message
                        setMicState("🎤", "语音状态，识别失败，仅支持长按空格重试")
                        setGestureHint("长按空格开始", "长按空格重新开始语音，松开自动上屏，上滑取消")
                        voiceActive = false
                        voicePending = false
                        voiceStopRequested = false
                        refreshLanguageControl()
                        modelStatus.text = "语音未完成 · 请检查本地模型和麦克风权限"
                        listener.onVoiceError(message)
                        showInlineVoiceState(
                            conciseVoiceError(message.ifBlank { "语音输入失败" }),
                            error = true,
                        )
                        hideInlineVoiceStateLater(1_500L)
                    }
                }
                override fun onReady() {
                    post {
                        if (eventGeneration != voiceEventGeneration) return@post
                        if (voiceCancelled) return@post
                        if (voiceActive && !voiceStopRequested) {
                            setMicState("⏹", "语音输入进行中，松开空格结束，上滑取消")
                            setGestureHint("松开空格上屏 · 上滑取消", "松开空格结束语音并自动上屏，上滑取消")
                            modelStatus.text = "正在录音 · 本地模型准备中"
                            showInlineVoiceState("正在录音 · 模型准备中…")
                        } else {
                            setMicState("⏹", "正在整理语音识别结果，请稍候")
                            setGestureHint("整理识别结果…", "正在整理语音识别结果，请稍候")
                            modelStatus.text = "正在整理识别结果…"
                            showInlineVoiceState("正在识别…")
                        }
                    }
                }
                override fun onModelReady() {
                    post {
                        if (eventGeneration != voiceEventGeneration) return@post
                        if (!voiceActive || voiceStopRequested || voiceCancelled || cancelPreview) return@post
                        modelPrepared = true
                        setMicState("⏹", "语音输入进行中，松开空格结束，上滑取消")
                        setGestureHint("松开空格上屏 · 上滑取消", "松开空格结束语音并自动上屏，上滑取消")
                        modelStatus.text = "正在识别 · 松开空格结束"
                        showInlineVoiceState("正在聆听…")
                    }
                }
            })
        }
        fun stopVoice() {
            if (!voiceActive || voiceStopRequested) return
            listener.stopVoiceRecognition()
            setMicState("🎤", "正在整理语音识别结果，请稍候")
            setGestureHint("整理识别结果…", "正在整理语音识别结果，请稍候")
            voiceStopRequested = true
            refreshLanguageControl()
            modelStatus.text = "正在整理识别结果…"
            showInlineVoiceState("正在识别…")
        }
        fun cancelVoice() {
            if (voiceCancelled) return
            voiceEventGeneration++
            voiceCancelled = true
            voicePending = false
            cancelPreview = false
            voiceActive = false
            voiceStopRequested = false
            refreshLanguageControl()
            listener.cancelVoiceRecognition()
            recognizedText = ""
            setMicState("🎤", "语音状态，已取消，仅支持长按空格启动")
            setGestureHint("长按空格开始", "长按空格开始语音，松开自动上屏，上滑取消")
            listener.onVoiceCancel()
            transcript.text = "已取消语音输入"
            modelStatus.text = "语音已取消 · 音频未保存"
            showInlineVoiceState("已取消")
            hideInlineVoiceStateLater(260L)
        }
        voiceStartAction = { startVoice() }
        voiceStopAction = { stopVoice() }
        voiceCancelAction = {
            voiceGestureSession = false
            cancelVoice()
        }
        voiceCancelPreviewAction = { cancelling ->
            cancelPreview = cancelling
            if (cancelling) {
                setMicState("⏹", "取消语音输入中，松开将丢弃本次语音")
                setGestureHint("上滑取消 · 松开丢弃", "继续上滑取消语音，松开将丢弃本次语音")
                transcript.text = "上滑取消 · 松开丢弃本次语音"
                modelStatus.text = "取消状态 · 松开将丢弃"
                showInlineVoiceState("松开取消", cancelling = true)
            } else {
                setMicState("⏹", "语音输入进行中，松开空格结束，上滑取消")
                setGestureHint("松开空格上屏 · 上滑取消", "松开空格结束语音并自动上屏，上滑取消")
                transcript.text = recognizedText.ifBlank { "正在聆听… 松开空格结束" }
                modelStatus.text = "正在聆听 · 松开空格结束"
                showInlineVoiceState(recognizedText.ifBlank { "正在聆听…" })
            }
        }
    }

    private fun stopVoiceIfActive() {
        val hadVoice = voicePending || voiceActive || voiceGestureSession
        voicePending = false
        voiceStopRequested = false
        voiceEventGeneration++
        listener.cancelVoiceRecognition()
        voiceActive = false
        voiceGestureSession = false
        spaceVoiceGestureController.reset()
        voiceInlineGeneration++
        hideInlineVoiceState()
        if (hadVoice || panel == Panel.VOICE) listener.onVoiceCancel()
        voiceStartAction = null
        voiceStopAction = null
        voiceCancelAction = null
        voiceCancelPreviewAction = null
    }

    private fun clipboardHistoryCard(entry: ClipboardEntry): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            minimumHeight = dp(70)
            tag = "clip-card"
            contentDescription = "剪贴板：${entry.text}，点击使用"
            if (Build.VERSION.SDK_INT >= 30) {
                stateDescription = if (entry.pinned) "已置顶" else "未置顶"
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                feedback()
                listener.onCharacter(entry.text)
            }
        }
        card.addView(TextView(context).apply {
            text = entry.text
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, wrapParams())
        val meta = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        meta.addView(TextView(context).apply {
            text = if (entry.pinned) "已置顶" else android.text.format.DateUtils.getRelativeTimeSpanString(
                entry.timestamp, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
            )
            textSize = 11f
        }, weightParams(1f))
        meta.addView(button(if (entry.pinned) "取消置顶" else "置顶", 10f, true).apply {
            tag = "clip-pin:${entry.text}"
            setOnClickListener {
                feedback()
                ClipboardHistoryRepository.togglePin(context, entry.text)
                renderClipboard(reusePanel = true)
            }
        }, wrapParams())
        meta.addView(button("使用", 10f, true).apply {
            tag = "clip-use:${entry.text}"
            setOnClickListener {
                feedback()
                listener.onCharacter(entry.text)
            }
        }, wrapParams())
        card.addView(meta, wrapParams())
        return card
    }

    protected fun renderClipboard(reusePanel: Boolean = false) {
        inlineEditTarget = null
        if (!reusePanel || expandedPanel.childCount == 0) {
            expandedPanel.removeAllViews()
            addPanelHead("剪贴板")
        } else {
            while (expandedPanel.childCount > 1) {
                expandedPanel.removeViewAt(expandedPanel.childCount - 1)
            }
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            tag = "clipboard-panel"
        }
        val tabs = panelRenderer.panelChipScroll(listOf("剪贴板", "常用语"), if (clipboardTab == 0) "剪贴板" else "常用语") { label ->
            clipboardTab = if (label == "剪贴板") 0 else 1
            renderClipboard(reusePanel = true)
        }
        body.addView(tabs, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { bottomMargin = dp(8) })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (clipboardTab == 0) {
            // Reading the system clipboard + parsing the history JSON is disk/I/O
            // work; do it off the UI thread and render once it returns.
            col.addView(sectionTitle("最近复制"), wrapParams())
            val loadingHint = TextView(context).apply {
                text = "正在读取剪贴板…"
                textSize = 13f
                setPadding(dp(4), dp(6), dp(4), 0)
                tag = "panel-note"
            }
            col.addView(loadingHint, wrapParams())
            val gen = ++clipboardLoadGen
            // View.post before attachment is queued until the view enters a
            // window. This avoids racing the background load against the panel
            // hierarchy construction and also ensures ClipboardManager is read
            // while this UI owns foreground focus.
            col.post {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val primaryClip = runCatching { clipboard?.primaryClip }.getOrNull()
                Thread {
                    val historyResult = runCatching {
                        if (primaryClip != null) {
                            ClipboardHistoryRepository.captureClip(context, primaryClip)
                        }
                        ClipboardHistoryRepository.load(context)
                    }
                    post {
                        if (gen != clipboardLoadGen || clipboardTab != 0 || col.parent == null) return@post
                        (loadingHint.parent as? ViewGroup)?.removeView(loadingHint)
                        fun addRefreshAction() {
                            col.addView(button("重新读取", 12f, true).apply {
                                tag = "clipboard-refresh"
                                contentDescription = "重新读取剪贴板"
                                setOnClickListener {
                                    feedback()
                                    renderClipboard(reusePanel = true)
                                }
                            }, LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                dp(48),
                            ).apply {
                                topMargin = dp(8)
                            })
                        }
                        if (historyResult.isFailure) {
                            col.addView(TextView(context).apply {
                                text = "暂时无法读取剪贴板，请重试。"
                                textSize = 13f
                                setPadding(dp(4), dp(6), dp(4), 0)
                                tag = "panel-error"
                            }, wrapParams())
                            addRefreshAction()
                        } else if (historyResult.getOrThrow().isEmpty()) {
                            col.addView(TextView(context).apply {
                                text = "暂无剪贴历史；复制文本后重新打开这里即可看到。"
                                textSize = 13f
                                setPadding(dp(4), dp(6), dp(4), 0)
                                tag = "panel-note"
                            }, wrapParams())
                            addRefreshAction()
                        } else {
                            historyResult.getOrThrow().forEach { entry -> col.addView(clipboardHistoryCard(entry), LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply { bottomMargin = dp(7) }) }
                            addClipboardRetentionControls(body)
                        }
                        onClipboardContentLoaded()
                    }
                }.apply { isDaemon = true }.start()
            }
        } else {
            col.addView(button("新增常用语", 13f, true).apply {
                tag = "quick-phrase-add"
                setOnClickListener {
                    feedback()
                    openQuickPhraseEditor(null)
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { bottomMargin = dp(8) })

            val phrases = QuickPhraseRepository.load(context)
            if (phrases.isEmpty()) {
                col.addView(TextView(context).apply {
                    text = "还没有常用语；点击上方按钮添加后即可一键输入。"
                    textSize = 12f
                    setPadding(dp(4), dp(6), dp(4), 0)
                    tag = "panel-note"
                }, wrapParams())
            }
            phrases.groupBy { it.category }
                .forEach { (category, phrases) ->
                    col.addView(sectionTitle(category), wrapParams())
                    phrases.forEach { phrase ->
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            tag = "phrase-card"
                        }
                        row.addView(
                            key(phrase.text, false, null, 1f, 13f) {
                                listener.onCharacter(phrase.text)
                            }.apply {
                                setPadding(dp(12), 0, dp(12), 0)
                                tag = "phrase:${phrase.id}"
                            },
                            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) },
                        )
                        row.addView(button("编辑", 11f, true).apply {
                            tag = "phrase-edit:${phrase.id}"
                            setOnClickListener {
                                feedback()
                                openQuickPhraseEditor(phrase)
                            }
                        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(5) })
                        row.addView(button("删除", 11f, true).apply {
                            tag = "phrase-delete:${phrase.id}"
                            setOnClickListener {
                                feedback()
                                val dialog = android.app.AlertDialog.Builder(context)
                                    .setTitle("删除常用语？")
                                    .setMessage(phrase.text)
                                    .setNegativeButton("取消", null)
                                    .setPositiveButton("删除") { _, _ ->
                                        QuickPhraseRepository.remove(context, phrase.id)
                                        renderClipboard(reusePanel = true)
                                    }
                                    .create()
                                dialog.setOnShowListener {
                                    SetupUi.styleDialog(dialog, context, destructivePositive = true)
                                }
                                dialog.show()
                            }
                        }, LinearLayout.LayoutParams(dp(48), dp(48)))
                        col.addView(row, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(48),
                        ).apply { bottomMargin = dp(7) })
                    }
                }
        }
        val clipboardScroll = panelRenderer.panelVerticalScroll(col, "clipboard-scroll")
        panelRenderer.rememberPanelVerticalScroll(clipboardScroll, "clipboard:$clipboardTab")
        body.addView(
            clipboardScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        // Keep history management reachable while the clipboard is loading or
        // already empty. Rebuilding the async content must not make the
        // destructive-action entry point disappear for a frame.
        if (clipboardTab == 0) addClipboardRetentionControls(body)
        expandedPanel.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(panelBodyHeightDp()),
        ))
        expandedPanel.post { requestLayout() }
        applyTheme()
        onViewHierarchyRebuilt()
    }

    /** Keep clipboard retention actions available in every keyboard-view entry point. */
    private fun addClipboardRetentionControls(body: LinearLayout) {
        if (body.findViewWithTag<View>("clipboard-retention-actions") != null) return
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "clipboard-retention-actions"
        }
        row.addView(
            clipboardRetentionAction("清除未固定", destructive = false) {
                ClipboardHistoryRepository.clearUnpinned(context)
                renderClipboard(reusePanel = true)
                focusPanelEntryPoint()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) },
        )
        row.addView(
            clipboardRetentionAction("清空全部", destructive = true) {
                showClipboardClearConfirmation(body)
            },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
        body.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(6) },
        )
        applyTheme()
    }

    private fun showClipboardClearConfirmation(body: LinearLayout) {
        body.findViewWithTag<View>("clipboard-retention-actions")?.let(body::removeView)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "clipboard-clear-confirmation"
            contentDescription = "确认清空全部剪贴历史"
        }
        row.addView(
            clipboardRetentionAction("取消", destructive = false) {
                renderClipboard(reusePanel = true)
                focusPanelEntryPoint()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) },
        )
        row.addView(
            clipboardRetentionAction("确认清空", destructive = true) {
                ClipboardHistoryRepository.clearAll(context)
                renderClipboard(reusePanel = true)
                focusPanelEntryPoint()
            }.apply {
                tag = "clipboard-clear-confirm"
                contentDescription = "确认清空全部剪贴历史"
            },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
        body.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(6) },
        )
        applyTheme()
        row.findViewWithTag<View>("clipboard-clear-confirm")?.requestFocus()
    }

    private fun clipboardRetentionAction(
        label: String,
        destructive: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minimumHeight = dp(48)
        isClickable = true
        isFocusable = true
        tag = if (destructive) "clipboard-retention-destructive" else "clipboard-retention-action"
        contentDescription = if (destructive) {
            "$label，删除全部剪贴历史"
        } else {
            "$label，保留已固定内容"
        }
        setOnClickListener {
            feedback()
            onClick()
        }
    }

    /** Called on the UI thread after the asynchronous clipboard body is populated. */
    protected open fun onClipboardContentLoaded() = Unit

    private fun emojiCell(emoji: String): View {
        val cell = FrameLayout(context).apply {
            tag = "emoji-cell"
            contentDescription = emoji
            isClickable = true
            isFocusable = true
            background = null
        }
        val fallback = TextView(context).apply {
            text = emoji
            textSize = 21f
            gravity = Gravity.CENTER
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cell.addView(fallback, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        FluentEmojiAssetRepository.pathFor(context, emoji)?.let { assetPath ->
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                tag = assetPath
            }
            cell.addView(image, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            requestEmojiBitmap(context, assetPath) { bitmap ->
                if (image.tag == assetPath) {
                    image.setImageBitmap(bitmap)
                    image.visibility = View.VISIBLE
                    fallback.visibility = View.INVISIBLE
                }
            }
        }
        cell.setOnClickListener {
            feedback()
            listener.onEmojiSelected(emoji)
        }
        return cell
    }

    private fun openQuickPhraseEditor(phrase: QuickPhrase?) {
        val intent = Intent(context, QuickPhraseEditActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(QuickPhraseEditActivity.EXTRA_ID, phrase?.id ?: 0L)
            .putExtra(QuickPhraseEditActivity.EXTRA_CATEGORY, phrase?.category.orEmpty())
            .putExtra(QuickPhraseEditActivity.EXTRA_TEXT, phrase?.text.orEmpty())
        context.startActivity(intent)
    }

    private fun renderTextEditor() {
        addPanelHead("文本编辑")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            tag = "text_editor_panel"
        }
        val quick = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("全选" to "select-all", "复制" to "copy", "剪切" to "cut", "粘贴" to "paste", "撤销" to "undo")
            .forEach { (label, action) ->
                quick.addView(
                    key(label, true, null, 1f, 10f) { listener.onTextEdit(action) }.apply {
                        tag = "textedit-action:$action"
                    },
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) },
                )
            }
        body.addView(quick, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { bottomMargin = dp(10) })
        val cross = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "textedit-cross"
        }
        fun cell(label: String? = null, action: String? = null, center: Boolean = false): TextView =
            button(label ?: "", if (center) 9f else 14f, !center).apply {
                if (action != null) {
                    setOnClickListener {
                        feedback()
                        listener.onTextEdit(action)
                    }
                } else {
                    tag = "textedit-spacer"
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    contentDescription = null
                }
                if (center) text = "光标"
            }
        listOf(
            listOf(cell(), cell("▲", "up"), cell()),
            listOf(cell("◀", "left"), cell(center = true), cell("▶", "right")),
            listOf(cell(), cell("▼", "down"), cell()),
        ).forEach { rowItems ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEach { c -> row.addView(c, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) }) }
            cross.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { bottomMargin = dp(5) })
        }
        body.addView(cross, LinearLayout.LayoutParams(dp(158), dp(150)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        expandedPanel.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(panelBodyHeightDp()),
        ))
        applyTextEditControlAvailability(body)
    }

    /** Keep controls that cannot be implemented for arbitrary editors visibly unavailable. */
    private fun applyTextEditControlAvailability(root: View) {
        fun visit(view: View) {
            val label = when (view) {
                is ImeKeyView -> view.contentDescription?.toString().orEmpty()
                is TextView -> view.text.toString()
                else -> ""
            }
            if (label.isNotEmpty() && TextEditControlPolicy.isUnavailableLabel(label, passwordField)) {
                val reason = TextEditControlPolicy.unavailableReason(label, passwordField)
                view.isEnabled = false
                view.isClickable = false
                view.alpha = 0.38f
                view.contentDescription = label
                if (Build.VERSION.SDK_INT >= 30) view.stateDescription = reason
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
    }

    /** Keep copy/cut/paste honest as the target editor selection changes. */
    internal fun refreshTextEditAvailability(
        selectionAvailable: Boolean,
        clipboardAvailable: Boolean,
    ) {
        if (panel != Panel.TEXT_EDITOR) return

        fun visit(view: View) {
            val action = (view.tag as? String)
                ?.takeIf { it.startsWith("textedit-action:") }
                ?.substringAfter(':')
            if (action != null && (view is ImeKeyView || view is TextView)) {
                val label = when (view) {
                    is ImeKeyView -> view.contentDescription?.toString().orEmpty()
                    is TextView -> view.text.toString()
                    else -> ""
                }
                val policyUnavailable = TextEditControlPolicy.isUnavailableLabel(label, passwordField)
                val dynamicReason = when {
                    policyUnavailable -> TextEditControlPolicy.unavailableReason(label, passwordField)
                    label in setOf("复制", "剪切") && !selectionAvailable -> "请先选择文本"
                    label == "粘贴" && !clipboardAvailable -> "剪贴板暂无文本"
                    else -> null
                }
                val unavailable = dynamicReason != null
                view.isEnabled = !unavailable
                view.isClickable = !unavailable
                view.alpha = if (unavailable) 0.38f else 1f
                val reason = dynamicReason ?: "当前编辑器暂不支持"
                view.contentDescription = if (unavailable && Build.VERSION.SDK_INT < 30) {
                    "$label，不可用：$reason"
                } else {
                    label
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    view.stateDescription = if (unavailable) {
                        reason
                    } else {
                        "可用"
                    }
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(expandedPanel)
    }

    private fun renderSettings(reusePanel: Boolean = false) {
        val previousFocusKey = if (reusePanel) semanticFocusKey(expandedPanel.findFocus()) else null
        val previousScrollY = if (reusePanel && expandedPanel.childCount > 1) {
            (expandedPanel.getChildAt(1) as? ScrollView)?.scrollY ?: settingsScrollY
        } else {
            settingsScrollY
        }
        if (!reusePanel || expandedPanel.childCount == 0) {
            addPanelHead("偏好设置")
        } else {
            while (expandedPanel.childCount > 1) {
                expandedPanel.removeViewAt(expandedPanel.childCount - 1)
            }
        }
        val scroll = ScrollView(context).apply {
            tag = "settings-scroll"
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setOnScrollChangeListener { _, _, scrollY, _, _ -> settingsScrollY = scrollY }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(18))
            tag = "settings-panel"
        }
        content.addView(sectionTitle("键盘主题"), wrapParams())
        content.addView(panelRenderer.panelChipScroll(ImeTheme.entries.map { it.label }, theme.label) { label ->
            ImeTheme.entries.firstOrNull { it.label == label }?.let { selectedTheme ->
                setTheme(selectedTheme)
                renderSettings(reusePanel = true)
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { bottomMargin = dp(12) })
        content.addView(sectionTitle("外观"), wrapParams())
        content.addView(panelRenderer.panelChipScroll(ImeAppearance.entries.map { it.label }, appearance.label) { label ->
            appearance = ImeAppearance.entries.first { it.label == label }
            setAppearance(appearance)
            listener.onAppearanceChanged(appearance)
            renderSettings(reusePanel = true)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { bottomMargin = dp(12) })
        content.addView(sectionTitle("强调色"), wrapParams())
        content.addView(
            accentColorRow(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )
        content.addView(sectionTitle("按键皮肤"), wrapParams())
        content.addView(
            settingGroup(
                settingsSlider("圆角", 0, 24, skinRadius) { v ->
                    skinRadius = v; listener.onSkinChanged(skinOpacity, skinRadius, skinFontSize, skinPrimaryColor)
                    applyTheme()
                },
                settingsSlider("不透明度", 70, 100, skinOpacity) { v ->
                    skinOpacity = v; listener.onSkinChanged(skinOpacity, skinRadius, skinFontSize, skinPrimaryColor)
                    applyTheme()
                },
                settingsSlider("按键字号", 14, 22, skinFontSize) { v ->
                    skinFontSize = v; listener.onSkinChanged(skinOpacity, skinRadius, skinFontSize, skinPrimaryColor)
                    applyTheme()
                },
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )
        content.addView(sectionTitle("按键与输入"), wrapParams())
        content.addView(
            settingGroup(
                settingToggleRow("按键音效", "机械轴敲击反馈"),
                settingToggleRow("触感震动", "轻微触感反馈"),
                settingToggleRow("按键气泡", "可选字母预览，默认仅按键变色"),
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )
        content.addView(sectionTitle("智能输入"), wrapParams())
        content.addView(
            settingGroup(
                settingNavigationRow(
                    "模糊音与智能纠错",
                    "进入后配置 z/zh、c/ch、s/sh 等规则",
                ) { showPanel(Panel.FUZZY_SETTINGS) },
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )
        scroll.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        expandedPanel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        scroll.post {
            scroll.scrollTo(0, previousScrollY)
            previousFocusKey?.let { key -> findSemanticFocusTarget(expandedPanel, key)?.requestFocus() }
        }
        if (reusePanel) {
            applyTheme()
            onViewHierarchyRebuilt()
        }
    }

    private fun semanticFocusKey(view: View?): String? {
        val description = view?.contentDescription?.toString()
            ?.substringBefore('，')
            ?.takeIf { it.isNotBlank() }
        return description ?: (view?.tag as? String)?.takeIf { it.isNotBlank() }
    }

    private fun findSemanticFocusTarget(root: View, key: String): View? {
        if (semanticFocusKey(root) == key && root.isFocusable) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findSemanticFocusTarget(root.getChildAt(index), key)?.let { return it }
            }
        }
        return null
    }

    private fun sectionTitle(textValue: String): TextView = TextView(context).apply {
        text = textValue
        textSize = 12f
        includeFontPadding = false
        setPadding(dp(4), dp(2), 0, dp(8))
        tag = "panel-section-title"
    }

    private fun settingGroup(vararg rows: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        tag = "setting-group"
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                addView(View(context).apply { tag = "setting-divider" }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1),
                ).apply {
                    marginStart = dp(44)
                    marginEnd = dp(12)
                })
            }
            addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (row is TextView) dp(54) else LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun settingIcon(label: String): ImageView = ImageView(context).apply {
        setImageResource(
            when (label) {
                "按键音效" -> R.drawable.ic_volume
                "触感震动" -> R.drawable.ic_vibration
                "按键气泡" -> R.drawable.ic_bubble
                "模糊音与智能纠错", "启用模糊音" -> R.drawable.ic_tune
                else -> R.drawable.ic_tune
            },
        )
        scaleType = ImageView.ScaleType.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        tag = "setting-icon"
    }

    private fun settingToggleRow(label: String, sub: String): LinearLayout {
        val row = LinearLayout(context)
        fun updateRowAccessibility(enabled: Boolean) {
            row.contentDescription = "$label，$sub，${if (enabled) "已开启" else "已关闭"}"
            if (Build.VERSION.SDK_INT >= 30) {
                row.stateDescription = if (enabled) "已开启" else "已关闭"
            }
        }
        val toggleView = toggle(label, ::updateRowAccessibility).apply {
            // The row is the single accessibility/control target. Keep the
            // visual switch touchable, but do not expose a duplicate node.
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        row.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            tag = "setting-row"
            minimumHeight = dp(ImeGeometryTokens.SETTING_ROW_HEIGHT_DP)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleView.performClick() }
            addView(settingIcon(label), LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                marginEnd = dp(8)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = label
                    textSize = 14f
                    includeFontPadding = false
                }, wrapParams())
                addView(TextView(context).apply {
                    text = sub
                    textSize = 11f
                    includeFontPadding = false
                    setPadding(0, dp(3), 0, 0)
                }, wrapParams())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, weightParams(1f))
            addView(toggleView, wrapParams())
        }
        updateRowAccessibility(onState(label))
        return row
    }

    private fun settingNavigationRow(label: String, sub: String, onTap: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            tag = "setting-row"
            contentDescription = "$label，$sub，点击进入"
            minimumHeight = dp(ImeGeometryTokens.SETTING_ROW_HEIGHT_DP)
            isClickable = true
            isFocusable = true
            setOnClickListener { feedback(); onTap() }
            addView(settingIcon(label), LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                marginEnd = dp(8)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = label
                    textSize = 14f
                    includeFontPadding = false
                }, wrapParams())
                addView(TextView(context).apply {
                    text = sub
                    textSize = 11f
                    includeFontPadding = false
                    setPadding(0, dp(3), 0, 0)
                }, wrapParams())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, weightParams(1f))
            addView(TextView(context).apply {
                text = "›"
                textSize = 18f
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                tag = "setting-chevron"
            }, LinearLayout.LayoutParams(dp(28), dp(44)))
        }

    private fun renderFuzzySettings() {
        addPanelHead("模糊音纠错")
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(18))
            tag = "fuzzy-settings-panel"
        }
        content.addView(TextView(context).apply {
            text = "用于处理常见的近音输入。开启后，候选会同时尝试相近声母，不会改变用户已经输入的拼音。"
            textSize = 13f
            setLineSpacing(0f, 1.15f)
            tag = "panel-note"
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54),
        ).apply { bottomMargin = dp(10) })
        content.addView(
            settingGroup(settingToggleRow("启用模糊音", "z/zh · c/ch · s/sh · l/n")),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) },
        )
        content.addView(sectionTitle("当前规则"), wrapParams())
        content.addView(TextView(context).apply {
            text = "z / zh · c / ch · s / sh · l / n · en / eng · in / ing"
            textSize = 13f
            setPadding(0, dp(6), 0, dp(6))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(14) })
        content.addView(TextView(context).apply {
            text = "规则由输入法自动参与候选计算，暂不单独修改每一组映射。"
            textSize = 12f
            tag = "panel-note"
        }, wrapParams())
        scroll.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        expandedPanel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
    }

    private fun toggle(seed: String, onChanged: (Boolean) -> Unit = {}): View {
        val on = when (seed) {
            "按键音效" -> soundEnabled
            "触感震动" -> hapticEnabled
            "模糊音纠错" -> fuzzyEnabled
            "按键气泡" -> popupEnabled
            else -> true
        }
        val isOn = onState(seed)
        val knob = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(ImeGeometryTokens.SWITCH_KNOB_DP),
                dp(ImeGeometryTokens.SWITCH_KNOB_DP),
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            background = ImeDrawableFactory.rounded(Color.WHITE, dp(ImeGeometryTokens.PILL_RADIUS_DP))
            translationX = if (isOn) dp(ImeGeometryTokens.SWITCH_KNOB_TRAVEL_DP).toFloat() else 0f
        }
        return FrameLayout(context).apply {
            setPadding(
                dp(ImeGeometryTokens.SWITCH_PADDING_DP),
                dp(ImeGeometryTokens.SWITCH_PADDING_DP),
                dp(ImeGeometryTokens.SWITCH_PADDING_DP),
                dp(ImeGeometryTokens.SWITCH_PADDING_DP),
            )
            minimumWidth = dp(ImeGeometryTokens.SWITCH_WIDTH_DP)
            minimumHeight = dp(ImeGeometryTokens.SWITCH_HEIGHT_DP)
            tag = "toggle"
            isClickable = true
            isFocusable = true
            fun updateAccessibilityState(enabled: Boolean) {
                contentDescription = "$seed，${if (enabled) "已开启" else "已关闭"}"
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    stateDescription = if (enabled) "已开启" else "已关闭"
                }
            }
            updateAccessibilityState(isOn)
            addView(knob)
            setOnClickListener {
                feedback()
                val next = !onState(seed)
                toggleCallback(seed)?.invoke(next)
                updateAccessibilityState(next)
                onChanged(next)
                val knobView = getChildAt(0)
                knobView.layoutParams = FrameLayout.LayoutParams(
                    dp(ImeGeometryTokens.SWITCH_KNOB_DP),
                    dp(ImeGeometryTokens.SWITCH_KNOB_DP),
                ).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
                knobView.animate()
                    .cancel()
                knobView.animate()
                    .translationX(
                        if (next) dp(ImeGeometryTokens.SWITCH_KNOB_TRAVEL_DP).toFloat() else 0f,
                    )
                    .setDuration(160L)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()
                applyTheme()
            }
        }
    }

    private fun onState(seed: String): Boolean = when (seed) {
        "按键音效" -> soundEnabled
        "触感震动" -> hapticEnabled
        "模糊音纠错", "启用模糊音" -> fuzzyEnabled
        "按键气泡" -> popupEnabled
        else -> true
    }

    private fun toggleCallback(seed: String): ((Boolean) -> Unit)? = when (seed) {
        "按键音效" -> { { soundEnabled = it; listener.onSoundChanged(it) } }
        "触感震动" -> { { hapticEnabled = it; listener.onHapticChanged(it) } }
        "模糊音纠错", "启用模糊音" -> { { fuzzyEnabled = it; listener.onFuzzyChanged(it) } }
        "按键气泡" -> { { popupEnabled = it; listener.onPopupChanged(it) } }
        else -> null
    }


    private fun accentColorRow(): LinearLayout {
        val current = AccentPalette.normalize(skinPrimaryColor)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "setting-group"
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val swatchGrid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        AccentPalette.presets.chunked(6).forEach { presetRow ->
            val swatchRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            presetRow.forEach { (hex, label) ->
                val selected = AccentPalette.normalize(hex) == current
                swatchRow.addView(
                    FrameLayout(context).apply {
                        tag = "accent-swatch"
                        contentDescription = "强调色$label，${if (selected) "已选中" else "未选中"}"
                        if (Build.VERSION.SDK_INT >= 30) {
                            stateDescription = if (selected) "已选中" else "未选中"
                        }
                        isClickable = true
                        isFocusable = true
                        addView(View(context).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(AccentPalette.parse(hex))
                                if (selected) setStroke(dp(2), ImeDrawableFactory.contrastText(AccentPalette.parse(hex)))
                            }
                        }, FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                            gravity = Gravity.CENTER
                        })
                        if (selected) {
                            addView(TextView(context).apply {
                                text = "✓"
                                textSize = 13f
                                gravity = Gravity.CENTER
                                includeFontPadding = false
                                tag = "accent-selected-mark:$hex"
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }, FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                                gravity = Gravity.CENTER
                            })
                        }
                        setOnClickListener { feedback(); applyAccentColor(hex) }
                    },
                    LinearLayout.LayoutParams(dp(48), dp(48)),
                )
            }
            swatchGrid.addView(swatchRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48),
            ))
        }
        row.addView(swatchGrid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(144),
        ))
        val customSelected = AccentPalette.presets.none { AccentPalette.normalize(it.first) == current }
        row.addView(TextView(context).apply {
            text = if (customSelected) "自定义 · $current" else "自定义颜色"
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(10), 0)
            tag = "accent-custom"
            minHeight = dp(48)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = "自定义强调色，${if (customSelected) "已选中" else "未选中"}"
            if (Build.VERSION.SDK_INT >= 30) stateDescription = if (customSelected) "已选中" else "未选中"
            setOnClickListener { feedback(); showCustomAccentDialog() }
        }, LinearLayout.LayoutParams(dp(132), dp(48)).apply {
            topMargin = dp(6)
        })
        row.addView(TextView(context).apply {
            text = AccentPalette.presets.firstOrNull { AccentPalette.normalize(it.first) == current }?.second ?: current
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
            tag = "panel-note"
        }, wrapParams())
        return row
    }

    private fun applyAccentColor(hex: String) {
        skinPrimaryColor = AccentPalette.normalize(hex)
        listener.onSkinChanged(skinOpacity, skinRadius, skinFontSize, skinPrimaryColor)
        applyTheme()
        if (panel == Panel.SETTINGS) renderSettings(reusePanel = true)
    }

    private fun showCustomAccentDialog() {
        val field = EditText(context).apply {
            setText(AccentPalette.normalize(skinPrimaryColor).removePrefix("#"))
            hint = "RRGGBB"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setSelectAllOnFocus(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("自定义强调色")
            .setMessage("输入 6 位十六进制颜色，例如 5B6B7A")
            .setView(field)
            .setPositiveButton("应用", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            SetupUi.styleDialog(dialog, context)
            val accent = AccentPalette.parse(skinPrimaryColor)
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(accent)
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setTextColor(accent)
            field.backgroundTintList = ColorStateList.valueOf(accent)
            SetupUi.styleCursor(context, field)
            field.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
                    true
                } else {
                    false
                }
            }
            field.requestFocus()
            field.selectAll()
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().trim().removePrefix("#")
                if (!value.matches(Regex("[0-9a-fA-F]{6}"))) {
                    field.error = "请输入 6 位十六进制颜色"
                    field.requestFocus()
                    return@setOnClickListener
                }
                applyAccentColor("#$value")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun settingsSlider(labelText: String, min: Int, max: Int, initial: Int, onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            tag = "setting-row"
            minimumHeight = dp(ImeGeometryTokens.SETTING_ROW_HEIGHT_DP)
        }
        row.addView(TextView(context).apply { text = labelText; textSize = 13f }, weightParams(1f))
        val valueView = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = dp(48)
            contentDescription = "$labelText 当前值"
        }
        val suffix = when (labelText) {
            "圆角" -> " dp"
            "不透明度" -> "%"
            "按键字号" -> " sp"
            else -> ""
        }
        val seekBar = SeekBar(context).apply {
            this.min = min
            this.max = max
            progress = initial.coerceIn(min, max)
            minimumHeight = dp(48)
            isFocusable = true
            tag = "settings-slider:$labelText"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val description = "$labelText，$progress$suffix"
                    valueView.text = "$progress$suffix"
                    contentDescription = description
                    if (Build.VERSION.SDK_INT >= 30) stateDescription = description
                    if (fromUser) onChange(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        row.addView(seekBar, weightParams(2f))
        row.addView(valueView, LinearLayout.LayoutParams(dp(48), dp(48)))
        val initialDescription = "$labelText，${seekBar.progress}$suffix"
        valueView.text = "${seekBar.progress}$suffix"
        seekBar.contentDescription = initialDescription
        if (Build.VERSION.SDK_INT >= 30) seekBar.stateDescription = initialDescription
        return row
    }

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
        applyThemeRecursive(target, theme.tokens(appearance, night, AccentPalette.parse(skinPrimaryColor)))
    }

    private fun applyAssociationTheme() {
        if (!::topZone.isInitialized) return
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        applyThemeRecursive(associationRow, theme.tokens(appearance, night, AccentPalette.parse(skinPrimaryColor)))
    }

    private fun firstCandidateOrComposition(): String =
        currentCandidates.firstOrNull() ?: composition.text.toString()

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

    private fun renderExpanded(open: Boolean) {
        if (!open) {
            candidateExpandBtn.text = "⌄"
            candidateExpandBtn.contentDescription = "展开更多候选"
            candidateOverlay.animate().cancel()
            keyboardBody.animate().cancel()
            candidateOverlay.visibility = View.GONE
            candidateOverlay.alpha = 1f
            candidateOverlay.translationY = 0f
            keyboardBody.visibility = View.VISIBLE
            keyboardBody.alpha = 0.96f
            keyboardBody.animate()
                .alpha(1f)
                .setDuration(120L)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
            candidateExpandedOpen = false
            renderedExpandedCandidates = null
            renderedExpandedComposition = null
            candidateBarController.syncExpandControl(
            expandedOpen = candidateExpandedOpen,
            hasCandidates = currentCandidates.isNotEmpty(),
        )
            return
        }
        val preview = composition.text.toString()
        if (candidateExpandedOpen && candidateOverlay.visibility == View.VISIBLE &&
            renderedExpandedCandidates == currentCandidates && renderedExpandedComposition == preview
        ) return
        val previousScroll = if (renderedExpandedComposition == preview) {
            (candidateOverlay.getChildAt(1) as? ScrollView)?.scrollY ?: 0
        } else 0
        renderedExpandedCandidates = currentCandidates.toList()
        renderedExpandedComposition = preview
        candidateExpandedOpen = true
        candidateExpandBtn.text = "⌃"
        candidateExpandBtn.contentDescription = "收起候选"
        if (Build.VERSION.SDK_INT >= 30) candidateExpandBtn.stateDescription = "已展开"
        keyboardBody.visibility = View.GONE
        keyboardBody.alpha = 1f
        candidateOverlay.visibility = View.VISIBLE
        candidateOverlay.alpha = 0f
        candidateOverlay.translationY = dp(8).toFloat()
        candidateOverlay.removeAllViews()
        candidateOverlay.addView(
            panelHead("候选字词"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ),
        )
        val scroll = ScrollView(context)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (currentCandidates.isEmpty()) {
            col.addView(title("暂无候选", small = true), wrapParams())
        } else {
            expandedCandidateRows(currentCandidates).forEach { chunk ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { cand ->
                    row.addView(
                        key(cand, false, null, 1f, 15f) { listener.onCandidateSelected(cand) }.apply {
                            allowTwoLineLabel()
                            contentDescription = "候选:$cand"
                        },
                        LinearLayout.LayoutParams(
                            0,
                            dp(keyRowHeightDp()),
                            candidateColumnSpan(cand).toFloat(),
                        ).apply { marginEnd = dp(5) },
                    )
                }
                val remaining = 4 - chunk.sumOf(::candidateColumnSpan)
                if (remaining > 0) row.addView(View(context), LinearLayout.LayoutParams(0, 1, remaining.toFloat()))
                col.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(keyRowHeightDp()),
                    ),
                )
            }
        }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        candidateOverlay.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        applyTheme()
        if (previousScroll > 0) scroll.post { scroll.scrollTo(0, previousScroll) }
        candidateOverlay.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(160L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
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
            mainTextSize = mainTextSizeOverride ?: (if (func) 15f else 20f),
            fitMainText = func || text.length > 2,
        ).apply {
            tag = "key:$text"
            setTag(MARK_FUNCTION_KEY, func)
            contentDescription = if (text.isNotEmpty()) text else if (iconRes != 0) "功能键" else " "
            if (!func && secondary != null && !showSecondaryHints) setSecondaryVisible(false)
            minimumHeight = dp(48)
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
        minHeight = dp(48)
        minimumHeight = dp(48)
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

    private fun backspaceKey(): ImeKeyView = key("", true, null, 1f, 15f, iconRes = R.drawable.ic_backspace) {
        performBackspaceOnce()
    }.apply {
        tag = "key-backspace"
        contentDescription = "删除，向上滑清空"
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        R.id.accessibility_clear_all,
                        "清空全部",
                    ),
                )
            }

            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                if (action == R.id.accessibility_clear_all) {
                    if (!host.isEnabled) return false
                    feedback()
                    listener.onClearAll()
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        }
        val clearHint = TextView(context).apply {
            text = "↑ 清空"
            textSize = 7.5f
            gravity = Gravity.CENTER
            includeFontPadding = false
            alpha = 0.72f
            setTextColor(Color.GRAY)
            isClickable = false
            isFocusable = false
            tag = "backspace-clear-hint"
            contentDescription = null
            visibility = View.INVISIBLE
        }
        addView(clearHint, FrameLayout.LayoutParams(dp(30), dp(14)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(2)
        })
        fun setClearHintActive(active: Boolean) {
            clearHint.visibility = if (backspaceGestureController.active) View.VISIBLE else View.INVISIBLE
            if (active) {
                val destructive = theme.tokens(
                    appearance,
                    isNight(),
                    AccentPalette.parse(skinPrimaryColor),
                ).destructive
                clearHint.text = "清空"
                clearHint.setTextColor(ImeDrawableFactory.contrastText(destructive))
                clearHint.background = ImeDrawableFactory.rounded(destructive, dp(ImeGeometryTokens.BADGE_RADIUS_DP))
                clearHint.alpha = 1f
            } else {
                val secondary = theme.tokens(
                    appearance,
                    isNight(),
                    AccentPalette.parse(skinPrimaryColor),
                ).keySecondaryText
                clearHint.text = "↑ 清空"
                clearHint.setTextColor(secondary)
                clearHint.background = null
                clearHint.alpha = 0.72f
            }
        }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    clearHint.alpha = 1f
                    backspaceGestureController.begin(
                        view,
                        event.getPointerId(event.actionIndex),
                        event.rawX,
                        event.rawY,
                        ::setClearHintActive,
                    )
                    if (debugLogging) Log.d("OpenIme", "backspace-touch-down x=${event.rawX} y=${event.rawY}")
                    true
                }
                // The keyboard root owns MOVE/UP so the gesture survives even
                // when the finger leaves this key's rectangle.
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> true
                else -> true
            }
        }
    }

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

    protected fun applyTheme() {
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val t = theme.tokens(appearance, night, AccentPalette.parse(skinPrimaryColor))
        setBackgroundColor(t.keyboardBackground)
        mainDock.setBackgroundColor(t.expandedBackground)
        keyboardBody.setBackgroundColor(t.keyboardBackground)
        topZone.setBackgroundColor(t.toolbarBackground)
        expandedPanel.setBackgroundColor(t.expandedBackground)
        candidateOverlay.setBackgroundColor(t.expandedBackground)
        applyThemeRecursive(this, t)
        composition.setTextColor(t.keySecondaryText)
        candidateExpandBtn.setTextColor(t.keySecondaryText)
        candidateEmojiBtn.setTextColor(t.keySecondaryText)
        applyFloatingChromeTheme(t)
        if (voiceInlineActive) applyInlineVoicePalette()
    }

    /** Reuse the renderer's design tokens for views added by production decorators. */
    internal fun applyThemeToSubtree(target: View) {
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        applyThemeRecursive(target, theme.tokens(appearance, night, AccentPalette.parse(skinPrimaryColor)))
    }

    private fun applyFloatingChromeTheme(tokens: ImeTheme.Tokens? = null) {
        if (!::floatingDragHandle.isInitialized) return
        val night = isNight()
        val t = tokens ?: theme.tokens(appearance, night, AccentPalette.parse(skinPrimaryColor))
        (floatingDragHandle as? FloatingDragHandleView)?.setDotColor(t.border)
        if (floatingWindowMode) {
            mainDock.background = ImeDrawableFactory.rounded(t.keyboardBackground, dp(ImeGeometryTokens.CARD_RADIUS_DP))
            mainDock.clipToOutline = true
        } else {
            mainDock.setBackgroundColor(t.expandedBackground)
            mainDock.clipToOutline = false
        }
    }

    private fun applyThemeRecursive(view: View, t: ImeTheme.Tokens) {
        when (view) {
            is ImeKeyView -> {
                val side = view.getTag(MARK_SIDE_KEY) == true ||
                    (view.parent as? View)?.tag in setOf("pinyin9-actions", "t9-actions", "digits-actions")
                val label = view.contentDescription?.toString().orEmpty()
                // Function styling is driven by the explicit semantic tag set in
                // key(), never by matching localized label substrings.
                val function = view.getTag(MARK_FUNCTION_KEY) == true
                // Numeric glyphs are white grid keys only when they are real
                // number keys. Function labels such as 123 must stay gray.
                val white = !side && (
                    view.getTag(MARK_WHITE_KEY) == true ||
                        (!function && DIGITS_ONLY.matches(label))
                    )
                val primary = !side && (view.tag == "tab-active" ||
                    view.tag == "key-shift-caps" ||
                    view.tag == "key-shift-active" ||
                    view.tag == "key-enter")
                val color = when {
                    primary -> t.primary
                    white -> t.lightKeyBackground
                    side -> t.sideKeyBackground
                    function -> t.functionKeyBackground
                    else -> t.keyBackground
                }
                val pressedColor = when {
                    primary -> ImeDrawableFactory.dim(color, 0.88f)
                    side -> ImeDrawableFactory.dim(t.sideKeyBackground, 0.88f)
                    function -> ImeDrawableFactory.dim(t.functionKeyBackground, 0.88f)
                    else -> t.keyPressedBackground
                }
                view.applyMainTextScale(skinFontScale())
                view.background = statefulRounded(color, pressedColor, dp(skinRadius))
                // Skin opacity slider fades key backgrounds toward transparency.
                view.background?.alpha = (skinOpacity.coerceIn(70, 100) * 255 / 100)
                view.elevation = 0f
                when {
                    primary -> {
                        val onPrimary = ImeDrawableFactory.contrastText(t.primary)
                        view.setColors(onPrimary, onPrimary, onPrimary)
                    }
                    white -> view.setColors(t.lightKeyText, t.lightKeyText, t.lightKeyText)
                    side -> view.setColors(t.sideKeyText, t.sideKeyText, t.sideKeyText)
                    function -> view.setColors(t.functionKeyText, t.functionKeyText, t.functionKeyText)
                    else -> view.setColors(t.keyText, t.keySecondaryText, t.keyText)
                }
            }
            is LinearLayout -> {
                when (view.tag) {
                    "candidate-first-row" -> view.background = statefulRounded(
                        t.keyBackground,
                        t.keyPressedBackground,
                        dp(ImeGeometryTokens.KEY_RADIUS_DP),
                    )
                    "candidate-row" -> view.background = statefulRounded(
                        Color.TRANSPARENT,
                        t.keyPressedBackground,
                        dp(ImeGeometryTokens.KEY_RADIUS_DP),
                    )
                    "setting-row" -> if (view.isClickable) {
                        view.background = statefulRounded(
                            Color.TRANSPARENT,
                            t.keyPressedBackground,
                            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        )
                    }
                    "nine-symbol-scroll-content", "digits-symbol-scroll-content" -> view.background = ImeDrawableFactory.rounded(
                        t.sideKeyBackground,
                        dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                    )
                    "setting-group" -> view.background = ImeDrawableFactory.rounded(
                        t.toolCardBackground,
                        dp(ImeGeometryTokens.CARD_RADIUS_DP),
                    )
                    "clip-card" -> view.background = ImeDrawableFactory.rounded(
                        t.toolCardBackground,
                        dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                    )
                    "panel-head" -> view.background = ImeDrawableFactory.rounded(
                        t.panelHeadBackground,
                        dp(ImeGeometryTokens.CARD_RADIUS_DP),
                    )
                    else -> if ((view.tag as? String)?.startsWith("tool:") == true && view.isClickable) {
                        view.background = statefulRounded(
                            t.toolCardBackground,
                            t.keyPressedBackground,
                            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        )
                    }
                }
                if (view.contentDescription != null && view.isClickable && view.tag == null) {
                    view.background = statefulRounded(
                        t.toolCardBackground,
                        t.keyPressedBackground,
                        dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                    )
                }
            }
            is ScrollView -> {
                when (view.tag) {
                    "nine-punct-stack", "digits-symbol-scroll" -> {
                        view.background = ImeDrawableFactory.rounded(
                            t.sideKeyBackground,
                            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        )
                    }
                }
            }
            is ImageView -> {
                if (view.tag == "setting-icon") {
                    val icon = t.primary
                    view.imageTintList = ColorStateList.valueOf(icon)
                    val dark = ImeDrawableFactory.contrastText(t.keyboardBackground) == Color.WHITE
                    view.background = ImeDrawableFactory.rounded(
                        if (dark) Color.argb(42, Color.red(icon), Color.green(icon), Color.blue(icon))
                        else Color.argb(24, Color.red(icon), Color.green(icon), Color.blue(icon)),
                        dp(ImeGeometryTokens.KEY_RADIUS_DP),
                    )
                } else if (view.tag == "key-panel-back") {
                    view.imageTintList = ColorStateList.valueOf(t.keyText)
                    view.background = statefulRounded(
                        t.panelHeadBackground,
                        ImeDrawableFactory.dim(t.panelHeadBackground),
                        dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                    )
                } else if ((view.parent is LinearLayout && (view.parent as LinearLayout).tag == "toolbar-row") ||
                    hasAncestorTag(view, "tools-panel")) {
                    view.imageTintList = ColorStateList.valueOf(t.keyText)
                    if (view.isClickable) {
                        view.background = statefulRounded(
                            Color.TRANSPARENT,
                            t.keyPressedBackground,
                            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        )
                    }
                }
            }
            is SeekBar -> {
                // Keep the value control in the same accent system as active
                // tabs, primary keys, and selected swatches. The platform
                // default tint is otherwise blue even after a custom accent
                // has been chosen.
                view.progressTintList = ColorStateList.valueOf(t.primary)
                view.thumbTintList = ColorStateList.valueOf(t.primary)
                view.progressBackgroundTintList = ColorStateList.valueOf(t.panelHeadBackground)
            }
            is TextView -> {
                val tag = view.tag as? String
                if (view.parent !is ImeKeyView) view.setTextColor(t.keyText)
                when {
                    tag == "backspace-clear-hint" -> {
                        view.setTextColor(t.keySecondaryText)
                    }
                    tag == "candidate-first" -> {
                        view.setTextColor(t.keyText)
                    }
                    tag == "candidate-word" -> {
                        view.setTextColor(t.candidateText)
                    }
                    tag == "panel-note" -> {
                        view.setTextColor(t.keySecondaryText)
                    }
                    tag == "panel-error" -> {
                        val error = t.destructive
                        view.setTextColor(error)
                        view.background = ImeDrawableFactory.rounded(
                            Color.argb(
                                28,
                                Color.red(error),
                                Color.green(error),
                                Color.blue(error),
                            ),
                            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        )
                    }
                    tag == "tab-active" -> {
                        view.setTextColor(ImeDrawableFactory.contrastText(t.primary))
                        view.background = statefulRounded(
                            t.primary,
                            ImeDrawableFactory.dim(t.primary),
                            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        )
                    }
                    tag == "panel-tab" -> {
                        view.setTextColor(t.keySecondaryText)
                        view.background = statefulRounded(
                            t.panelHeadBackground,
                            ImeDrawableFactory.dim(t.panelHeadBackground),
                            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        )
                    }
                    tag == "quick-phrase-add" -> {
                        view.setTextColor(ImeDrawableFactory.contrastText(t.primary))
                        view.background = statefulRounded(
                            t.primary,
                            ImeDrawableFactory.dim(t.primary),
                            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        )
                    }
                    tag == "panel-button" ||
                        tag == "clipboard-refresh" ||
                        tag?.startsWith("clip-pin:") == true ||
                        tag?.startsWith("clip-use:") == true ||
                        tag?.startsWith("phrase-edit:") == true ||
                        tag?.startsWith("phrase-delete:") == true -> {
                        view.setTextColor(t.keyText)
                        view.background = statefulRounded(
                            t.panelHeadBackground,
                            ImeDrawableFactory.dim(t.panelHeadBackground),
                            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        )
                    }
                    tag == "clipboard-retention-action" -> {
                        view.setTextColor(t.keyText)
                        view.background = statefulRounded(
                            t.panelHeadBackground,
                            ImeDrawableFactory.dim(t.panelHeadBackground),
                            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        )
                    }
                    tag == "clipboard-retention-destructive" -> {
                        view.setTextColor(ImeDrawableFactory.contrastText(t.destructive))
                        view.background = statefulRounded(
                            t.destructive,
                            ImeDrawableFactory.dim(t.destructive, 0.86f),
                            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        )
                    }
                    tag?.startsWith("punct:") == true ||
                        tag?.startsWith("digit-symbol:") == true -> {
                        view.setTextColor(t.keyText)
                        view.background = statefulRounded(
                            Color.TRANSPARENT,
                            t.keyPressedBackground,
                            dp(ImeGeometryTokens.KEY_RADIUS_DP),
                        )
                    }
                    tag == "nine-pinyin-path-filter" -> {
                        view.setTextColor(ImeDrawableFactory.contrastText(t.primary))
                        view.background = statefulRounded(
                            t.primary,
                            ImeDrawableFactory.dim(t.primary, 0.86f),
                            dp(ImeGeometryTokens.KEY_RADIUS_DP),
                        )
                    }
                    tag == "accent-custom" -> {
                        val customSelected = AccentPalette.presets.none {
                            AccentPalette.normalize(it.first) == AccentPalette.normalize(skinPrimaryColor)
                        }
                        view.setTextColor(if (customSelected) ImeDrawableFactory.contrastText(t.primary) else t.keyText)
                        view.background = if (customSelected) {
                            statefulRounded(
                                t.primary,
                                ImeDrawableFactory.dim(t.primary),
                                dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                            )
                        } else {
                            statefulRounded(
                                t.panelHeadBackground,
                                ImeDrawableFactory.dim(t.panelHeadBackground),
                                dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                            )
                        }
                    }
                    tag?.startsWith("accent-selected-mark:") == true -> {
                        val hex = tag.substringAfter(':')
                        view.setTextColor(ImeDrawableFactory.contrastText(AccentPalette.parse(hex)))
                    }
                    tag == "key-panel-back" -> {
                        view.setTextColor(t.keyText)
                        view.background = statefulRounded(
                            t.panelHeadBackground,
                            ImeDrawableFactory.dim(t.panelHeadBackground),
                            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        )
                    }
                    tag == "panel-title" -> {
                        view.setTextColor(t.keyText)
                    }
                    tag == "candidate-emoji" || tag == "candidate-expand" -> {
                        view.setTextColor(t.keySecondaryText)
                        view.background = statefulRounded(
                            t.panelHeadBackground,
                            ImeDrawableFactory.dim(t.panelHeadBackground),
                            dp(ImeGeometryTokens.KEY_RADIUS_DP),
                        )
                    }
                    tag == "voice-transcript" -> {
                        view.setTextColor(t.keyText)
                        view.background = ImeDrawableFactory.rounded(t.toolCardBackground, dp(ImeGeometryTokens.CARD_RADIUS_DP))
                    }
                    tag == "voice-model-status" -> {
                        view.setTextColor(t.keySecondaryText)
                    }
                    tag == "association-candidate" -> {
                        view.setTextColor(t.candidateText)
                        view.background = statefulRounded(
                            t.toolCardBackground,
                            ImeDrawableFactory.dim(t.toolCardBackground),
                            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        )
                    }
                    tag == "tools-page-dots" -> {
                        view.setTextColor(t.keySecondaryText)
                    }
                    tag == "voice-mic" -> {
                        view.setTextColor(ImeDrawableFactory.contrastText(t.primary))
                        view.background = statefulRounded(
                            t.primary,
                            ImeDrawableFactory.dim(t.primary, 0.88f),
                            dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        )
                    }
                    view.parent is LinearLayout &&
                        ((view.parent as LinearLayout).tag == "nine-symbol-scroll-content" ||
                            (view.parent as LinearLayout).tag == "digits-symbol-scroll-content") -> {
                        view.setTextColor(t.sideKeyText)
                    }
                }
            }
            is FrameLayout -> when (view.tag) {
                "emoji-cell" -> view.background = statefulRounded(
                    Color.TRANSPARENT,
                    t.keyPressedBackground,
                    dp(ImeGeometryTokens.KEY_RADIUS_DP),
                )
                "accent-swatch" -> view.background = statefulRounded(
                    Color.TRANSPARENT,
                    t.keyPressedBackground,
                    dp(ImeGeometryTokens.KEY_RADIUS_DP),
                )
                "toggle" -> {
                    val seed = view.contentDescription?.toString()
                        ?.substringBefore('，')
                        .orEmpty()
                    val enabled = onState(seed)
                    view.background = ImeDrawableFactory.rounded(
                        if (enabled) t.primary else t.panelHeadBackground,
                        dp(ImeGeometryTokens.PILL_RADIUS_DP),
                    )
                }
            }
            else -> when (view.tag) {
                "handwriting-canvas" -> view.background = ImeDrawableFactory.rounded(
                    t.canvasBackground,
                    dp(ImeGeometryTokens.CARD_RADIUS_DP),
                )
                "voice-wave-bar" -> view.background = ImeDrawableFactory.rounded(t.primary, dp(ImeGeometryTokens.PILL_RADIUS_DP))
                "setting-divider" -> view.setBackgroundColor(t.border)
            }
        }
        if (view is HandwritingPadView) {
            view.setInkColor(t.primary)
            view.setGridColor(
                Color.argb(
                    72,
                    Color.red(t.border),
                    Color.green(t.border),
                    Color.blue(t.border),
                ),
            )
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyThemeRecursive(view.getChildAt(i), t)
        }
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

    private fun hasAncestorTag(view: View, tag: String): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent.tag == tag) return true
            parent = parent.parent
        }
        return false
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun wrapParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
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
    private fun weightParams(weight: Float) = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        weight,
    ).apply {
        marginEnd = dp(3)
    }
}
