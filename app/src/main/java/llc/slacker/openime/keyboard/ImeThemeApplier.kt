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
import llc.slacker.openime.theme.AccentPalette
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
    private val skinRadiusPx: () -> Int,
    private val skinOpacity: () -> Int,
    private val skinPrimaryColor: () -> String,
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
            val selected = view.tag == "candidate-grid-first"
            val fill = if (selected) ImeSurfacePolicy.selectedSurface(t) else t.expandedBackground
            view.background = ImeDrawableFactory.rounded(fill, 0, ImeSurfacePolicy.divider(t), toPx(1).coerceAtLeast(1))
            view.setColors(if (selected) t.primary else t.keyText)
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
            (view.tag == "key-enter" && (!side || label == "确定"))

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
        val keyFace = statefulRounded(color, pressedColor, skinRadiusPx())
        val keyCap = ImeDrawableFactory.keyCap(keyFace, pressedColor, skinRadiusPx(), toPx(1))
        val halfGap = toPx(ImeGeometryTokens.KEY_GAP_DP) / 2
        view.background = InsetDrawable(keyCap, halfGap, halfGap, halfGap, halfGap)
        view.background?.alpha =
            skinOpacity().coerceIn(70, 100) * 255 / 100
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
            "setting-row" -> if (view.isClickable) {
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.toolCardBackground, t),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }
            "nine-symbol-scroll-content", "digits-symbol-scroll-content" -> view.background = null
            "keyboard-choice", "keyboard-choice-selected" -> {
                val selected = view.tag == "keyboard-choice-selected"
                view.background = ImeDrawableFactory.rounded(t.toolCardBackground, toPx(16),
                    if (selected) t.primary else Color.TRANSPARENT, if (selected) toPx(2) else 0)
                view.invalidate()
            }
            "segmented-track" -> view.background = ImeDrawableFactory.rounded(t.functionKeyBackground, toPx(12))
            "segmented-track-tall" -> view.background = paintedWithinTarget(
                ImeDrawableFactory.rounded(t.functionKeyBackground, toPx(12)),
                SEGMENT_PAINTED_DP,
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
            "panel-head" -> view.setBackgroundColor(t.panelHeadBackground)
            "toolbar-row" -> view.setBackgroundColor(t.toolbarBackground)
            "compose-zone" -> view.setBackgroundColor(t.candidateBackground)
            "candidate-field" -> view.background = null
            else -> {
                if (
                    (view.tag as? String)?.startsWith("tool:") == true &&
                    view.isClickable
                ) {
                    view.background = statefulRounded(
                        t.toolCardBackground,
                        ImeSurfacePolicy.pressedSurface(t.toolCardBackground, t),
                        toPx(ImeGeometryTokens.CARD_RADIUS_DP),
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
        if (view.tag == "nine-punct-stack" && view.getChildAt(0)?.tag == "nine-pinyin-panel") {
            // Reading list: the whole rail is one panel and its items are flat.
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
            view.tag == "candidate-emoji" || view.tag == "candidate-expand" -> 20
            view.tag == "key-panel-back" -> 24
            view.tag?.toString()?.startsWith("clip-pin:") == true || view.tag?.toString()?.startsWith("phrase-") == true -> 18
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
            (view.tag as? String)?.startsWith("clip-pin:") == true || (view.tag as? String)?.startsWith("phrase-") == true -> {
                view.imageTintList = ColorStateList.valueOf(if (view.isSelected) t.primary else t.keySecondaryText)
            }
            view.tag == "candidate-emoji" || view.tag == "candidate-expand" -> {
                view.imageTintList = ColorStateList.valueOf(t.keySecondaryText)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.candidateBackground, t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }
            view.tag == "setting-icon" -> {
                view.imageTintList = ColorStateList.valueOf(t.primary)
                view.background = ImeDrawableFactory.rounded(
                    ImeSurfacePolicy.subtleAccentSurface(t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }
            view.tag == "undo-toolbar" -> view.imageTintList = ColorStateList.valueOf(t.keySecondaryText)
            view.tag == "keyboard-radio-selected" ->
                view.imageTintList = ColorStateList.valueOf(ImeSurfacePolicy.selectedText(t))
            view.tag == "keyboard-radio-off" ->
                view.imageTintList = ColorStateList.valueOf(t.keySecondaryText)
            view.tag == "key-panel-back" -> {
                view.imageTintList = ColorStateList.valueOf(t.keyText)
                view.background = statefulRounded(
                    t.panelHeadBackground,
                    ImeSurfacePolicy.pressedSurface(t.panelHeadBackground, t),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }
            (
                view.parent is LinearLayout &&
                    (view.parent as LinearLayout).tag == "toolbar-row"
                ) || hasAncestorTag(view, "tools-panel") -> {
                view.imageTintList = ColorStateList.valueOf(if (hasAncestorTag(view, "tools-panel")) ImeSurfacePolicy.selectedText(t) else t.keyText)
                if (view.isClickable) {
                    view.background = statefulRounded(
                        Color.TRANSPARENT,
                        ImeSurfacePolicy.pressedSurface(t.toolbarBackground, t),
                        toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                    )
                }
            }
        }
    }

    private fun applySeekBar(view: SeekBar, t: ImeTheme.Tokens) {
        view.progressDrawable = view.context.getDrawable(android.R.drawable.progress_horizontal)?.mutate()
        view.progressTintList = ColorStateList.valueOf(t.primary)
        view.progressBackgroundTintList = ColorStateList.valueOf(t.functionKeyBackground)
        view.secondaryProgressTintList = ColorStateList.valueOf(t.functionKeyBackground)
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

            tag == "undo-clear-action" -> {
                view.setTextColor(t.primary)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.subtleAccentSurface(t),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "candidate-first" ->
                view.setTextColor(t.keyText)

            tag == "candidate-word" ->
                view.setTextColor(t.candidateText)

            tag == "panel-note" ||
                tag == "setting-value" ||
                tag == "setting-chevron" ||
                tag == "panel-section-title" ->
                view.setTextColor(t.keySecondaryText)

            tag == "setting-label" ->
                view.setTextColor(t.keyText)

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
                view.background = ImeDrawableFactory.rounded(t.toolCardBackground, toPx(10), t.border, toPx(1))
                view.typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            tag == "segment-option" -> { view.setTextColor(t.keySecondaryText); view.background = null }
            tag == "segment-selected-tall" -> {
                view.setTextColor(t.keyText)
                view.background = paintedWithinTarget(
                    ImeDrawableFactory.rounded(t.toolCardBackground, toPx(10), t.border, toPx(1)),
                    SEGMENT_PAINTED_DP,
                )
                view.typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            tag == "segment-option-tall" -> { view.setTextColor(t.keySecondaryText); view.background = null }
            tag == "keyboard-radio-selected" -> view.setTextColor(t.primary)
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
                view.setTextColor(t.keyText)
                view.background = statefulRounded(
                    t.sideKeyBackground,
                    ImeSurfacePolicy.pressedSurface(t.sideKeyBackground, t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }

            tag == "nine-pinyin-path-selected" -> {
                val selected = ImeDrawableFactory.blend(t.primary, t.sideKeyBackground, 0.22f)
                view.setTextColor(ImeSurfacePolicy.accentTextOn(t.primary, selected))
                view.background = statefulRounded(
                    selected,
                    ImeSurfacePolicy.pressedSurface(selected, t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }

            tag == "nine-pinyin-path-filter" -> {
                view.setTextColor(t.sideKeyText)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.sideKeyBackground, t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }

            tag == "accent-custom" -> { view.setTextColor(t.keyText); view.background = null }
            tag?.startsWith("accent-selected-mark:") == true -> {
                val hex = tag.substringAfter(':')
                view.setTextColor(
                    ImeDrawableFactory.contrastText(
                        AccentPalette.parse(hex),
                    ),
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
            "emoji-cell" -> {
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.sideKeyBackground, t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }
            "accent-swatch" -> {
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
                view.background = paintedWithinTarget(
                    ImeDrawableFactory.rounded(
                        ImeSurfacePolicy.switchTrack(enabled, t),
                        toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                    ),
                    ImeGeometryTokens.SWITCH_HEIGHT_DP,
                )
            }
        }
    }

    private fun applyTaggedView(view: View, t: ImeTheme.Tokens) {
        when (view.tag) {
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
                    Color.WHITE,
                    toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                )
            }
            "setting-divider" ->
                view.setBackgroundColor(ImeDrawableFactory.withAlpha(t.border, if (ImeSurfacePolicy.isDark(t)) 150 else 48))
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
    ): InsetDrawable {
        val extra = toPx(ImeGeometryTokens.TOUCH_TARGET_DP) - toPx(paintedDp)
        return InsetDrawable(drawable, 0, extra / 2, 0, extra - extra / 2)
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
