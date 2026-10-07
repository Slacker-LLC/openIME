package llc.slacker.openime.theme

import android.graphics.Color

/**
 * Shared geometry vocabulary for the native surfaces.
 *
 * Ordinary interactive surfaces use rounded rectangles. The few remaining
 * exceptions are semantic shapes: color swatches and switch knobs are dots,
 * while switches and waveform bars are capsules. Keeping the scale here
 * prevents each panel from inventing another near-identical radius or size.
 */
internal object ImeGeometryTokens {
    /** Portrait key row height on the reference design's 390-unit canvas. */
    const val KEY_ROW_HEIGHT_DP = 54
    const val KEY_RADIUS_DP = 8
    const val CONTROL_RADIUS_DP = 12
    const val CARD_RADIUS_DP = 16
    const val BADGE_RADIUS_DP = 8
    // Kept as a compatibility name for setup callers; setup actions are no
    // longer full pills and use the same radius as every other control.
    const val SETUP_PILL_RADIUS_DP = CONTROL_RADIUS_DP
    const val PILL_RADIUS_DP = 99
    const val DIALOG_RADIUS_DP = CARD_RADIUS_DP

    const val TOUCH_TARGET_DP = 48
    const val FIELD_HEIGHT_DP = 56
    const val PRIMARY_ROW_HEIGHT_DP = 52
    const val SETTING_ROW_HEIGHT_DP = 56
    const val TOOL_CARD_HEIGHT_DP = 80
    const val VOICE_CONTROL_HEIGHT_DP = TOUCH_TARGET_DP
    // iOS switch geometry: a 51x31 track and a 27 knob, so the knob sits 2
    // from every edge, on and off alike.
    const val SWITCH_WIDTH_DP = 51
    const val SWITCH_HEIGHT_DP = 31
    const val SWITCH_KNOB_DP = 27
    const val SWITCH_PADDING_DP = 2
    // ic_chevron_right at 18dp: the stroke ends 6dp short of the box's right
    // edge, so rows pull the box out by that much and the glyph ends on the
    // same 16dp line as switches and values.
    const val CHEVRON_DP = 18
    const val CHEVRON_GLYPH_END_INSET_DP = 6
    const val KEY_GAP_DP = 6
    const val KEY_ROW_GAP_DP = KEY_GAP_DP
    const val KEY_SIDE_MARGIN_DP = 2
    const val KEY_POPUP_HEIGHT_DP = 76
    const val KEY_POPUP_MIN_WIDTH_DP = 40
    const val KEY_POPUP_WIDTH_SCALE = 1.08f
    const val KEY_POPUP_VERTICAL_GAP_DP = 8
    const val LANDSCAPE_KEY_ROW_HEIGHT_DP = 40
    // The toolbar itself is compact; the top zone still reserves the larger
    // composed height so typing never moves the keyboard window.
    const val TOOLBAR_HEIGHT_DP = TOUCH_TARGET_DP
    const val TOP_BAR_HEIGHT_DP = 48
    const val ICON_SIZE_DP = 24
    // Same as a settings icon tile, so step text lines up with the rows below.
    const val STEP_MARK_SIZE_DP = 32
    const val HERO_MARK_SIZE_DP = 72
    const val COMPOSED_TOP_ZONE_HEIGHT_DP = 70
    // Landscape: one strip a little taller than a key row, pinyin and candidates side by side.
    const val LANDSCAPE_TOP_ZONE_HEIGHT_DP = 48
}

/** Shared spacing scale for every non-keyboard layout. */
internal object ImeSpacingTokens {
    const val XXS_DP = 2
    const val XS_DP = 4
    const val SM_DP = 8
    const val MD_DP = 12
    const val LG_DP = 16
    const val XL_DP = 24
    const val XXL_DP = 32
}

/** Shared motion timing for native IME surfaces. */
internal object ImeMotionTokens {
    const val POPUP_ENTER_MS = 80L
    const val SURFACE_FADE_MS = 100L
    const val CANDIDATE_COLLAPSE_MS = 120L
    const val STANDARD_TRANSITION_MS = 160L
}

