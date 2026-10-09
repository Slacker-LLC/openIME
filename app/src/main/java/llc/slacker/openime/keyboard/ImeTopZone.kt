package llc.slacker.openime.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import llc.slacker.openime.R
import llc.slacker.openime.floating.FloatingDragController
import llc.slacker.openime.theme.ImeDrawableFactory
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeTheme
import llc.slacker.openime.theme.ImeTypographyTokens

internal enum class ImeTopZoneState {
    IDLE,
    COMPOSING,
    CANDIDATE_EXPANDED,
    VOICE_INLINE,
}

/**
 * Concrete owner of the fixed-height IME top zone.
 *
 * The host supplies data/actions. This view owns construction and visibility
 * of the toolbar, composition/candidate surface, and inline voice surface.
 */
/** Composing strip: a short pinyin line over the candidate row, 60dp in all. */
private const val COMPOSITION_LINE_DP = 18
private const val CANDIDATE_ROW_DP = 42

internal class ImeTopZone(
    context: Context,
    private val toPx: (Int) -> Int,
    private val onFeedback: () -> Unit,
    private val isCompositionSyncing: () -> Boolean,
    private val onCompositionEdited: (String) -> Unit,
    onKeyboardSelect: () -> Unit,
    onClipboard: () -> Unit,
    onEmoji: () -> Unit,
    onSymbols: () -> Unit,
    onTextEditor: () -> Unit,
    onQuickPhrases: () -> Unit = {},
    onHideKeyboard: () -> Unit,
    onTools: () -> Unit,
    onExpandCandidates: () -> Unit,
    private val onUndo: () -> Unit,
    private val onSplitToggle: () -> Unit = {},
    private val onAssociationDismiss: () -> Unit = {},
) : LinearLayout(context) {
    val toolbarRow = LinearLayout(context)
    val composeZone = LinearLayout(context)
    val composition = EditText(context)
    val candidateField = LinearLayout(context)
    val expandedCaption = TextView(context)
    val candidateRow = LinearLayout(context)
    val candidateScroll = HorizontalScrollView(context)
    val associationRow = LinearLayout(context)
    val candidateExpandButton = ImageView(context)
    val candidateEmojiButton = ImageView(context)
    val voiceInlineZone = LinearLayout(context)
    val voiceInlineIcon = ImageView(context)
    val voiceInlineStatus = TextView(context)
    val voiceInlineWaves = mutableListOf<View>()
    val associationBack = ImageView(context)
    private lateinit var toolbarIcons: List<View>
    private lateinit var compactToolbarIcons: List<View>
    private var compact = false
    private lateinit var keyboardHide: View
    private lateinit var associationScroll: HorizontalScrollView
    private var toolbarMode = ToolbarMode.NORMAL
    private var associationsShown = false
    private var autofillShown = false
    private lateinit var splitIcon: View
    private var splitToggleShown = false
    private var splitActive = false
    /** True while chips occupy the strip (system autofill or the toolbar suggestions). */
    val autofillChipsShown: Boolean get() = autofillShown
    /** Called when the user closes the chip strip with its back control. */
    var onAutofillDismissed: (() -> Unit)? = null

    /** Chips from the system autofill service, hosted in a strip of their own. */
    private lateinit var autofillScroll: HorizontalScrollView
    private val autofillRow = LinearLayout(context)

    /** What the toolbar row shows; icons, autofill chips and associations are exclusive. */
    private enum class ToolbarMode { NORMAL, AUTOFILL, ASSOCIATION }

    private fun idleToolbarMode(): ToolbarMode = when {
        associationsShown -> ToolbarMode.ASSOCIATION
        autofillShown -> ToolbarMode.AUTOFILL
        else -> ToolbarMode.NORMAL
    }

    init {
        tag = "ime_toolbar"
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = toPx(ImeGeometryTokens.COMPOSED_TOP_ZONE_HEIGHT_DP)

        toolbarRow.apply {
            tag = "toolbar-row"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(toPx(6), 0, toPx(6), 0)
            minimumHeight = toPx(ImeGeometryTokens.TOOLBAR_HEIGHT_DP)
        }
        val keyboardIcon = toolbarIcon(R.drawable.ic_keyboard, "切换键盘", "keyboard-selector", onKeyboardSelect)
        val clipboardIcon = toolbarIcon(R.drawable.ic_clipboard, "剪贴板", "clipboard-toolbar", onClipboard)
        val phraseIcon = toolbarIcon(R.drawable.ic_bubble, "常用语", "quick-phrase-toolbar", onQuickPhrases)
        val emojiIcon = toolbarIcon(R.drawable.ic_emoji, "表情", "toolbar", onEmoji)
        val textEditIcon = toolbarIcon(R.drawable.ic_text_cursor, "文本编辑", "toolbar", onTextEditor)
        val undoIcon = toolbarIcon(R.drawable.ic_undo, "撤销", "undo-toolbar") { onUndo() }
        splitIcon = toolbarIcon(R.drawable.ic_keyboard_split, "左右分离键盘", "split-toggle") { onSplitToggle() }
        val toolsIcon = toolbarIcon(R.drawable.ic_grid, "更多", "toolbar", onTools)
        // Docked keeps the full toolbar. Floating (game) mode drops text editing
        // and undo and puts quick phrases one tap away.
        toolbarIcons = listOf(keyboardIcon, clipboardIcon, emojiIcon, textEditIcon, undoIcon, toolsIcon)
        compactToolbarIcons = listOf(keyboardIcon, phraseIcon, emojiIcon, toolsIcon)
        (toolbarIcons + phraseIcon + splitIcon).distinct().forEach {
            toolbarRow.addView(it, LinearLayout.LayoutParams(0, toPx(48), 1f))
        }

        // Association state: "‹  words…  ∨". The back control and the hide
        // control are fixed-width; the words take the rest of the row.
        associationBack.apply {
            tag = "association-back"
            contentDescription = "返回工具栏"
            setImageResource(R.drawable.ic_arrow_back)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onFeedback()
                // The same back control closes whichever strip is open.
                if (toolbarMode == ToolbarMode.AUTOFILL) dismissAutofillChips() else onAssociationDismiss()
            }
        }
        toolbarRow.addView(
            associationBack,
            LinearLayout.LayoutParams(toPx(ImeGeometryTokens.TOUCH_TARGET_DP), toPx(48)),
        )

        associationRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "association-row"
        }
        associationScroll = HorizontalScrollView(context).apply {
            tag = "association-scroll"
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                associationRow,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
                ),
            )
        }
        toolbarRow.addView(
            associationScroll,
            LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f),
        )

        autofillRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "autofill-row"
        }
        autofillScroll = HorizontalScrollView(context).apply {
            tag = "autofill-scroll"
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                autofillRow,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
                ),
            )
        }
        toolbarRow.addView(
            autofillScroll,
            LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f),
        )

        keyboardHide = toolbarIcon(R.drawable.ic_chevron_down, "收起键盘", "keyboard-hide", onHideKeyboard)
        toolbarRow.addView(keyboardHide, LinearLayout.LayoutParams(0, toPx(48), 1f))

        addView(
            toolbarRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.COMPOSED_TOP_ZONE_HEIGHT_DP),
            ),
        )

        composeZone.apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            tag = "compose-zone"
        }
        composition.apply {
            tag = "pinyin-composition-editor"
            contentDescription = "可编辑拼音预编辑"
            textSize = ImeTypographyTokens.BODY_SP
            letterSpacing = 0.04f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
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
            setPadding(toPx(14), toPx(3), toPx(14), 0)
            minimumHeight = toPx(COMPOSITION_LINE_DP)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!isCompositionSyncing()) {
                        onCompositionEdited(s?.toString().orEmpty())
                    }
                }
            })
        }
        composeZone.addView(
            composition,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(COMPOSITION_LINE_DP),
            ),
        )

        candidateField.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "candidate-field"
            setPadding(toPx(6), 0, toPx(0), 0)
        }
        candidateRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        candidateScroll.apply {
            isHorizontalScrollBarEnabled = false
            addView(
                candidateRow,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    toPx(CANDIDATE_ROW_DP),
                ),
            )
        }
        expandedCaption.apply {
            text = "候选字词"; textSize = ImeTypographyTokens.BODY_SP; gravity = Gravity.CENTER_VERTICAL
            setPadding(toPx(10), 0, 0, 0); tag = "panel-note"; visibility = View.GONE
        }
        candidateField.addView(expandedCaption, LinearLayout.LayoutParams(0, toPx(48), 1f))
        candidateField.addView(
            candidateScroll,
            LinearLayout.LayoutParams(0, toPx(CANDIDATE_ROW_DP), 1f),
        )

        candidateEmojiButton.apply {
            tag = "candidate-emoji"
            setImageResource(R.drawable.ic_emoji)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "表情"
            setPadding(toPx(7), 0, toPx(7), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onFeedback()
                onEmoji()
            }
        }
        candidateExpandButton.apply {
            tag = "candidate-expand"
            setImageResource(R.drawable.ic_chevron_down)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "展开更多候选"
            setPadding(toPx(7), 0, toPx(7), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onFeedback()
                onExpandCandidates()
            }
        }
        candidateField.addView(candidateExpandButton, touchTargetParams())
        composeZone.addView(
            candidateField,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(CANDIDATE_ROW_DP),
            ),
        )
        addView(
            composeZone,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.COMPOSED_TOP_ZONE_HEIGHT_DP),
            ),
        )

        voiceInlineZone.apply {
            tag = "voice-inline-zone"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(toPx(12), 0, toPx(12), 0)
        }
        voiceInlineIcon.apply {
            tag = "voice-inline-icon"
            contentDescription = null
            setImageResource(R.drawable.ic_mic)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        voiceInlineZone.addView(
            voiceInlineIcon,
            LinearLayout.LayoutParams(toPx(22), toPx(22)).apply { marginEnd = toPx(9) },
        )
        voiceInlineStatus.apply {
            tag = "voice-inline-status"
            text = "正在聆听"
            textSize = ImeTypographyTokens.BODY_SP
            setTextColor(Color.WHITE)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
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
        repeat(10) { index ->
            val bar = View(context).apply {
                tag = "voice-inline-wave-$index"
                background = ImeDrawableFactory.rounded(
                    Color.WHITE,
                    toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                )
            }
            voiceInlineWaves += bar
            inlineWave.addView(
                bar,
                LinearLayout.LayoutParams(
                    toPx(3),
                    toPx(if (index % 2 == 0) 10 else 16),
                ).apply {
                    if (index > 0) marginStart = toPx(3)
                },
            )
        }
        voiceInlineZone.addView(inlineWave, 0,
            LinearLayout.LayoutParams(toPx(64), LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = toPx(12) })
        voiceInlineIcon.visibility = View.GONE
        voiceInlineZone.addView(TextView(context).apply {
            text = "↑ 上滑取消"
            textSize = ImeTypographyTokens.SMALL_SP
            gravity = Gravity.CENTER
            tag = "voice-cancel-hint"
            includeFontPadding = false
        }, LinearLayout.LayoutParams(toPx(88), toPx(30)))
        addView(
            voiceInlineZone,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(54),
            ).apply {
                setMargins(toPx(8), toPx(8), toPx(8), toPx(8))
            },
        )
        // The quick-phrase icon exists for floating mode only; settle the
        // initial docked state instead of waiting for the first toolbar change.
        refreshToolbar()
    }

    fun showAssociations(show: Boolean) {
        associationsShown = show
        toolbarMode = idleToolbarMode()
        refreshToolbar()
    }

    /**
     * Shows the autofill [chips] (already inflated by the system) in the toolbar
     * row, or the normal toolbar again when the list is empty. Associations keep
     * priority for as long as they are showing.
     */
    fun setAutofillChips(chips: List<View>, chipWidthPx: Int = 0, chipHeightPx: Int = 0) {
        autofillRow.removeAllViews()
        chips.forEach { chip ->
            (chip.parent as? ViewGroup)?.removeView(chip)
            autofillRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    if (chipWidthPx > 0) chipWidthPx else LinearLayout.LayoutParams.WRAP_CONTENT,
                    if (chipHeightPx > 0) chipHeightPx else LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginEnd = toPx(8)
                },
            )
        }
        autofillShown = chips.isNotEmpty()
        autofillScroll.scrollTo(0, 0)
        toolbarMode = idleToolbarMode()
        refreshToolbar()
    }

    /** Back control of the autofill strip: hide the chips until the next response. */
    private fun dismissAutofillChips() {
        onAutofillDismissed?.invoke()
        autofillRow.removeAllViews()
        autofillShown = false
        toolbarMode = idleToolbarMode()
        refreshToolbar()
    }

    private var longPressDrag: FloatingDragController? = null
    private var longPressArmed = false
    private var longPressDragging = false
    private var pressRawX = 0f
    private var pressRawY = 0f
    private val longPressRunnable = Runnable {
        longPressArmed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }

    /**
     * Floating mode: long-press anywhere on the top zone, then drag, to move the
     * keyboard. Taps and scrolls on toolbar icons and candidates are untouched.
     */
    fun setLongPressDrag(controller: FloatingDragController?) {
        longPressDrag = controller
        resetLongPress()
    }

    private fun resetLongPress() {
        removeCallbacks(longPressRunnable)
        longPressArmed = false
        longPressDragging = false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val drag = longPressDrag ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetLongPress()
                pressRawX = ev.rawX
                pressRawY = ev.rawY
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                if (longPressArmed) {
                    drag.begin(ev)
                    longPressDragging = true
                    return true
                }
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (kotlin.math.abs(ev.rawX - pressRawX) > slop ||
                    kotlin.math.abs(ev.rawY - pressRawY) > slop
                ) {
                    removeCallbacks(longPressRunnable)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // A long press that never moved is not a tap on the icon under it.
                val swallow = longPressArmed
                resetLongPress()
                return swallow
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val drag = longPressDrag
        if (drag == null || !longPressDragging) return super.onTouchEvent(event)
        drag.onTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            resetLongPress()
        }
        return true
    }

    /** Floating (game) mode: a slimmer toolbar without text editing and undo. */
    /**
     * Landscape strip: the zone is one key row plus a little, and while composing
     * the pinyin sits at the left of the candidates instead of above them.
     */
    fun setLandscapeStrip(on: Boolean) {
        val height = toPx(
            if (on) ImeGeometryTokens.LANDSCAPE_TOP_ZONE_HEIGHT_DP else ImeGeometryTokens.COMPOSED_TOP_ZONE_HEIGHT_DP,
        )
        minimumHeight = height
        toolbarRow.minimumHeight = minOf(height, toPx(ImeGeometryTokens.TOOLBAR_HEIGHT_DP))
        (toolbarRow.layoutParams as? LinearLayout.LayoutParams)?.let {
            if (it.height != height) { it.height = height; toolbarRow.layoutParams = it }
        }
        (composeZone.layoutParams as? LinearLayout.LayoutParams)?.let {
            if (it.height != height) { it.height = height; composeZone.layoutParams = it }
        }
        composeZone.orientation = if (on) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        composeZone.gravity = Gravity.CENTER_VERTICAL
        (composition.layoutParams as? LinearLayout.LayoutParams)?.let {
            if (on) {
                it.width = LinearLayout.LayoutParams.WRAP_CONTENT
                it.height = LinearLayout.LayoutParams.MATCH_PARENT
            } else {
                it.width = LinearLayout.LayoutParams.MATCH_PARENT
                it.height = toPx(COMPOSITION_LINE_DP)
            }
            composition.layoutParams = it
        }
        composition.maxWidth = if (on) toPx(160) else Int.MAX_VALUE
        composition.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        (candidateField.layoutParams as? LinearLayout.LayoutParams)?.let {
            if (on) {
                it.width = 0
                it.weight = 1f
                it.height = LinearLayout.LayoutParams.MATCH_PARENT
            } else {
                it.width = LinearLayout.LayoutParams.MATCH_PARENT
                it.weight = 0f
                it.height = toPx(CANDIDATE_ROW_DP)
            }
            candidateField.layoutParams = it
        }
    }

    /** Landscape docked keyboards get Sogou's toolbar button that splits or rejoins the keyboard. */
    fun setSplitToggle(visible: Boolean, active: Boolean) {
        splitToggleShown = visible
        splitActive = active
        splitIcon.contentDescription = if (active) "恢复普通键盘" else "左右分离键盘"
        splitIcon.alpha = if (active) 1f else 0.72f
        refreshToolbar()
    }

    fun setCompactToolbar(value: Boolean) {
        if (compact == value) return
        compact = value
        refreshToolbar()
    }

    private fun refreshToolbar() {
        val normal = toolbarMode == ToolbarMode.NORMAL
        val association = toolbarMode == ToolbarMode.ASSOCIATION
        val autofill = toolbarMode == ToolbarMode.AUTOFILL
        val shown = if (compact) compactToolbarIcons else toolbarIcons
        (toolbarIcons + compactToolbarIcons).distinct().forEach {
            it.visibility = if (normal && it in shown) View.VISIBLE else View.GONE
        }
        splitIcon.visibility = if (normal && splitToggleShown) View.VISIBLE else View.GONE
        associationBack.visibility = if (association || autofill) View.VISIBLE else View.GONE
        associationScroll.visibility = if (association) View.VISIBLE else View.GONE
        autofillScroll.visibility = if (autofill) View.VISIBLE else View.GONE
        keyboardHide.visibility = View.VISIBLE
        (keyboardHide.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            if (association || autofill) {
                params.width = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
                params.weight = 0f
            } else {
                params.width = 0
                params.weight = 1f
            }
            keyboardHide.layoutParams = params
        }
    }

    fun setContentInset(contentInsetPx: Int) {
        toolbarRow.setPadding(contentInsetPx + toPx(6), 0, contentInsetPx + toPx(6), 0)
        composition.setPadding(contentInsetPx + toPx(14), toPx(3), contentInsetPx + toPx(14), 0)
    }

    fun renderState(
        state: ImeTopZoneState,
        showCompositionEditor: Boolean,
    ) {
        val composing = state == ImeTopZoneState.COMPOSING ||
            state == ImeTopZoneState.CANDIDATE_EXPANDED
        toolbarRow.visibility = if (state == ImeTopZoneState.IDLE) View.VISIBLE else View.GONE
        composeZone.visibility = if (composing) View.VISIBLE else View.GONE
        voiceInlineZone.visibility = if (state == ImeTopZoneState.VOICE_INLINE) View.VISIBLE else View.GONE
        composition.visibility = if (composing && showCompositionEditor) View.VISIBLE else View.GONE
        candidateField.visibility = if (composing) View.VISIBLE else View.GONE
        val expanded = state == ImeTopZoneState.CANDIDATE_EXPANDED
        candidateScroll.visibility = if (expanded) View.GONE else View.VISIBLE
        expandedCaption.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun toolbarIcon(
        iconRes: Int,
        description: String,
        tagValue: String,
        onTap: () -> Unit,
    ): ImageView = ImageView(context).apply {
        contentDescription = description
        tag = tagValue
        minimumWidth = 0
        minimumHeight = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setImageResource(iconRes)
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener {
            onFeedback()
            onTap()
        }
    }

    private fun touchTargetParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
        )
}
