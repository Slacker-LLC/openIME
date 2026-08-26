package llc.slacker.openime

import android.graphics.Color

/**
 * Design tokens migrated from ui-suite/css/themes.css.
 * The five themes map 1:1 to the web prototype's CSS variables.
 */
enum class ImeTheme(val key: String, val label: String) {
    IOS("theme-ios", "iOS Minimal Glass"),
    DARK("theme-dark", "Midnight Dark"),
    CYBERPUNK("theme-cyberpunk", "Cyberpunk Neon"),
    CLASSIC("theme-classic", "Classic Desktop"),
    MACOS("theme-macos", "macOS Tahoe Minimal"),
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
    )

    fun tokens(appearance: ImeAppearance = ImeAppearance.DARK, systemDark: Boolean = false): Tokens {
        fun c(hex: String): Int = Color.parseColor(hex)
        val useDark = when (appearance) {
            ImeAppearance.SYSTEM -> systemDark
            ImeAppearance.LIGHT -> false
            ImeAppearance.DARK -> true
        }
        return when (this) {
            // preview.html 基准调色板（深海军蓝 + 白色九键/数字键 + 灰侧键）
            IOS -> if (useDark) {
                Tokens(
                    c("#4b86f7"), c("#0e1724"), c("#19283c"), c("#19283c"), c("#f2f6fb"),
                    c("#3a4c62"), c("#f7f9fc"), c("#8fa1b5"), c("#27374c"), c("#aeb9c7"), c("#223249"),
                    c("#223249"), Color.argb(20, 255, 255, 255), c("#aeb8c5"), c("#17253a"), c("#101b2b"),
                    c("#ffffff"), c("#111820"), c("#aeb8c5"), c("#0d1218"), c("#23344c"), c("#24354d"),
                )
            } else {
                Tokens(
                    c("#3478f6"), c("#eef2f7"), c("#e2e8f0"), c("#e2e8f0"), c("#1f2937"),
                    c("#ffffff"), c("#172033"), c("#667085"), c("#dfe6ef"), c("#475569"), c("#d4deeb"),
                    c("#ffffff"), Color.argb(30, 15, 23, 42), c("#d5dde8"), c("#f5f7fa"), c("#e7edf4"),
                    c("#ffffff"), c("#172033"), c("#dfe6ef"), c("#334155"), c("#e5ebf2"), c("#dfe6ef"),
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
    }
}
