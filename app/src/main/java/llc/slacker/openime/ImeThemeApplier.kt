package llc.slacker.openime

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
        val side = isSideKey(view)
        val label = view.contentDescription?.toString().orEmpty()
        val function = isFunctionKey(view)
        val white = !side && (
            isWhiteKey(view) ||
                (!function && DIGITS_ONLY.matches(label))
            )
        val primary = !side && (
            view.tag == "tab-active" ||
                view.tag == "key-shift-caps" ||
                view.tag == "key-shift-active" ||
                view.tag == "key-enter"
            )

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
        val keyEdge = ImeDrawableFactory.rounded(pressedColor, skinRadiusPx())
        val keyCap = LayerDrawable(arrayOf(keyEdge, keyFace)).apply {
            setLayerInset(1, 0, 0, 0, toPx(1).coerceAtLeast(1))
        }
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
                view.setColors(t.lightKeyText, t.lightKeyText, t.lightKeyText)
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
            "candidate-first-row" -> {
                val selected = ImeSurfacePolicy.selectedSurface(t)
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
            "nine-symbol-scroll-content",
            "digits-symbol-scroll-content",
            -> {
                view.background = ImeDrawableFactory.rounded(
                    t.sideKeyBackground,
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }
            "setting-group" -> {
                view.background = ImeDrawableFactory.rounded(
                    t.toolCardBackground,
                    toPx(ImeGeometryTokens.CARD_RADIUS_DP),
                    ImeSurfacePolicy.divider(t),
                    toPx(1).coerceAtLeast(1),
                )
            }
            "clip-card" -> {
                view.background = ImeDrawableFactory.rounded(
                    t.toolCardBackground,
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                    ImeSurfacePolicy.divider(t),
                    toPx(1).coerceAtLeast(1),
                )
            }
            "panel-head" -> {
                view.background = ImeDrawableFactory.rounded(
                    t.panelHeadBackground,
                    toPx(ImeGeometryTokens.CARD_RADIUS_DP),
                    ImeSurfacePolicy.divider(t),
                    toPx(1).coerceAtLeast(1),
                )
            }
            "toolbar-row" -> view.setBackgroundColor(t.toolbarBackground)
            "compose-zone" -> view.setBackgroundColor(t.candidateBackground)
            "candidate-field" -> {
                view.background = ImeDrawableFactory.rounded(
                    t.candidateBackground,
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }
            else -> {
                if (
                    (view.tag as? String)?.startsWith("tool:") == true &&
                    view.isClickable
                ) {
                    view.background = statefulRounded(
                        t.toolCardBackground,
                        ImeSurfacePolicy.pressedSurface(t.toolCardBackground, t),
                        toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
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
        if (view.tag == "nine-punct-stack" || view.tag == "digits-symbol-scroll") {
            view.background = ImeDrawableFactory.rounded(
                t.sideKeyBackground,
                toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
            )
        }
    }

    private fun applyImageView(view: ImageView, t: ImeTheme.Tokens) {
        when {
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
            view.tag == "key-panel-back" -> {
                view.imageTintList = ColorStateList.valueOf(t.keySecondaryText)
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
                view.imageTintList = ColorStateList.valueOf(t.keySecondaryText)
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
        view.progressTintList = ColorStateList.valueOf(t.primary)
        view.thumbTintList = ColorStateList.valueOf(t.primary)
        view.progressBackgroundTintList =
            ColorStateList.valueOf(t.panelHeadBackground)
    }

    private fun applyTextView(view: TextView, t: ImeTheme.Tokens) {
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
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "panel-tab" -> {
                view.setTextColor(t.keySecondaryText)
                view.background = statefulRounded(
                    t.panelHeadBackground,
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
                view.background = statefulRounded(
                    t.panelHeadBackground,
                    ImeSurfacePolicy.pressedSurface(t.panelHeadBackground, t),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "clipboard-retention-destructive" ||
                tag == "clipboard-clear-confirm" ||
                tag?.startsWith("phrase-delete:") == true -> {
                val destructiveSurface = ImeSurfacePolicy.destructiveSurface(t)
                view.setTextColor(ImeSurfacePolicy.destructiveText(t))
                view.background = statefulRounded(
                    destructiveSurface,
                    ImeSurfacePolicy.pressedSurface(destructiveSurface, t),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag?.startsWith("punct:") == true ||
                tag?.startsWith("digit-symbol:") == true -> {
                view.setTextColor(t.keyText)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    ImeSurfacePolicy.pressedSurface(t.sideKeyBackground, t),
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }

            tag == "nine-pinyin-path-selected" -> {
                val selected = ImeSurfacePolicy.selectedSurface(t)
                view.setTextColor(ImeSurfacePolicy.selectedText(t))
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

            tag == "accent-custom" -> {
                val customSelected = AccentPalette.presets.none {
                    AccentPalette.normalize(it.first) ==
                        AccentPalette.normalize(skinPrimaryColor())
                }
                view.setTextColor(
                    if (customSelected) {
                        ImeDrawableFactory.contrastText(t.primary)
                    } else {
                        t.keyText
                    },
                )
                view.background = if (customSelected) {
                    statefulRounded(
                        t.primary,
                        ImeSurfacePolicy.primaryPressed(t),
                        toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                    )
                } else {
                    statefulRounded(
                        t.panelHeadBackground,
                        ImeSurfacePolicy.pressedSurface(t.panelHeadBackground, t),
                        toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                    )
                }
            }

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
                    t.toolCardBackground,
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
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
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
                view.background = ImeDrawableFactory.rounded(
                    if (enabled) t.primary else t.panelHeadBackground,
                    toPx(ImeGeometryTokens.PILL_RADIUS_DP),
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
                val parent = view.parent as? FrameLayout
                val seed = parent?.contentDescription?.toString()
                    ?.substringBefore('，')
                    .orEmpty()
                val trackColor =
                    if (toggleState(seed)) t.primary else t.panelHeadBackground
                view.background = ImeDrawableFactory.rounded(
                    ImeDrawableFactory.contrastText(trackColor),
                    toPx(ImeGeometryTokens.PILL_RADIUS_DP),
                )
            }
            "setting-divider" ->
                view.setBackgroundColor(ImeSurfacePolicy.divider(t))
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
        val DIGITS_ONLY = Regex("[0-9]+")
    }
}
