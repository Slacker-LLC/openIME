package llc.slacker.openime

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
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
        fun onVoicePartial(text: String) {}
        fun onVoiceFinal(text: String) {}
        fun onVoiceError(message: String) {}
        fun onEnter()
        fun onCompositionChanged(composition: String, candidates: List<String>)
        fun onCandidateSelected(candidate: String)
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
    }

    /** Visual class marker for white keys (nine/digits grid). */
    private val MARK_WHITE_KEY = 0x1F000001
    /** Visual class marker for the gray side/action column in nine-key layouts. */
    private val MARK_SIDE_KEY = 0x1F000002
    private val MARK_FUNCTION_KEY = 0x1F000003

    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatAction = object : Runnable {
        override fun run() {
            if (!deleteCompositionAtCursor()) listener.onBackspace()
            repeatHandler.postDelayed(this, 60L)
        }
    }

    private val engine = CandidateEngine(PinyinLexicon.load(context).also {
        UserPhraseRepository.configure(context)
    })
    private var theme = ImeTheme.IOS
    private var appearance = ImeAppearance.SYSTEM
    private var mode = KeyboardMode.PINYIN_26
    private var panel = Panel.NONE
    private var shiftState = ShiftState.LOWERCASE
    private var soundEnabled = true
    private var hapticEnabled = true
    private var popupEnabled = true
    private var fuzzyEnabled = false
    private var skinRadius = 9

    private val pinyinBuffer = StringBuilder()
    private var lastNineDigits = ""
    private var lastNineCandidates = emptyList<String>()
    private var lastT9Digits = ""
    private var currentCandidates = emptyList<String>()
    private var currentItems: List<String>? = null
    private var candidateExpandedOpen = false
    private var symbolCategory = "常用"
    private var emojiCategory = "表情"
    private var showStickers = false
    private var clipboardTab = 0
    private var voiceLanguageIndex = 0
    private var toolPage = 0
    private var voiceProvider: SpeechRecognitionProvider? = null
    private var voiceActive = false
    private var voiceToggleAction: (() -> Unit)? = null
    private var voiceStartAction: (() -> Unit)? = null
    private var voiceStopAction: (() -> Unit)? = null
    private var floatingKeyboard = true
    private var popupView: View? = null
    private var keepPopupAfterKeyUp = false
    private val popupHideRunnable = Runnable { hidePopup() }
    private var contentInsetPx = dp(5)
    private var systemBottomInsetPx = 0
    private val maxContentWidthDp = 600
    private val fixedImeHeightDp = 296
    private val fixedKeyboardBodyHeightDp = fixedImeHeightDp - 64
    private var syncingComposition = false
    private var t9Filter = "T9"
    private var passwordField = false
    private var inlineEditTarget: EditText? = null

    private lateinit var mainDock: LinearLayout
    private lateinit var keyboardHost: FrameLayout
    private lateinit var topZone: LinearLayout
    private lateinit var toolbarRow: LinearLayout
    private lateinit var composeZone: LinearLayout
    private lateinit var composition: EditText
    private lateinit var candidateRow: LinearLayout
    private lateinit var associationRow: LinearLayout
    private lateinit var candidateExpandBtn: TextView
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
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(fixedImeHeightDp),
        )
        mainDock = LinearLayout(context).apply {
            tag = "main-dock"
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(fixedImeHeightDp),
            )
        }
        keyboardHost = FrameLayout(context).apply {
            tag = "keyboard-host"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(fixedKeyboardBodyHeightDp),
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
            dp(fixedKeyboardBodyHeightDp),
        ))
        addView(
            mainDock,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(fixedImeHeightDp),
            ),
        )
        addView(
            expandedPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(fixedImeHeightDp),
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
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp(fixedImeHeightDp)
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
        toolbarRow.setPadding(contentInsetPx + dp(12), 0, contentInsetPx + dp(12), 0)
        composition.setPadding(contentInsetPx + dp(10), dp(3), contentInsetPx + dp(10), 0)
        requestLayout()
    }

    /** Top zone: fixed 64dp. Idle = icon toolbar; composing = 20 pre-edit + 44 candidates. */
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
            setPadding(dp(12), 0, dp(12), 0)
            minimumHeight = dp(64)
        }
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_grid, "切换键盘", "keyboard-selector") { showPanel(Panel.KEYBOARD_SELECT) },
            LinearLayout.LayoutParams(dp(44), dp(48)),
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_clipboard, "剪贴板", "toolbar") { showPanel(Panel.CLIPBOARD) },
            LinearLayout.LayoutParams(dp(44), dp(48)),
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_emoji, "Emoji", "toolbar") { showPanel(Panel.EMOJI) },
            LinearLayout.LayoutParams(dp(44), dp(48)),
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_symbols, "符号", "toolbar") { showPanel(Panel.SYMBOLS) },
            LinearLayout.LayoutParams(dp(44), dp(48)),
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_more, "更多", "toolbar") { showPanel(Panel.TOOLS) },
            LinearLayout.LayoutParams(dp(44), dp(48)),
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
            textSize = 16f
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
            setPadding(dp(10), dp(3), dp(10), 0)
            minimumHeight = dp(20)
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
            dp(20),
        ))
        val candField = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        candidateRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val candScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                candidateRow,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(44),
                ),
            )
        }
        candField.addView(candScroll, LinearLayout.LayoutParams(0, dp(44), 1f))
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
            LinearLayout.LayoutParams(dp(30), dp(44)),
        )
        composeZone.addView(candField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44),
        ))
        topZone.addView(composeZone, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(64),
        ))
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
            setOnClickListener { onTap() }
        }

    fun cycleMode() {
        val next = when (mode) {
            KeyboardMode.PINYIN_26 -> KeyboardMode.ENGLISH_26
            KeyboardMode.ENGLISH_26 -> KeyboardMode.PINYIN_9
            KeyboardMode.PINYIN_9 -> KeyboardMode.ENGLISH_T9
            KeyboardMode.ENGLISH_T9 -> KeyboardMode.DIGITS
            KeyboardMode.DIGITS -> KeyboardMode.PINYIN_26
        }
        setMode(next)
    }

    fun setMode(newMode: KeyboardMode) {
        if (panel != Panel.NONE) closePanelToKeyboard()
        repeatHandler.removeCallbacks(nineTapReset)
        nineTapReset.run()
        mode = newMode
        clearAssociationCandidates()
        pinyinBuffer.clear()
        lastNineDigits = ""
        lastNineCandidates = emptyList()
        lastT9Digits = ""
        currentCandidates = emptyList()
        currentItems = emptyList()
        keyboardBody.animate().cancel()
        keyboardBody.alpha = 0.96f
        renderModeBody()
        keyboardBody.animate().alpha(1f).setDuration(100L).start()
        listener.onModeChanged(newMode)
    }

    fun showPanel(newPanel: Panel) {
        if (newPanel == Panel.NONE || newPanel == Panel.CANDIDATE_EXPANDED) return
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

    fun closePanelToKeyboard(): Boolean {
        if (candidateExpandedOpen) {
            renderExpanded(false)
            listener.onCandidateExpanded(false)
            return true
        }
        if (panel == Panel.NONE) return false
        stopVoiceIfActive()
        panel = Panel.NONE
        renderModeBody()
        mainDock.alpha = 0.96f
        mainDock.animate().alpha(1f).setDuration(100L).start()
        listener.onPanelChanged(Panel.NONE)
        return true
    }

    fun renderState(state: ImeState) {
        passwordField = state.passwordField
        val previousSelection = composition.selectionStart
        setCompositionText(
            state.composition,
            previousSelection.takeIf { it >= 0 },
        )
        currentItems = state.candidates
        currentCandidates = state.candidates
        if (state.composition.isEmpty()) {
            pinyinBuffer.clear()
            lastNineDigits = ""
            lastT9Digits = ""
        } else {
            pinyinBuffer.setLength(0)
            pinyinBuffer.append(state.composition)
            if (mode == KeyboardMode.ENGLISH_T9) lastT9Digits = state.composition
        }
        updateTopZone(state.composition.isNotEmpty())
        renderCandidateRow()
        applyCandidateTheme()
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
                    setOnClickListener { listener.onCandidateSelected(candidate) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(34),
                ).apply { marginEnd = dp(5) },
            )
        }
        applyCandidateTheme()
    }

    fun clearAssociationCandidates() {
        if (::associationRow.isInitialized) associationRow.removeAllViews()
    }

    fun setTheme(newTheme: ImeTheme) {
        theme = newTheme
        applyTheme()
        listener.onThemeChanged(newTheme)
    }

    fun setAppearance(newAppearance: ImeAppearance) {
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

    fun shutdown() {
        stopVoiceIfActive()
        repeatHandler.removeCallbacks(repeatAction)
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
        return deep(this)
    }

    internal fun tapTestTarget(query: String): Boolean {
        val target = findTestTarget(query)
        return target?.performClick() == true
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
        toolbarRow.visibility = if (composing) View.GONE else View.VISIBLE
        composeZone.visibility = if (composing) View.VISIBLE else View.GONE
    }

    private fun renderCandidateRow() {
        candidateRow.removeAllViews()
        if (currentCandidates.isEmpty()) {
            candidateRow.addView(
                TextView(context).apply {
                    text = if (composeZone.visibility == View.VISIBLE) composition.text else ""
                    textSize = 12f
                    setPadding(dp(10), 0, dp(10), 0)
                },
                wrapParams(),
            )
            return
        }
        currentCandidates.take(6).forEachIndexed { index, cand ->
            candidateRow.addView(candidateItemView(index, cand), wrapParams())
        }
    }

    private fun candidateItemView(index: Int, cand: String): LinearLayout {
        val word = TextView(context).apply {
            text = cand
            textSize = 14f
            maxLines = 1
            setPadding(dp(10), 0, dp(10), 0)
            tag = if (index == 0) "candidate-first" else "candidate-word"
            isClickable = false
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = if (index == 0) "candidate-first-row" else null
            minimumHeight = dp(34)
            addView(word, wrapParams())
            isClickable = true
            setOnClickListener { listener.onCandidateSelected(cand) }
        }
    }

    private fun renderModeBody() {
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
    }

    private fun renderPinyin26() {
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
                val iconRes = if (shiftState == ShiftState.CAPS_LOCK) R.drawable.ic_caps_lock else R.drawable.ic_shift
                val shift = key("", true, null, 1f, iconRes = iconRes) { cycleShift() }
                shift.tag = if (shiftState == ShiftState.CAPS_LOCK) {
                    "key-shift-caps"
                } else if (shiftState == ShiftState.SHIFT_ONCE) {
                    "key-shift-active"
                } else {
                    "key-shift"
                }
                row.addView(shift, flexKeyParams(1.25f))
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
        bottom.addView(key("123", true, null, 1f, 15f) { setMode(KeyboardMode.DIGITS) }, flexKeyParams(1.3f))
        bottom.addView(
            key(if (mode == KeyboardMode.ENGLISH_26) "." else "，。", true, null, 1f, 15f) {
                commitKeyboardCharacter(if (mode == KeyboardMode.ENGLISH_26) "." else "，")
            },
            flexKeyParams(0.95f),
        )
        bottom.addView(
            spaceVoiceKey(if (mode == KeyboardMode.ENGLISH_26) "space" else "空格") {
                listener.onSpace()
            },
            flexKeyParams(3.4f),
        )
        bottom.addView(
            key("中/英", true, null, 1f, 14f) { cycleMode() }.apply { tag = "key:mode" },
            flexKeyParams(1.05f),
        )
        bottom.addView(
            key(if (mode == KeyboardMode.ENGLISH_26) "Go" else "确定", true, null, 1f, 15f) { listener.onEnter() }
                .apply { tag = "key-enter" },
            flexKeyParams(1.8f),
        )
        keyboardBody.addView(bottom, rowParams(includeBottomGap = false))
    }

    private fun renderEnglish26() = renderPinyin26()

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
                        publishComposition(lastT9Digits, engine.getT9EnglishCandidates(lastT9Digits))
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
            key("0", false, null, 1f, 20f) { onNineKey("0") }.apply {
                tag = "key-9:0"
                setTag(MARK_WHITE_KEY, true)
                setOnLongClickListener {
                    commitKeyboardCharacter("0")
                    true
                }
            },
            flexKeyParams(0.9f, gapDp = 2),
        )
        centerBottom.addView(
            spaceVoiceKey("空格", white = true) { commitFirstCandidateOrSpace() },
            flexKeyParams(2.5f, gapDp = 2),
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
            key(if (chinese) "确定" else "Go", true, null, 1f, 13f) {
                if (composition.text.isNotEmpty()) {
                    listener.onCandidateSelected(firstCandidateOrComposition())
                } else {
                    listener.onEnter()
                }
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
            key("返回", true, null, 1f, 14f) { setMode(KeyboardMode.PINYIN_26) }.apply {
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
            key("换行", true, null, 1f, 13f) { listener.onEnter() }
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
        if (white) setTag(MARK_WHITE_KEY, true)
        var voiceLongPressed = false
        setOnLongClickListener {
            voiceLongPressed = true
            listener.onVoicePressChanged(true)
            true
        }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (voiceLongPressed) {
                        voiceLongPressed = false
                        listener.onVoicePressChanged(false)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    /** Called by the listener after a long press on the combined space key. */
    fun toggleVoiceFromSpace() {
        if (panel != Panel.VOICE) {
            showPanel(Panel.VOICE)
            post { voiceToggleAction?.invoke() }
        } else {
            voiceToggleAction?.invoke()
        }
    }

    /** Starts recording after the combined space key crosses the long-press threshold. */
    fun startVoiceFromSpace() {
        if (panel != Panel.VOICE) {
            showPanel(Panel.VOICE)
            post { voiceStartAction?.invoke() }
        } else {
            voiceStartAction?.invoke()
        }
    }

    /** Ends recording when the combined space key is released. */
    fun stopVoiceFromSpace() {
        voiceStopAction?.invoke()
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
    }

    private fun panelHead(name: String): LinearLayout {
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            minimumHeight = dp(44)
            tag = "panel-head"
        }
        nav.addView(
            button("‹", 22f, true).apply {
                tag = "key-panel-back"
                minimumHeight = dp(40)
                contentDescription = "✕ 键盘"
                setOnClickListener { closePanelToKeyboard() }
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
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
            dp(44),
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
            KeyboardMode.ENGLISH_T9 to "英文九键",
            KeyboardMode.DIGITS to "数字键盘",
        )
        modes.chunked(2).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { (modeValue, label) ->
                row.addView(
                    key(label, true, null, 1f, 13f) {
                        setMode(modeValue)
                    }.apply {
                        tag = if (mode == modeValue) "tab-active" else "keyboard-choice"
                        contentDescription = modeValue.name
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
            dp(fixedImeHeightDp - 44),
        ))
    }

    private fun filterChip(label: String, active: Boolean, onTap: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = dp(42)
            minHeight = dp(34)
            setPadding(dp(12), 0, dp(12), 0)
            tag = if (active) "tab-active" else "panel-tab"
            contentDescription = label
            isClickable = true
            setOnClickListener { onTap() }
        }

    private fun panelChipScroll(
        labels: List<String>,
        selected: String,
        onSelected: (String) -> Unit,
    ): HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labels.forEach { label ->
            row.addView(
                filterChip(label, label == selected) { onSelected(label) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(32),
                ).apply { marginEnd = dp(6) },
            )
        }
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
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

    private fun renderTools() {
        addPanelHead("工具")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            tag = "tools-panel"
        }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val cards = if (toolPage == 0) {
            listOf(
                R.drawable.ic_grid to ("切换键盘" to Panel.KEYBOARD_SELECT),
                R.drawable.ic_emoji to ("表情" to Panel.EMOJI),
                R.drawable.ic_clipboard to ("剪贴板" to Panel.CLIPBOARD),
                R.drawable.ic_handwriting to ("手写输入" to Panel.HANDWRITING),
                R.drawable.ic_symbols to ("符号" to Panel.SYMBOLS),
                R.drawable.ic_settings to ("更多设置" to Panel.SETTINGS),
                R.drawable.ic_mic to ("语音" to Panel.VOICE),
                R.drawable.ic_game to ("浮动键盘" to Panel.GAMING),
                R.drawable.ic_keyboard to ("文本编辑" to Panel.TEXT_EDITOR),
            )
        } else {
            listOf(
                R.drawable.ic_settings to ("主题设置" to Panel.SETTINGS),
                R.drawable.ic_game to ("浮动键盘" to Panel.GAMING),
            )
        }
        cards.chunked(4).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { (icon, pair) ->
                val (label, target) = pair
                row.addView(
                    toolCard(icon, label) {
                        target?.let { showPanel(it) }
                    },
                    LinearLayout.LayoutParams(0, dp(70), 1f).apply { marginEnd = dp(8) },
                )
            }
            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(70),
            ).apply { bottomMargin = dp(8) })
        }
        body.addView(grid, matchParams())
        body.addView(TextView(context).apply {
            text = if (toolPage == 0) "•  ○" else "○  •"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            tag = "tools-page-dots"
            contentDescription = "工具下一页"
            isClickable = true
            setOnClickListener {
                toolPage = if (toolPage == 0) 1 else 0
                renderPanel(Panel.TOOLS)
            }
        }, wrapParams())
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
            setPadding(0, dp(7), 0, dp(6))
            contentDescription = label
            isClickable = true
            setOnClickListener { onTap() }
        }
        card.addView(ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        card.addView(TextView(context).apply {
            text = label
            textSize = 12f
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
        val cats = ImeData.symbols.keys.toList()
        val tabs = panelChipScroll(cats, symbolCategory) { cat ->
            if (cat != symbolCategory) {
                symbolCategory = cat
                renderPanel(Panel.SYMBOLS)
            }
        }
        body.addView(tabs, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(32),
        ).apply { bottomMargin = dp(8) })
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ImeData.symbols[symbolCategory].orEmpty().chunked(6).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { s ->
                row.addView(
                    key(s, false, null, 1f, 17f) { listener.onSymbolSelected(s) },
                    gridCellParams(38, 6, 6),
                )
            }
            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38),
            ).apply { bottomMargin = dp(6) })
        }
        body.addView(
            panelVerticalScroll(grid, "symbols-scroll"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        expandedPanel.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(252),
        ))
    }

    private fun renderEmoji() {
        addPanelHead("Emoji")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            tag = "emoji-panel"
        }
        val cats = listOf("表情", "手势", "动物", "食物")
        val tabs = panelChipScroll(cats + "贴图", if (showStickers) "贴图" else emojiCategory) { cat ->
            if (cat == "贴图") {
                showStickers = true
            } else {
                showStickers = false
                emojiCategory = cat
            }
            renderEmoji()
        }
        body.addView(tabs, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(32),
        ).apply { bottomMargin = dp(8) })
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (showStickers) {
            ImeData.stickers.chunked(8).forEach { chunk ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { s ->
                    row.addView(
                        key(s.title, false, null, 1f, 12f) { listener.onEmojiSelected(s.text) },
                        gridCellParams(34, 8, 5),
                    )
                }
                grid.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(34),
                ).apply { bottomMargin = dp(5) })
            }
        } else {
            ImeData.emojis[emojiCategory].orEmpty().chunked(8).forEach { chunk ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { e ->
                    row.addView(
                        key(e, false, null, 1f, 21f) { listener.onEmojiSelected(e) },
                        gridCellParams(34, 8, 5),
                    )
                }
                grid.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(34),
                ).apply { bottomMargin = dp(5) })
            }
        }
        body.addView(
            panelVerticalScroll(grid, "emoji-scroll"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        expandedPanel.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(252),
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
            dp(34),
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
            dp(145),
        ).apply { bottomMargin = dp(7) })
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(key("撤销", true, null, 1f, 13f) { pad.undo() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) })
        actions.addView(key("清空", true, null, 1f, 13f) { pad.clear() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) })
        actions.addView(key("空格", true, null, 1f, 13f) { listener.onSpace() }, LinearLayout.LayoutParams(0, dp(38), 1f))
        body.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(38),
        ))
        expandedPanel.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(252),
        ))
    }

    private fun renderVoice() {
        voiceToggleAction = null
        addPanelHead("语音输入")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val initialProvider = SpeechRecognitionProvider(context)
        voiceProvider = initialProvider
        val modelReady = initialProvider.isAvailable()
        val modelStatus = TextView(context).apply {
            text = if (modelReady) "离线模型已就绪 · 音频不出设备" else "离线模型未就绪 · 未启用联网识别"
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
            text = "长按空格开始，松开结束；识别文字会在这里预览"
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
        val languages = listOf("普通话" to "zh-CN", "English" to "en-US")
        var finalDelivered = false
        var recognizedText = ""
        val langButton = button(languages[voiceLanguageIndex].first, 13f, true).apply {
            setOnClickListener {
                voiceLanguageIndex = (voiceLanguageIndex + 1) % languages.size
                text = languages[voiceLanguageIndex].first
            }
        }
        controls.addView(langButton, LinearLayout.LayoutParams(0, dp(58), 1f))
        val micButton = button("🎤", 22f, false).apply {
            tag = "voice-mic"
        }
        controls.addView(micButton, LinearLayout.LayoutParams(dp(58), dp(58)))
        val commitButton = button("上屏", 14f, false).apply {
            setOnClickListener {
                val text = recognizedText.trim()
                if (!finalDelivered && text.isNotEmpty()) {
                    listener.onCharacter(text)
                    finalDelivered = true
                }
            }
        }
        controls.addView(commitButton, LinearLayout.LayoutParams(0, dp(58), 1f))
        body.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(58),
        ))
        expandedPanel.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(252),
        ))
        fun startVoice() {
            val provider = voiceProvider ?: SpeechRecognitionProvider(context).also { voiceProvider = it }
            if (voiceActive) return
            if (!provider.isAvailable()) {
                modelStatus.text = "离线模型未就绪 · 请先放入已校验的模型包"
                transcript.text = "当前不会调用联网识别；模型接入后这里显示实时结果"
                return
            }
            finalDelivered = false
            recognizedText = ""
            voiceActive = true
            micButton.text = "⏹"
            modelStatus.text = "正在使用离线模型 · 音频不出设备"
            transcript.text = "正在聆听… 松开空格结束"
            provider.start(languages[voiceLanguageIndex].second, object : SpeechRecognitionProvider.SpeechEvents {
                override fun onPartial(text: String) {
                    // AudioRecord inference callbacks arrive from the voice
                    // worker thread; keep view and InputConnection mutations
                    // on the IME main thread.
                    post {
                        if (!voiceActive) return@post
                        if (text.isNotBlank()) recognizedText = text
                        transcript.text = text
                        modelStatus.text = "正在聆听 · 松开空格结束"
                        listener.onVoicePartial(text)
                    }
                }
                override fun onFinal(text: String) {
                    post {
                        if (text.isNotBlank()) recognizedText = text
                        transcript.text = text
                        micButton.text = "🎤"
                        voiceActive = false
                        finalDelivered = true
                        modelStatus.text = "离线识别完成 · 可继续长按空格"
                        listener.onVoiceFinal(text)
                    }
                }
                override fun onRms(rms: Float) {
                    val h = (8 + (rms * 4f).coerceIn(0f, 52f)).toInt().coerceIn(8, 64)
                    post {
                        waves.forEach { it.layoutParams = LinearLayout.LayoutParams(dp(4), h).apply {
                            marginEnd = dp(5)
                        } }
                        waveBar.invalidate()
                    }
                }
                override fun onError(message: String) {
                    post {
                        recognizedText = ""
                        transcript.text = message
                        micButton.text = "🎤"
                        voiceActive = false
                        finalDelivered = false
                        modelStatus.text = "语音未完成 · 请检查本地模型和麦克风权限"
                        listener.onVoiceError(message)
                    }
                }
                override fun onReady() {
                    post {
                        micButton.text = "⏹"
                        modelStatus.text = "正在聆听 · 松开空格结束"
                    }
                }
            })
        }
        fun stopVoice() {
            if (!voiceActive) return
            voiceProvider?.stop()
            micButton.text = "🎤"
            voiceActive = false
            modelStatus.text = "已停止 · 可继续长按空格"
        }
        micButton.setOnClickListener {
            if (voiceActive) stopVoice() else startVoice()
        }
        voiceStartAction = { startVoice() }
        voiceStopAction = { stopVoice() }
        voiceToggleAction = { if (voiceActive) stopVoice() else startVoice() }
    }

    private fun stopVoiceIfActive() {
        voiceProvider?.stop()
        voiceProvider = null
        voiceActive = false
        voiceToggleAction = null
        voiceStartAction = null
        voiceStopAction = null
    }

    private fun renderClipboard() {
        expandedPanel.removeAllViews()
        inlineEditTarget = null
        addPanelHead("剪贴板")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            tag = "clipboard-panel"
        }
        val tabs = panelChipScroll(listOf("剪贴板", "常用语"), if (clipboardTab == 0) "剪贴板" else "常用语") { label ->
            clipboardTab = if (label == "剪贴板") 0 else 1
            renderClipboard()
        }
        body.addView(tabs, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(32),
        ).apply { bottomMargin = dp(8) })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (clipboardTab == 0) {
            if (!passwordField) ClipboardHistoryRepository.capturePrimary(context)
            val history = ClipboardHistoryRepository.load(context)
            if (history.isEmpty()) {
                col.addView(sectionTitle("最近复制"), wrapParams())
                col.addView(TextView(context).apply {
                    text = "暂无剪贴历史；复制文本后重新打开这里即可看到。"
                    textSize = 13f
                    setPadding(dp(4), dp(6), dp(4), 0)
                    tag = "panel-note"
                }, wrapParams())
            } else {
                col.addView(sectionTitle("最近复制"), wrapParams())
                history.forEach { entry ->
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
                        text = if (entry.pinned) "已置顶" else "刚刚"
                        textSize = 11f
                    }, weightParams(1f))
                    meta.addView(button("置顶", 10f, true).apply {
                        setOnClickListener {
                            ClipboardHistoryRepository.togglePin(context, entry.text)
                            renderClipboard()
                        }
                    }, wrapParams())
                    meta.addView(button("使用", 10f, true).apply {
                        setOnClickListener { listener.onCharacter(entry.text) }
                    }, wrapParams())
                    card.addView(meta, wrapParams())
                    col.addView(card, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(7) })
                }
            }
        } else {
            col.addView(button("新增常用语", 13f, true).apply {
                tag = "quick-phrase-add"
                setOnClickListener { openQuickPhraseEditor(null) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44),
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
                                QuickPhraseRepository.remove(context, phrase.id)
                                renderClipboard()
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
            dp(252),
        ))
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
                    LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginEnd = dp(5) },
                )
            }
        body.addView(quick, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(32),
        ).apply { bottomMargin = dp(10) })
        val cross = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "textedit-cross"
        }
        fun cell(label: String? = null, action: String? = null, center: Boolean = false): TextView =
            button(label ?: "", if (center) 9f else 14f, !center).apply {
                if (action != null) setOnClickListener { listener.onTextEdit(action) }
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
            dp(252),
        ))
    }

    private fun renderSettings() {
        addPanelHead("更多设置")
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
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
            renderPanel(Panel.SETTINGS)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(32),
        ).apply { bottomMargin = dp(14) })
        content.addView(sectionTitle("按键与输入"), wrapParams())
        listOf(
            "按键音效" to "声音反馈",
            "触感震动" to "轻微震动",
            "按键气泡" to "字母预览",
        ).forEach { (label, sub) ->
            content.addView(settingToggleRow(label, sub), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54),
            ).apply { bottomMargin = dp(8) })
        }
        content.addView(settingNavigationRow(
            "模糊音纠错",
            "进入后配置 z/zh、c/ch、s/sh 等规则",
        ) { showPanel(Panel.FUZZY_SETTINGS) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(60),
        ).apply { bottomMargin = dp(8) })
        content.addView(TextView(context).apply {
            text = "当前外观：iOS。按键圆角采用默认规范，不单独暴露调节项。"
            textSize = 12f
            setPadding(dp(4), dp(10), dp(4), 0)
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

    private fun sectionTitle(textValue: String): TextView = TextView(context).apply {
        text = textValue
        textSize = 13f
        includeFontPadding = false
        setPadding(dp(4), dp(2), 0, dp(8))
        tag = "panel-section-title"
    }

    private fun settingToggleRow(label: String, sub: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            tag = "setting-row"
            contentDescription = label
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
            addView(toggle(label), wrapParams())
        }

    private fun settingNavigationRow(label: String, sub: String, onTap: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            tag = "setting-row"
            contentDescription = label
            isClickable = true
            setOnClickListener { onTap() }
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
                textSize = 26f
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
        content.addView(settingToggleRow("启用模糊音", "z/zh · c/ch · s/sh · l/n"), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54),
        ).apply { bottomMargin = dp(14) })
        content.addView(sectionTitle("当前规则"), wrapParams())
        content.addView(panelChipScroll(
            listOf("z / zh", "c / ch", "s / sh", "l / n", "f / h"),
            "z / zh",
        ) { }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(32),
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
            minimumHeight = dp(26)
            contentDescription = seed
            tag = "toggle"
            addView(knob)
            setOnClickListener {
                val next = !onState(seed)
                toggleCallback(seed)?.invoke(next)
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
        "模糊音纠错" -> fuzzyEnabled
        "按键气泡" -> popupEnabled
        else -> true
    }

    private fun toggleCallback(seed: String): ((Boolean) -> Unit)? = when (seed) {
        "按键音效" -> { { soundEnabled = it; listener.onSoundChanged(it) } }
        "触感震动" -> { { hapticEnabled = it; listener.onHapticChanged(it) } }
        "模糊音纠错" -> { { fuzzyEnabled = it; listener.onFuzzyChanged(it) } }
        "按键气泡" -> { { popupEnabled = it; listener.onPopupChanged(it) } }
        else -> null
    }

    private fun settingsSlider(labelText: String, min: Int, max: Int, initial: Int, onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            tag = "setting-row"
        }
        row.addView(TextView(context).apply { text = labelText; textSize = 13f }, weightParams(1f))
        row.addView(SeekBar(context).apply {
            this.min = min
            this.max = max
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }, weightParams(2f))
        return row
    }

    private fun renderGaming() {
        addPanelHead("浮动键盘")
        listener.onFloatingKeyboardChanged(floatingKeyboard)
        val macros = listOf("收到！", "集合进攻！", "稳住能赢！", "请求集合！", "保护输出！")
        val hud = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "gaming-panel"
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        var lastTouchX = 0f
        var lastTouchY = 0f
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "floating-header"
        }
        val dragHandle = TextView(context).apply {
            text = "⠿  拖动浮动键盘"
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            tag = "floating-drag-handle"
            contentDescription = "拖动浮动键盘"
            setPadding(dp(4), 0, dp(8), 0)
            isClickable = true
            setOnTouchListener { _, event ->
                if (!floatingKeyboard) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        Log.d("MinisIme", "floating-drag-down x=${event.rawX} y=${event.rawY}")
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
        header.addView(button(if (floatingKeyboard) "贴底固定" else "恢复浮动", 11f, true).apply {
            contentDescription = if (floatingKeyboard) "贴底固定" else "恢复浮动"
            setOnClickListener {
                floatingKeyboard = !floatingKeyboard
                listener.onFloatingKeyboardChanged(floatingKeyboard)
                renderPanel(Panel.GAMING)
            }
        }, LinearLayout.LayoutParams(dp(82), dp(30)))
        hud.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(30),
        ).apply { bottomMargin = dp(4) })
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
            dp(34),
        ))
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEach { rowText ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowText.forEach { ch ->
                row.addView(
                    key(ch.toString(), false, null, 1f, 13f) { listener.onCharacter(ch.toString()) }.apply {
                        tag = "game-mini"
                    },
                    LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(4) },
                )
            }
            if (rowText.startsWith("z")) {
                val gameBackspace = backspaceKey().apply { tag = "game-mini" }
                row.addView(gameBackspace, LinearLayout.LayoutParams(0, dp(34), 1.2f))
                row.addView(
                    key("发送", true, null, 1.6f, 12f) { listener.onEnter() }.apply {
                        tag = "game-mini"
                    },
                    LinearLayout.LayoutParams(0, dp(34), 1.6f),
                )
            }
            hud.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34),
            ).apply { bottomMargin = dp(5) })
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

    private fun onKeyTapped(base: String) {
        if (insertIntoInlineEditor(base)) return
        clearAssociationCandidates()
        if (mode == KeyboardMode.PINYIN_26) {
            val (py, selection) = replaceCompositionSelection(base)
            publishComposition(py, engine.getCandidates(py, fuzzyEnabled), selection)
        } else if (mode == KeyboardMode.ENGLISH_26) {
            val ch = if (shiftState != ShiftState.LOWERCASE) base.uppercase() else base
            val (py, selection) = replaceCompositionSelection(ch)
            publishComposition(py, engine.getEnglishCompletions(py), selection)
            if (shiftState == ShiftState.SHIFT_ONCE) {
                shiftState = ShiftState.LOWERCASE
                listener.onShiftStateChanged(shiftState)
                renderModeBody()
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
            publishComposition(digits, engine.getT9EnglishCandidates(digits), selection)
        } else if (mode == KeyboardMode.PINYIN_9) {
            // Chinese 9-key is a continuous digit buffer. The visible pre-edit
            // is always Pinyin, never the raw keypad digits, so a user can edit
            // the syllables and still see the same candidate pipeline as 26-key.
            val digits = (lastNineDigits + num).take(24)
            val result = engine.get9KeyCandidates(digits)
            val preview = result.pinyins.firstOrNull { it.isNotBlank() }
                ?: digits.mapNotNull { digit ->
                    ImeData.keypad9Map[digit.toString()]
                        ?.firstOrNull { it.length == 1 && it[0] in 'a'..'z' }
                }.joinToString("")
            val candidates = (result.candidates + engine.getCandidates(preview, fuzzyEnabled))
                .filter { it.isNotEmpty() && it.none(Char::isDigit) }
                .distinct()
                .take(96)
            lastNineDigits = digits
            lastNineCandidates = candidates
            publishComposition(preview, candidates)
        } else {
            listener.onCharacter(num)
        }
    }

    /** Insert an editable syllable boundary without committing the text. */
    private fun onPinyinSegment() {
        if (mode != KeyboardMode.PINYIN_26 && mode != KeyboardMode.PINYIN_9) return
        if (insertIntoInlineEditor(" ")) return
        val current = composition.text.toString()
        if (current.isBlank() || current.endsWith(' ')) return
        if (mode == KeyboardMode.PINYIN_9) lastNineDigits = ""
        val (next, selection) = replaceCompositionSelection(" ")
        publishComposition(next, candidatesForComposition(next), selection)
    }

    private fun publishComposition(text: String, candidates: List<String>, selection: Int? = null) {
        setCompositionText(text, selection)
        pinyinBuffer.clear()
        pinyinBuffer.append(text)
        currentCandidates = candidates
        updateTopZone(text.isNotEmpty())
        renderCandidateRow()
        listener.onCompositionChanged(text, candidates)
        applyCandidateTheme()
    }

    /** Keep the editable pre-edit field in sync without re-entering its watcher. */
    private fun setCompositionText(text: String, selection: Int? = null) {
        syncingComposition = true
        if (composition.text.toString() != text) composition.setText(text)
        val requested = selection ?: composition.selectionStart.takeIf { it >= 0 } ?: text.length
        composition.setSelection(requested.coerceIn(0, text.length))
        syncingComposition = false
    }

    /** Recompute candidates after the user edits the visible Pinyin field. */
    private fun onCompositionEdited(text: String) {
        clearAssociationCandidates()
        pinyinBuffer.clear()
        pinyinBuffer.append(text)
        when (mode) {
            KeyboardMode.PINYIN_9 -> lastNineDigits = ""
            KeyboardMode.ENGLISH_T9 -> lastT9Digits = text
            else -> Unit
        }
        val candidates = candidatesForComposition(text)
        currentCandidates = candidates
        updateTopZone(text.isNotEmpty())
        renderCandidateRow()
        listener.onCompositionChanged(text, candidates)
        applyCandidateTheme()
    }

    private fun candidatesForComposition(text: String): List<String> = when (mode) {
        KeyboardMode.PINYIN_26 -> engine.getCandidates(text, fuzzyEnabled)
        KeyboardMode.ENGLISH_26 -> engine.getEnglishCompletions(text)
        KeyboardMode.PINYIN_9 -> engine.getCandidates(text, fuzzyEnabled)
        KeyboardMode.ENGLISH_T9 -> engine.getT9EnglishCandidates(text)
        KeyboardMode.DIGITS -> emptyList()
    }

    private fun replaceCompositionSelection(insert: String): Pair<String, Int> {
        val current = composition.text.toString()
        val start = composition.selectionStart.takeIf { it >= 0 }?.coerceIn(0, current.length)
            ?: current.length
        val end = composition.selectionEnd.takeIf { it >= 0 }?.coerceIn(start, current.length)
            ?: start
        return (current.substring(0, start) + insert + current.substring(end)) to (start + insert.length)
    }

    /** Delete at the visible pre-edit cursor; fall back to target-text deletion otherwise. */
    private fun deleteCompositionAtCursor(): Boolean {
        if (!composition.hasFocus() || composition.text.isEmpty()) return false
        val current = composition.text.toString()
        val start = composition.selectionStart.coerceIn(0, current.length)
        val end = composition.selectionEnd.coerceIn(start, current.length)
        val deleteStart = if (start == end) {
            if (start == 0) return true
            start - 1
        } else {
            start
        }
        val next = current.removeRange(deleteStart, end)
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
        renderModeBody()
    }

    private fun firstCandidateOrComposition(): String =
        currentCandidates.firstOrNull() ?: composition.text.toString()

    private fun feedback() {
        if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (soundEnabled) playSoundEffect(SoundEffectConstants.CLICK)
    }

    private fun renderExpanded(open: Boolean) {
        if (!open) {
            candidateOverlay.visibility = View.GONE
            keyboardBody.visibility = View.VISIBLE
            candidateExpandedOpen = false
            return
        }
        candidateExpandedOpen = true
        candidateOverlay.visibility = View.VISIBLE
        candidateOverlay.removeAllViews()
        candidateOverlay.addView(
            panelHead("候选字词"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44),
            ),
        )
        val scroll = ScrollView(context)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (currentCandidates.isEmpty()) {
            col.addView(title("暂无候选", small = true), wrapParams())
        } else {
            currentCandidates.chunked(4).forEach { chunk ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { cand ->
                    row.addView(
                        key(cand, false, null, 1f, 15f) { listener.onCandidateSelected(cand) },
                        LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) },
                    )
                }
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
            mainTextSize = mainTextSizeOverride ?: if (func) 15f else 20f,
        ).apply {
            tag = "key:$text"
            setTag(MARK_FUNCTION_KEY, func)
            contentDescription = if (text.isNotEmpty()) text else if (iconRes != 0) "功能键" else " "
            minimumHeight = dp(48)
            setOnClickListener {
                feedback()
                onTap()
            }
            if (popupEnabled) {
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> if (text.isNotEmpty()) showPopup(this, text)
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
        minHeight = dp(28)
        minimumHeight = dp(28)
    }

    private fun backspaceKey(): ImeKeyView = key("", true, null, 1f, 15f, iconRes = R.drawable.ic_backspace) {
        if (!deleteCompositionAtCursor()) listener.onBackspace()
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
        }
        addView(clearHint, FrameLayout.LayoutParams(dp(30), dp(14)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(2)
        })
        var swipeStartX = 0f
        var swipeStartY = 0f
        var clearSwipe = false
        fun setClearHintActive(active: Boolean) {
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
        setOnLongClickListener {
            repeatHandler.removeCallbacks(repeatAction)
            repeatHandler.postDelayed(repeatAction, 300L)
            true
        }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.rawX
                    swipeStartY = event.rawY
                    clearSwipe = false
                    setClearHintActive(false)
                    clearHint.alpha = 1f
                    Log.d("MinisIme", "backspace-touch-down x=${event.rawX} y=${event.rawY}")
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val upward = swipeStartY - event.rawY
                    val horizontal = kotlin.math.abs(event.rawX - swipeStartX)
                    if (!clearSwipe && upward >= dp(36) && horizontal <= dp(96)) {
                        clearSwipe = true
                        repeatHandler.removeCallbacks(repeatAction)
                        setClearHintActive(true)
                        showPopup(this, "清空")
                        repeatHandler.removeCallbacks(popupHideRunnable)
                        Log.d("MinisIme", "backspace-clear-swipe dy=$upward dx=$horizontal")
                        listener.onClearAll()
                    }
                    clearSwipe
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatHandler.removeCallbacks(repeatAction)
                    val consumed = clearSwipe
                    if (consumed) {
                        setClearHintActive(false)
                        repeatHandler.postDelayed(popupHideRunnable, 520L)
                    } else {
                        setClearHintActive(false)
                    }
                    clearSwipe = false
                    consumed
                }
                else -> clearSwipe
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
        val t = theme.tokens(appearance, night)
        val popupWidth = dp(48)
        val popupHeight = dp(56)
        val p = TextView(context).apply {
            text = char
            textSize = if (char == "清空") 17f else 24f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setTextColor(Color.WHITE)
            background = rounded(t.primary, dp(12))
            elevation = dp(4).toFloat()
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
        val t = theme.tokens(appearance, night)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = rounded(t.primary, dp(12))
            elevation = dp(4).toFloat()
            contentDescription = "长按符号选择"
        }
        choices.forEach { symbol ->
            row.addView(TextView(context).apply {
                text = symbol
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
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
        val t = theme.tokens(appearance, night)
        setBackgroundColor(t.keyboardBackground)
        mainDock.setBackgroundColor(t.expandedBackground)
        keyboardBody.setBackgroundColor(t.keyboardBackground)
        topZone.setBackgroundColor(t.toolbarBackground)
        expandedPanel.setBackgroundColor(t.expandedBackground)
        candidateOverlay.setBackgroundColor(t.expandedBackground)
        applyThemeRecursive(this, t)
        composition.setTextColor(t.keySecondaryText)
        candidateExpandBtn.setTextColor(t.keySecondaryText)
    }

    private fun applyCandidateTheme() {
        if (!::candidateRow.isInitialized) return
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val t = theme.tokens(appearance, night)
        applyThemeRecursive(candidateRow, t)
        composition.setTextColor(t.keySecondaryText)
        candidateExpandBtn.setTextColor(t.keySecondaryText)
    }

    private fun applyThemeRecursive(view: View, t: ImeTheme.Tokens) {
        when (view) {
            is ImeKeyView -> {
                val white = view.getTag(MARK_WHITE_KEY) == true ||
                    view.contentDescription?.toString()?.matches(Regex("[0-9]+")) == true
                val side = view.getTag(MARK_SIDE_KEY) == true ||
                    (view.parent as? View)?.tag in setOf("pinyin9-actions", "t9-actions", "digits-actions")
                val function = view.getTag(MARK_FUNCTION_KEY) == true ||
                    labelIsFunc(view.contentDescription?.toString().orEmpty())
                val primary = !side && (view.tag == "tab-active" ||
                    view.tag == "key-shift-caps" ||
                    view.tag == "key-enter")
                val color = when {
                    primary -> t.primary
                    white -> t.lightKeyBackground
                    side -> t.sideKeyBackground
                    function -> t.functionKeyBackground
                    else -> t.keyBackground
                }
                view.background = statefulRounded(color, dim(color), dp(skinRadius))
                view.elevation = dp(1).toFloat()
                when {
                    primary -> view.setColors(Color.WHITE, t.keySecondaryText, Color.WHITE)
                    white -> view.setColors(t.lightKeyText, t.lightKeyText, t.lightKeyText)
                    side -> view.setColors(t.sideKeyText, t.sideKeyText, t.sideKeyText)
                    function -> view.setColors(t.functionKeyText, t.functionKeyText, t.functionKeyText)
                    else -> view.setColors(t.keyText, t.keySecondaryText, t.keyText)
                }
            }
            is LinearLayout -> {
                when (view.tag) {
                    "candidate-first-row" -> view.background = rounded(t.lightKeyBackground, dp(9))
                    "nine-punct-stack", "digits-symbol-stack" -> view.background = rounded(t.sideKeyBackground, dp(9))
                    "setting-row", "clip-card" -> view.background = rounded(t.toolCardBackground, dp(10))
                    "gaming-panel" -> view.background = rounded(t.toolCardBackground, dp(12))
                }
                if (view.contentDescription != null && view.isClickable && view.tag == null) {
                    view.background = rounded(t.toolCardBackground, dp(14))
                }
            }
            is ImageView -> {
                if ((view.parent is LinearLayout && (view.parent as LinearLayout).tag == "toolbar-row") ||
                    hasAncestorTag(view, "tools-panel")) {
                    view.imageTintList = ColorStateList.valueOf(t.keyText)
                }
            }
            is TextView -> {
                val tag = view.tag as? String
                if (view.parent !is ImeKeyView) view.setTextColor(t.keyText)
                when {
                    tag == "backspace-clear-hint" -> {
                        view.setTextColor(t.keySecondaryText)
                    }
                    tag == "candidate-first" -> {
                        view.setTextColor(t.primary)
                    }
                    tag == "candidate-word" -> {
                        view.setTextColor(t.candidateText)
                    }
                    tag == "tab-active" -> {
                        view.setTextColor(t.primary)
                        view.background = statefulRounded(t.lightKeyBackground, dim(t.lightKeyBackground), dp(99))
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
                        view.setTextColor(Color.WHITE)
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
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyThemeRecursive(view.getChildAt(i), t)
        }
    }

    private fun ImeKeyView.mainIsFunc(): Boolean {
        return labelIsFunc(contentDescription?.toString().orEmpty())
    }

    private fun labelIsFunc(text: String): Boolean =
        text.contains("功能键") || text.contains("Backspace") || text.contains("Shift") ||
            text.contains("⇧") || text.contains("⇪") || text.contains("⌫") ||
            text.contains("空格") || text.contains("space") || text.contains("确定") ||
            text.contains("确认") || text.contains("Go") || text.contains("123") ||
            text.contains("中/英") || text.contains("中英") || text.contains("拼音") ||
            text.contains("返回") || text.contains("换行") || text.contains("重输") ||
            text.contains("符号") || text.contains("全拼") || text.contains("T9") ||
            text.contains("abc") || text.contains("ABC") || text.contains("26键") ||
            text.contains("手写") || text.contains("语音") || text.contains("短语") ||
            text.contains("游戏") || text.contains("设置") || text.contains("候选") ||
            text.contains("模板") || text.contains("粘贴") || text.contains("复制") ||
            text.contains("剪切") || text.contains("撤销") || text.contains("清空") ||
            text.contains("全选") || text.contains("上屏") || text.contains("光标") ||
            text.contains("取消") || text.contains("收起") || text.contains("⌄") ||
            text.contains("▼") || text.contains("▲") || text.contains("•") ||
            text.contains("✕")

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
        dp(48),
    ).apply {
        if (includeBottomGap) bottomMargin = dp(6)
    }
    private fun flexKeyParams(
        weight: Float = 1f,
        heightDp: Int = 48,
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
