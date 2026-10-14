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
import llc.slacker.openime.floating.LongPressDragTarget
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

/**
 * The reference's candidate strip is `[4] 候选列表(308) [1] [3] 更多候选(40) [4]`
 * (`input_toolbar_typing.xml:16-30`, weights from `style.xml:530-534`, 506 and 468):
 * a small inset at each end, a hairline split before the expand key and a 3-unit
 * gap ahead of it.
 */
private const val CANDIDATE_EDGE_UNITS = 4
private const val CANDIDATE_BUTTON_GAP_UNITS = 3

/**
 * One idle-toolbar button: `cls_toolbar_idle_button` is 40 units wide and as
 * tall as the row, with its own pill face (`style.xml:237-248`).
 */
private const val TOOLBAR_BUTTON_UNITS = 40

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
    private lateinit var toolbarIconScroll: HorizontalScrollView
    private val toolbarStripSpacer = View(context)
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
    private lateinit var autofillClose: ImageView
    /** True while the strip carries this app's own suggestion rows rather than the system's chips. */
    private var autofillOwn = false

    /**
     * The symbol history the symbol board shows in the toolbar
     * (`input_toolbar_quickinput.xml:4-18`, see [RecentSymbolStripPolicy]).
     */
    private val recentSymbolStrip = LinearLayout(context)
    private val recentSymbolRow = LinearLayout(context)
    private lateinit var recentSymbolClose: ImageView
    private var recentSymbolsShown = false

    /**
     * The tip banner the reference shows in the toolbar row while the floating
     * keyboard is up (`input_toolbar_tip.xml:43-50`, see [ToolbarTipPolicy]).
     */
    private val tipStrip = LinearLayout(context)
    private val tipLead = View(context)
    private lateinit var tipLabel: TextView
    private lateinit var tipClose: ImageView
    private var tipShown = false
    /** True while the zone draws the landscape strip, where the pre-edit sits beside the candidates. */
    private var landscapeStrip = false

    /**
     * What the toolbar row shows; icons, autofill chips, associations, the
     * symbol history and the tip are exclusive. The history takes the row while
     * the symbol board is open, as the reference's own symbol toolbar does; the
     * tip gives to every strip that has something to say while the user types.
     */
    private enum class ToolbarMode { NORMAL, AUTOFILL, ASSOCIATION, RECENT_SYMBOLS, TIP }

    private fun idleToolbarMode(): ToolbarMode = when {
        recentSymbolsShown -> ToolbarMode.RECENT_SYMBOLS
        associationsShown -> ToolbarMode.ASSOCIATION
        autofillShown -> ToolbarMode.AUTOFILL
        tipShown -> ToolbarMode.TIP
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
        val clipboardIcon = toolbarIcon(R.drawable.ic_clipboard, "剪贴板", "clipboard-toolbar", onClipboard)
        val emojiIcon = toolbarIcon(R.drawable.ic_emoji, "表情", "toolbar", onEmoji)
        val phraseIcon = toolbarIcon(R.drawable.ic_bubble, "常用语", "quick-phrase-toolbar", onQuickPhrases)
        val keyboardIcon = toolbarIcon(R.drawable.ic_keyboard, "切换键盘", "keyboard-selector", onKeyboardSelect)
        val textEditIcon = toolbarIcon(R.drawable.ic_text_cursor, "文本编辑", "toolbar", onTextEditor)
        val undoIcon = toolbarIcon(R.drawable.ic_undo, "撤销", "undo-toolbar") { onUndo() }
        splitIcon = toolbarIcon(R.drawable.ic_keyboard_split, "左右分离键盘", "split-toggle") { onSplitToggle() }
        val toolsIcon = toolbarIcon(R.drawable.ic_grid, "更多", "toolbar", onTools)
        // The reference's own idle order, for the tools we show: clipboard,
        // emoji, then the board selector (`input_toolbar_idle.xml:21-25`). Our
        // own extras follow them. Floating (game) mode keeps the compact set,
        // where quick phrases take the clipboard's place.
        toolbarIcons = listOf(
            clipboardIcon,
            emojiIcon,
            keyboardIcon,
            textEditIcon,
            undoIcon,
            toolsIcon,
        )
        compactToolbarIcons = listOf(emojiIcon, phraseIcon, keyboardIcon, toolsIcon)

        // The strip scrolls when the tools do not fit, as the reference's own
        // toolbar strip does (`sliderenable="true" sliderstopslowdown="true"`,
        // `input_toolbar_idle.xml:14`), and its slack sits in a spacer at the end
        // of the strip (`toolbar_right_spacer`, line 34).
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "toolbar-icon-strip"
        }
        (toolbarIcons + compactToolbarIcons + splitIcon).distinct().forEach {
            strip.addView(it, LinearLayout.LayoutParams(toPx(TOOLBAR_BUTTON_UNITS), toPx(48)))
        }
        toolbarStripSpacer.apply { tag = "toolbar-strip-spacer" }
        strip.addView(toolbarStripSpacer, LinearLayout.LayoutParams(0, 1, 1f))
        toolbarIconScroll = HorizontalScrollView(context).apply {
            tag = "toolbar-icon-scroll"
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = true
            addView(
                strip,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    toPx(48),
                ),
            )
        }
        toolbarRow.addView(toolbarIconScroll, LinearLayout.LayoutParams(0, toPx(48), 1f))

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
        // Our own quick-input rows end with the reference's own close key
        // (`btn_quickinput_close` over `cls_history_close_button`, the 40-unit box
        // the symbol history's close uses; `input_toolbar_quickinput.xml:45-46` for
        // the clipboard row, `:31-32` for the message row). The system's autofill
        // chips keep the strip's back control instead.
        autofillClose = ImageView(context).apply {
            tag = "autofill-close"
            setImageResource(R.drawable.ic_close)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "关闭建议"
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onFeedback()
                dismissAutofillChips()
            }
        }
        toolbarRow.addView(autofillClose, touchTargetParams())

        // The reference keeps the icons at a fixed 40 units and leaves the slack
        // of the strip empty: `btn_hide` stays its own button on the right
        // (`input_toolbar_idle.xml:41-43`, `cls_toolbar_idle_button` width 40,
        // `style.xml:237-248`).
        buildRecentSymbolStrip()
        buildTipStrip()
        keyboardHide = toolbarIcon(R.drawable.ic_chevron_down, "收起键盘", "keyboard-hide", onHideKeyboard)
        toolbarRow.addView(
            keyboardHide,
            LinearLayout.LayoutParams(toPx(TOOLBAR_BUTTON_UNITS), toPx(48)),
        )

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
            textSize = ImeTypographyTokens.COMPOSITION_SP
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
            // The block's own insets: a 5-unit lead and the row's 3-unit top
            // (`input_toolbar_typing.xml:5,7`); the row leaves its right side to
            // the surface beside it (line 18) and the string scrolls inside it.
            setPadding(toPx(CompositionPolicy.LEAD_UNITS), toPx(3), 0, 0)
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
            setPadding(0, 0, 0, 0)
        }
        // The reference's strip opens with a 4-unit inset.
        candidateField.addView(
            View(context).apply { tag = "candidate-strip-lead" },
            LinearLayout.LayoutParams(toPx(CANDIDATE_EDGE_UNITS), LinearLayout.LayoutParams.MATCH_PARENT),
        )
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
        // The hairline split sits between the list and the expand key, and the
        // expand key keeps a 3-unit gap from it.
        candidateField.addView(
            View(context).apply { tag = "candidate-strip-split" },
            LinearLayout.LayoutParams(toPx(1).coerceAtLeast(1), LinearLayout.LayoutParams.MATCH_PARENT),
        )
        candidateField.addView(
            View(context).apply { tag = "candidate-strip-gap" },
            LinearLayout.LayoutParams(
                toPx(CANDIDATE_BUTTON_GAP_UNITS),
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        candidateField.addView(candidateExpandButton, touchTargetParams())
        candidateField.addView(
            View(context).apply { tag = "candidate-strip-tail" },
            LinearLayout.LayoutParams(toPx(CANDIDATE_EDGE_UNITS), LinearLayout.LayoutParams.MATCH_PARENT),
        )
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
     * priority for as long as they are showing. [own] marks the rows this app
     * built for its own suggestions, which close with the reference's own key.
     */
    fun setAutofillChips(
        chips: List<View>,
        chipWidthPx: Int = 0,
        chipHeightPx: Int = 0,
        own: Boolean = false,
    ) {
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
        autofillOwn = chips.isNotEmpty() && own
        autofillScroll.scrollTo(0, 0)
        toolbarMode = idleToolbarMode()
        refreshToolbar()
    }

    /** Back control of the autofill strip: hide the chips until the next response. */
    private fun dismissAutofillChips() {
        onAutofillDismissed?.invoke()
        autofillRow.removeAllViews()
        autofillShown = false
        autofillOwn = false
        toolbarMode = idleToolbarMode()
        refreshToolbar()
    }

    /**
     * Lays out the symbol history as the reference's symbol toolbar does: a
     * 4-unit lead inset, the scrolling list of the symbols the user has typed,
     * the same hairline split its candidate strip uses, a 3-unit gap, the close
     * key and a 4-unit tail (`input_toolbar_quickinput.xml:4-18`). The close key
     * keeps our 48dp touch target where the reference's box is 40 units.
     */
    private fun buildRecentSymbolStrip() {
        recentSymbolStrip.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "recent-symbol-strip"
            visibility = View.GONE
        }
        recentSymbolStrip.addView(
            View(context).apply { tag = "recent-symbol-lead" },
            LinearLayout.LayoutParams(
                toPx(RecentSymbolStripPolicy.LEAD_UNITS),
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        recentSymbolRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "recent-symbol-row"
        }
        recentSymbolStrip.addView(
            HorizontalScrollView(context).apply {
                tag = "recent-symbol-scroll"
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(
                    recentSymbolRow,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
                    ),
                )
            },
            LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f),
        )
        recentSymbolStrip.addView(
            View(context).apply { tag = "recent-symbol-split" },
            LinearLayout.LayoutParams(toPx(1).coerceAtLeast(1), LinearLayout.LayoutParams.MATCH_PARENT),
        )
        recentSymbolStrip.addView(
            View(context).apply { tag = "recent-symbol-gap" },
            LinearLayout.LayoutParams(
                toPx(RecentSymbolStripPolicy.GAP_UNITS),
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        recentSymbolClose = ImageView(context).apply {
            tag = "recent-symbol-close"
            setImageResource(R.drawable.ic_close)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "关闭最近符号"
            setPadding(toPx(7), 0, toPx(7), 0)
            isClickable = true
            isFocusable = true
        }
        recentSymbolStrip.addView(recentSymbolClose, touchTargetParams())
        recentSymbolStrip.addView(
            View(context).apply { tag = "recent-symbol-tail" },
            LinearLayout.LayoutParams(
                toPx(RecentSymbolStripPolicy.TAIL_UNITS),
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        toolbarRow.addView(recentSymbolStrip, LinearLayout.LayoutParams(0, toPx(48)))
    }

    /**
     * Shows [symbols] — the symbols the user has typed, most recent first — in
     * the toolbar row, or the normal toolbar when there are none. [onClose]
     * ends the strip's own visit: the board stays open.
     */
    fun showRecentSymbols(
        symbols: List<String>,
        onPick: (String) -> Unit,
        onClose: () -> Unit,
    ) {
        recentSymbolRow.removeAllViews()
        symbols.forEachIndexed { index, symbol ->
            recentSymbolRow.addView(
                recentSymbolCell(symbol, onPick),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ).apply {
                    if (index < symbols.lastIndex) {
                        marginEnd = toPx(RecentSymbolStripPolicy.CELL_GAP_UNITS)
                    }
                },
            )
        }
        recentSymbolClose.setOnClickListener {
            onFeedback()
            onClose()
        }
        recentSymbolsShown = symbols.isNotEmpty()
        toolbarMode = idleToolbarMode()
        refreshToolbar()
    }

    /** Takes the symbol history out of the toolbar row (panel closed, or its close key tapped). */
    fun hideRecentSymbols() {
        if (!recentSymbolsShown) return
        recentSymbolsShown = false
        toolbarMode = idleToolbarMode()
        refreshToolbar()
    }

    /**
     * Lays out the reference's tip banner: one pill across the toolbar row, a
     * lead space, the label at the left of the rest and a round close key
     * (`input_toolbar_tip.xml:43-50`, `style.xml:442`, `:1265-1313`). The strip
     * is the row's full 48 units and the pill is painted inside it, as
     * [ToolbarTipPolicy.PILL_UNITS] describes.
     */
    private fun buildTipStrip() {
        tipStrip.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "toolbar-tip"
            visibility = View.GONE
        }
        tipLead.apply { tag = "toolbar-tip-lead" }
        tipStrip.addView(
            tipLead,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT),
        )
        tipLabel = TextView(context).apply {
            tag = "toolbar-tip-label"
            textSize = ImeTypographyTokens.BODY_SP
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
            isClickable = false
        }
        tipStrip.addView(
            tipLabel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )
        tipClose = ImageView(context).apply {
            tag = "toolbar-tip-close"
            setImageResource(R.drawable.ic_close)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "关闭提示"
            isClickable = true
            isFocusable = true
        }
        tipStrip.addView(tipClose, touchTargetParams())
        toolbarRow.addView(tipStrip, LinearLayout.LayoutParams(0, toPx(48)))
    }

    /**
     * Shows the tip banner with [text] in the toolbar row, or the normal toolbar
     * again when [hideTooltip] runs. The label is left-aligned and ellipsized
     * (`cls_toolbar_tip_label`, `style.xml:1267-1270`), and the close key is the
     * same 48dp touch target as every other toolbar control.
     */
    fun showTooltip(text: String, onClose: () -> Unit) {
        tipLabel.text = text
        tipClose.setOnClickListener {
            onFeedback()
            onClose()
        }
        val changed = !tipShown
        tipShown = true
        if (changed) {
            toolbarMode = idleToolbarMode()
            refreshToolbar()
        }
    }

    /** Takes the tip banner out of the toolbar row (floating mode ended, or its close key tapped). */
    fun hideTooltip() {
        if (!tipShown) return
        tipShown = false
        toolbarMode = idleToolbarMode()
        refreshToolbar()
    }

    /**
     * The banner's own fractions of the row width: the container's 3.333%
     * horizontal padding, its 3.571% lead space and the 1.2% the label keeps
     * before its text (`style.xml:442`, `:1265`, `:1278`).
     */
    private fun applyTipGeometry(rowWidth: Int) {
        fun units(fraction: Float): Int = (rowWidth * fraction).toInt().coerceAtLeast(0)
        val pad = units(ToolbarTipPolicy.CONTAINER_PAD_FRACTION)
        tipStrip.setPadding(pad, 0, pad, 0)
        (tipLead.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = units(ToolbarTipPolicy.LEAD_WEIGHT)
            tipLead.layoutParams = params
        }
        tipLabel.setPadding(units(ToolbarTipPolicy.LABEL_PAD_FRACTION), 0, 0, 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) {
            applyTipGeometry(w)
            applyCompositionGeometry(w)
        }
    }

    /**
     * The pre-edit's own cap: half the page (`maxwidth="halfMaxWidth"` on the
     * composition's wrapper, `input_toolbar_typing.xml:7`). The row above the
     * candidates gives the other half back to the surface beside it, and a long
     * string scrolls inside the cap the way the reference's own block does
     * (`sliderenable="true"` on `cls_comp_container`, `style.xml:575-577`).
     */
    private fun compositionMaxWidthPx(): Int = CompositionPolicy.maxWidthPx(
        if (width > 0) width else resources.displayMetrics.widthPixels,
    )

    private fun applyCompositionGeometry(zoneWidth: Int) {
        val cap = CompositionPolicy.maxWidthPx(zoneWidth)
        composition.maxWidth = cap
        (composition.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            val desired = if (landscapeStrip) LinearLayout.LayoutParams.WRAP_CONTENT else cap
            if (params.width != desired) {
                params.width = desired
                composition.layoutParams = params
            }
        }
    }

    /** One history cell: the reference's 36-unit box over candidate-sized text. */
    private fun recentSymbolCell(symbol: String, onPick: (String) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            tag = "recent-symbol-cell"
            isClickable = true
            isFocusable = true
            minimumWidth = toPx(RecentSymbolStripPolicy.CELL_UNITS)
            contentDescription = "最近符号:$symbol"
            addView(
                TextView(context).apply {
                    text = symbol
                    textSize = ImeTypographyTokens.CANDIDATE_SP
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                    tag = "recent-symbol-label"
                    isClickable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setPadding(
                        toPx(RecentSymbolStripPolicy.CELL_PAD_DP),
                        0,
                        toPx(RecentSymbolStripPolicy.CELL_PAD_DP),
                        0,
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            setOnClickListener {
                onFeedback()
                onPick(symbol)
            }
        }

    private var longPressDrag: LongPressDragTarget? = null
    /** 停靠键盘的高度调节只允许在顶栏范围起手，避免抢候选/输入区的长按。 */
    private var longPressToolbarOnly = false
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
    fun setLongPressDrag(controller: LongPressDragTarget?, toolbarOnly: Boolean = false) {
        longPressDrag = controller
        longPressToolbarOnly = toolbarOnly
        resetLongPress()
    }

    private fun resetLongPress() {
        removeCallbacks(longPressRunnable)
        longPressArmed = false
        longPressDragging = false
    }

    /** 手指是否落在顶栏（工具条）范围内；用窗口坐标比较，兼容嵌套层级。 */
    private fun isInsideToolbar(rawX: Float, rawY: Float): Boolean {
        if (toolbarRow.width == 0 || toolbarRow.height == 0) return false
        val location = IntArray(2)
        toolbarRow.getLocationInWindow(location)
        return rawX >= location[0] && rawX <= location[0] + toolbarRow.width &&
            rawY >= location[1] && rawY <= location[1] + toolbarRow.height
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val drag = longPressDrag ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetLongPress()
                if (longPressToolbarOnly && !isInsideToolbar(ev.rawX, ev.rawY)) return false
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
        landscapeStrip = on
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
                it.width = compositionMaxWidthPx()
                it.height = toPx(COMPOSITION_LINE_DP)
            }
            composition.layoutParams = it
        }
        composition.maxWidth = compositionMaxWidthPx()
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
        val recent = toolbarMode == ToolbarMode.RECENT_SYMBOLS
        val tip = toolbarMode == ToolbarMode.TIP
        val shown = if (compact) compactToolbarIcons else toolbarIcons
        (toolbarIcons + compactToolbarIcons).distinct().forEach {
            it.visibility = if (normal && it in shown) View.VISIBLE else View.GONE
        }
        splitIcon.visibility = if (normal && splitToggleShown) View.VISIBLE else View.GONE
        associationBack.visibility =
            if (association || (autofill && !autofillOwn)) View.VISIBLE else View.GONE
        autofillClose.visibility = if (autofill && autofillOwn) View.VISIBLE else View.GONE
        associationScroll.visibility = if (association) View.VISIBLE else View.GONE
        autofillScroll.visibility = if (autofill) View.VISIBLE else View.GONE
        recentSymbolStrip.visibility = if (recent) View.VISIBLE else View.GONE
        tipStrip.visibility = if (tip) View.VISIBLE else View.GONE
        keyboardHide.visibility = View.VISIBLE
        // The icons keep the reference's own width and their own row takes the
        // slack; a suggestion strip, the autofill chips or the symbol history
        // take it instead.
        (toolbarIconScroll.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            val flexible = normal
            params.width = 0
            params.weight = if (flexible) 1f else 0f
            toolbarIconScroll.layoutParams = params
        }
        (recentSymbolStrip.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = 0
            params.weight = if (recent) 1f else 0f
            recentSymbolStrip.layoutParams = params
        }
        (tipStrip.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = 0
            params.weight = if (tip) 1f else 0f
            tipStrip.layoutParams = params
        }
    }

    fun setContentInset(contentInsetPx: Int) {
        toolbarRow.setPadding(contentInsetPx + toPx(6), 0, contentInsetPx + toPx(6), 0)
        composition.setPadding(
            contentInsetPx + toPx(CompositionPolicy.LEAD_UNITS),
            toPx(3),
            contentInsetPx,
            0,
        )
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
