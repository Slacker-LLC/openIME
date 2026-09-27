package llc.slacker.openime

import android.content.res.ColorStateList
import android.graphics.Color
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
            primary -> ImeDrawableFactory.dim(color, 0.88f)
            side -> ImeDrawableFactory.dim(t.sideKeyBackground, 0.88f)
            function -> ImeDrawableFactory.dim(t.functionKeyBackground, 0.88f)
            else -> t.keyPressedBackground
        }

        view.applyMainTextScale(keyMainTextScale())
        view.background = statefulRounded(color, pressedColor, skinRadiusPx())
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
                view.background = statefulRounded(
                    t.keyBackground,
                    t.keyPressedBackground,
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }
            "candidate-row" -> {
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    t.keyPressedBackground,
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }
            "setting-row" -> if (view.isClickable) {
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    t.keyPressedBackground,
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
                )
            }
            "clip-card" -> {
                view.background = ImeDrawableFactory.rounded(
                    t.toolCardBackground,
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }
            "panel-head" -> {
                view.background = ImeDrawableFactory.rounded(
                    t.panelHeadBackground,
                    toPx(ImeGeometryTokens.CARD_RADIUS_DP),
                )
            }
            else -> {
                if (
                    (view.tag as? String)?.startsWith("tool:") == true &&
                    view.isClickable
                ) {
                    view.background = statefulRounded(
                        t.toolCardBackground,
                        t.keyPressedBackground,
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
                t.keyPressedBackground,
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
            view.tag == "setting-icon" -> {
                val icon = t.primary
                view.imageTintList = ColorStateList.valueOf(icon)
                val dark =
                    ImeDrawableFactory.contrastText(t.keyboardBackground) ==
                        Color.WHITE
                view.background = ImeDrawableFactory.rounded(
                    if (dark) {
                        Color.argb(
                            42,
                            Color.red(icon),
                            Color.green(icon),
                            Color.blue(icon),
                        )
                    } else {
                        Color.argb(
                            24,
                            Color.red(icon),
                            Color.green(icon),
                            Color.blue(icon),
                        )
                    },
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }
            view.tag == "key-panel-back" -> {
                view.imageTintList = ColorStateList.valueOf(t.keyText)
                view.background = statefulRounded(
                    t.panelHeadBackground,
                    ImeDrawableFactory.dim(t.panelHeadBackground),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }
            (
                view.parent is LinearLayout &&
                    (view.parent as LinearLayout).tag == "toolbar-row"
                ) || hasAncestorTag(view, "tools-panel") -> {
                view.imageTintList = ColorStateList.valueOf(t.keyText)
                if (view.isClickable) {
                    view.background = statefulRounded(
                        Color.TRANSPARENT,
                        t.keyPressedBackground,
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

            tag == "candidate-first" ->
                view.setTextColor(t.keyText)

            tag == "candidate-word" ->
                view.setTextColor(t.candidateText)

            tag == "panel-note" ->
                view.setTextColor(t.keySecondaryText)

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
                view.setTextColor(ImeDrawableFactory.contrastText(t.primary))
                view.background = statefulRounded(
                    t.primary,
                    ImeDrawableFactory.dim(t.primary),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "panel-tab" -> {
                view.setTextColor(t.keySecondaryText)
                view.background = statefulRounded(
                    t.panelHeadBackground,
                    ImeDrawableFactory.dim(t.panelHeadBackground),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "quick-phrase-add" -> {
                view.setTextColor(ImeDrawableFactory.contrastText(t.primary))
                view.background = statefulRounded(
                    t.primary,
                    ImeDrawableFactory.dim(t.primary),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "panel-button" ||
                tag == "clipboard-refresh" ||
                tag?.startsWith("clip-pin:") == true ||
                tag?.startsWith("clip-use:") == true ||
                tag?.startsWith("phrase-edit:") == true ||
                tag?.startsWith("phrase-delete:") == true -> {
                view.setTextColor(t.keyText)
                view.background = statefulRounded(
                    t.panelHeadBackground,
                    ImeDrawableFactory.dim(t.panelHeadBackground),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "clipboard-retention-action" -> {
                view.setTextColor(t.keyText)
                view.background = statefulRounded(
                    t.panelHeadBackground,
                    ImeDrawableFactory.dim(t.panelHeadBackground),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "clipboard-retention-destructive" -> {
                view.setTextColor(
                    ImeDrawableFactory.contrastText(t.destructive),
                )
                view.background = statefulRounded(
                    t.destructive,
                    ImeDrawableFactory.dim(t.destructive, 0.86f),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag?.startsWith("punct:") == true ||
                tag?.startsWith("digit-symbol:") == true -> {
                view.setTextColor(t.keyText)
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    t.keyPressedBackground,
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }

            tag == "nine-pinyin-path-filter" -> {
                view.setTextColor(ImeDrawableFactory.contrastText(t.primary))
                view.background = statefulRounded(
                    t.primary,
                    ImeDrawableFactory.dim(t.primary, 0.86f),
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
                        ImeDrawableFactory.dim(t.primary),
                        toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                    )
                } else {
                    statefulRounded(
                        t.panelHeadBackground,
                        ImeDrawableFactory.dim(t.panelHeadBackground),
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
                    ImeDrawableFactory.dim(t.panelHeadBackground),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "panel-title" ->
                view.setTextColor(t.keyText)

            tag == "candidate-emoji" || tag == "candidate-expand" -> {
                view.setTextColor(t.keySecondaryText)
                view.background = statefulRounded(
                    t.panelHeadBackground,
                    ImeDrawableFactory.dim(t.panelHeadBackground),
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
                    ImeDrawableFactory.dim(t.toolCardBackground),
                    toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
                )
            }

            tag == "tools-page-dots" ->
                view.setTextColor(t.keySecondaryText)

            tag == "voice-mic" -> {
                view.setTextColor(
                    ImeDrawableFactory.contrastText(t.primary),
                )
                view.background = statefulRounded(
                    t.primary,
                    ImeDrawableFactory.dim(t.primary, 0.88f),
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
                    t.keyPressedBackground,
                    toPx(ImeGeometryTokens.KEY_RADIUS_DP),
                )
            }
            "accent-swatch" -> {
                view.background = statefulRounded(
                    Color.TRANSPARENT,
                    t.keyPressedBackground,
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
            "setting-divider" ->
                view.setBackgroundColor(t.border)
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
