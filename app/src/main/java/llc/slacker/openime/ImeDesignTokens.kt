package llc.slacker.openime

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
    const val PRIMARY_ROW_HEIGHT_DP = 56
    const val SETTING_ROW_HEIGHT_DP = 56
    const val TOOL_CARD_HEIGHT_DP = 64
    const val VOICE_CONTROL_HEIGHT_DP = TOUCH_TARGET_DP
    const val SWITCH_WIDTH_DP = 48
    const val SWITCH_HEIGHT_DP = 28
    const val SWITCH_KNOB_DP = 20
    const val SWITCH_PADDING_DP = 4
    const val SWITCH_KNOB_TRAVEL_DP = SWITCH_WIDTH_DP - SWITCH_PADDING_DP * 2 - SWITCH_KNOB_DP
    const val KEY_GAP_DP = 6
    const val KEY_ROW_GAP_DP = KEY_GAP_DP
    const val KEY_SIDE_MARGIN_DP = 2
    const val KEY_POPUP_HEIGHT_DP = 76
    const val KEY_POPUP_MIN_WIDTH_DP = 40
    const val KEY_POPUP_WIDTH_SCALE = 1.08f
    const val KEY_POPUP_VERTICAL_GAP_DP = 8
    const val LANDSCAPE_KEY_ROW_HEIGHT_DP = 40
    const val FLOATING_LANDSCAPE_WIDTH_DP = 368
    // The toolbar itself is compact; the top zone still reserves the larger
    // composed height so typing never moves the keyboard window.
    const val TOOLBAR_HEIGHT_DP = TOUCH_TARGET_DP
    const val COMPOSED_TOP_ZONE_HEIGHT_DP = 70
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