/**
 * Text roles shared by the keyboard and every app surface. The six keyboard
 * roles come first; the last three are the sizes the reference design uses
 * outside them (helper text on setup/data pages, a glyph handle, and the large
 * digit / symbol keys), named here so no screen invents its own number.
 */
internal object ImeTypographyTokens {
    const val CAPTION_SP = 11f
    const val SMALL_SP = 12f
    const val BODY_SP = 14f
    const val TITLE_SP = 16f
    const val CANDIDATE_SP = 18f
    const val GLYPH_SP = 20f
    const val KEY_LETTER_SP = 21f
    /** Letters on the 26-key surface, one step below [KEY_LETTER_SP]. */
    const val KEY_LETTER_COMPACT_SP = 19f
    const val SYMBOL_SP = 24f
    const val DISPLAY_SP = 28f

    // App-page roles (home, preferences): a grouped list reads one step larger
    // than the keyboard's panels. Mirrored by the ime_text_* dimens.
    const val DETAIL_SP = 13f
    const val ROW_SP = 15f
    const val STATUS_SP = 17f
    const val PAGE_TITLE_SP = 18f
    const val HEADING_SP = 26f

    // Compatibility names for existing callers; every alias resolves to the
    // canonical six-step scale above.
    const val PANEL_TITLE_SP = TITLE_SP
    const val PANEL_BODY_SP = BODY_SP
    const val PANEL_NOTE_SP = BODY_SP
}

/**
 * Native design tokens for the bundled IME renderer.
 *
 * The supplied dual-theme prototype is the visual source of truth for the
 * default iOS skin: "跟随系统" selects the exact dark or light palette at
 * runtime. The legacy theme enum values stay available for state/API
 * compatibility, but the product intentionally exposes only this skin.
 */
enum class ImeTheme(val key: String, val label: String) {
    IOS("theme-ios", "iOS 极简"),
    ;

    data class Tokens(
        val primary: Int,
        val keyboardBackground: Int,
        val toolbarBackground: Int,
        val candidateBackground: Int,
        val candidateText: Int,
        val keyBackground: Int,
        val keyText: Int,
        val keySecondaryText: Int,
        val functionKeyBackground: Int,
        val functionKeyText: Int,
        val keyPressedBackground: Int,
        val compositionBackground: Int,
        val border: Int,
        val sidebarBackground: Int,
        val expandedBackground: Int,
        val canvasBackground: Int,
        val lightKeyBackground: Int,
        val lightKeyText: Int,
        val sideKeyBackground: Int,
        val sideKeyText: Int,
        val toolCardBackground: Int,
        val panelHeadBackground: Int,
        val destructive: Int = Color.parseColor("#F4212E"),
        val success: Int = Color.parseColor("#1F8A4C"),
        val onAccent: Int = Color.parseColor("#07131D"),
        val textSecondaryRole: Int? = null,
    ) {
        val canvas: Int get() = keyboardBackground
        val surface: Int get() = toolbarBackground
        val outline: Int get() = border
        val text: Int get() = keyText
        val textSecondary: Int get() = textSecondaryRole ?: keySecondaryText
        val accent: Int get() = primary
        val danger: Int get() = destructive
        val accentPressed: Int get() = ImeSurfacePolicy.primaryPressed(this)
    }

