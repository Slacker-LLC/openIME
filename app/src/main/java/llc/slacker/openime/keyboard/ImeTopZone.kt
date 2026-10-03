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
    private val onUndoClear: () -> Unit,
    private val onUndoClearExpired: () -> Unit,
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
    val undoBanner = LinearLayout(context)
    private val undoBannerLabel = TextView(context)
    private val undoBannerAction = TextView(context)
    private lateinit var toolbarIcons: List<View>
    private lateinit var compactToolbarIcons: List<View>
    private var compact = false
    private lateinit var keyboardHide: View
    private lateinit var associationScroll: HorizontalScrollView
    private var toolbarMode = ToolbarMode.NORMAL
    private var associationsShown = false

    /** What the toolbar row shows; icons, associations and the undo banner are exclusive. */
    private enum class ToolbarMode { NORMAL, ASSOCIATION, UNDO }
    private val hideUndoClearRunnable = Runnable {
        if (toolbarRow.findViewWithTag<View>("undo-toolbar")?.isActivated == true) {
            toolbarRow.findViewWithTag<View>("undo-toolbar")?.isActivated = false
            onUndoClearExpired()
        }
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
        val undoIcon = toolbarIcon(R.drawable.ic_undo, "撤销", "undo-toolbar") { onUndoClear() }
        val toolsIcon = toolbarIcon(R.drawable.ic_grid, "更多", "toolbar", onTools)
        // Docked keeps the full toolbar. Floating (game) mode drops text editing
        // and undo and puts quick phrases one tap away.
        toolbarIcons = listOf(keyboardIcon, clipboardIcon, emojiIcon, textEditIcon, undoIcon, toolsIcon)
        compactToolbarIcons = listOf(keyboardIcon, phraseIcon, emojiIcon, toolsIcon)
        (toolbarIcons + phraseIcon).distinct().forEach {
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
                onAssociationDismiss()
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

        keyboardHide = toolbarIcon(R.drawable.ic_chevron_down, "收起键盘", "keyboard-hide", onHideKeyboard)
        toolbarRow.addView(keyboardHide, LinearLayout.LayoutParams(0, toPx(48), 1f))

        // After a clear-all: "已清空        [撤销]" for the undo window.
        undoBanner.apply {
            tag = "undo-banner"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(toPx(16), 0, toPx(8), 0)
        }
        undoBannerLabel.apply {
            text = "已清空"
            textSize = ImeTypographyTokens.BODY_SP
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        undoBannerAction.apply {
            tag = "undo-clear-action"
            text = "撤销"
            textSize = ImeTypographyTokens.BODY_SP
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = toPx(72)
            setPadding(toPx(16), 0, toPx(16), 0)
            isClickable = true
            isFocusable = true
            contentDescription = "撤销清空"
            setOnClickListener {
                removeCallbacks(hideUndoClearRunnable)
                hideUndoClear()
                onFeedback()
                onUndoClear()
            }
        }
        undoBanner.addView(undoBannerLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        undoBanner.addView(undoBannerAction, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, toPx(40)))
        toolbarRow.addView(undoBanner, LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f))
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
            minimumHeight = toPx(22)
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
                toPx(22),
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
                    toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
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
            LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f),
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
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
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
        if (toolbarMode != ToolbarMode.UNDO) {
            toolbarMode = if (show) ToolbarMode.ASSOCIATION else ToolbarMode.NORMAL
        }
        refreshToolbar()
    }

    fun showUndoClear() {
        removeCallbacks(hideUndoClearRunnable)
        toolbarRow.findViewWithTag<View>("undo-toolbar")?.isActivated = true
        toolbarMode = ToolbarMode.UNDO
        refreshToolbar()
        postDelayed(hideUndoClearRunnable, CLEAR_UNDO_VISIBLE_MS)
    }

    fun hideUndoClear(discardSnapshot: Boolean = false) {
        removeCallbacks(hideUndoClearRunnable)
        val wasVisible = toolbarRow.findViewWithTag<View>("undo-toolbar")?.isActivated == true
        toolbarRow.findViewWithTag<View>("undo-toolbar")?.isActivated = false
        if (toolbarMode == ToolbarMode.UNDO) {
            toolbarMode = if (associationsShown) ToolbarMode.ASSOCIATION else ToolbarMode.NORMAL
            refreshToolbar()
        }
        if (discardSnapshot && wasVisible) onUndoClearExpired()
    }

    private var longPressDrag: FloatingDragController? = null
    private var longPressArmed = false
    private var longPressDragging = false
    private var pressRawX = 0f
    private var pressRawY = 0f
    private val longPressRunnable = Runnable {
        longPressArmed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
    fun setCompactToolbar(value: Boolean) {
        if (compact == value) return
        compact = value
        refreshToolbar()
    }

    private fun refreshToolbar() {
        val normal = toolbarMode == ToolbarMode.NORMAL
        val association = toolbarMode == ToolbarMode.ASSOCIATION
        val shown = if (compact) compactToolbarIcons else toolbarIcons
        (toolbarIcons + compactToolbarIcons).distinct().forEach {
            it.visibility = if (normal && it in shown) View.VISIBLE else View.GONE
        }
        associationBack.visibility = if (association) View.VISIBLE else View.GONE
        associationScroll.visibility = if (association) View.VISIBLE else View.GONE
        undoBanner.visibility = if (toolbarMode == ToolbarMode.UNDO) View.VISIBLE else View.GONE
        keyboardHide.visibility = if (toolbarMode == ToolbarMode.UNDO) View.GONE else View.VISIBLE
        (keyboardHide.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            if (association) {
                params.width = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
                params.weight = 0f
            } else {
                params.width = 0
                params.weight = 1f
            }
            keyboardHide.layoutParams = params
        }
    }

    /** Colour the undo banner from the active tokens (called with every theme pass). */
    fun applyTokens(t: ImeTheme.Tokens) {
        undoBannerLabel.setTextColor(t.keyText)
        undoBannerAction.setTextColor(ImeSurfacePolicy.selectedText(t))
        undoBannerAction.background = ImeDrawableFactory.rounded(
            ImeSurfacePolicy.subtleAccentSurface(t),
            toPx(ImeGeometryTokens.PILL_RADIUS_DP),
        )
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
        if (composing && toolbarMode == ToolbarMode.UNDO) hideUndoClear()
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

    private companion object {
        const val CLEAR_UNDO_VISIBLE_MS = 5_000L
    }
}