/** Six text roles shared by the keyboard and every app surface. */
internal object ImeTypographyTokens {
    const val CAPTION_SP = 11f
    const val BODY_SP = 14f
    const val TITLE_SP = 16f
    const val CANDIDATE_SP = 18f
    const val KEY_LETTER_SP = 21f
    const val DISPLAY_SP = 28f

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
    IOS("theme-ios", "iOS 极简玻璃"),
    DARK("theme-dark", "午夜深色"),
    CYBERPUNK("theme-cyberpunk", "赛博霓虹"),
    CLASSIC("theme-classic", "经典桌面"),
    MACOS("theme-macos", "macOS Tahoe 极简"),
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
    ) {
        val canvas: Int get() = keyboardBackground
        val surface: Int get() = toolbarBackground
        val outline: Int get() = border
        val text: Int get() = keyText
        val textSecondary: Int get() = keySecondaryText
        val accent: Int get() = primary
        val danger: Int get() = destructive
        val accentPressed: Int get() = ImeSurfacePolicy.primaryPressed(this)
    }

    fun tokens(
        appearance: ImeAppearance = ImeAppearance.DARK,
        systemDark: Boolean = false,
        accentOverride: Int? = null,
    ): Tokens {
        fun c(hex: String): Int = Color.parseColor(hex)
        val useDark = when (appearance) {
            ImeAppearance.SYSTEM -> systemDark
            ImeAppearance.LIGHT -> false
            ImeAppearance.DARK -> true
        }
        val base = when (this) {
            // minis_ime_dual_theme_renderer.html 的 Dark Obsidian / Light Crystal
            // 调色板。390 × 296 只是设计基准，尺寸仍由原生 View 的实际窗口计算。
            IOS -> if (useDark) {
                // Dark mode is a separate neutral scale rather than an
                // inverted light palette. The keyboard base stays darkest,
                // controls sit one surface above it, and typing keys occupy
                // the clearest foreground plane.
                Tokens(
                    c("#6EC3F7"), c("#1C1C1E"), c("#242426"), c("#262628"), c("#F2F2F7"),
                    c("#3A3A3C"), c("#F2F2F7"), c("#AEAEB2"), c("#2C2C2E"), c("#F2F2F7"), c("#4A4A4D"),
                    c("#242426"), c("#48484A"), c("#2C2C2E"), c("#202022"), c("#242426"),
                    c("#3A3A3C"), c("#F2F2F7"), c("#2C2C2E"), c("#F2F2F7"), c("#303033"), c("#242426"),
                    success = c("#5BD08A"),
                )
            } else {
                // Light mode deliberately separates the cool-gray keyboard
                // base, secondary controls and near-white typing surfaces.
                // Text remains neutral instead of using absolute black.
                Tokens(
                    c("#1D9BF0"), c("#D5D8DE"), c("#EEF0F3"), c("#F7F8FA"), c("#1F2023"),
                    c("#FFFFFF"), c("#1C1C1E"), c("#6E6E73"), c("#C5C9D1"), c("#2C2D31"), c("#DDE1E7"),
                    c("#F2F3F5"), c("#B7BCC5"), c("#C5C9D1"), c("#F1F2F4"), c("#F8F9FA"),
                    c("#FFFFFF"), c("#1C1C1E"), c("#C5C9D1"), c("#2C2D31"), c("#FFFFFF"), c("#E4E7EB"),
                    success = c("#1F8A4C"),
                )
            }
            DARK -> Tokens(
                c("#3b82f6"), c("#1e293b"), c("#0f172a"), c("#0f172a"), c("#f1f5f9"),
                c("#334155"), c("#f8fafc"), c("#94a3b8"), c("#1e293b"), c("#94a3b8"), c("#475569"),
                c("#1e3a8a"), Color.argb(20, 255, 255, 255), c("#475569"), c("#0f172a"), c("#0f172a"),
                c("#e2e8f0"), c("#0f172a"), c("#475569"), c("#f1f5f9"), c("#1e293b"), c("#0f172a"),
            )
            CYBERPUNK -> Tokens(
                c("#ff007f"), c("#0d0e1f"), c("#12142e"), c("#080918"), c("#00f0ff"),
                c("#161838"), c("#00f0ff"), c("#ffe600"), c("#090a18"), c("#00f0ff"), c("#ff007f"),
                c("#2a0845"), Color.argb(76, 0, 240, 255), c("#161838"), c("#080918"), c("#080918"),
                c("#161838"), c("#00f0ff"), c("#090a18"), c("#ffe600"), c("#12142e"), c("#0d0e1f"),
            )
            CLASSIC -> Tokens(
                c("#e11d48"), c("#f3f4f6"), c("#ffffff"), c("#ffffff"), c("#111827"),
                c("#ffffff"), c("#111827"), c("#9ca3af"), c("#e5e7eb"), c("#374151"), c("#d1d5db"),
                c("#fee2e2"), c("#d1d5db"), c("#e5e7eb"), c("#ffffff"), c("#ffffff"),
                c("#ffffff"), c("#111827"), c("#e5e7eb"), c("#111827"), c("#f8fafc"), c("#e2e8f0"),
            )
            MACOS -> Tokens(
                c("#0ea5e9"), c("#e2e8f0"), c("#ffffff"), c("#ffffff"), c("#1e293b"),
                c("#ffffff"), c("#0f172a"), c("#64748b"), c("#cbd5e1"), c("#475569"), c("#94a3b8"),
                c("#e0f2fe"), Color.argb(15, 0, 0, 0), c("#cbd5e1"), c("#ffffff"), c("#ffffff"),
                c("#ffffff"), c("#0f172a"), c("#cbd5e1"), c("#0f172a"), c("#f1f5f9"), c("#e2e8f0"),
            )
        }
        val accent = accentOverride ?: return base
        return base.copy(primary = accent)
    }
}

/**
 * Derived interaction roles. Base palettes own neutral hierarchy; these
 * functions derive selected/pressed/disabled surfaces so every panel uses the
 * same state language instead of inventing another gray.
 */
internal object ImeSurfacePolicy {
    const val DISABLED_ALPHA = 0.42f

    fun isDark(tokens: ImeTheme.Tokens): Boolean =
        ImeContrastPolicy.relativeLuminance(tokens.keyboardBackground) < 0.16

    fun selectedSurface(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(
            tokens.primary,
            tokens.candidateBackground,
            if (isDark(tokens)) 0.24f else 0.12f,
        )

    fun selectedText(tokens: ImeTheme.Tokens): Int =
        if (
            ImeContrastPolicy.contrastRatio(tokens.primary, selectedSurface(tokens)) >= 4.5
        ) {
            tokens.primary
        } else {
            tokens.keyText
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