    fun tokens(
        appearance: ImeAppearance = ImeAppearance.DARK,
        systemDark: Boolean = false,
    ): Tokens {
        fun c(hex: String): Int = Color.parseColor(hex)
        val useDark = when (appearance) {
            ImeAppearance.SYSTEM -> systemDark
            ImeAppearance.LIGHT -> false
            ImeAppearance.DARK -> true
        }
        return if (useDark) {
            Tokens(
                c("#6EC3F7"), c("#1C1C1E"), c("#242426"), c("#242426"), c("#F2F2F7"),
                c("#3A3A3C"), c("#F2F2F7"), c("#AEAEB2"), c("#2C2C2E"), c("#F2F2F7"), c("#4A4A4D"),
                c("#242426"), c("#48484A"), c("#2C2C2E"), c("#1C1C1E"), c("#242426"),
                c("#3A3A3C"), c("#F2F2F7"), c("#2C2C2E"), c("#F2F2F7"), c("#303032"), c("#242426"),
                success = c("#5BD08A"),
            )
        } else {
            Tokens(
                c("#1D9BF0"), c("#D5D8DF"), c("#EFF0F4"), c("#EFF0F4"), c("#1F2023"),
                c("#FFFFFF"), c("#1C1C1E"), c("#6D6D72"), c("#C5C9D2"), c("#2C2D31"), c("#DDE1E7"),
                c("#F2F3F5"), c("#B7BCC5"), c("#C5C9D2"), c("#F1F2F4"), c("#F8F9FA"),
                c("#FFFFFF"), c("#1C1C1E"), c("#C5C9D2"), c("#2C2D31"), c("#FFFFFF"), c("#E4E7EC"),
                success = c("#1F8A4C"),
                textSecondaryRole = c("#6D6D72"),
            )
        }
    }
}

/**
 * Derived interaction roles. Base palettes own neutral hierarchy; these
 * functions derive selected/pressed/disabled surfaces so every panel uses the
 * same state language instead of inventing another gray.
 */
internal object ImeSurfacePolicy {
    const val DISABLED_ALPHA = 0.42f
    private const val TEXT_CONTRAST = 4.5
    private const val SHADE_STEP = 0.03f
    private const val MAX_SHADE_STEPS = 24

    fun isDark(tokens: ImeTheme.Tokens): Boolean =
        ImeContrastPolicy.relativeLuminance(tokens.keyboardBackground) < 0.16

