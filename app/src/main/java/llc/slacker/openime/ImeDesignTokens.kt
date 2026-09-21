package llc.slacker.openime

import android.graphics.Color

/**
 * Shared geometry vocabulary for the native surfaces.
 *
 * These values are semantic rather than one universal radius: keys need a
 * tighter silhouette, cards need a calmer container edge, and pills are
 * intentionally fully rounded. Keeping the scale here prevents each panel
 * from inventing another near-identical corner radius.
 */
internal object ImeGeometryTokens {
    const val KEY_RADIUS_DP = 8
    const val CONTROL_RADIUS_DP = 12
    const val CARD_RADIUS_DP = 16
    const val SETUP_PILL_RADIUS_DP = 28
    const val PILL_RADIUS_DP = 99

    const val TOUCH_TARGET_DP = 48
    const val PRIMARY_ROW_HEIGHT_DP = 56
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
    )

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
                Tokens(
                    c("#afc6ff"), c("#181a1e"), c("#202228"), c("#202228"), c("#e8e9ed"),
                    c("#303238"), c("#e8e9ed"), c("#b3b7c2"), c("#25272c"), c("#d7dae2"), c("#3a3d44"),
                    c("#25272c"), c("#343740"), c("#25272c"), c("#181a1e"), c("#202228"),
                    c("#303238"), c("#e8e9ed"), c("#25272c"), c("#d7dae2"), c("#25272c"), c("#202228"),
                )
            } else {
                Tokens(
                    c("#1D9BF0"), c("#e6e7eb"), c("#f0f1f3"), c("#f0f1f3"), c("#202124"),
                    c("#fafafb"), c("#202124"), c("#555b66"), c("#d2d4db"), c("#374151"), c("#e3e5e9"),
                    c("#f5f6f8"), c("#c8cbd2"), c("#d2d4db"), c("#e6e7eb"), c("#f0f1f3"),
                    c("#fafafb"), c("#202124"), c("#d2d4db"), c("#374151"), c("#f8f9fa"), c("#f0f1f3"),
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
