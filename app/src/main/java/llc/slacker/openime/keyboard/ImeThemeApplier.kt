package llc.slacker.openime.keyboard

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import llc.slacker.openime.R
import llc.slacker.openime.handwriting.HandwritingPadView
import llc.slacker.openime.panel.TileGridPolicy
import llc.slacker.openime.theme.ImeDrawableFactory
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeTheme
import llc.slacker.openime.widget.ImeKeyView

/**
 * Applies the current IME design tokens to an already-built native view tree.
 *
 * It owns traversal and tag-to-style rules only. Theme/settings ownership stays
 * with ImeKeyboardView, which supplies the few dynamic predicates and values.
 */
internal class ImeThemeApplier(
    private val toPx: (Int) -> Int,
    private val statefulRounded: (Int, Int, Int) -> android.graphics.drawable.StateListDrawable,
    private val keyMainTextScale: () -> Float,
    private val referenceScale: () -> Float,
    private val toggleState: (String) -> Boolean,
    private val isSideKey: (ImeKeyView) -> Boolean,
    private val isFunctionKey: (ImeKeyView) -> Boolean,
    private val isWhiteKey: (ImeKeyView) -> Boolean,
) {
    fun apply(view: View, tokens: ImeTheme.Tokens) {
        when (view) {
            is ImeKeyView -> applyKey(view, tokens)
            is LinearLayout -> applyLinearLayout(view, tokens)
            is ScrollView -> applyScrollView(view, tokens)
            is ImageView -> applyImageView(view, tokens)
            is SeekBar -> applySeekBar(view, tokens)
            is TextView -> applyTextView(view, tokens)
            is FrameLayout -> applyFrameLayout(view, tokens)
            else -> applyTaggedView(view, tokens)
        }

        if (view is HandwritingPadView) {
            view.setInkColor(tokens.primary)
            view.setGridColor(
                Color.argb(
                    72,
                    Color.red(tokens.border),
                    Color.green(tokens.border),
                    Color.blue(tokens.border),
                ),
            )
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                apply(view.getChildAt(index), tokens)
            }
        }
    }

    private fun applyKey(view: ImeKeyView, t: ImeTheme.Tokens) {
        if (view.tag == "candidate-grid" || view.tag == "candidate-grid-first") {
            // One item class in the reference (`cls_morecands_button`,
            // style.xml:1141-1149): every cell is a flat label with a hairline
            // edge on the list's own background, so no cell carries the accent
            // tile here.
            val edge = ImeDrawableFactory.rounded(
                Color.TRANSPARENT,
                0,
                ImeSurfacePolicy.divider(t),
                toPx(1).coerceAtLeast(1),
            )
            view.background = LayerDrawable(
                arrayOf(edge, statefulRounded(Color.TRANSPARENT, ImeSurfacePolicy.pressedSurface(t.candidateBackground, t), 0)),
            )
            view.setColors(t.keyText)
            return
        }
        val side = isSideKey(view)
        val label = view.contentDescription?.toString().orEmpty()
        val function = isFunctionKey(view)
        val white = !side && (
            isWhiteKey(view) ||
                (!function && DIGITS_ONLY.matches(label))
            )
        val primary = view.tag == "tab-active" ||
            (view.tag == "key-enter" && view.currentMainText != "↵")

        val color = when {
            primary -> t.primary
            white -> t.lightKeyBackground
            side -> t.sideKeyBackground
            function -> t.functionKeyBackground
            else -> t.keyBackground
        }
        val pressedColor = when {
            primary -> ImeSurfacePolicy.primaryPressed(t)
            side -> ImeSurfacePolicy.pressedSurface(t.sideKeyBackground, t)
            function -> ImeSurfacePolicy.pressedSurface(t.functionKeyBackground, t)
            else -> ImeSurfacePolicy.pressedSurface(color, t)
        }

        view.applyMainTextScale(keyMainTextScale())
        val keyRadius = toPx(ImeGeometryTokens.KEY_RADIUS_DP)
        val keyFace = statefulRounded(color, pressedColor, keyRadius)
        val keyCap = ImeDrawableFactory.keyCap(keyFace, pressedColor, keyRadius, toPx(1))
        val halfGap = toPx(ImeGeometryTokens.KEY_GAP_DP) / 2
        view.background = InsetDrawable(keyCap, halfGap, halfGap, halfGap, halfGap)
        view.elevation = 0f

        when {
            primary -> {
                val onPrimary = ImeDrawableFactory.contrastText(t.primary)
                view.setColors(onPrimary, onPrimary, onPrimary)
            }
            white ->
                view.setColors(t.lightKeyText, t.keySecondaryText, t.lightKeyText)
            side ->
                view.setColors(t.sideKeyText, t.sideKeyText, t.sideKeyText)
            function ->
                view.setColors(
                    t.functionKeyText,
                    t.functionKeyText,
                    t.functionKeyText,
                )
            else ->
                view.setColors(t.keyText, t.keySecondaryText, t.keyText)
        }
    }

    private fun applyLinearLayout(view: LinearLayout, t: ImeTheme.Tokens) {
        when (view.tag) {
            // The option group 全部 / 单字 sits on (`cls_morecand_filter_containder`,
            // style.xml:1157-1159: a 20-unit pill in the darker neutral).
            "candidate-filter-container" ->
                view.background = ImeDrawableFactory.rounded(t.candidateBackground, toPx(20))

            "app-setting-key" -> {
                val radius = toPx(ImeGeometryTokens.KEY_RADIUS_DP)
                view.background = ImeDrawableFactory.keyCap(
                    statefulRounded(t.keyBackground, t.keyPressedBackground, radius),
                    t.border, radius, toPx(1),
                )
            }
            "app-setting-group" -> view.background = null
            "candidate-first-row" -> {
                val selected = t.keyBackground
                view.background = statefulRounded(
                    selected,
                    ImeSurfacePolicy.pressedSurface(selected, t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }
            "candidate-row" -> {
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.candidateBackground, t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }
            // A symbol-history cell carries the candidate cell's own pressed
            // fill, rounded to the 6 units the reference gives it
            // (`cls_history_symbollist_button`, style.xml:1061-1070).
            "recent-symbol-cell" -> {
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.candidateBackground, t),
                    toPx(RecentSymbolStripPolicy.CELL_RADIUS_DP),
                )
            }
            // The pre-edit block carries the reference's own press tint while the
            // user edits the string (`cls_comp_container` pushedbkcolor
            // @color/comp_pushedbk, style.xml:575-577 — black 8%, white 12%,
            // the alphas our pressed surfaces already use).
            "pinyin-composition-editor" -> {
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.toolbarBackground, t),
                    0,
                )
            }
            // The tip banner: the reference's own pill, painted inside the row's
            // touch height (`cls_toolbar_tip_container` bkround 999,
            // style.xml:1273-1274), with the label in the key text colour
            // (`cls_toolbar_tip_label`, style.xml:1267-1270).
            "toolbar-tip" -> {
                view.background = paintedWithinTarget(
                    ImeDrawableFactory.rounded(
                        ImeSurfacePolicy.tipBackground(t),
                        toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                    ),
                    ToolbarTipPolicy.PILL_UNITS,
                    padsView = false,
                )
            }
            // The close key keeps the reference's square of the pill's height
            // (`width="match_height"`, style.xml:1306-1307) and its own
            // translucent faces (`toolbar_tip_close_button_bk`, colors.xml:163).
            "toolbar-tip-close" -> {
                view.background = paintedSquareWithinTarget(
                    statefulRounded(
                        ImeSurfacePolicy.tipCloseBackground(t),
                        ImeSurfacePolicy.tipClosePressed(t),
                        toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                    ),
                    ToolbarTipPolicy.PILL_UNITS,
                )
            }
            // Rows sit inside a card that clips to its corners, so the pressed
            // fill is square and the card supplies the rounding.
            "setting-row" -> if (view.isClickable) {
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.toolCardBackground, t),
                    0,
                )
            }
            "settings-card" -> view.background = ImeDrawableFactory.rounded(
                t.toolCardBackground,
                toPx(ImeGeometryTokens.CARD_RADIUS_DP),
            )
            "quick-tile" -> {
                val fill = if (view.isSelected) t.primary else t.toolCardBackground
                view.background = statefulRounded(
                    fill,
                    if (view.isSelected) ImeSurfacePolicy.primaryPressed(t) else ImeSurfacePolicy.pressedSurface(fill, t),
                    toPx(14),
                )
            }
            "nine-symbol-scroll-content", "digits-symbol-scroll-content" -> view.background = null
            // Tool-style tiles: the icon tile carries the colour, not the card.
            // A launcher tile is the reference's own rounded card: the whole
            // cell takes `setting_button_bk` with `bkround="14"`, the accent
            // when it is the chosen one (`cls_settings_button`, style.xml:626-647).
            "keyboard-choice" -> view.background = statefulRounded(
                t.toolCardBackground,
                ImeSurfacePolicy.pressedSurface(t.toolCardBackground, t),
                toPx(TileGridPolicy.TILE_RADIUS_DP),
            )
            "keyboard-choice-selected" -> view.background = statefulRounded(
                t.primary,
                ImeSurfacePolicy.primaryPressed(t),
                toPx(TileGridPolicy.TILE_RADIUS_DP),
            )
            "segmented-track" -> view.background = ImeDrawableFactory.rounded(
                if (hasAncestorTag(view, "settings-panel")) ImeSurfacePolicy.controlTrack(t) else t.functionKeyBackground,
                toPx(12),
            )
            "segmented-track-tall" -> view.background = paintedWithinTarget(
                ImeDrawableFactory.rounded(ImeSurfacePolicy.controlTrack(t), toPx(10)),
                SEGMENT_PAINTED_DP,
                padsView = false,
            )
            "phrase-card" -> view.background = ImeDrawableFactory.rounded(t.toolCardBackground, toPx(14))
            "setting-group" -> {
                view.background = ImeDrawableFactory.rounded(
                    t.toolCardBackground,
                    toPx(ImeGeometryTokens.CARD_RADIUS_DP),
                    ImeSurfacePolicy.divider(t),
                    0,
                )
            }
            "clip-card" -> {
                view.background = ImeDrawableFactory.rounded(
                    t.toolCardBackground,
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                    ImeSurfacePolicy.divider(t),
                    0,
                )
            }
            // A panel's title row shares the panel's ground: no separate band.
            "panel-head" -> view.setBackgroundColor(t.keyboardBackground)
            "toolbar-row" -> view.setBackgroundColor(t.toolbarBackground)
            "compose-zone" -> view.setBackgroundColor(t.candidateBackground)
            "candidate-field" -> view.background = null
            else -> {
                // A tool's own tile is the card: the whole cell takes the
                // reference's radius-14 face, its name drawn inside it.
                if ((view.tag as? String)?.startsWith("tool:") == true) {
                    view.background = statefulRounded(
                        t.toolCardBackground,
                        ImeSurfacePolicy.pressedSurface(t.toolCardBackground, t),
                        toPx(TileGridPolicy.TILE_RADIUS_DP),
                    )
                }
            }
        }

        if (
            view.contentDescription != null &&
            view.isClickable &&
            view.tag == null
        ) {
            view.background = statefulRounded(
                t.toolCardBackground,
                ImeSurfacePolicy.pressedSurface(t.toolCardBackground, t),
                toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
            )
        }
    }

    private fun applyScrollView(view: ScrollView, t: ImeTheme.Tokens) {
        val content = view.getChildAt(0)?.tag
        if (
            (view.tag == "nine-punct-stack" || view.tag == "digits-symbol-scroll") &&
            content in setOf("nine-pinyin-panel", "nine-symbol-scroll-content", "digits-symbol-scroll-content")
        ) {
            // Reading or symbol list: the whole rail is one panel and its items are flat.
            // Inset by half a key gap so the panel lines up with the key grid.
            view.background = InsetDrawable(
                ImeDrawableFactory.rounded(t.sideKeyBackground, toPx(ImeGeometryTokens.KEY_RADIUS_DP)),
                toPx(ImeGeometryTokens.KEY_GAP_DP) / 2,
            )
        } else if (view.tag == "nine-punct-stack" || view.tag == "digits-symbol-scroll") {
            view.background = null
        }
    }

    private fun applyImageView(view: ImageView, t: ImeTheme.Tokens) {
        val iconSize = when {
            view.tag == "candidate-expanded-backspace" -> 24
            view.tag == "candidate-emoji" || view.tag == "candidate-expand" -> 20
            view.tag == "key-panel-back" -> 24
            view.tag?.toString()?.startsWith("clip-pin:") == true || view.tag?.toString()?.startsWith("phrase-") == true -> 18
            // Tile icons (工具 and 切换键盘 share the tile): one size for both pages.
            view.tag == "tool-icon" || view.tag == "tool-icon-selected" -> 28
            hasAncestorTag(view, "tools-panel") -> 24
            (view.parent as? View)?.tag == "toolbar-row" -> 22
            else -> 0
        }
        if (iconSize > 0) {
            fun fitIcon() {
                val drawable = view.drawable ?: return
                if (view.width <= 0 || view.height <= 0) return
                val size = toPx(iconSize).toFloat()
                val scale = size / drawable.intrinsicWidth.coerceAtLeast(1)
                view.imageMatrix = android.graphics.Matrix().apply {
                    setScale(scale, scale)
                    postTranslate((view.width - size) / 2f, (view.height - size) / 2f)
                }
            }
            view.setPadding(0, 0, 0, 0)
            view.scaleType = ImageView.ScaleType.MATRIX
            if (view.getTag(REFERENCE_ICON_SIZE) == null) {
                view.setTag(REFERENCE_ICON_SIZE, iconSize)
                view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitIcon() }
            }
            view.post { fitIcon() }
        }

        when {
            view.tag == "punct:add" || view.tag == "digit-symbol:add" -> {
                // The rail's circled plus: drawn like the symbols around it.
                view.imageTintList = ColorStateList.valueOf(t.sideKeyText)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.sideKeyBackground, t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }
            (view.tag as? String)?.startsWith("clip-pin:") == true || (view.tag as? String)?.startsWith("phrase-") == true -> {
                view.imageTintList = ColorStateList.valueOf(if (view.isSelected) t.primary else t.keySecondaryText)
            }
            view.tag == "candidate-emoji" || view.tag == "candidate-expand" -> {
                // `cls_btn_morecand` is a `cls_bk_toolbar_button`: a transparent
                // pill that only shows a press tint (`bkround="111"`,
                // `pushedbkcolor="@color/canditem_pushedbk"`, style.xml:231-235, 482-488).
                view.imageTintList = ColorStateList.valueOf(t.keySecondaryText)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.candidateBackground, t),
                    toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                )
            }
            view.tag == "candidate-expanded-backspace" -> {
                // The reference's corner key (`cls_button_backspace` as the
                // expanded page uses it, input_more_cand.xml:22-26): a function
                // key that rounds only the list's bottom-right corner.
                view.imageTintList = ColorStateList.valueOf(t.functionKeyText)
                view.background = ImeDrawableFactory.statefulCorners(
                    normal = t.functionKeyBackground,
                    pressed = ImeSurfacePolicy.pressedSurface(t.functionKeyBackground, t),
                    bottomRightPx = toPx(11).toFloat(),
                )
            }
            // Row icons are decoration, so they stay neutral; the accent is
            // reserved for controls and their on/current state.
            view.tag == "setting-icon" -> {
                view.imageTintList = ColorStateList.valueOf(ImeSurfacePolicy.iconTint(t))
                view.background = ImeDrawableFactory.rounded(ImeSurfacePolicy.iconTile(t), toPx(9))
            }
            view.tag == "setting-chevron" ->
                view.imageTintList = ColorStateList.valueOf(ImeSurfacePolicy.chevron(t))
            view.tag == "quick-tile-icon" -> {
                val selected = (view.parent as? View)?.isSelected == true
                view.imageTintList = ColorStateList.valueOf(
                    if (selected) ImeDrawableFactory.contrastText(t.primary) else ImeSurfacePolicy.iconTint(t),
                )
            }
            view.tag == "tool-icon-selected" -> {
                // The chosen tile is the accent, so its glyph reads on it
                // (`setting_button_icon_selected` over a `theme_color` face).
                view.imageTintList = ColorStateList.valueOf(ImeDrawableFactory.contrastText(t.primary))
                view.background = null
            }
            view.tag == "tool-icon" -> {
                view.imageTintList = ColorStateList.valueOf(ImeSurfacePolicy.iconTint(t))
                view.background = null
            }
            view.tag == "undo-toolbar" -> view.imageTintList = ColorStateList.valueOf(t.keySecondaryText)
            view.tag == "key-panel-back" -> {
                view.imageTintList = ColorStateList.valueOf(t.keyText)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.keyboardBackground, t),
                    toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                )
            }
            (
                view.parent is LinearLayout &&
                    (view.parent as LinearLayout).tag == "toolbar-row"
                ) || hasAncestorTag(view, "tools-panel") -> {
                view.imageTintList = ColorStateList.valueOf(if (hasAncestorTag(view, "tools-panel")) ImeSurfacePolicy.selectedText(t) else t.keyText)
                if (view.isClickable) {
                    // Every idle-toolbar key wears its own pill face at rest
                    // (`cls_toolbar_idle_button`: `bkcolor="@color/toolbar_idle_button_bk"`,
                    // `bkround="999"`, `pushedbkcolor`, style.xml:237-248), which on the
                    // toolbar surface is the key cap colour.
                    view.background = statefulRounded(
                        t.keyBackground,
                        ImeSurfacePolicy.pressedSurface(t.keyBackground, t),
                        toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                    )
                }
            }
        }
    }

    private fun applySeekBar(view: SeekBar, t: ImeTheme.Tokens) {
        view.progressDrawable = view.context.getDrawable(android.R.drawable.progress_horizontal)?.mutate()
        view.progressTintList = ColorStateList.valueOf(t.primary)
        view.progressBackgroundTintList = ColorStateList.valueOf(ImeSurfacePolicy.controlTrack(t))
        view.secondaryProgressTintList = ColorStateList.valueOf(ImeSurfacePolicy.controlTrack(t))
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            view.maxHeight = toPx(4)
            view.minHeight = toPx(4)
        }
        view.thumbTintList = null
        view.thumb = ImeDrawableFactory.rounded(Color.WHITE, toPx(99), t.border, toPx(1)).apply {
            setSize(toPx(20), toPx(20))
        }
        view.splitTrack = false
    }

    private fun applyTextView(view: TextView, t: ImeTheme.Tokens) {
        if (view.parent !is ImeKeyView) {
            val baseSize = (view.getTag(REFERENCE_TEXT_SIZE) as? Float) ?: (view.textSize / view.resources.displayMetrics.scaledDensity).also { view.setTag(REFERENCE_TEXT_SIZE, it) }
            view.textSize = baseSize * referenceScale()
        }
        val tag = view.tag as? String
        if (view.parent !is ImeKeyView) {
            view.setTextColor(t.keyText)
        }

        when {
            tag == "backspace-clear-hint" ->
                view.setTextColor(t.keySecondaryText)

            tag == "candidate-first" ->
                view.setTextColor(t.keyText)

            tag == "candidate-word" ->
                view.setTextColor(t.candidateText)

            // 历史符号列表按钮 takes the candidate text colour (style.xml:1063).
            tag == "recent-symbol-label" ->
                view.setTextColor(t.candidateText)

            // The tip's label takes `keybutton_text` (cls_toolbar_tip_label,
            // style.xml:1267-1270) — the same key text colour the toolbar uses.
            tag == "toolbar-tip-label" ->
                view.setTextColor(t.keyText)

            tag == "candidate-filter-chip" -> {
                // 全部 / 单字: the chosen one is the raised surface with plain
                // text, the other a dimmed label on the group's own background
                // (`cls_filter_button`, style.xml:773-780).
                val selected = view.isSelected
                view.setTextColor(if (selected) t.keyText else t.textSecondary)
                view.typeface = if (selected) {
                    android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                } else {
                    android.graphics.Typeface.DEFAULT
                }
                view.background = statefulRounded(
                    if (selected) t.keyBackground else Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.candidateBackground, t),
                    toPx(20),
                )
            }

            tag == "panel-note" ||
                tag == "setting-sub" ||
                tag == "setting-value" ||
                tag == "setting-chevron" ||
                tag == "panel-section-title" ->
                view.setTextColor(t.keySecondaryText)

            tag == "setting-label" ->
                view.setTextColor(t.keyText)

            tag == "quick-tile-label" -> view.setTextColor(
                if ((view.parent as? View)?.isSelected == true) ImeDrawableFactory.contrastText(t.primary) else t.keyText,
            )

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
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "tab-active" -> {
                val selected = ImeSurfacePolicy.selectedSurface(t)
                view.setTextColor(ImeSurfacePolicy.selectedText(t))
                view.background = statefulRounded(
                    selected,
                    ImeSurfacePolicy.pressedSurface(selected, t),
                    toPx(if (hasAncestorTag(view, "emoji-panel")) 99 else ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "panel-tab" -> {
                view.setTextColor(t.keySecondaryText)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.panelHeadBackground, t),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "quick-phrase-add" -> {
                view.setTextColor(ImeDrawableFactory.contrastText(t.primary))
                view.background = statefulRounded(
                    t.primary,
                    ImeSurfacePolicy.primaryPressed(t),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "panel-button" ||
                tag == "clipboard-refresh" ||
                tag?.startsWith("clip-pin:") == true ||
                tag?.startsWith("clip-use:") == true ||
                tag?.startsWith("phrase-edit:") == true -> {
                view.setTextColor(t.keyText)
                view.background = statefulRounded(
                    t.panelHeadBackground,
                    ImeSurfacePolicy.pressedSurface(t.panelHeadBackground, t),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "clipboard-retention-action" -> {
                view.setTextColor(t.keyText)
                view.background = paintedWithinTarget(
                    statefulRounded(
                        t.panelHeadBackground,
                        ImeSurfacePolicy.pressedSurface(t.panelHeadBackground, t),
                        toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                    ),
                    RETENTION_PAINTED_DP,
                )
            }

            tag == "clipboard-retention-destructive" || tag == "clipboard-clear-confirm" -> {
                val destructiveText = ImeSurfacePolicy.destructiveLabel(t)
                view.setTextColor(destructiveText)
                view.background = paintedWithinTarget(
                    ImeDrawableFactory.rounded(
                        t.panelHeadBackground, toPx(8), destructiveText, toPx(1),
                    ),
                    RETENTION_PAINTED_DP,
                )
            }

            tag?.startsWith("phrase-delete:") == true -> {
                val destructiveText = ImeSurfacePolicy.destructiveLabel(t)
                view.setTextColor(destructiveText)
                view.background = ImeDrawableFactory.rounded(
                    t.panelHeadBackground, toPx(8), destructiveText, toPx(1),
                )
            }

            tag == "segment-selected" -> {
                view.setTextColor(t.keyText)
                view.background = if (hasAncestorTag(view, "settings-panel")) {
                    ImeDrawableFactory.rounded(ImeSurfacePolicy.segmentSelected(t), toPx(10))
                } else {
                    ImeDrawableFactory.rounded(t.toolCardBackground, toPx(10), t.border, toPx(1))
                }
                view.typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            tag == "segment-option" -> { view.setTextColor(t.keySecondaryText); view.background = null }
            tag == "segment-selected-tall" -> {
                view.setTextColor(t.keyText)
                view.background = paintedWithinTarget(
                    ImeDrawableFactory.rounded(ImeSurfacePolicy.segmentSelected(t), toPx(8)),
                    SEGMENT_PAINTED_DP - 4,
                    padsView = false,
                )
                view.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            tag == "segment-option-tall" -> { view.setTextColor(t.keySecondaryText); view.background = null }
            // The tile's name takes `keybutton_text_3` (50% black, or 50% white
            // in the dark skin, values/colors.xml:15) and white on the chosen
            // tile (`setting_button_selectedtext`).
            tag == "tool-label" -> view.setTextColor(t.keySecondaryText)
            tag == "tool-label-selected" -> view.setTextColor(ImeDrawableFactory.contrastText(t.primary))
            tag == "fuzzy-rules" -> view.background = ImeDrawableFactory.rounded(t.toolCardBackground, toPx(16))
            tag == "textedit-spacer" -> view.background = null
            tag == "textedit-center" -> {
                view.setTextColor(t.primary)
                view.background = ImeDrawableFactory.rounded(ImeSurfacePolicy.selectedSurface(t), toPx(12))
            }
            tag?.startsWith("clip-pin:") == true || tag?.startsWith("phrase-edit:") == true || tag?.startsWith("phrase-delete:") == true -> {
                view.setTextColor(t.keySecondaryText); view.background = null
                view.compoundDrawableTintList = ColorStateList.valueOf(if (view.isSelected) t.primary else t.keySecondaryText)
            }
            tag?.startsWith("punct:") == true ||
                tag?.startsWith("digit-symbol:") == true -> {
                // Flat on the rail's panel; only a press shows a surface.
                view.setTextColor(t.keyText)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.sideKeyBackground, t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }

            tag == "nine-pinyin-path-selected" || tag == "nine-pinyin-path-filter" -> {
                // A reading is a list item, not a key: it carries no state of
                // its own, exactly like the symbol cells beside it (the
                // reference's cls_pinyin9_filter_button is the symbol item's
                // style). Which reading is current is shown in the pre-edit.
                view.setTextColor(t.keyText)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.sideKeyBackground, t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }

            tag == "key-panel-back" -> {
                view.setTextColor(t.keyText)
                view.background = statefulRounded(
                    t.panelHeadBackground,
                    ImeSurfacePolicy.pressedSurface(t.panelHeadBackground, t),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "panel-title" ->
                view.setTextColor(t.keyText)

            tag == "candidate-emoji" || tag == "candidate-expand" -> {
                view.setTextColor(t.keySecondaryText)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.candidateBackground, t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }

            tag == "voice-transcript" -> {
                view.setTextColor(t.keyText)
                view.background = ImeDrawableFactory.rounded(
                    t.toolCardBackground,
                    toPx(ImeGeometryTokens.CARD_RADIUS_DP),
                )
            }

            tag == "voice-model-status" ->
                view.setTextColor(t.keySecondaryText)

            tag == "association-candidate" -> {
                view.setTextColor(t.candidateText)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.toolCardBackground, t),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "tools-page-dots" ->
                view.setTextColor(t.keySecondaryText)

            tag == "voice-mic" -> {
                val onAccent = ImeDrawableFactory.contrastText(t.primary)
                view.setTextColor(onAccent)
                view.compoundDrawableTintList = ColorStateList.valueOf(onAccent)
                view.background = statefulRounded(
                    t.primary,
                    ImeSurfacePolicy.primaryPressed(t),
                    toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                )
            }

            view.parent is LinearLayout &&
                (
                    (view.parent as LinearLayout).tag ==
                        "nine-symbol-scroll-content" ||
                        (view.parent as LinearLayout).tag ==
                        "digits-symbol-scroll-content"
                    ) -> {
                view.setTextColor(t.sideKeyText)
            }
        }
    }

    private fun applyFrameLayout(view: FrameLayout, t: ImeTheme.Tokens) {
        when (view.tag) {
            // The expanded page's list card (`cls_morecand_bk`, style.xml:1162-1166):
            // one rounded surface for the whole grid, an 11 radius and a 2 edge.
            "candidate-expanded-card" ->
                view.background = ImeDrawableFactory.rounded(
                    t.keyBackground,
                    toPx(11),
                    ImeSurfacePolicy.divider(t),
                    toPx(2).coerceAtLeast(1),
                )

            "emoji-cell" -> {
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.sideKeyBackground, t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }
            "toggle" -> {
                val seed = view.contentDescription?.toString()
                    ?.substringBefore('，')
                    .orEmpty()
                val enabled = toggleState(seed)
                // padsView = false: the track must not replace the switch's own
                // 2dp side padding, or the knob sits 0 from the left edge when
                // off and 4 from the right when on.
                view.background = paintedWithinTarget(
                    ImeDrawableFactory.rounded(
                        ImeSurfacePolicy.switchTrack(enabled, t),
                        toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                    ),
                    ImeGeometryTokens.SWITCH_HEIGHT_DP,
                    padsView = false,
                )
            }
        }
    }

    private fun applyTaggedView(view: View, t: ImeTheme.Tokens) {
        when (view.tag) {
            SymbolRailRenderer.DIVIDER_TAG -> view.setBackgroundColor(
                ImeDrawableFactory.blend(t.keySecondaryText, t.sideKeyBackground, 0.22f),
            )
            "handwriting-canvas" -> {
                view.background = ImeDrawableFactory.rounded(
                    t.canvasBackground,
                    toPx(ImeGeometryTokens.CARD_RADIUS_DP),
                )
            }
            "voice-wave-bar" -> {
                view.background = ImeDrawableFactory.rounded(
                    t.primary,
                    toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                )
            }
            "toggle-knob" -> {
                view.background = ImeDrawableFactory.rounded(
                    ImeSurfacePolicy.switchKnob(t),
                    toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                )
            }
            "setting-divider" ->
                view.setBackgroundColor(ImeDrawableFactory.withAlpha(t.border, if (ImeSurfacePolicy.isDark(t)) 150 else 48))
            "row-hairline" -> view.setBackgroundColor(ImeSurfacePolicy.hairline(t))
            // `cls_candidate_split` is filled with @color/canditem_split
            // (style.xml:504-506), the colour the candidate row's hairlines use.
            "candidate-strip-split", "recent-symbol-split" ->
                view.setBackgroundColor(ImeSurfacePolicy.divider(t))
        }
    }

    /** Paint a 34dp pill inside a 48dp touch target. */
    /**
     * Paint [drawable] [paintedDp] tall, centred inside a full touch-target
     * high view: the design keeps compact controls, the touch target stays 48dp.
     */
    private fun paintedWithinTarget(
        drawable: android.graphics.drawable.Drawable,
        paintedDp: Int,
        padsView: Boolean = true,
    ): InsetDrawable {
        val extra = toPx(ImeGeometryTokens.TOUCH_TARGET_DP) - toPx(paintedDp)
        if (padsView) return InsetDrawable(drawable, 0, extra / 2, 0, extra - extra / 2)
        // An InsetDrawable background also pads its view by the inset, which
        // squeezed a segment's label into the painted pill and clipped it.
        return object : InsetDrawable(drawable, 0, extra / 2, 0, extra - extra / 2) {
            override fun getPadding(padding: android.graphics.Rect): Boolean {
                padding.set(0, 0, 0, 0)
                return false
            }
        }
    }

    /**
     * Paint [drawable] as a [paintedDp] square, centred in the full touch
     * target: the reference's close keys are as wide as the pill is high
     * (`width="match_height"`), and our touch target stays 48dp.
     */
    private fun paintedSquareWithinTarget(
        drawable: android.graphics.drawable.Drawable,
        paintedDp: Int,
    ): android.graphics.drawable.Drawable {
        val inset = (toPx(ImeGeometryTokens.TOUCH_TARGET_DP) - toPx(paintedDp)) / 2
        return object : InsetDrawable(drawable, inset, inset, inset, inset) {
            override fun getPadding(padding: android.graphics.Rect): Boolean {
                padding.set(0, 0, 0, 0)
                return false
            }
        }
    }

    private fun hasAncestorTag(view: View, tag: String): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent.tag == tag) return true
            parent = parent.parent
        }
        return false
    }

    private companion object {
        /** Painted heights of controls whose touch target is a full 48dp. */
        const val SEGMENT_PAINTED_DP = 34
        const val RETENTION_PAINTED_DP = 36
        const val REFERENCE_ICON_SIZE = 0x1F000011
        const val REFERENCE_TEXT_SIZE = 0x1F000010
        val DIGITS_ONLY = Regex("[0-9]+")
    }
}
