package llc.slacker.openime

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
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
    // Voice arms at the platform long-press threshold instead of a hard-coded
    // 150 ms, so a deliberate-but-brief space press no longer opens the mic.
    private val spaceVoiceTriggerMs = ViewConfiguration.getLongPressTimeout().toLong()
    // Whether long-press alternate glyphs are shown as small corner hints.
    private var showSecondaryHints = true
    // Touch-coordinate trace logs are debug-only; they must never spam logcat
    // (or cost latency) in release builds.
    private val debugLogging: Boolean by lazy {
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    private val repeatAction = object : Runnable {
        override fun run() {
            if (!backspaceGestureActive || backspaceClearArmed) return
            backspaceRepeatStarted = true
            performBackspaceOnce()
            repeatHandler.postDelayed(this, 60L)
        }
    }
    private var backspaceGestureActive = false
    private var backspaceClearArmed = false
    private var backspaceRepeatStarted = false
    private var backspaceRepeatSuspended = false
    private var backspacePointerId = -1
    private var backspaceStartX = 0f
    private var backspaceStartY = 0f
    private var backspaceAnchor: View? = null
    private var backspaceClearUiAction: ((Boolean) -> Unit)? = null
    private var backspaceRepeatStartAction: Runnable? = null

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
    private var lastTextMode = KeyboardMode.PINYIN_26
    private var preferredChineseMode = ImeSettingsRepository.loadPreferredChineseMode(context)
    protected var panel = Panel.NONE
    // Nested panel flows must be reversible. For example, Settings -> Fuzzy
    // settings should return to Settings instead of unexpectedly closing all
    // the way back to the keyboard.
    private val panelBackStack = mutableListOf<Panel>()
    fun currentPanel(): Panel = panel
    private var shiftState = ShiftState.LOWERCASE
    private var soundEnabled = true
    private var hapticEnabled = true
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
    private var lastT9Digits = ""
    private var currentCandidates = emptyList<String>()
    private var currentItems: List<String>? = null
    private var candidateExpandedOpen = false
    private var voiceEventGeneration = 0L
    private var renderedStripCandidates: List<String>? = null
    private var renderedStripComposition: String? = null
    private var renderedExpandedCandidates: List<String>? = null
    private var renderedExpandedComposition: String? = null
    private var symbolCategory = "中文"
    private var emojiCategory = "笑脸"
    private var clipboardTab = 0
    // Guards async clipboard loads so a stale background result can't render over a newer panel.
    private var clipboardLoadGen = 0
    private var voiceLanguageIndex = 0
    private var toolPage = 0
    private var settingsScrollY = 0
    private var voiceActive = false
    private var voicePending = false
    private var inlineVoicePaletteColor: Int? = null
    private var voiceStartAction: (() -> Unit)? = null
    private var voiceStopAction: (() -> Unit)? = null
    private var voiceCancelAction: (() -> Unit)? = null
    private var voiceCancelPreviewAction: ((Boolean) -> Unit)? = null
    private var voiceGestureSession = false
    private var spaceVoiceGestureActive = false
    private var spaceVoiceGestureCancel = false
    private var spaceVoiceDownY = 0f
    private var spaceVoicePointerId = -1
    private var voiceInlineActive = false
    private var voiceInlineCancel = false
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
    // Gaming opens docked by default; the in-panel "恢复浮动" toggle opts into
    // the floating drag surface instead of forcing it on entry.
    private var floatingKeyboard = false
    private var popupView: View? = null
    private var keepPopupAfterKeyUp = false
    private val popupHideRunnable = Runnable { hidePopup() }
    private var contentInsetPx = dp(5)
    private var systemBottomInsetPx = 0
    private var topZoneExpanded = false
    private val maxContentWidthDp = 600
    // Portrait keeps the historical 296dp total. Landscape uses a compact
    // keyboard, and key rows grow with the system font scale so sp labels are
    // never clipped inside a fixed-height key.
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun keyRowHeightDp(): Int {
        val base = if (isLandscape()) 40 else 48
        val fontGrow = ((resources.configuration.fontScale - 1f).coerceAtLeast(0f) * 12f)
            .toInt().coerceAtMost(12)
        return base + fontGrow
    }

    private fun imeHeightDp(): Int {
        // 64dp top zone + four key rows + three 6dp gaps + 22dp bottom breathing.
        val derived = 64 + keyRowHeightDp() * 4 + 6 * 3 + 22
        return maxOf(if (isLandscape()) 258 else 296, derived)
    }

    private fun topZoneHeightDp(): Int = if (topZoneExpanded) 70 else 64
    private fun keyboardBodyHeightDp(): Int = imeHeightDp() - topZoneHeightDp()
    private fun panelBodyHeightDp(): Int = (imeHeightDp() - 48).coerceAtLeast(0)
    private var syncingComposition = false
    private var t9Filter = "T9"
    private var passwordField = false
    private var inlineEditTarget: EditText? = null
    private val panelChipScrollPositions = mutableMapOf<String, Int>()
    private val panelVerticalScrollPositions = mutableMapOf<String, Int>()

    private lateinit var mainDock: LinearLayout
    private lateinit var keyboardHost: FrameLayout
    private lateinit var topZone: LinearLayout
    private lateinit var toolbarRow: LinearLayout
    private lateinit var composeZone: LinearLayout
    private lateinit var composition: EditText
    private lateinit var candidateRow: LinearLayout
    private lateinit var associationRow: LinearLayout
    private lateinit var candidateExpandBtn: TextView
    private lateinit var candidateEmojiBtn: TextView
    private lateinit var voiceInlineZone: LinearLayout
    private lateinit var voiceInlineStatus: TextView
    private val voiceInlineWaves = mutableListOf<View>()
    private val keyboardBody = LinearLayout(context)
    private val expandedPanel = LinearLayout(context)
    private val candidateOverlay = LinearLayout(context)
    private var nineTapKey = ""
    private var nineTapIndex = 0
    private val nineTapReset = Runnable {
        nineTapKey = ""
        nineTapIndex = 0
    }

    init {
        tag = "ime_root"
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
        if (!geometryChanged) return
        // Do not yank the user out of an open panel.
        applyDynamicHeights()
        if (!standalonePanel && currentPanel() == Panel.NONE) renderModeBody()
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (standalonePanel) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val desiredHeight = dp(imeHeightDp())
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val measuredHeight = when {
            mode == MeasureSpec.AT_MOST -> minOf(desiredHeight, size)
            mode == MeasureSpec.EXACTLY && size < desiredHeight -> size
            else -> desiredHeight
        }
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
        )
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
        val minimumInset = dp(5)
        val maxWidth = dp(maxContentWidthDp)
        contentInsetPx = maxOf(minimumInset, (measuredWidthPx - maxWidth) / 2)
        keyboardBody.setPadding(
            contentInsetPx,
            dp(6),
            contentInsetPx,
            dp(if (topZoneExpanded) 10 else 16),
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
        toolbarRow.setPadding(contentInsetPx + dp(10), 0, contentInsetPx + dp(10), 0)
        composition.setPadding(contentInsetPx + dp(14), dp(3), contentInsetPx + dp(14), 0)
        requestLayout()
    }

    /** Idle top zone is 64dp; composing expands to 70dp for a 48dp candidate target. */
    private fun buildTopZone() {
        topZone = LinearLayout(context).apply {
            tag = "ime_toolbar"
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(64)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64),
            )
        }
        toolbarRow = LinearLayout(context).apply {
            tag = "toolbar-row"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            minimumHeight = dp(64)
        }
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_grid, "切换键盘", "keyboard-selector") { showPanel(Panel.KEYBOARD_SELECT) },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_clipboard, "剪贴板", "toolbar") { showPanel(Panel.CLIPBOARD) },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_emoji, "Emoji", "toolbar") { showPanel(Panel.EMOJI) },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_symbols, "符号", "toolbar") { showPanel(Panel.SYMBOLS) },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        associationRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "association-row"
        }
        val associationScroll = HorizontalScrollView(context).apply {
            tag = "association-scroll"
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                associationRow,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)),
            )
        }
        toolbarRow.addView(
            associationScroll,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) },
        )
        // Keep the overflow action at the far right, as in the reference.
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_more, "更多", "toolbar") { showPanel(Panel.TOOLS) },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        topZone.addView(toolbarRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(64),
        ))

        composeZone = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            tag = "compose-zone"
        }
        composition = EditText(context).apply {
            tag = "pinyin-composition-editor"
            contentDescription = "可编辑拼音预编辑"
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            maxLines = 1
            setHorizontallyScrolling(true)
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true
            showSoftInputOnFocus = false
            setSelectAllOnFocus(false)
            background = null
            includeFontPadding = false
            setPadding(dp(14), dp(3), dp(14), 0)
            minimumHeight = dp(22)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!syncingComposition) onCompositionEdited(s?.toString().orEmpty())
                }
            })
        }
        composeZone.addView(composition, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(22),
        ))
        val candField = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        candidateRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        renderedStripCandidates = null
        renderedStripComposition = null
        val candScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                candidateRow,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(48),
                ),
            )
        }
        candField.setPadding(dp(8), 0, dp(8), 0)
        candField.addView(candScroll, LinearLayout.LayoutParams(0, dp(48), 1f))
        // Persistent emoji shortcut kept visible while composing, so the user can
        // jump straight to the emoji panel without first committing/clearing.
        candidateEmojiBtn = TextView(context).apply {
            tag = "candidate-emoji"
            text = "☺"
            textSize = 17f
            gravity = Gravity.CENTER
            contentDescription = "表情"
            setPadding(dp(7), 0, dp(7), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                feedback()
                showPanel(Panel.EMOJI)
            }
        }
        candField.addView(
            candidateEmojiBtn,
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        candidateExpandBtn = TextView(context).apply {
            tag = "candidate-expand"
            text = "⌄"
            textSize = 15f
            gravity = Gravity.CENTER
            contentDescription = "展开更多候选"
            setPadding(dp(7), 0, dp(7), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val open = candidateOverlay.visibility == View.GONE
                renderExpanded(open)
                listener.onCandidateExpanded(open)
            }
        }
        candField.addView(
            candidateExpandBtn,
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        composeZone.addView(candField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
        topZone.addView(composeZone, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(70),
        ))

        // Long-press voice stays inside the current keyboard. This fixed-height
        // row replaces the toolbar in-place, so recording never opens another
        // panel or changes the IME height while the finger is held down.
        voiceInlineZone = LinearLayout(context).apply {
            tag = "voice-inline-zone"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), 0, dp(12), 0)
        }
        voiceInlineZone.addView(
            ImageView(context).apply {
                tag = "voice-inline-icon"
                contentDescription = null
                setImageResource(R.drawable.ic_mic)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(9) },
        )
        voiceInlineStatus = TextView(context).apply {
            tag = "voice-inline-status"
            text = "正在聆听…"
            textSize = 14f
            setTextColor(Color.WHITE)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        voiceInlineZone.addView(
            voiceInlineStatus,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )
        val inlineWave = LinearLayout(context).apply {
            tag = "voice-inline-waveform"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(6) { index ->
            val bar = View(context).apply {
                tag = "voice-inline-wave-$index"
                background = rounded(Color.WHITE, dp(99))
            }
            voiceInlineWaves += bar
            inlineWave.addView(
                bar,
                LinearLayout.LayoutParams(dp(3), dp(if (index % 2 == 0) 10 else 16)).apply {
                    if (index > 0) marginStart = dp(3)
                },
            )
        }
        voiceInlineZone.addView(
            inlineWave,
            LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.MATCH_PARENT),
        )
        topZone.addView(
            voiceInlineZone,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            },
        )
        mainDock.addView(topZone, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(64),
        ))
        syncCandidateExpandControl()
    }

    private fun toolbarIcon(iconRes: Int, desc: String, tagValue: String, onTap: () -> Unit): ImageView =
        ImageView(context).apply {
            contentDescription = desc
            tag = tagValue
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(iconRes)
            isClickable = true
            setOnClickListener { feedback(); onTap() }
        }

    fun cycleMode() {
        val next = when (mode) {
            KeyboardMode.PINYIN_26, KeyboardMode.PINYIN_9 -> KeyboardMode.ENGLISH_26
            KeyboardMode.ENGLISH_26 -> preferredChineseMode
            KeyboardMode.DIGITS -> lastTextMode
            KeyboardMode.ENGLISH_T9 -> preferredChineseMode
        }
        setMode(next)
    }

    fun setMode(newMode: KeyboardMode, notifyListener: Boolean = true) {
        val effectiveMode = if (newMode == KeyboardMode.ENGLISH_T9) {
            KeyboardMode.PINYIN_26
        } else {
            newMode
        }
        if (effectiveMode != KeyboardMode.DIGITS) {
            lastTextMode = effectiveMode
            if (effectiveMode == KeyboardMode.PINYIN_26 || effectiveMode == KeyboardMode.PINYIN_9) {
                // Persist the 26/9-key choice so it survives process death.
                if (preferredChineseMode != effectiveMode) {
                    ImeSettingsRepository.savePreferredChineseMode(context, effectiveMode)
                }
                preferredChineseMode = effectiveMode
            }
        }
        if (panel != Panel.NONE) dismissPanelForModeSwitch()
        repeatHandler.removeCallbacks(nineTapReset)
        nineTapReset.run()
        val layoutChanged = mode != effectiveMode
        mode = effectiveMode
        symbolCategory = when (effectiveMode) {
            KeyboardMode.ENGLISH_26 -> "英文"
            KeyboardMode.DIGITS -> "数学"
            else -> "中文"
        }
        clearAssociationCandidates()
        pinyinBuffer.clear()
        lastNineDigits = ""
        lastNineCandidates = emptyList()
        lastNineSegmentPrefix = ""
        lastNinePinyinPaths = emptyList()
        lastT9Digits = ""
        currentCandidates = emptyList()
        currentItems = emptyList()
        // Rebuild the key rows (and play the switch fade) only when the layout
        // actually changes. Re-focusing another field in the same mode now reuses
        // the existing rows instead of recreating ~150 views on every focus.
        if (layoutChanged || renderedMode != effectiveMode) {
            keyboardBody.animate().cancel()
            keyboardBody.alpha = 0.96f
            renderModeBody()
            keyboardBody.animate().alpha(1f).setDuration(100L).start()
        }
        if (notifyListener) listener.onModeChanged(effectiveMode)
    }

    fun showPanel(newPanel: Panel) {
        if (newPanel == Panel.NONE || newPanel == Panel.CANDIDATE_EXPANDED) return
        hidePopup()
        if (panel == Panel.VOICE && newPanel != Panel.VOICE) stopVoiceIfActive()
        if (panel != Panel.NONE && panel != newPanel) panelBackStack += panel
        panel = newPanel
        mainDock.visibility = View.GONE
        // Publish the page before rendering it. Opening a floating IME can
        // cause InputMethodService to receive a window relayout immediately;
        // the service must already know that GAMING is the active panel or it
        // may restore the IME window to the bottom during that callback.
        listener.onPanelChanged(newPanel)
        renderPanel(newPanel)
        expandedPanel.alpha = 0.96f
        expandedPanel.animate().alpha(1f).setDuration(120L).start()
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
            expandedPanel.alpha = 0.96f
            expandedPanel.animate().alpha(1f).setDuration(120L).start()
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

    fun renderState(state: ImeState) {
        passwordField = state.passwordField
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
            lastT9Digits = ""
            if (candidateExpandedOpen) {
                renderExpanded(false)
                listener.onCandidateExpanded(false)
            }
        } else {
            pinyinBuffer.setLength(0)
            pinyinBuffer.append(state.composition)
            if (mode == KeyboardMode.ENGLISH_T9) lastT9Digits = state.composition
            if (candidateExpandedOpen) {
                renderExpanded(true)
            }
        }
        updateTopZone(state.composition.isNotEmpty())
        renderCandidateRow()
        syncCandidateExpandControl()
    }

    /** Keep the overflow affordance honest when the current composition has no candidates. */
    private fun syncCandidateExpandControl() {
        if (!::candidateExpandBtn.isInitialized) return
        val canExpandOrClose = candidateExpandedOpen || currentCandidates.isNotEmpty()
        candidateExpandBtn.isEnabled = canExpandOrClose
        candidateExpandBtn.alpha = if (canExpandOrClose) 1f else 0.38f
        if (!canExpandOrClose) {
            candidateExpandBtn.contentDescription = "暂无更多候选"
            if (Build.VERSION.SDK_INT >= 30) candidateExpandBtn.stateDescription = "不可用"
        } else if (Build.VERSION.SDK_INT >= 30) {
            candidateExpandBtn.stateDescription = if (candidateExpandedOpen) "已展开" else "可展开"
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
        if (::associationRow.isInitialized) associationRow.removeAllViews()
    }

    fun setTheme(newTheme: ImeTheme) {
        // Called on every focus via reloadPersistedSettings(); avoid a full-tree
        // recolor when nothing changed.
        if (theme == newTheme) return
        theme = newTheme
        applyTheme()
        listener.onThemeChanged(newTheme)
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
        backspaceRepeatStartAction?.let { repeatHandler.removeCallbacks(it) }
        backspaceRepeatStartAction = null
        backspaceGestureActive = false
        backspaceClearArmed = false
        backspaceRepeatStarted = false
        backspaceAnchor?.isPressed = false
        backspaceAnchor = null
        backspaceClearUiAction = null
        spaceVoiceGestureActive = false
        spaceVoiceGestureCancel = false
        pendingRowRebuild = false
        voiceInlineActive = false
        voiceInlineCancel = false
        hidePopup()
    }

    internal fun isVoiceActive(): Boolean = voiceActive

    internal fun findTestTarget(query: String): View? {
        findViewWithTag<View>(query)?.let { return it }
        fun deep(view: View): View? {
            if (view.isClickable && view.contentDescription?.toString() == query) return view
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
        beginBackspaceGesture(anchor, rawX, rawY) { }
        updateBackspaceGesture(rawX, rawY - dp(48))
        finishBackspaceGesture(commit = true)
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
        topZoneExpanded = composing && !voiceInlineActive
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
            dp(if (topZoneExpanded) 10 else 16),
        )
        if (voiceInlineActive) {
            toolbarRow.visibility = View.GONE
            composeZone.visibility = View.GONE
            voiceInlineZone.visibility = View.VISIBLE
            return
        }
        voiceInlineZone.visibility = View.GONE
        toolbarRow.visibility = if (composing) View.GONE else View.VISIBLE
        composeZone.visibility = if (composing) View.VISIBLE else View.GONE
    }

    private fun showInlineVoiceState(
        message: String,
        cancelling: Boolean = false,
        rms: Float? = null,
    ) {
        voiceInlineActive = true
        voiceInlineCancel = cancelling
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
        if (!::voiceInlineZone.isInitialized) return
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val tokens = theme.tokens(appearance, night, AccentPalette.parse(skinPrimaryColor))
        val backgroundColor = if (voiceInlineCancel) Color.rgb(220, 38, 38) else tokens.primary
        if (inlineVoicePaletteColor == backgroundColor) return
        inlineVoicePaletteColor = backgroundColor
        voiceInlineZone.background = rounded(backgroundColor, dp(13))
        val foregroundColor = contrastText(backgroundColor)
        voiceInlineStatus.setTextColor(foregroundColor)
        voiceInlineWaves.forEach { it.background = rounded(foregroundColor, dp(99)) }
    }

    private fun hideInlineVoiceState() {
        stopInlineVoicePulse()
        voiceInlineActive = false
        voiceInlineCancel = false
        if (::voiceInlineZone.isInitialized) voiceInlineZone.visibility = View.GONE
        updateTopZone(composition.text?.isNotEmpty() == true)
    }

    private fun hideInlineVoiceStateLater(delayMs: Long) {
        val generation = voiceInlineGeneration
        postDelayed({
            if (generation == voiceInlineGeneration && !voiceActive) hideInlineVoiceState()
        }, delayMs)
    }

    private fun renderCandidateRow() {
        val visibleCandidates = currentCandidates.take(CANDIDATE_STRIP_LIMIT)
        val preview = composition.text.toString()
        if (renderedStripCandidates == visibleCandidates && renderedStripComposition == preview) return
        val scroll = candidateRow.parent as? HorizontalScrollView
        val keepScroll = renderedStripComposition == preview
        val previousScrollX = if (keepScroll) scroll?.scrollX ?: 0 else 0
        renderedStripCandidates = visibleCandidates.toList()
        renderedStripComposition = preview
        if (currentCandidates.isEmpty()) {
            if (candidateRow.childCount != 1 || candidateRow.getChildAt(0) !is TextView ||
                candidateRow.getChildAt(0).tag != "candidate-empty"
            ) {
                candidateRow.removeAllViews()
                candidateRow.addView(
                    TextView(context).apply {
                        tag = "candidate-empty"
                        textSize = 12f
                        setPadding(dp(10), 0, dp(10), 0)
                    },
                    wrapParams(),
                )
            }
            (candidateRow.getChildAt(0) as TextView).text =
                if (composeZone.visibility == View.VISIBLE) composition.text else ""
            if (!keepScroll) scroll?.scrollTo(0, 0)
            return
        }
        if (candidateRow.childCount == 1 && candidateRow.getChildAt(0).tag == "candidate-empty") {
            candidateRow.removeAllViews()
        }
        val extra = candidateRow.childCount - visibleCandidates.size
        if (extra > 0) candidateRow.removeViews(visibleCandidates.size, extra)
        visibleCandidates.forEachIndexed { index, cand ->
            val existing = candidateRow.getChildAt(index) as? LinearLayout
            if (existing == null) {
                candidateRow.addView(
                    candidateItemView(index, cand),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(48),
                    ).apply { marginEnd = dp(6) },
                )
            } else {
                bindCandidateItem(existing, index, cand)
            }
        }
        if (keepScroll && previousScrollX > 0) {
            scroll?.post { scroll.scrollTo(previousScrollX.coerceAtMost(candidateRow.width), 0) }
        } else {
            scroll?.scrollTo(0, 0)
        }
    }

    private fun candidateItemView(index: Int, cand: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true
            addView(
                TextView(context).apply {
                    textSize = 14f
                    maxLines = 1
                    includeFontPadding = false
                    setPadding(dp(12), 0, dp(12), 0)
                    isClickable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                wrapParams(),
            )
            bindCandidateItem(this, index, cand)
        }
    }

    private fun bindCandidateItem(row: LinearLayout, index: Int, cand: String) {
        row.tag = if (index == 0) "candidate-first-row" else "candidate-row"
        row.contentDescription = "候选:$cand"
        val word = row.getChildAt(0) as TextView
        if (word.text.toString() != cand) word.text = cand
        word.tag = if (index == 0) "candidate-first" else "candidate-word"
        word.typeface = if (index == 0) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val t = theme.tokens(appearance, night, AccentPalette.parse(skinPrimaryColor))
        word.setTextColor(if (index == 0) t.keyText else t.candidateText)
        row.background = statefulRounded(
            if (index == 0) t.keyBackground else Color.TRANSPARENT,
            t.keyPressedBackground,
            dp(8),
        )
        row.setOnClickListener {
            feedback()
            listener.onCandidateSelected(cand)
        }
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
        // Corner hints default on (9-key needs them); renderPinyin26 opts out.
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
            KeyboardMode.ENGLISH_T9 -> renderEnglish9()
            KeyboardMode.DIGITS -> renderDigits()
        }
        updateTopZone(composition.text?.isNotEmpty() == true)
        if (width > 0) updateResponsiveGeometry(width)
        applyTheme()
        onViewHierarchyRebuilt()
        renderedMode = mode
    }

    private fun renderPinyin26() {
        // Keep the 26-key surface clean: long-press digits still work, but the
        // small corner numerals are not painted by default.
        showSecondaryHints = false
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val hints = mapOf(
            'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
            'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        )
        rows.forEachIndexed { rowIndex, rowText ->
            val row = rowHost().apply {
                if (rowIndex == 1) tag = "key-row-secondary"
            }
            if (rowIndex == 2) {
                val leadingKey = if (mode == KeyboardMode.PINYIN_26) {
                    key("分词", true, "@#/", 1f, 12f) { onPinyinSegment() }.apply {
                        tag = "key-segment"
                        contentDescription = "分词，长按输入@井号或斜杠"
                        setOnLongClickListener {
                            showChoicePopup(this, listOf("@", "#", "/"))
                            true
                        }
                    }
                } else {
                    val iconRes = if (shiftState == ShiftState.CAPS_LOCK) R.drawable.ic_caps_lock else R.drawable.ic_shift
                    key("", true, null, 1f, iconRes = iconRes) { cycleShift() }.apply {
                        tag = if (shiftState == ShiftState.CAPS_LOCK) {
                            "key-shift-caps"
                        } else if (shiftState == ShiftState.SHIFT_ONCE) {
                            "key-shift-active"
                        } else {
                            "key-shift"
                        }
                    }
                }
                row.addView(leadingKey, flexKeyParams(1.25f))
            }
            rowText.forEach { ch ->
                val main = if (mode == KeyboardMode.ENGLISH_26 && shiftState != ShiftState.LOWERCASE) {
                    ch.uppercaseChar()
                } else {
                    ch
                }.toString()
                val secondary = if (mode == KeyboardMode.PINYIN_26) hints[ch] else null
                val base = ch.toString()
                val k = key(main, false, secondary, 1f, 20f) { onKeyTapped(base) }.apply {
                    tag = "key:$base"
                }
                if (secondary != null) {
                    k.setOnLongClickListener {
                        commitKeyboardCharacter(secondary)
                        true
                    }
                }
                row.addView(k, flexKeyParams())
            }
            if (rowIndex == 2) {
                row.addView(backspaceKey(), flexKeyParams(1.25f))
            }
            keyboardBody.addView(row, rowParams())
        }
        val bottom = rowHost()
        // Balance the two outer keys around the centered space so it is optically
        // centered on the first frame (same result ProductionKeyPolicy/V2 used to
        // apply in a post pass).
        val outerLeft0 = 1.3f
        val innerLeft = 0.95f
        val innerRight = 1.05f
        val outerRight0 = 1.8f
        val balanced = ProductionKeyPolicy.balancedOuterWeights(
            leftTotal = outerLeft0 + innerLeft,
            rightTotal = innerRight + outerRight0,
            leftOuter = outerLeft0,
            rightOuter = outerRight0,
        )
        bottom.addView(key("123", true, null, 1f, 15f) { setMode(KeyboardMode.DIGITS) }, flexKeyParams(balanced.leftOuter))
        bottom.addView(
            key(if (mode == KeyboardMode.ENGLISH_26) "." else "，", true, null, 1f, 15f) {
                commitKeyboardCharacter(if (mode == KeyboardMode.ENGLISH_26) "." else "，")
            },
            flexKeyParams(innerLeft),
        )
        bottom.addView(
            spaceVoiceKey(if (mode == KeyboardMode.ENGLISH_26) "space" else "空格", white = true) {
                listener.onSpace()
            },
            flexKeyParams(3.4f),
        )
        bottom.addView(
            key("中/英", true, null, 1f, 14f) { cycleMode() }.apply { tag = "key:mode" },
            flexKeyParams(innerRight),
        )
        bottom.addView(
            key(enterKeyLabel(mode == KeyboardMode.ENGLISH_26), true, null, 1f, 15f) { listener.onEnter() }
                .apply { tag = "key-enter" },
            flexKeyParams(balanced.rightOuter),
        )
        keyboardBody.addView(bottom, rowParams(includeBottomGap = false))
    }

    private fun renderEnglish26() = renderPinyin26()

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

    private fun rowHost(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = rowParams()
    }

    private fun renderPinyin9() = renderNine(true)

    private fun renderEnglish9() = renderNine(false)

    /** Nine key / T9 layout. Column widths are weights, not prototype pixels. */
    private fun renderNine(chinese: Boolean) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = if (chinese) "pinyin9-layout" else "t9-layout"
        }

        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (chinese) {
            left.addView(punctStack(), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(156),
            ))
        } else {
            val filters = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                tag = "t9-filter-container"
            }
            listOf("T9", "abc", "ABC").forEach { f ->
                filters.addView(
                    filterChip(f, f == t9Filter) {
                        t9Filter = f
                        renderModeBody()
                        publishComposition(lastT9Digits, candidatesForComposition(lastT9Digits))
                    },
                    LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1f,
                    ).apply {
                        marginStart = dp(2)
                        marginEnd = dp(2)
                    },
                )
            }
            left.addView(filters, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ))
        }
        left.addView(
            key("符号", true, null, 1f, 13f) { showPanel(Panel.SYMBOLS) }
                .apply { setTag(MARK_SIDE_KEY, true) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(6) },
        )
        container.addView(left, adaptiveColumnParams(1f))

        val center = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        center.addView(
            nineGrid(chinese).apply { tag = if (chinese) "pinyin9-grid" else "t9-grid" },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(156),
            ),
        )
        val centerBottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        centerBottom.addView(
            key("123", true, null, 1f, 13f) { setMode(KeyboardMode.DIGITS) }
                .apply { setTag(MARK_SIDE_KEY, true) },
            flexKeyParams(0.9f, gapDp = 2),
        )
        centerBottom.addView(
            spaceVoiceKey("空格", white = true) { commitFirstCandidateOrSpace() },
            flexKeyParams(3.4f, gapDp = 2),
        )
        centerBottom.addView(
            key("中/英", true, null, 1f, 13f) { cycleMode() }.apply {
                tag = "key:mode"
                setTag(MARK_SIDE_KEY, true)
            },
            flexKeyParams(0.95f, gapDp = 2),
        )
        center.addView(centerBottom, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { topMargin = dp(6) })
        container.addView(center, adaptiveColumnParams(3.7f))

        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = if (chinese) "pinyin9-actions" else "t9-actions"
        }
        side.addView(backspaceKey().apply { setTag(MARK_SIDE_KEY, true) }, sideKeyParams(48, true))
        side.addView(
            key("重输", true, null, 1f, 13f) {
                publishComposition("", emptyList())
            }.apply { setTag(MARK_SIDE_KEY, true) },
            sideKeyParams(48, true),
        )
        side.addView(
            key(enterKeyLabel(!chinese), true, null, 1f, 13f) {
                listener.onEnter()
            }.apply {
                tag = "key-enter"
                setTag(MARK_SIDE_KEY, true)
            },
            sideKeyParams(102),
        )
        container.addView(side, adaptiveColumnParams(1f))
        keyboardBody.addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(210),
        ))
    }

    /** Adaptive-width gray punct column（，。？！）, tap commits the character. */
    private fun punctStack(): LinearLayout {
        val stack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "nine-punct-stack"
        }
        listOf("，", "。", "？", "！").forEach { p ->
            stack.addView(TextView(context).apply {
                text = p
                textSize = 17f
                gravity = Gravity.CENTER
                contentDescription = p
                isClickable = true
                setOnClickListener { commitKeyboardCharacter(p) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return stack
    }

    /** 3x3 white grid with letter labels; contentDescription/tag key-9:<digit>. */
    private fun nineGrid(chinese: Boolean): LinearLayout {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            listOf("1" to "@#", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        ).forEachIndexed { rowIndex, rowDef ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowDef.forEach { (num, sub) ->
                val display = if (chinese && num == "1") "分词" else sub
                val secondary = if (chinese && num == "1") "@#/" else null
                row.addView(
                    key(display, false, secondary, 1f, if (num == "1" && chinese) 12f else 17f) {
                        if (chinese && num == "1") onPinyinSegment() else onNineKey(num)
                    }.apply {
                        tag = "key-9:$num"
                        contentDescription = if (chinese && num == "1") "1，分词" else num
                        setTag(MARK_WHITE_KEY, true)
                        if (chinese && num == "1") {
                            setOnLongClickListener {
                                // The segmentation key keeps its tap action;
                                // long press opens the same transient selector
                                // interaction as the clear gesture, then the
                                // user can choose @, # or / horizontally.
                                showChoicePopup(this, listOf("@", "#", "/"))
                                true
                            }
                        } else if (chinese && ImeData.keypad9Map[num].orEmpty().any {
                                it.length == 1 && it[0] in 'a'..'z'
                            }) {
                            setOnLongClickListener {
                                // A long press keeps the 9-key surface useful for
                                // literal digits without making digits the default
                                // Chinese Pinyin composition.
                                commitKeyboardCharacter(num)
                                true
                            }
                        }
                    },
                    flexKeyParams(gapDp = 2),
                )
            }
            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { if (rowIndex < 2) bottomMargin = dp(6) })
        }
        return grid
    }

    private fun renderDigits() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "digits-layout"
        }
        val symStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "digits-symbol-stack"
        }
        listOf("%", "+", "−", "＊").forEach { s ->
            symStack.addView(TextView(context).apply {
                text = s
                textSize = 17f
                gravity = Gravity.CENTER
                contentDescription = s
                isClickable = true
                setOnClickListener { commitKeyboardCharacter(s) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(symStack, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(156),
        ))
        left.addView(
            key("符号", true, null, 1f, 13f) { showPanel(Panel.SYMBOLS) }
                .apply { setTag(MARK_SIDE_KEY, true) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(6) },
        )
        container.addView(left, adaptiveColumnParams(1f))

        val grid = LinearLayout(context).apply {
            tag = "digits-grid"
            orientation = LinearLayout.VERTICAL
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { d ->
                row.addView(
                    key(d, false, null, 1f, 22f) { commitKeyboardCharacter(d) }.apply {
                        tag = "key:$d"
                        setTag(MARK_WHITE_KEY, true)
                    },
                    flexKeyParams(gapDp = 2),
                )
            }
            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { if (rowIndex < 2) bottomMargin = dp(6) })
        }
        val center = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        center.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(156),
        ))
        val centerBottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        centerBottom.addView(
            key("返回", true, null, 1f, 14f) { setMode(lastTextMode) }.apply {
                tag = "key:mode"
                setTag(MARK_SIDE_KEY, true)
            },
            flexKeyParams(),
        )
        centerBottom.addView(
            spaceVoiceKey("空格", white = true) { listener.onSpace() }.apply {
                setTag(MARK_WHITE_KEY, true)
            },
            flexKeyParams(),
        )
        centerBottom.addView(
            key(".", false, null, 1f, 22f) { commitKeyboardCharacter(".") }.apply {
                tag = "key:."
                setTag(MARK_WHITE_KEY, true)
            },
            flexKeyParams(),
        )
        center.addView(centerBottom, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { topMargin = dp(6) })
        container.addView(center, adaptiveColumnParams(3.7f))

        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "digits-actions"
        }
        side.addView(backspaceKey().apply { setTag(MARK_SIDE_KEY, true) }, sideKeyParams(48, true))
        side.addView(
            key("0", false, null, 1f, 22f) { commitKeyboardCharacter("0") }.apply {
                tag = "key:0"
                setTag(MARK_SIDE_KEY, true)
            },
            sideKeyParams(48, true),
        )
        side.addView(
            key("@", true, null, 1f, 15f) { commitKeyboardCharacter("@") }
                .apply { setTag(MARK_SIDE_KEY, true) },
            sideKeyParams(48, true),
        )
        side.addView(
            key(enterKeyLabel(false, "换行"), true, null, 1f, 13f) { listener.onEnter() }
                .apply { tag = "key-enter"; setTag(MARK_SIDE_KEY, true) },
            sideKeyParams(48),
        )
        container.addView(side, adaptiveColumnParams(1f))
        keyboardBody.addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(210),
        ))
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
            popupView?.let { popup ->
                if (event.x < popup.left || event.x >= popup.right ||
                    event.y < popup.top || event.y >= popup.bottom) hidePopup()
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (backspaceGestureActive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(backspacePointerId)
                    if (index >= 0) updateBackspaceGesture(
                        event.rawX + event.getX(index) - event.x,
                        event.rawY + event.getY(index) - event.y,
                    )
                }
                MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == backspacePointerId) {
                    finishBackspaceGesture(commit = true)
                }
                MotionEvent.ACTION_UP -> finishBackspaceGesture(commit = true)
                MotionEvent.ACTION_CANCEL -> finishBackspaceGesture(commit = false)
            }
        }
        if (spaceVoiceGestureActive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(spaceVoicePointerId)
                    if (index < 0) return handled
                    val pointerY = event.rawY + event.getY(index) - event.y
                    val cancelNow = spaceVoiceDownY - pointerY >= dp(48)
                    if (cancelNow != spaceVoiceGestureCancel) {
                        spaceVoiceGestureCancel = cancelNow
                        voiceCancelPreviewAction?.invoke(cancelNow)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                    if (event.actionMasked == MotionEvent.ACTION_POINTER_UP &&
                        event.getPointerId(event.actionIndex) != spaceVoicePointerId) return handled
                    val isCancel = spaceVoiceGestureCancel || event.actionMasked == MotionEvent.ACTION_CANCEL
                    spaceVoiceGestureActive = false
                    spaceVoiceGestureCancel = false
                    if (isCancel) {
                        if (voiceCancelAction != null) {
                            voiceCancelAction?.invoke()
                        } else {
                            cancelVoiceGesture()
                        }
                    } else {
                        listener.onVoicePressChanged(false)
                    }
                }
            }
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
        var voiceLongPressed = false
        setOnLongClickListener {
            if (voiceLongPressed) return@setOnLongClickListener true
            // Accessibility actions do not deliver a touch DOWN/UP sequence.
            when {
                voiceActive -> stopVoiceFromSpace()
                voicePending -> cancelVoiceForManualInput()
                else -> listener.onVoiceToggle()
            }
            true
        }
        if (white) setTag(MARK_WHITE_KEY, true)
        var voiceCancelPreview = false
        var voiceDownY = 0f
        val voiceTrigger = Runnable {
            if (!voiceLongPressed) {
                voiceLongPressed = true
                spaceVoiceGestureActive = true
                spaceVoiceGestureCancel = false
                spaceVoiceDownY = voiceDownY
                // Tactile confirmation the moment voice actually arms.
                hapticFeedback()
                listener.onVoicePressChanged(true)
            }
        }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    feedback()
                    voiceDownY = event.rawY
                    spaceVoicePointerId = event.getPointerId(event.actionIndex)
                    voiceCancelPreview = false
                    spaceVoiceDownY = event.rawY
                    spaceVoiceGestureActive = false
                    spaceVoiceGestureCancel = false
                    repeatHandler.postDelayed(voiceTrigger, spaceVoiceTriggerMs)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val cancelNow = voiceLongPressed && voiceDownY - event.rawY >= dp(48)
                    if (voiceLongPressed && cancelNow != voiceCancelPreview) {
                        voiceCancelPreview = cancelNow
                        spaceVoiceGestureCancel = cancelNow
                        voiceCancelPreviewAction?.invoke(cancelNow)
                    }
                    voiceLongPressed
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatHandler.removeCallbacks(voiceTrigger)
                    if (voiceLongPressed) {
                        isPressed = false
                        voiceLongPressed = false
                        spaceVoiceGestureActive = false
                        spaceVoiceGestureCancel = false
                        val isCancel = voiceCancelPreview || event.actionMasked == MotionEvent.ACTION_CANCEL
                        if (isCancel) {
                            if (voiceCancelAction != null) {
                                voiceCancelAction?.invoke()
                            } else {
                                cancelVoiceGesture()
                            }
                        } else {
                            listener.onVoicePressChanged(false)
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    /** Compatibility entry for older test hosts; the production gesture is long-press space. */
    fun toggleVoiceFromSpace() {
        startVoiceFromSpace()
    }

    /** Starts recording after the combined space key crosses the long-press threshold. */
    fun startVoiceFromSpace() {
        prepareVoiceController()
        voiceGestureSession = true
        voiceInlineGeneration++
        showInlineVoiceState("正在准备麦克风…")
        startInlineVoicePulse()
        // Let the in-place state row draw before model/session startup begins.
        post {
            if (voiceGestureSession) voiceStartAction?.invoke()
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
        spaceVoiceGestureActive = false
        spaceVoiceGestureCancel = false
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
            Panel.TOOLS -> renderTools()
            Panel.KEYBOARD_SELECT -> renderKeyboardSelect()
            Panel.SYMBOLS -> renderSymbols()
            Panel.EMOJI -> renderEmoji()
            Panel.HANDWRITING -> renderHandwriting()
            Panel.VOICE -> renderVoice()
            Panel.CLIPBOARD -> renderClipboard()
            Panel.TEXT_EDITOR -> renderTextEditor()
            Panel.SETTINGS -> renderSettings()
            Panel.FUZZY_SETTINGS -> renderFuzzySettings()
            Panel.GAMING -> renderGaming()
            else -> closePanelToKeyboard()
        }
        applyTheme()
        onViewHierarchyRebuilt()
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
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            minimumHeight = dp(48)
            tag = "panel-head"
        }
        nav.addView(
            button("‹", 18f, true).apply {
                tag = "key-panel-back"
                minimumHeight = dp(48)
                contentDescription = "返回键盘"
                setOnClickListener { closePanelToKeyboard() }
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

    private fun addPanelHead(name: String) {
        expandedPanel.addView(panelHead(name), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
    }

    private fun renderKeyboardSelect() {
        addPanelHead("切换键盘")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            tag = "keyboard-select-panel"
        }
        body.addView(TextView(context).apply {
            text = "选择输入布局"
            textSize = 13f
            setPadding(dp(4), 0, 0, dp(8))
            tag = "panel-section-title"
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(28),
        ))
        val modes = listOf(
            KeyboardMode.PINYIN_26 to "拼音 26 键",
            KeyboardMode.PINYIN_9 to "拼音 9 键",
            KeyboardMode.ENGLISH_26 to "英文 26 键",
            KeyboardMode.DIGITS to "数字键盘",
        )
        modes.chunked(2).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { (modeValue, label) ->
                row.addView(
                    key(label, true, null, 1f, 13f) {
                        setMode(modeValue)
                    }.apply {
                        val selected = mode == modeValue
                        tag = if (selected) "tab-active" else "keyboard-choice"
                        contentDescription = "$label，${if (selected) "已选中" else "未选中"}"
                        if (Build.VERSION.SDK_INT >= 30) {
                            stateDescription = if (selected) "已选中" else "未选中"
                        }
                    },
                    LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(7) },
                )
            }
            if (chunk.size == 1) {
                row.addView(View(context), LinearLayout.LayoutParams(0, dp(50), 1f))
            }
            body.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50),
            ).apply { bottomMargin = dp(7) })
        }
        expandedPanel.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(imeHeightDp() - 48),
        ))
    }

    private fun filterChip(label: String, active: Boolean, onTap: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(10), 0, dp(10), 0)
            tag = if (active) "tab-active" else "panel-tab"
            contentDescription = "$label，${if (active) "已选中" else "未选中"}"
            if (Build.VERSION.SDK_INT >= 30) {
                stateDescription = if (active) "已选中" else "未选中"
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { feedback(); onTap() }
        }

    private fun panelChipScroll(
        labels: List<String>,
        selected: String,
        onSelected: (String) -> Unit,
    ): HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        val scrollKey = labels.joinToString("\u001f")
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var selectedView: View? = null
        labels.forEach { label ->
            val chip = filterChip(label, label == selected) { onSelected(label) }
            if (label == selected) selectedView = chip
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(48),
                ).apply { marginEnd = dp(6) },
            )
        }
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        setOnScrollChangeListener { _, scrollX, _, _, _ ->
            panelChipScrollPositions[scrollKey] = scrollX
        }
        post {
            val remembered = panelChipScrollPositions[scrollKey]
            if (remembered != null) {
                scrollTo(remembered, 0)
            } else {
                selectedView?.let { active ->
                    val target = (active.left - (width - active.width) / 2).coerceAtLeast(0)
                    scrollTo(target, 0)
                    panelChipScrollPositions[scrollKey] = scrollX
                }
            }
        }
    }

    private fun panelVerticalScroll(content: View, tagValue: String): ScrollView =
        ScrollView(context).apply {
            tag = tagValue
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    private fun rememberPanelVerticalScroll(scroll: ScrollView, scrollKey: String) {
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            panelVerticalScrollPositions[scrollKey] = scrollY
        }
        scroll.post {
            panelVerticalScrollPositions[scrollKey]?.let { remembered ->
                scroll.scrollTo(0, remembered)
            }
        }
    }

    private fun renderTools() {
        addPanelHead("工具")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            tag = "tools-panel"
        }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        data class ToolEntry(
            val label: String,
            val target: Panel,
            val iconRes: Int? = null,
            val glyph: String? = null,
            val enabled: Boolean = true,
        )
        // When no handwriting recognizer is configured, hide the entry entirely
        // instead of showing a dead grey card (V2 used to patch this in a post pass).
        val handwritingAvailable = HandwritingFeaturePolicy.entryEnabled(UnavailableHandwritingProvider)
        val cards = listOf(
            ToolEntry("表情", Panel.EMOJI, R.drawable.ic_emoji),
            ToolEntry("剪贴板", Panel.CLIPBOARD, R.drawable.ic_clipboard),
            ToolEntry("手写输入", Panel.HANDWRITING, R.drawable.ic_handwriting, enabled = handwritingAvailable),
            ToolEntry("符号", Panel.SYMBOLS, R.drawable.ic_symbols),
            ToolEntry("切换键盘", Panel.KEYBOARD_SELECT, R.drawable.ic_grid),
            ToolEntry("文本编辑", Panel.TEXT_EDITOR, R.drawable.ic_keyboard),
            ToolEntry("游戏键盘", Panel.GAMING, R.drawable.ic_game),
            ToolEntry("设置", Panel.SETTINGS, R.drawable.ic_settings),
        ).filter { it.enabled }
        cards.chunked(4).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { entry ->
                val toolEntryView = if (entry.glyph != null) {
                    toolGlyphCard(entry.glyph, entry.label) { showPanel(entry.target) }
                } else {
                    toolCard(entry.iconRes ?: R.drawable.ic_settings, entry.label) {
                        showPanel(entry.target)
                    }
                }
                row.addView(
                    toolEntryView,
                    LinearLayout.LayoutParams(0, dp(66), 1f).apply { marginEnd = dp(8) },
                )
            }
            repeat(4 - chunk.size) {
                row.addView(View(context), LinearLayout.LayoutParams(0, dp(66), 1f).apply { marginEnd = dp(8) })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(66),
            ).apply { bottomMargin = dp(8) })
        }
        body.addView(grid, matchParams())
        expandedPanel.addView(
            panelVerticalScroll(body, "tools-scroll"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
    }

    private fun toolCard(iconRes: Int, label: String, onTap: () -> Unit): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(6))
            contentDescription = label
            isClickable = true
            isFocusable = true
            setOnClickListener { feedback(); onTap() }
        }
        card.addView(ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { bottomMargin = dp(6) })
        card.addView(TextView(context).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, wrapParams())
        return card
    }

    private fun toolGlyphCard(glyph: String, label: String, onTap: () -> Unit): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(6))
            contentDescription = label
            isClickable = true
            isFocusable = true
            setOnClickListener { feedback(); onTap() }
        }
        card.addView(TextView(context).apply {
            text = glyph
            textSize = if (glyph == "Aa") 15f else 16f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { bottomMargin = dp(6) })
        card.addView(TextView(context).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, wrapParams())
        return card
    }

    private fun renderSymbols() {
        addPanelHead("符号")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            tag = "symbols-panel"
        }
        expandedPanel.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(panelBodyHeightDp()),
        ))

        fun renderContent(notifyRebuilt: Boolean) {
            body.removeAllViews()
            val cats = listOf("常用", "中文", "英文", "数学", "序号", "单位", "特殊", "编程", "自定义")
            val tabs = panelChipScroll(cats, symbolCategory) { cat ->
                if (cat != symbolCategory) {
                    symbolCategory = cat
                    renderContent(true)
                }
            }
            body.addView(tabs, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { bottomMargin = dp(8) })
            if (symbolCategory == "自定义") {
                body.addView(button("管理自定义符号", 12f, true).apply {
                    contentDescription = "管理自定义符号"
                    isClickable = true
                    setOnClickListener {
                        feedback()
                        context.startActivity(
                            Intent(context, SymbolManagerActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48),
                ).apply { bottomMargin = dp(8) })
            }
            val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            symbolItems(symbolCategory).chunked(6).forEach { chunk ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { s ->
                    row.addView(
                        key(s, false, null, 1f, if (s.length > 2) 12f else 17f) {
                            listener.onSymbolSelected(s)
                        },
                        gridCellParams(48, 6, 6),
                    )
                }
                repeat(6 - chunk.size) {
                    row.addView(View(context), gridCellParams(48, 6, 6))
                }
                grid.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48),
                ).apply { bottomMargin = dp(6) })
            }
            val symbolsScroll = panelVerticalScroll(grid, "symbols-scroll")
            rememberPanelVerticalScroll(symbolsScroll, "symbols:$symbolCategory")
            body.addView(
                symbolsScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            applyTheme()
            if (notifyRebuilt) onViewHierarchyRebuilt()
        }

        renderContent(false)
    }

    private fun renderEmoji() {
        addPanelHead("表情")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            tag = "emoji-panel"
        }
        expandedPanel.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(panelBodyHeightDp()),
        ))

        fun renderContent(notifyRebuilt: Boolean) {
            body.removeAllViews()
            val cats = listOf("最近") + ImeData.fluentSmileysByCategory.keys.toList()
            val tabs = panelChipScroll(cats, emojiCategory) { cat ->
                if (cat != emojiCategory) {
                    emojiCategory = cat
                    renderContent(true)
                }
            }
            body.addView(tabs, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { bottomMargin = dp(10) })
            val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val emojiItems = if (emojiCategory == "最近") {
                EmojiRecentRepository.load(context)
            } else {
                ImeData.fluentSmileysByCategory[emojiCategory].orEmpty()
            }
            if (emojiItems.isEmpty() && emojiCategory == "最近") {
                grid.addView(TextView(context).apply {
                    text = "最近使用的表情会显示在这里"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    tag = "panel-note"
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
            }
            emojiItems.chunked(8).forEach { chunk ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { e ->
                    row.addView(
                        emojiCell(e),
                        gridCellParams(48, 8, 4),
                    )
                }
                grid.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48),
                ).apply { bottomMargin = dp(4) })
            }
            val emojiScroll = panelVerticalScroll(grid, "emoji-scroll")
            rememberPanelVerticalScroll(emojiScroll, "emoji:$emojiCategory")
            body.addView(
                emojiScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            applyTheme()
            if (notifyRebuilt) onViewHierarchyRebuilt()
        }

        renderContent(false)
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
        val pad = HandwritingPadView(context) { strokes ->
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
        actions.addView(key("撤销", true, null, 1f, 13f) { pad.undo() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(key("清空", true, null, 1f, 13f) { pad.clear() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(key("空格", true, null, 1f, 13f) { listener.onSpace() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        body.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
        expandedPanel.addView(
            panelVerticalScroll(body, "handwriting-scroll"),
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
                voiceLanguageIndex = (voiceLanguageIndex + 1) % languages.size
                val selectedLanguage = languages[voiceLanguageIndex].first
                text = selectedLanguage
                contentDescription = "语音语言：$selectedLanguage，点击切换"
                if (Build.VERSION.SDK_INT >= 30) stateDescription = selectedLanguage
            }
        }
        controls.addView(langButton, LinearLayout.LayoutParams(0, dp(58), 1f))
        val micButton = button("🎤", 18f, false).apply {
            tag = "voice-mic"
            isEnabled = false
            contentDescription = "语音状态，当前未开始，仅支持长按空格启动"
        }
        controls.addView(micButton, LinearLayout.LayoutParams(dp(58), dp(58)))
        val gestureHint = button("长按空格开始", 13f, true).apply {
            tag = "voice-gesture-hint"
            isEnabled = false
            contentDescription = "长按空格开始语音，松开自动上屏，上滑取消"
        }
        controls.addView(gestureHint, LinearLayout.LayoutParams(0, dp(58), 1f))
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
            dp(58),
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
                        modelStatus.text = "正在聆听 · 松开空格结束"
                        setMicState("⏹", "语音输入进行中，松开空格结束，上滑取消")
                        setGestureHint("松开空格上屏 · 上滑取消", "松开空格结束语音并自动上屏，上滑取消")
                        showInlineVoiceState(text.ifBlank { "正在聆听…" })
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
                        if (!voiceActive || voiceCancelled || cancelPreview) return@postDelayed
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
                        modelStatus.text = "语音未完成 · 请检查本地模型和麦克风权限"
                        listener.onVoiceError(message)
                        showInlineVoiceState(message.ifBlank { "语音输入失败" })
                        hideInlineVoiceStateLater(1_500L)
                    }
                }
                override fun onReady() {
                    post {
                        if (eventGeneration != voiceEventGeneration) return@post
                        if (voiceCancelled) return@post
                        if (voiceActive) {
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
                        if (!voiceActive || voiceCancelled || cancelPreview) return@post
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
            if (!voiceActive) return
            listener.stopVoiceRecognition()
            setMicState("🎤", "正在整理语音识别结果，请稍候")
            setGestureHint("整理识别结果…", "正在整理语音识别结果，请稍候")
            voiceActive = false
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
        voiceEventGeneration++
        listener.cancelVoiceRecognition()
        voiceActive = false
        voiceGestureSession = false
        spaceVoiceGestureActive = false
        spaceVoiceGestureCancel = false
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
            setOnClickListener {
                ClipboardHistoryRepository.togglePin(context, entry.text)
                renderClipboard(reusePanel = true)
            }
        }, wrapParams())
        meta.addView(button("使用", 10f, true).apply {
            setOnClickListener { listener.onCharacter(entry.text) }
        }, wrapParams())
        card.addView(meta, wrapParams())
        return card
    }

    private fun renderClipboard(reusePanel: Boolean = false) {
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
        val tabs = panelChipScroll(listOf("剪贴板", "常用语"), if (clipboardTab == 0) "剪贴板" else "常用语") { label ->
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
            Thread {
                if (!passwordField) ClipboardHistoryRepository.capturePrimary(context)
                val history = ClipboardHistoryRepository.load(context)
                post {
                    if (gen != clipboardLoadGen || clipboardTab != 0 || col.parent == null) return@post
                    (loadingHint.parent as? ViewGroup)?.removeView(loadingHint)
                    if (history.isEmpty()) {
                        col.addView(TextView(context).apply {
                            text = "暂无剪贴历史；复制文本后重新打开这里即可看到。"
                            textSize = 13f
                            setPadding(dp(4), dp(6), dp(4), 0)
                            tag = "panel-note"
                        }, wrapParams())
                    } else {
                        history.forEach { entry -> col.addView(clipboardHistoryCard(entry), LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = dp(7) }) }
                    }
                }
            }.apply { isDaemon = true }.start()
        } else {
            col.addView(button("新增常用语", 13f, true).apply {
                tag = "quick-phrase-add"
                setOnClickListener { openQuickPhraseEditor(null) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { bottomMargin = dp(8) })

            QuickPhraseRepository.load(context)
                .groupBy { it.category }
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
                            setOnClickListener { openQuickPhraseEditor(phrase) }
                        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(5) })
                        row.addView(button("删除", 11f, true).apply {
                            tag = "phrase-delete:${phrase.id}"
                            setOnClickListener {
                                feedback()
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("删除常用语？")
                                    .setMessage(phrase.text)
                                    .setNegativeButton("取消", null)
                                    .setPositiveButton("删除") { _, _ ->
                                        QuickPhraseRepository.remove(context, phrase.id)
                                        renderClipboard(reusePanel = true)
                                    }
                                    .show()
                            }
                        }, LinearLayout.LayoutParams(dp(48), dp(48)))
                        col.addView(row, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(48),
                        ).apply { bottomMargin = dp(7) })
                    }
                }
        }
        body.addView(
            panelVerticalScroll(col, "clipboard-scroll"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        expandedPanel.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(panelBodyHeightDp()),
        ))
        applyTheme()
        onViewHierarchyRebuilt()
    }

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

    private fun symbolItems(category: String): List<String> = when (category) {
        "中文" -> ImeData.symbols["中文标点"].orEmpty()
        "英文" -> ImeData.symbols["英文标点"].orEmpty()
        "数学" -> listOf(
            ImeData.symbols["数学运算"].orEmpty(),
            ImeData.symbols["更多数学"].orEmpty(),
            ImeData.symbols["希腊字母"].orEmpty(),
            ImeData.symbols["上下标"].orEmpty(),
        ).flatten()
        "序号" -> listOf(
            ImeData.symbols["数字序号"].orEmpty(),
            ImeData.symbols["数字扩展"].orEmpty(),
        ).flatten()
        "单位" -> listOf(
            ImeData.symbols["货币单位"].orEmpty(),
            ImeData.symbols["单位符号"].orEmpty(),
        ).flatten()
        "编程" -> ImeData.symbols["技术编程"].orEmpty()
        "特殊" -> listOf(
            ImeData.symbols["数字序号"].orEmpty(),
            ImeData.symbols["特殊图形"].orEmpty(),
            ImeData.symbols["几何图形"].orEmpty(),
            ImeData.symbols["箭头线条"].orEmpty(),
            ImeData.symbols["括号边框"].orEmpty(),
            ImeData.symbols["网络颜文字"].orEmpty(),
        ).flatten()
        "自定义" -> CustomSymbolRepository.load(context).map { it.symbol }
        else -> listOf(
            ImeData.symbols["常用"].orEmpty(),
            ImeData.symbols["中文标点"].orEmpty().take(12),
            ImeData.symbols["数学运算"].orEmpty().take(12),
        ).flatten().distinct()
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
                    key(label, true, null, 1f, 10f) { listener.onTextEdit(action) },
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
                    setOnClickListener { listener.onTextEdit(action) }
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
    }

    private fun renderSettings(reusePanel: Boolean = false) {
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
        content.addView(sectionTitle("外观"), wrapParams())
        content.addView(panelChipScroll(ImeAppearance.entries.map { it.label }, appearance.label) { label ->
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
                    renderSettings(reusePanel = true)
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
        scroll.post { scroll.scrollTo(0, previousScrollY) }
        if (reusePanel) {
            applyTheme()
            onViewHierarchyRebuilt()
        }
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

    private fun settingIcon(label: String): TextView = TextView(context).apply {
        text = when (label) {
            "按键音效" -> "◖"
            "触感震动" -> "✦"
            "按键气泡" -> "A"
            "模糊音与智能纠错", "启用模糊音" -> "✧"
            "外观与键盘高度" -> "◐"
            else -> "•"
        }
        textSize = 15f
        gravity = Gravity.CENTER
        includeFontPadding = false
        contentDescription = label
        tag = "setting-icon"
    }

    private fun settingToggleRow(label: String, sub: String): LinearLayout {
        val toggleView = toggle(label)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            tag = "setting-row"
            contentDescription = label
            minimumHeight = dp(56)
            isClickable = true
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
            }, weightParams(1f))
            addView(toggleView, wrapParams())
        }
    }

    private fun settingNavigationRow(label: String, sub: String, onTap: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            tag = "setting-row"
            contentDescription = label
            minimumHeight = dp(60)
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
            }, weightParams(1f))
            addView(TextView(context).apply {
                text = "›"
                textSize = 18f
                gravity = Gravity.CENTER
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

    private fun toggle(seed: String): View {
        val on = when (seed) {
            "按键音效" -> soundEnabled
            "触感震动" -> hapticEnabled
            "模糊音纠错" -> fuzzyEnabled
            "按键气泡" -> popupEnabled
            else -> true
        }
        val isOn = onState(seed)
        val knob = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                gravity = if (isOn) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
            }
            background = rounded(Color.WHITE, dp(99))
        }
        return FrameLayout(context).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            minimumWidth = dp(48)
            minimumHeight = dp(48)
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
                (getChildAt(0)).layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                    gravity = if (next) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
                }
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
        val swatches = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        AccentPalette.presets.forEach { (hex, label) ->
            val selected = AccentPalette.normalize(hex) == current
            swatches.addView(
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
                            if (selected) setStroke(dp(2), contrastText(AccentPalette.parse(hex)))
                        }
                    }, FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                        gravity = Gravity.CENTER
                    })
                    setOnClickListener { feedback(); applyAccentColor(hex) }
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
        }
        val swatchScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(swatches, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(48),
            ))
        }
        row.addView(swatchScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
        row.addView(TextView(context).apply {
            text = "自定义"
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(10), 0, dp(10), 0)
            tag = "panel-button"
            minHeight = dp(48)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = "自定义强调色"
            setOnClickListener { feedback(); showCustomAccentDialog() }
        }, LinearLayout.LayoutParams(dp(96), dp(48)).apply {
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
            minimumHeight = dp(64)
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

    private fun renderGaming() {
        addPanelHead("游戏键盘")
        listener.onFloatingKeyboardChanged(floatingKeyboard)
        val macros = listOf("收到！", "集合进攻！", "稳住能赢！", "请求集合！", "保护输出！")
        val hud = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "gaming-panel"
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }
        var lastTouchX = 0f
        var lastTouchY = 0f
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "floating-header"
        }
        val dragHandle = TextView(context).apply {
            text = "⠿  拖动键盘"
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            tag = "floating-drag-handle"
            contentDescription = "拖动键盘"
            setPadding(dp(4), 0, dp(8), 0)
            isClickable = true
            setOnTouchListener { _, event ->
                if (!floatingKeyboard) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        if (debugLogging) Log.d("OpenIme", "floating-drag-down x=${event.rawX} y=${event.rawY}")
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - lastTouchX
                        val deltaY = event.rawY - lastTouchY
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        listener.onFloatingKeyboardDragged(deltaX, deltaY)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }
        header.addView(dragHandle, weightParams(1f))
        val floatingToggle = button(if (floatingKeyboard) "贴底固定" else "恢复浮动", 11f, true).apply {
            contentDescription = if (floatingKeyboard) "贴底固定" else "恢复浮动"
            setOnClickListener { view ->
                floatingKeyboard = !floatingKeyboard
                listener.onFloatingKeyboardChanged(floatingKeyboard)
                val label = if (floatingKeyboard) "贴底固定" else "恢复浮动"
                (view as TextView).text = label
                view.contentDescription = label
            }
        }
        header.addView(floatingToggle, LinearLayout.LayoutParams(dp(88), dp(48)))
        hud.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { bottomMargin = dp(2) })
        val macroRow = HorizontalScrollView(context)
        val macroContent = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        macros.forEach { m ->
            macroContent.addView(
                key(m, true, null, 1f, 11f) { listener.onCharacter(m) }.apply {
                    tag = "game-mini"
                },
                wrapParams(),
            )
        }
        macroRow.addView(macroContent, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        hud.addView(macroRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { rowIndex, rowText ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowText.forEach { ch ->
                row.addView(
                    key(ch.toString(), false, null, 1f, 13f) { listener.onCharacter(ch.toString()) }.apply {
                        tag = "game-mini"
                    },
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) },
                )
            }
            if (rowText.startsWith("z")) {
                row.addView(
                    key("空格", true, null, 1.2f, 11f) { listener.onSpace() }.apply {
                        tag = "game-mini"
                    },
                    LinearLayout.LayoutParams(0, dp(48), 1.2f).apply { marginEnd = dp(4) },
                )
                val gameBackspace = backspaceKey().apply { tag = "game-mini" }
                row.addView(gameBackspace, LinearLayout.LayoutParams(0, dp(48), 1.2f).apply { marginEnd = dp(4) })
                row.addView(
                    key("发送", true, null, 1.6f, 12f) { listener.onEnter() }.apply {
                        tag = "game-mini"
                    },
                    LinearLayout.LayoutParams(0, dp(48), 1.6f),
                )
            }
            hud.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { bottomMargin = if (rowIndex < 2) dp(1) else 0 })
        }
        val stage = FrameLayout(context).apply {
            tag = "floating-stage"
            clipChildren = false
        }
        val cardWidth = minOf(
            dp(360),
            (width - contentInsetPx * 2 - dp(8)).coerceAtLeast(dp(1)),
        )
        stage.addView(hud, FrameLayout.LayoutParams(
            cardWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })
        expandedPanel.addView(
            stage,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
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
        if (mode == KeyboardMode.ENGLISH_T9) {
            val (digits, selection) = replaceCompositionSelection(num)
            lastT9Digits = digits
            publishComposition(digits, candidatesForComposition(digits), selection)
        } else if (mode == KeyboardMode.PINYIN_9) {
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
        renderCandidateRow()
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
        renderCandidateRow()
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
            KeyboardMode.ENGLISH_T9 -> lastT9Digits = text
            else -> Unit
        }
        val candidates = candidatesForComposition(text)
        currentCandidates = candidates
        updateTopZone(text.isNotEmpty())
        renderCandidateRow()
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
        if (!::associationRow.isInitialized) return
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        applyThemeRecursive(associationRow, theme.tokens(appearance, night, AccentPalette.parse(skinPrimaryColor)))
    }

    private fun firstCandidateOrComposition(): String =
        currentCandidates.firstOrNull() ?: composition.text.toString()

    protected fun feedback() {
        if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (soundEnabled) playSoundEffect(SoundEffectConstants.CLICK)
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
            candidateOverlay.visibility = View.GONE
            keyboardBody.visibility = View.VISIBLE
            candidateExpandedOpen = false
            renderedExpandedCandidates = null
            renderedExpandedComposition = null
            syncCandidateExpandControl()
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
        candidateOverlay.visibility = View.VISIBLE
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
                        LinearLayout.LayoutParams(0, dp(48), candidateColumnSpan(cand).toFloat()).apply { marginEnd = dp(5) },
                    )
                }
                val remaining = 4 - chunk.sumOf(::candidateColumnSpan)
                if (remaining > 0) row.addView(View(context), LinearLayout.LayoutParams(0, 1, remaining.toFloat()))
                col.addView(row, matchParams())
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
            mainTextSize = (mainTextSizeOverride ?: (if (func) 15f else 20f)) * skinFontScale(),
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
                        -> if (keepPopupAfterKeyUp) {
                            keepPopupAfterKeyUp = false
                        } else {
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

    private fun beginBackspaceGesture(
        anchor: View,
        rawX: Float,
        rawY: Float,
        clearUiAction: (Boolean) -> Unit,
    ) {
        if (backspaceGestureActive) finishBackspaceGesture(commit = false)
        repeatHandler.removeCallbacks(repeatAction)
        backspaceRepeatStartAction?.let(repeatHandler::removeCallbacks)
        backspaceGestureActive = true
        backspaceClearArmed = false
        backspaceRepeatStarted = false
        backspaceRepeatSuspended = false
        backspaceStartX = rawX
        backspaceStartY = rawY
        backspaceAnchor = anchor
        backspaceClearUiAction = clearUiAction
        anchor.isPressed = true
        anchor.parent?.requestDisallowInterceptTouchEvent(true)
        clearUiAction(false)
        hidePopup()
        feedback()
        val startRepeat = Runnable {
            if (backspaceGestureActive && !backspaceClearArmed) repeatAction.run()
        }
        backspaceRepeatStartAction = startRepeat
        repeatHandler.postDelayed(startRepeat, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun updateBackspaceGesture(rawX: Float, rawY: Float) {
        if (!backspaceGestureActive) return
        val upward = backspaceStartY - rawY
        val horizontal = kotlin.math.abs(rawX - backspaceStartX)
        // As soon as the motion clearly points upward, suspend repeat-delete
        // while waiting for the clear threshold. A slow swipe must not erase
        // characters one by one before it becomes an atomic clear.
        if (upward >= dp(8) && horizontal <= dp(96)) {
            backspaceRepeatSuspended = true
            backspaceRepeatStartAction?.let(repeatHandler::removeCallbacks)
            repeatHandler.removeCallbacks(repeatAction)
        } else if (backspaceRepeatSuspended) {
            backspaceRepeatSuspended = false
            backspaceRepeatStartAction?.let {
                repeatHandler.postDelayed(it, if (backspaceRepeatStarted) 60L else ViewConfiguration.getLongPressTimeout().toLong())
            }
        }
        val shouldArm = if (backspaceClearArmed) {
            upward > dp(16) && horizontal <= dp(120)
        } else {
            upward >= dp(36) && horizontal <= dp(96)
        }
        if (shouldArm == backspaceClearArmed) return
        backspaceClearArmed = shouldArm
        backspaceClearUiAction?.invoke(shouldArm)
        if (shouldArm) {
            backspaceRepeatStartAction?.let(repeatHandler::removeCallbacks)
            repeatHandler.removeCallbacks(repeatAction)
            backspaceAnchor?.let { showPopup(it, "清空") }
            repeatHandler.removeCallbacks(popupHideRunnable)
            // Tactile confirmation that the gesture crossed into "clear all".
            hapticFeedback()
        } else {
            hidePopup()
            // Matching light tick when sliding back out of the armed clear tier.
            hapticFeedback()
        }
    }

    private fun finishBackspaceGesture(commit: Boolean) {
        if (!backspaceGestureActive) return
        val clearAll = commit && backspaceClearArmed
        val deleteOnce = commit && !backspaceClearArmed && !backspaceRepeatStarted
        repeatHandler.removeCallbacks(repeatAction)
        backspaceRepeatStartAction?.let(repeatHandler::removeCallbacks)
        backspaceRepeatStartAction = null
        backspaceAnchor?.apply {
            isPressed = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        backspaceGestureActive = false
        backspaceClearUiAction?.invoke(false)
        backspaceClearArmed = false
        backspaceRepeatStarted = false
        backspaceAnchor = null
        backspaceClearUiAction = null
        hidePopup()
        when {
            clearAll -> {
                // One callback performs one batch clear. Never emulate this by
                // dispatching hundreds of backspace events.
                hapticFeedback()
                listener.onClearAll()
            }
            deleteOnce -> {
                hidePopup()
                performBackspaceOnce()
            }
            else -> hidePopup()
        }
    }

    private fun backspaceKey(): ImeKeyView = key("", true, null, 1f, 15f, iconRes = R.drawable.ic_backspace) {
        performBackspaceOnce()
    }.apply {
        tag = "key-backspace"
        contentDescription = "删除，向上滑清空"
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
            clearHint.visibility = if (backspaceGestureActive) View.VISIBLE else View.INVISIBLE
            if (active) {
                clearHint.text = "清空"
                clearHint.setTextColor(Color.WHITE)
                clearHint.background = rounded(Color.rgb(211, 47, 47), dp(5))
                clearHint.alpha = 1f
            } else {
                clearHint.text = "↑ 清空"
                clearHint.setTextColor(Color.GRAY)
                clearHint.background = null
                clearHint.alpha = 0.72f
            }
        }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    clearHint.alpha = 1f
                    beginBackspaceGesture(view, event.rawX, event.rawY, ::setClearHintActive)
                    backspacePointerId = event.getPointerId(event.actionIndex)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun showPopup(anchor: View, char: String) {
        hidePopup()
        keepPopupAfterKeyUp = false
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val t = theme.tokens(appearance, night, AccentPalette.parse(skinPrimaryColor))
        val popupWidth = (anchor.width * 1.08f).toInt().coerceIn(dp(40), dp(64))
        val popupHeight = dp(if (char == "清空") 36 else 48)
        val p = TextView(context).apply {
            text = char
            textSize = if (char.length > 1) 13f else 16f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setTextColor(if (char == "清空") Color.WHITE else t.keyText)
            background = rounded(
                if (char == "清空") Color.rgb(185, 40, 40) else t.keyBackground,
                dp(10),
            )
            elevation = dp(2).toFloat()
        }
        val anchorLocation = IntArray(2)
        val rootLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        getLocationOnScreen(rootLocation)
        val anchorLeft = anchorLocation[0] - rootLocation[0]
        val anchorTop = anchorLocation[1] - rootLocation[1]
        val centeredLeft = anchorLeft + (anchor.width - popupWidth) / 2
        val maxLeft = (width - popupWidth - contentInsetPx).coerceAtLeast(contentInsetPx)
        val left = centeredLeft.coerceIn(contentInsetPx, maxLeft)
        val top = (anchorTop - popupHeight - dp(8)).coerceAtLeast(dp(4))
        addView(p, LayoutParams(popupWidth, popupHeight).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left
            topMargin = top
        })
        popupView = p
    }

    /** Horizontal long-press selector for symbols that share one key. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showChoicePopup(anchor: View, choices: List<String>) {
        hidePopup()
        keepPopupAfterKeyUp = true
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val t = theme.tokens(appearance, night, AccentPalette.parse(skinPrimaryColor))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = rounded(t.keyBackground, dp(10))
            elevation = dp(2).toFloat()
            contentDescription = "长按符号选择"
        }
        choices.forEach { symbol ->
            row.addView(TextView(context).apply {
                text = symbol
                textSize = 16f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(t.keyText)
                background = statefulRounded(Color.TRANSPARENT, t.keyPressedBackground, dp(8))
                isClickable = true
                isFocusable = true
                contentDescription = "输入$symbol"
                setPadding(dp(11), 0, dp(11), 0)
                setOnClickListener {
                    hidePopup()
                    feedback()
                    listener.onCharacter(symbol)
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val anchorLocation = IntArray(2)
        val rootLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        getLocationOnScreen(rootLocation)
        val popupWidth = dp(48 * choices.size + 10)
        val popupHeight = dp(58)
        val anchorLeft = anchorLocation[0] - rootLocation[0]
        val anchorTop = anchorLocation[1] - rootLocation[1]
        val centeredLeft = anchorLeft + (anchor.width - popupWidth) / 2
        val maxLeft = (width - popupWidth - contentInsetPx).coerceAtLeast(contentInsetPx)
        val left = centeredLeft.coerceIn(contentInsetPx, maxLeft)
        val top = (anchorTop - popupHeight - dp(8)).coerceAtLeast(dp(4))
        addView(row, LayoutParams(popupWidth, popupHeight).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left
            topMargin = top
        })
        popupView = row
    }

    private fun hidePopup() {
        repeatHandler.removeCallbacks(popupHideRunnable)
        popupView?.let { removeView(it) }
        popupView = null
        keepPopupAfterKeyUp = false
    }

    private fun applyTheme() {
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
        if (voiceInlineActive) applyInlineVoicePalette()
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
                    primary -> dim(color, 0.88f)
                    side -> dim(t.sideKeyBackground, 0.88f)
                    function -> dim(t.functionKeyBackground, 0.88f)
                    else -> t.keyPressedBackground
                }
                view.background = statefulRounded(color, pressedColor, dp(skinRadius))
                // Skin opacity slider fades key backgrounds toward transparency.
                view.background?.alpha = (skinOpacity.coerceIn(70, 100) * 255 / 100)
                view.elevation = 0f
                when {
                    primary -> view.setColors(contrastText(t.primary), t.keySecondaryText, contrastText(t.primary))
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
                        dp(8),
                    )
                    "candidate-row" -> view.background = statefulRounded(
                        Color.TRANSPARENT, t.keyPressedBackground, dp(8),
                    )
                    "setting-row" -> if (view.isClickable) {
                        view.background = statefulRounded(Color.TRANSPARENT, t.keyPressedBackground, dp(10))
                    }
                    "nine-punct-stack", "digits-symbol-stack" -> view.background = rounded(t.sideKeyBackground, dp(9))
                    "setting-group" -> view.background = rounded(t.toolCardBackground, dp(12))
                    "clip-card" -> view.background = rounded(t.toolCardBackground, dp(10))
                    "gaming-panel" -> view.background = rounded(t.toolCardBackground, dp(12))
                }
                if (view.contentDescription != null && view.isClickable && view.tag == null) {
                    view.background = statefulRounded(t.toolCardBackground, t.keyPressedBackground, dp(10))
                }
            }
            is ImageView -> {
                if ((view.parent is LinearLayout && (view.parent as LinearLayout).tag == "toolbar-row") ||
                    hasAncestorTag(view, "tools-panel")) {
                    view.imageTintList = ColorStateList.valueOf(t.keyText)
                    if (view.isClickable) {
                        view.background = statefulRounded(Color.TRANSPARENT, t.keyPressedBackground, dp(10))
                    }
                }
            }
            is TextView -> {
                val tag = view.tag as? String
                if (view.parent !is ImeKeyView) view.setTextColor(t.keyText)
                when {
                    tag == "setting-icon" -> {
                        val icon = t.primary
                        view.setTextColor(icon)
                        val dark = contrastText(t.keyboardBackground) == Color.WHITE
                        view.background = rounded(
                            if (dark) Color.argb(42, Color.red(icon), Color.green(icon), Color.blue(icon))
                            else Color.argb(24, Color.red(icon), Color.green(icon), Color.blue(icon)),
                            dp(8),
                        )
                    }
                    tag == "backspace-clear-hint" -> {
                        view.setTextColor(t.keySecondaryText)
                    }
                    tag == "candidate-first" -> {
                        view.setTextColor(t.keyText)
                    }
                    tag == "candidate-word" -> {
                        view.setTextColor(t.candidateText)
                    }
                    tag == "tab-active" -> {
                        view.setTextColor(contrastText(t.primary))
                        view.background = statefulRounded(t.primary, dim(t.primary), dp(99))
                    }
                    tag == "panel-tab" -> {
                        view.setTextColor(t.keySecondaryText)
                        view.background = statefulRounded(t.panelHeadBackground, dim(t.panelHeadBackground), dp(99))
                    }
                    tag == "panel-button" -> {
                        view.setTextColor(t.keyText)
                        view.background = statefulRounded(t.panelHeadBackground, dim(t.panelHeadBackground), dp(10))
                    }
                    tag == "key-panel-back" -> {
                        view.setTextColor(t.keyText)
                        view.background = statefulRounded(t.panelHeadBackground, dim(t.panelHeadBackground), dp(12))
                    }
                    tag == "panel-title" -> {
                        view.setTextColor(t.keyText)
                    }
                    tag == "candidate-expand" -> {
                        view.setTextColor(t.keySecondaryText)
                    }
                    tag == "voice-transcript" -> {
                        view.setTextColor(t.keyText)
                        view.background = rounded(t.toolCardBackground, dp(12))
                    }
                    tag == "voice-model-status" -> {
                        view.setTextColor(t.keySecondaryText)
                    }
                    tag == "association-candidate" -> {
                        view.setTextColor(t.candidateText)
                        view.background = statefulRounded(
                            t.toolCardBackground,
                            dim(t.toolCardBackground),
                            dp(10),
                        )
                    }
                    tag == "tools-page-dots" -> {
                        view.setTextColor(t.keySecondaryText)
                    }
                    tag == "voice-mic" -> {
                        view.setTextColor(contrastText(t.primary))
                        view.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(t.primary)
                        }
                    }
                    view.parent is LinearLayout &&
                        ((view.parent as LinearLayout).tag == "nine-punct-stack" ||
                            (view.parent as LinearLayout).tag == "digits-symbol-stack") -> {
                        view.setTextColor(t.sideKeyText)
                    }
                }
            }
            is FrameLayout -> when (view.tag) {
                "emoji-cell" -> view.background = statefulRounded(Color.TRANSPARENT, t.keyPressedBackground, dp(8))
                "toggle" -> {
                    val enabled = onState(view.contentDescription?.toString().orEmpty())
                    view.background = rounded(
                        if (enabled) t.primary else t.panelHeadBackground,
                        dp(99),
                    )
                }
            }
            else -> when (view.tag) {
                "handwriting-canvas" -> view.background = rounded(t.canvasBackground, dp(14))
                "voice-wave-bar" -> view.background = rounded(t.primary, dp(99))
                "setting-divider" -> view.setBackgroundColor(t.border)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyThemeRecursive(view.getChildAt(i), t)
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun statefulRounded(normal: Int, pressed: Int, radius: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, radius))
        addState(intArrayOf(), rounded(normal, radius))
    }

    private fun dim(color: Int, factor: Float = 0.82f): Int = Color.argb(
        Color.alpha(color),
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )

    private fun contrastText(background: Int): Int {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        val luminance =
            0.2126 * channel(Color.red(background)) +
                0.7152 * channel(Color.green(background)) +
                0.0722 * channel(Color.blue(background))
        val whiteContrast = 1.05 / (luminance + 0.05)
        val dark = Color.rgb(15, 23, 42)
        val darkContrast = (luminance + 0.05) / 0.0572
        return if (whiteContrast >= darkContrast) Color.WHITE else dark
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
    private fun sideKeyParams(heightDp: Int, includeBottomGap: Boolean = false) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(heightDp),
        ).apply {
            if (includeBottomGap) bottomMargin = dp(6)
        }
    private fun adaptiveColumnParams(weight: Float) = LinearLayout.LayoutParams(
        0,
        dp(210),
        weight,
    ).apply {
        marginStart = dp(2)
        marginEnd = dp(2)
    }
    private fun matchParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
    private fun wrapParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
    private fun rowParams(includeBottomGap: Boolean = true) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(keyRowHeightDp()),
    ).apply {
        if (includeBottomGap) bottomMargin = dp(6)
    }
    private fun flexKeyParams(
        weight: Float = 1f,
        heightDp: Int = keyRowHeightDp(),
        gapDp: Int = 2,
    ) = LinearLayout.LayoutParams(
        0,
        dp(heightDp),
        weight,
    ).apply {
        marginStart = dp(gapDp)
        marginEnd = dp(gapDp)
    }
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
