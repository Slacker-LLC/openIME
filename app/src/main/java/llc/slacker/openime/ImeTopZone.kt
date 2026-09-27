package llc.slacker.openime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

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
    onHideKeyboard: () -> Unit,
    onTools: () -> Unit,
    onExpandCandidates: () -> Unit,
) : LinearLayout(context) {
    val toolbarRow = LinearLayout(context)
    val composeZone = LinearLayout(context)
    val composition = EditText(context)
    val candidateField = LinearLayout(context)
    val candidateRow = LinearLayout(context)
    val candidateScroll = HorizontalScrollView(context)
    val associationRow = LinearLayout(context)
    val candidateExpandButton = TextView(context)
    val candidateEmojiButton = TextView(context)
    val voiceInlineZone = LinearLayout(context)
    val voiceInlineIcon = ImageView(context)
    val voiceInlineStatus = TextView(context)
    val voiceInlineWaves = mutableListOf<View>()

    init {
        tag = "ime_toolbar"
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = toPx(ImeGeometryTokens.COMPOSED_TOP_ZONE_HEIGHT_DP)

        toolbarRow.apply {
            tag = "toolbar-row"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(toPx(10), 0, toPx(10), 0)
            minimumHeight = toPx(ImeGeometryTokens.TOOLBAR_HEIGHT_DP)
        }
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_grid, "切换键盘", "keyboard-selector", onKeyboardSelect),
            touchTargetParams(),
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_clipboard, "剪贴板", "clipboard-toolbar", onClipboard),
            touchTargetParams(),
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_emoji, "表情", "toolbar", onEmoji),
            touchTargetParams(),
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_symbols, "符号", "toolbar", onSymbols),
            touchTargetParams(),
        )

        associationRow.apply {
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
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
                ),
            )
        }
        toolbarRow.addView(
            associationScroll,
            LinearLayout.LayoutParams(
                0,
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
                1f,
            ).apply { marginStart = toPx(4) },
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_keyboard_hide, "收起键盘", "keyboard-hide", onHideKeyboard),
            touchTargetParams(),
        )
        toolbarRow.addView(
            toolbarIcon(R.drawable.ic_more, "更多", "toolbar", onTools),
            touchTargetParams(),
        )
        addView(
            toolbarRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.TOOLBAR_HEIGHT_DP),
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
            setPadding(toPx(8), 0, toPx(8), 0)
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
        candidateField.addView(
            candidateScroll,
            LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f),
        )

        candidateEmojiButton.apply {
            tag = "candidate-emoji"
            text = "☺"
            textSize = 17f
            gravity = Gravity.CENTER
            contentDescription = "表情"
            setPadding(toPx(7), 0, toPx(7), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onFeedback()
                onEmoji()
            }
        }
        candidateField.addView(candidateEmojiButton, touchTargetParams())

        candidateExpandButton.apply {
            tag = "candidate-expand"
            text = "⌄"
            textSize = 15f
            gravity = Gravity.CENTER
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
        candidateField.addView(
            toolbarIcon(
                R.drawable.ic_keyboard_hide,
                "收起键盘",
                "keyboard-hide-composing",
                onHideKeyboard,
            ),
            touchTargetParams(),
        )
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
            text = "正在聆听…"
            textSize = 14f
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
        repeat(6) { index ->
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
        voiceInlineZone.addView(
            inlineWave,
            LinearLayout.LayoutParams(toPx(42), LinearLayout.LayoutParams.MATCH_PARENT),
        )
        addView(
            voiceInlineZone,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ).apply {
                setMargins(toPx(8), toPx(8), toPx(8), toPx(8))
            },
        )
    }

    fun setContentInset(contentInsetPx: Int) {
        toolbarRow.setPadding(contentInsetPx + toPx(10), 0, contentInsetPx + toPx(10), 0)
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
    }

    private fun toolbarIcon(
        iconRes: Int,
        description: String,
        tagValue: String,
        onTap: () -> Unit,
    ): ImageView = ImageView(context).apply {
        contentDescription = description
        tag = tagValue
        minimumWidth = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
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
