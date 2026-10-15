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
    private val isAccentKey: (ImeKeyView) -> Boolean,
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
            // tile here. The hairline is the sheet's own border colour at 1
            // unit, the same `morecands_list_border` the sheet's 2-unit edge
            // takes (`values/colors.xml:117,123`).
            val sheet = ImeSurfacePolicy.moreCandsSheet(t.candidateBackground, t)
            val edge = ImeDrawableFactory.rounded(
                Color.TRANSPARENT,
                0,
                ImeSurfacePolicy.moreCandsSheetEdge(sheet, t),
                toPx(1).coerceAtLeast(1),
            )
            view.background = LayerDrawable(
                arrayOf(edge, statefulRounded(Color.TRANSPARENT, ImeSurfacePolicy.moreCandsPressed(t), 0)),
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
        // A key the reference draws on its theme colour: the send key once the
        // field asks for an action (`cls_button_enter`'s `actionbkcolor`,
        // style.xml:810-815) and the number page's return key
        // (`cls_button_back_26key` over `cls_button_back`, style.xml:857-858,
        // :838-843, whose `cls_bk_theme_circle` is the theme colour, :282-286).
        val primary = view.tag == "tab-active" ||
            isAccentKey(view) ||
            (view.tag == "key-enter" && view.currentMainText != "↵")

        val color = when {
            primary -> t.primary
            white -> t.lightKeyBackground
            side -> t.sideKeyBackground
            function -> t.functionKeyBackground
            else -> t.keyBackground
        }
        val pressedColor = when {
            // The accent key turns into a white key under the finger
            // (`action_button_pushedbk`); the tiles that also carry the accent
            // keep the plain pressed tint of their own class.
            primary -> ImeSurfacePolicy.actionKeyPressed(t)
            // The reference's two key fills trade places on press: a white key
            // takes the gray fill (`keybutton_pushed_bk`, `values/colors.xml:21`,
            // is `keybutton_func_bk`, `:20`) and a gray key takes the white one
            // (`keybutton_func_pushed_bk`, `:22`, is `keybutton_bk`, `:19`). The
            // dark skin swaps the same pair over its translucent fills
            // (`values/dark_colors.xml:18-22`).
            side || function -> t.lightKeyBackground
            else -> t.functionKeyBackground
        }

        view.applyMainTextScale(keyMainTextScale())
        val keyRadius = toPx(ImeGeometryTokens.KEY_RADIUS_DP)
        val keyFace = statefulRounded(color, pressedColor, keyRadius)
        val keyCap = ImeDrawableFactory.keyCap(
            keyFace,
            ImeSurfacePolicy.keyShadow(t),
            keyRadius,
            toPx(1),
        )
        // The cap is painted inside the cell by the key's own margins, as the
        // reference's key classes set them (2.25 × 4.75 on the 26-key board,
        // 2.5 × 2.75 on the nine-key family, 8 on a big side key's inner side).
        val unit = view.resources.displayMetrics.density * referenceScale()
        fun cap(dp: Float): Int = Math.round(dp * unit)
        view.background = InsetDrawable(
            keyCap,
            cap(view.marginLeftDp),
            cap(view.marginTopDp),
            cap(view.marginRightDp),
            cap(view.marginBottomDp),
        )
        view.elevation = 0f

        when {
            primary -> {
                val onPrimary = ImeDrawableFactory.contrastText(t.primary)
                view.setColors(onPrimary, onPrimary, onPrimary)
                // Under the finger the accent key wears the white face, so its
                // label goes dark for as long as it is held.
                view.setLabelColors(onPrimary, ImeDrawableFactory.contrastText(pressedColor))
            }
            // The corner glyph on any of these keys is the key hint colour
            // (`keybutton_text_2`), never the key text colour.
            white ->
                view.setColors(t.lightKeyText, ImeSurfacePolicy.keyHint(t), t.lightKeyText)
            side ->
                view.setColors(t.sideKeyText, t.sideKeyText, t.sideKeyText)
            // The English board's 中/英 key leads back to Chinese, and the
            // reference tints its wordmark with `keyboard_current_english` —
            // the accent at 95% on both skins (`cls_button_en2cn`,
            // `style.xml:897-904`; `values/colors.xml:18`,
            // `values/dark_colors.xml:17`).
            view.tag == "key:mode-en" -> view.setColors(
                ImeSurfacePolicy.accentTextOn(t.primary, t.functionKeyBackground),
                t.functionKeyText,
                t.functionKeyText,
            )
            function ->
                view.setColors(
                    t.functionKeyText,
                    t.functionKeyText,
                    t.functionKeyText,
                )
            else ->
                view.setColors(t.keyText, ImeSurfacePolicy.keyHint(t), t.keyText)
        }
    }

    private fun applyLinearLayout(view: LinearLayout, t: ImeTheme.Tokens) {
        when (view.tag) {
            // The option group 全部 / 单字 sits on (`cls_morecand_filter_containder`,
            // style.xml:1157-1159: a 20-unit pill in the darker neutral).
            "candidate-filter-container" ->
                view.background = ImeDrawableFactory.rounded(
                    ImeSurfacePolicy.filterTrack(t.candidateBackground, t),
                    toPx(20),
                )

            "app-setting-key" -> {
                val radius = toPx(ImeGeometryTokens.KEY_RADIUS_DP)
                view.background = ImeDrawableFactory.keyCap(
                    statefulRounded(t.keyBackground, t.keyPressedBackground, radius),
                    t.border, radius, toPx(1),
                )
            }
            "app-setting-group" -> view.background = null
            // A candidate cell, first or not, has no tile of its own and presses
            // to the reference's one candidate fill (`canditem_pushedbk`:
            // style.xml:1076-1080, `values/colors.xml:81`).
            "candidate-first-row", "candidate-row" -> {
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.candidatePressed(t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }
            // A symbol-history cell carries the candidate cell's own pressed
            // fill, rounded to the 6 units the reference gives it
            // (`cls_history_symbollist_button`, style.xml:1061-1070).
            "recent-symbol-cell" -> {
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.candidatePressed(t),
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
                    ImeSurfacePolicy.pressedSurface(t.candidateBackground, t),
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
            // (`width="match_height"`, style.xml:1307) and its own
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
                    ImeSurfacePolicy.cardPressed(t),
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
                    if (view.isSelected) ImeSurfacePolicy.primaryPressed(t) else ImeSurfacePolicy.cardPressed(t),
                    toPx(14),
                )
            }
            "nine-symbol-scroll-content", "digits-symbol-scroll-content" -> view.background = null
            // The keyboard-switch page's tiles are their own class: the whole
            // cell is the reference's rounded card (`setting_button_bk` with
            // `bkround="14"`) and the chosen one keeps that face — its class
            // declares no selected fill, only an accent label and glyph
            // (`cls_settings_option_mode_switch_button`, style.xml:649-668),
            // where the settings page's tiles fill with the accent
            // (`cls_settings_button`, `:626-647`).
            "keyboard-choice", "keyboard-choice-selected" -> view.background = statefulRounded(
                t.toolCardBackground,
                ImeSurfacePolicy.cardPressed(t),
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
                        ImeSurfacePolicy.cardPressed(t),
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
            // Reading or symbol list: the whole rail is one panel and its items are
            // flat. The panel is drawn inside the column with the margins the
            // reference gives the list itself — 2.5 units sideways, 2.75 up and
            // down (`cls_vertical_symbol_list`, `style.xml:1035-1036`) — so it
            // lines up with the key grid beside it.
            val unit = view.resources.displayMetrics.density * referenceScale()
            fun inset(dp: Float): Int = Math.round(dp * unit)
            view.background = InsetDrawable(
                ImeDrawableFactory.rounded(t.sideKeyBackground, toPx(ImeGeometryTokens.KEY_RADIUS_DP)),
                inset(ImeGeometryTokens.NINE_KEY_MARGIN_X_DP),
                inset(ImeGeometryTokens.NINE_KEY_MARGIN_Y_DP),
                inset(ImeGeometryTokens.NINE_KEY_MARGIN_X_DP),
                inset(ImeGeometryTokens.NINE_KEY_MARGIN_Y_DP),
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
                    ImeSurfacePolicy.railItemPressed(t),
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
                    ImeSurfacePolicy.candidatePressed(t),
                    toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                )
            }
            view.tag == "candidate-expanded-backspace" -> {
                // The reference's corner key (`cls_button_backspace` as the
                // expanded page uses it, input_more_cand.xml:22-26): a function
                // key that rounds only the list's bottom-right corner.
                view.imageTintList = ColorStateList.valueOf(t.functionKeyText)
                view.background = ImeDrawableFactory.statefulCorners(
                    normal = ImeSurfacePolicy.moreCandsBackspace(t),
                    pressed = ImeSurfacePolicy.moreCandsBackspacePressed(t),
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
                // The chosen tile's glyph answers in the accent on the plain
                // card (`ficon_textcolor_selected`, `theme_color`,
                // `cls_settings_option_mode_switch_button`, style.xml:649-668),
                // in the same shade as the name beside it.
                view.imageTintList = ColorStateList.valueOf(
                    ImeSurfacePolicy.accentTextOn(t.primary, t.toolCardBackground),
                )
                view.background = null
            }
            view.tag == "tool-icon" -> {
                view.imageTintList = ColorStateList.valueOf(ImeSurfacePolicy.iconTint(t))
                view.background = null
            }
            view.tag == "undo-toolbar" -> view.imageTintList =
                ColorStateList.valueOf(ImeSurfacePolicy.toolbarIcon(t))
            // The quick-input row's glyph takes `keybutton_text_2`
            // (`fi_toolbar_tip_superscript` glyphs, `input_toolbar_quickinput.xml:44-46`).
            view.tag == "suggestion-icon" -> {
                view.imageTintList = ColorStateList.valueOf(t.keySecondaryText)
                view.background = null
            }
            // The panel header's close key: the reference draws it as a circle
            // on the header's own ground, in the shade it uses for button glyphs
            // rather than the idle toolbar's icon grey — and never in the
            // page's accent, which its tiles carry (`cld_toolbar_close_button`,
            // style.xml:266-273).
            view.tag == "key-panel-back" -> {
                view.imageTintList = ColorStateList.valueOf(ImeSurfacePolicy.headerCloseIcon(t))
                view.background = paintedSquareWithinTarget(
                    statefulRounded(
                        ImeSurfacePolicy.headerCloseKey(t),
                        ImeSurfacePolicy.cardPressed(t),
                        toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                    ),
                    HEADER_CLOSE_PAINTED_DP,
                )
            }
            // The strips' own controls — the association back control and the
            // quick-input close key — are the reference's transparent toolbar
            // pills: `cls_btn_morecand_back` and `cls_history_close_button` both
            // extend `cls_bk_toolbar_button`, whose only face is the press tint
            // (`canditem_pushedbk`) under a `keybutton_text` glyph
            // (style.xml:231-235, `:519-525`, `:1055-1059`).
            view.tag == "association-back" || view.tag == "autofill-close" -> {
                view.imageTintList = ColorStateList.valueOf(t.keyText)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.candidatePressed(t),
                    toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                )
            }
            // The idle toolbar's own keys, the icon strip included: each is the
            // reference's 40-unit pill with the toolbar's icon colour.
            (
                (view.parent as? View)?.tag == "toolbar-row" ||
                    (view.parent as? View)?.tag == "toolbar-icon-strip"
                ) || hasAncestorTag(view, "tools-panel") -> {
                view.imageTintList = ColorStateList.valueOf(
                    if (hasAncestorTag(view, "tools-panel")) {
                        ImeSurfacePolicy.selectedText(t)
                    } else {
                        ImeSurfacePolicy.toolbarIcon(t)
                    },
                )
                if (view.isClickable) {
                    // Every idle-toolbar key wears its own pill face at rest
                    // (`cls_toolbar_idle_button`: `bkcolor="@color/toolbar_idle_button_bk"`,
                    // `bkround="999"`, `pushedbkcolor`, style.xml:237-248), which on the
                    // toolbar surface is the key cap colour, and presses to the
                    // reference's own shade instead of a derived one.
                    view.background = statefulRounded(
                        t.keyBackground,
                        ImeSurfacePolicy.pressedOnGround(t.toolbarBackground, t),
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

            // Our own quick-input rows read at the candidate font in
            // `canditem_text`, the colour the reference gives the quick-input
            // text (`input_toolbar_quickinput.xml:41-43`).
            tag == "suggestion-text" ->
                view.setTextColor(t.candidateText)

            tag == "candidate-filter-chip" -> {
                // 全部 / 单字: the chosen one is the raised surface with plain
                // text, the other a dimmed label on the group's own background
                // (`cls_filter_button`, style.xml:773-780). Its own text is
                // `keybutton_text_2` — black or white at 40%, the corner-mark
                // colour — and the chosen one's is `filter_button_selecttext`.
                val selected = view.isSelected
                val track = ImeSurfacePolicy.filterTrack(t.candidateBackground, t)
                view.setTextColor(
                    if (selected) {
                        ImeSurfacePolicy.filterChipText(t)
                    } else {
                        ImeSurfacePolicy.keyHint(t)
                    },
                )
                view.typeface = if (selected) {
                    android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                } else {
                    android.graphics.Typeface.DEFAULT
                }
                val chip = if (selected) ImeSurfacePolicy.filterChip(track, t) else Color.TRANSPARENT
                view.background = statefulRounded(
                    chip,
                    if (selected) {
                        ImeSurfacePolicy.pressedOnGround(chip, t)
                    } else {
                        ImeSurfacePolicy.pressedSurface(t.candidateBackground, t)
                    },
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
                // The symbol page's category tabs are the reference's own tab
                // class: a neutral pill filled with `symbol_option_pushedbk`,
                // never an accent tint (`cls_input_kbd_symbol_option`,
                // style.xml:715-724).
                val symbolTab = hasAncestorTag(view, "symbols-panel")
                val selected = ImeSurfacePolicy.selectedSurface(t)
                val fill = if (symbolTab) ImeSurfacePolicy.symbolTabFill(t) else selected
                view.setTextColor(
                    if (symbolTab) {
                        ImeSurfacePolicy.symbolTabText(t, selected = true)
                    } else {
                        ImeSurfacePolicy.selectedText(t)
                    },
                )
                view.background = statefulRounded(
                    fill,
                    if (symbolTab) fill else ImeSurfacePolicy.pressedSurface(selected, t),
                    toPx(
                        if (symbolTab || hasAncestorTag(view, "emoji-panel")) {
                            99
                        } else {
                            ImeGeometryTokens.CONTROL_RADIUS_DP
                        },
                    ),
                )
            }

            tag == "panel-tab" -> {
                val symbolTab = hasAncestorTag(view, "symbols-panel")
                view.setTextColor(
                    if (symbolTab) {
                        ImeSurfacePolicy.symbolTabText(t, selected = false)
                    } else {
                        t.keySecondaryText
                    },
                )
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    if (symbolTab) {
                        ImeSurfacePolicy.symbolTabFill(t)
                    } else {
                        ImeSurfacePolicy.pressedSurface(t.panelHeadBackground, t)
                    },
                    toPx(
                        if (symbolTab) 99 else ImeGeometryTokens.CONTROL_RADIUS_DP,
                    ),
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

            // 拆词: a word is a card, the selected run of them the panel's
            // selection face, and a separator carries no face of its own.
            tag?.startsWith("word-token-selected:") == true -> {
                view.setTextColor(ImeSurfacePolicy.selectedText(t))
                view.background = ImeDrawableFactory.rounded(
                    ImeSurfacePolicy.segmentSelected(t),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                    ImeSurfacePolicy.selectedText(t),
                    toPx(1),
                )
                view.typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            tag?.startsWith("word-token:") == true -> {
                view.setTextColor(t.keyText)
                view.background = ImeDrawableFactory.rounded(
                    t.toolCardBackground,
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
                view.typeface = android.graphics.Typeface.DEFAULT
            }

            tag?.startsWith("word-token-plain:") == true -> {
                view.setTextColor(t.keySecondaryText)
                view.background = null
                view.typeface = android.graphics.Typeface.DEFAULT
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
            // The tile's name is the reference's dimmed one — `keybutton_text_3`,
            // 50% black or 50% white (`values/colors.xml:15`,
            // `values/dark_colors.xml:14`) — drawn in our palette's own secondary
            // role, which keeps the 4.5:1 our text roles are held to. The chosen
            // tile's name and glyph answer in the accent (`selectedtextcolor` /
            // `ficon_textcolor_selected` are `theme_color`,
            // `cls_settings_option_mode_switch_button`, style.xml:649-668),
            // shaded to that same floor over the card.
            tag == "tool-label" -> view.setTextColor(t.keySecondaryText)
            tag == "tool-label-selected" -> view.setTextColor(
                ImeSurfacePolicy.accentTextOn(t.primary, t.toolCardBackground),
            )
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
                    ImeSurfacePolicy.railItemPressed(t),
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
                    ImeSurfacePolicy.railItemPressed(t),
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
                    ImeSurfacePolicy.candidatePressed(t),
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
                // The association bar's items are candidate items — the
                // reference's own class for them carries the candidate font and
                // the candidate text colour (`cls_option_associate_first_canditem`,
                // style.xml:562-566) — and none of the bar's classes names a
                // press colour, so they press to the candidate fill the strip's
                // cells use.
                view.setTextColor(t.candidateText)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.candidatePressed(t),
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
            // one rounded surface for the whole grid, an 11 radius and a 2 edge
            // in the sheet's own border colour.
            "candidate-expanded-card" -> {
                val sheet = ImeSurfacePolicy.moreCandsSheet(t.candidateBackground, t)
                view.background = ImeDrawableFactory.rounded(
                    sheet,
                    toPx(11),
                    ImeSurfacePolicy.moreCandsSheetEdge(sheet, t),
                    toPx(2).coerceAtLeast(1),
                )
            }

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
            // (style.xml:504-506), a black 12% / white 16% line rather than a
            // key-coloured stroke.
            "candidate-strip-split", "recent-symbol-split" ->
                view.setBackgroundColor(ImeSurfacePolicy.candidateSplit(t))
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

        /**
         * The header's close key is the reference's 40-unit circle
         * (`cls_toolbar_idle_button` is 40 wide, `cld_toolbar_close_button` as
         * wide as its row is high, `style.xml:237-248`, `:266-273`).
         */
        const val HEADER_CLOSE_PAINTED_DP = 40
        const val REFERENCE_ICON_SIZE = 0x1F000011
        const val REFERENCE_TEXT_SIZE = 0x1F000010
        val DIGITS_ONLY = Regex("[0-9]+")
    }
}
