package llc.slacker.openime

import android.graphics.Color

/**
 * Native design tokens for the bundled IME renderer.
 *
 * The supplied dual-theme prototype is the visual source of truth for the
 * default iOS skin: "跟随系统" selects the exact dark or light palette at
 * runtime. The legacy theme enum values stay available for state/API
 * compatibility, but the product intentionally exposes only this skin.
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
            // minis_ime_dual_theme_renderer.html 的 Dark Obsidian / Light Crystal
            // 调色板。390 × 296 只是设计基准，尺寸仍由原生 View 的实际窗口计算。
            IOS -> if (useDark) {
                Tokens(
                    c("#3b82f6"), c("#13151b"), c("#171b22"), c("#171b22"), c("#f1f5f9"),
                    c("#2d3341"), c("#f1f5f9"), c("#94a3b8"), c("#1f232d"), c("#94a3b8"), c("#3c4456"),
                    Color.argb(9, 255, 255, 255), Color.argb(20, 255, 255, 255), c("#1f232d"), c("#13151b"), c("#1f232d"),
                    c("#2d3341"), c("#f1f5f9"), c("#1f232d"), c("#94a3b8"), Color.argb(9, 255, 255, 255), c("#171b22"),
                )
            } else {
                Tokens(
                    c("#007aff"), c("#d3d7de"), c("#e2e6ec"), c("#e2e6ec"), c("#0f172a"),
                    c("#ffffff"), c("#0f172a"), c("#475569"), c("#abb4c2"), c("#475569"), c("#eceff3"),
                    Color.argb(30, 0, 0, 0), Color.argb(15, 0, 0, 0), c("#abb4c2"), c("#d3d7de"), c("#ffffff"),
                    c("#ffffff"), c("#0f172a"), c("#abb4c2"), c("#475569"), Color.argb(153, 255, 255, 255), c("#e2e6ec"),
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