    fun selectedSurface(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(
            tokens.primary,
            tokens.keyboardBackground,
            if (isDark(tokens)) 0.24f else 0.14f,
        )

    /**
     * The accent as text on the keyboard surface. Derived from the active
     * accent (never a fixed blue) so a custom accent stays one colour family
     * across pre-edit text, selected items and icons.
     */
    fun selectedText(tokens: ImeTheme.Tokens): Int =
        accentTextOn(tokens.primary, tokens.keyboardBackground)

    /**
     * [accent] itself when it already reads on [background] (4.5:1); otherwise
     * the nearest shade of the same hue that does. Light surfaces get a
     * darker shade, dark surfaces a lighter one.
     */
    fun accentTextOn(accent: Int, background: Int): Int {
        if (ImeContrastPolicy.contrastRatio(accent, background) >= TEXT_CONTRAST) return accent
        val darker = ImeContrastPolicy.relativeLuminance(background) >= 0.2
        var shade = accent
        repeat(MAX_SHADE_STEPS) {
            shade = adjustHslLightness(shade, if (darker) -SHADE_STEP else SHADE_STEP)
            if (ImeContrastPolicy.contrastRatio(shade, background) >= TEXT_CONTRAST) return shade
        }
        return shade
    }

    fun pressedSurface(base: Int, tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(
            tokens.keyText,
            base,
            if (isDark(tokens)) 0.12f else 0.08f,
        )

    fun primaryPressed(tokens: ImeTheme.Tokens): Int =
        adjustHslLightness(
            tokens.primary,
            if (isDark(tokens)) -0.06f else -0.08f,
        )

    private fun adjustHslLightness(color: Int, delta: Float): Int {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        var h = 0f
        var s = 0f
        val l = (max + min) / 2f
        val d = max - min
        if (d != 0f) {
            s = d / (1f - kotlin.math.abs(2f * l - 1f))
            h = when (max) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * (((b - r) / d) + 2f)
                else -> 60f * (((r - g) / d) + 4f)
            }
            if (h < 0f) h += 360f
        }
        val targetL = (l + delta).coerceIn(0f, 1f)
        val chroma = (1f - kotlin.math.abs(2f * targetL - 1f)) * s
        val x = chroma * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = targetL - chroma / 2f
        val (rr, gg, bb) = when {
            h < 60f -> Triple(chroma, x, 0f)
            h < 120f -> Triple(x, chroma, 0f)
            h < 180f -> Triple(0f, chroma, x)
            h < 240f -> Triple(0f, x, chroma)
            h < 300f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        return Color.rgb(
            ((rr + m) * 255f).toInt().coerceIn(0, 255),
            ((gg + m) * 255f).toInt().coerceIn(0, 255),
            ((bb + m) * 255f).toInt().coerceIn(0, 255),
        )
    }

    fun subtleAccentSurface(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(
            tokens.primary,
            tokens.toolCardBackground,
            if (isDark(tokens)) 0.18f else 0.08f,
        )

    fun destructiveSurface(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(
            tokens.destructive,
            tokens.toolCardBackground,
            if (isDark(tokens)) 0.18f else 0.10f,
        )

    /** Outlined destructive controls (clear / delete): a red tuned to read on panel heads. */
    fun destructiveLabel(tokens: ImeTheme.Tokens): Int =
        if (isDark(tokens)) Color.parseColor("#FF6771") else Color.parseColor("#D60016")

    /** Switch track: the accent marks "on" (an interactive state); off is neutral. */
    fun switchTrack(on: Boolean, tokens: ImeTheme.Tokens): Int = when {
        on -> tokens.primary
        else -> if (isDark(tokens)) Color.parseColor("#3A3D43") else Color.parseColor("#D7DAE0")
    }

    /** Switch knob: white on light surfaces, a soft white on dark ones. */
    fun switchKnob(tokens: ImeTheme.Tokens): Int =
        if (isDark(tokens)) Color.parseColor("#F2F3F5") else Color.WHITE

    /** Inset separator between rows of one grouped card. */
    fun hairline(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(tokens.keyText, tokens.toolCardBackground, if (isDark(tokens)) 0.10f else 0.08f)

    /** Decorative icon tile behind a setting's glyph: neutral, never the accent. */
    fun iconTile(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(tokens.keyText, tokens.toolCardBackground, if (isDark(tokens)) 0.08f else 0.06f)

    fun iconTint(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(tokens.keyText, tokens.toolCardBackground, 0.82f)

    fun chevron(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(tokens.keySecondaryText, tokens.toolCardBackground, 0.62f)

    /** Unfilled slider track and segmented-control track, a step off the card. */
    fun controlTrack(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(tokens.keyText, tokens.toolCardBackground, if (isDark(tokens)) 0.12f else 0.08f)

    /** The selected segment sits lighter than its track in both themes. */
    fun segmentSelected(tokens: ImeTheme.Tokens): Int =
        if (isDark(tokens)) ImeDrawableFactory.blend(tokens.keyText, controlTrack(tokens), 0.18f)
        else tokens.toolCardBackground

    fun destructiveText(tokens: ImeTheme.Tokens): Int {
        val surface = destructiveSurface(tokens)
        return if (ImeContrastPolicy.contrastRatio(tokens.destructive, surface) >= 4.5) {
            tokens.destructive
        } else {
            ImeDrawableFactory.contrastText(surface)
        }
    }

    fun divider(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.withAlpha(
            tokens.border,
            if (isDark(tokens)) 150 else 180,
        )
}

/** Shared WCAG contrast decisions for every native surface. */
internal object ImeContrastPolicy {
    private const val DARK_CONTENT = 0xff07131d.toInt()

    fun contrastText(background: Int): Int {
        val luminance = relativeLuminance(background)
        val whiteContrast = (1.0 + 0.05) / (luminance + 0.05)
        val darkContrast = (luminance + 0.05) / (relativeLuminance(DARK_CONTENT) + 0.05)
        return if (whiteContrast >= darkContrast) 0xffffffff.toInt() else DARK_CONTENT
    }

    fun contrastRatio(first: Int, second: Int): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        val lighter = maxOf(firstLuminance, secondLuminance)
        val darker = minOf(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((color ushr 16) and 0xff) +
            0.7152 * channel((color ushr 8) and 0xff) +
            0.0722 * channel(color and 0xff)
    }
}

/** Select an accessible, accent-aware focus indicator for keyboard navigation. */
internal object ImeFocusRingPolicy {
    fun resolve(background: Int, accent: Int): Int =
        if (ImeContrastPolicy.contrastRatio(accent, background) >= 3.0) {
            accent
        } else {
            ImeContrastPolicy.contrastText(background)
        }
}
