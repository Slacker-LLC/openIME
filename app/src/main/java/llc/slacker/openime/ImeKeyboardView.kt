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

    private val MARK_WHITE_KEY = 0x1F000001
    private val MARK_SIDE_KEY = 0x1F000002
    private val MARK_FUNCTION_KEY = 0x1F000003

    companion object {
        private val DIGITS_ONLY = Regex("[0-9]+")
        private const val EMOJI_CACHE_BYTES = 4 * 1024 * 1024
        private const val CANDIDATE_STRIP_LIMIT = 24
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
    private val spaceVoiceTriggerMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var showSecondaryHints = true
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
    private var renderedMode: KeyboardMode? = null
    private var lastTextMode = KeyboardMode.PINYIN_26
    private var preferredChineseMode = ImeSettingsRepository.loadPreferredChineseMode(context)
    protected var panel = Panel.NONE
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
    private var floatingKeyboard = false
    private var popupView: View? = null
    private var keepPopupAfterKeyUp = false
    private val popupHideRunnable = Runnable { hidePopup() }
    private var contentInsetPx = dp(5)
    private var systemBottomInsetPx = 0
    private val maxContentWidthDp = 600
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun keyRowHeightDp(): Int {
        val base = if (isLandscape()) 40 else 48
        val fontGrow = ((resources.configuration.fontScale - 1f).coerceAtLeast(0f) * 12f)
            .toInt().coerceAtMost(12)
        return base + fontGrow
    }

    private fun imeHeightDp(): Int {
        val derived = 64 + keyRowHeightDp() * 4 + 6 * 3 + 22
        return maxOf(if (isLandscape()) 258 else 296, derived)
    }

    private fun keyboardBodyHeightDp(): Int = imeHeightDp() - 64
    private var syncingComposition = false
    private var t9Filter = "T9"
    private var passwordField = false
    private var inlineEditTarget: EditText? = null
    private val panelChipScrollPositions = mutableMapOf<String, Int>()

    private lateinit var mainDock: LinearLayout
    private lateinit var keyboardHost: FrameLayout
    private lateinit var topZone: LinearLayout
    private lateinit var toolbarRow: LinearLayout
    private lateinit var composeZone: LinearLayout
    private lateinit var composition: EditText
    private lateinit var candidateRow: LinearLayout
    private lateinit var associationRow: LinearLayout
    private lateinit var candidateExpandBtn: TextView
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
        applyDynamicHeights()
        if (!standalonePanel && currentPanel() == Panel.NONE) renderModeBody()
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

    private fun updateResponsiveGeometry(measuredWidthPx: Int) {
        if (measuredWidthPx <= 0) return
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
        toolbarRow.setPadding(contentInsetPx + dp(10), 0, contentInsetPx + dp(10), 0)
        composition.setPadding(contentInsetPx + dp(14), dp(3), contentInsetPx + dp(14), 0)
        requestLayout()
    }

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
            LinearLayout.LayoutParams(dp(42), dp(44)),
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_clipboard, "剪贴板", "toolbar") { showPanel(Panel.CLIPBOARD) },
            LinearLayout.LayoutParams(dp(42), dp(44)),
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_emoji, "Emoji", "toolbar") { showPanel(Panel.EMOJI) },
            LinearLayout.LayoutParams(dp(42), dp(44)),
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_symbols, "符号", "toolbar") { showPanel(Panel.SYMBOLS) },
            LinearLayout.LayoutParams(dp(42), dp(44)),
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
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)),
            )
        }
        toolbarRow.addView(
            associationScroll,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) },
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_more, "更多", "toolbar") { showPanel(Panel.TOOLS) },
            LinearLayout.LayoutParams(dp(42), dp(44)),
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
                    dp(42),
                ),
            )
        }
        candField.setPadding(dp(8), 0, dp(8), 0)
        candField.addView(candScroll, LinearLayout.LayoutParams(0, dp(42), 1f))
        candidateExpandBtn = TextView(context).apply {
            tag = "candidate-expand"
            text = "⌄"
            textSize = 15f
            gravity = Gravity.CENTER
            contentDescription = "展开更多候选"
            setPadding(dp(7), 0, dp(7), 0)
            setOnClickListener {
                val open = candidateOverlay.visibility == View.GONE
                renderExpanded(open)
                listener.onCandidateExpanded(open)
            }
        }
        candField.addView(
            candidateExpandBtn,
            LinearLayout.LayoutParams(dp(42), dp(42)),
        )
        composeZone.addView(candField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(42),
        ))
        topZone.addView(composeZone, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(64),
        ))

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
        panel = newPanel
        mainDock.visibility = View.GONE
        listener.onPanelChanged(newPanel)
        renderPanel(newPanel)
        expandedPanel.alpha = 0.96f
        expandedPanel.animate().alpha(1f).setDuration(120L).start()
    }

    private fun dismissPanelForModeSwitch() {
        if (panel == Panel.NONE) return
        stopVoiceIfActive()
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
                    setPadding(dp(8), 0, dp(8), 0)
                    setOnClickListener { listener.onAssociationSelected(candidate) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(34),
                ).apply { marginEnd = dp(5) },
            )
        }
        applyAssociationTheme()
    }

    fun clearAssociationCandidates() {
        if (::associationRow.isInitialized) associationRow.removeAllViews()
    }

    fun setTheme(newTheme: ImeTheme) {
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

    fun setShiftState(next: ShiftState) {
        if (shiftState == next) return
        shiftState = next
        listener.onShiftStateChanged(next)
        refreshEnglishShiftPresentation()
    }

    open fun shutdown() {
        stopVoiceIfActive()
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

    internal fun editQuickPhraseForTest(text: String): Boolean {
        val phrase = QuickPhraseRepository.load(context).firstOrNull { it.text == text } ?: return false
        openQuickPhraseEditor(phrase)
        return true
    }

    internal fun useQuickPhraseForTest(text: String): Boolean {
        val phrase = QuickPhraseRepository.load(context).firstOrNull { it.text == text } ?: return false
        return findTestTarget("phrase:${phrase.id}")?.performClick() == true
    }

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
                        dp(42),
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
            minimumHeight = dp(42)
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
                        dp(38),
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
                row.addView(
                    key(display, false, secondary, 1f, if (num == "1" && chinese) 12f else 17f) {
                        if (chinese && num == "1") onPinyinSegment() else onNineKey(num)
                    }.apply {
                        tag = "key-9:$num"
                        contentDescription = if (chinese && num == "1") "1，分词" else num
                        setTag(MARK_WHITE_KEY, true)
                        if (chinese && num == "1") {
                            setOnLongClickListener {
                                showChoicePopup(this, listOf("@", "#", "/"))
                                true
                            }
                        } else if (chinese && ImeData.keypad9Map[num].orEmpty().any {
                                it.length == 1 && it[0] in 'a'..'z'
                            }) {
                            setOnLongClickListener {
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
