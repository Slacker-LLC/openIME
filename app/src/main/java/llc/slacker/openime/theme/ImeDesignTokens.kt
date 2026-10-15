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
    /** 键盘高度可调范围（占默认高度的百分比）。所有读写、裁剪、滑块都用这一对常量。 */
    const val KEYBOARD_HEIGHT_MIN_PERCENT = 60
    const val KEYBOARD_HEIGHT_MAX_PERCENT = 160
    const val KEYBOARD_HEIGHT_DEFAULT_PERCENT = 100

    /** 浮动键盘宽度（占竖屏宽度的百分比）。上限 150 允许在平板或横屏上撑得更宽。 */
    const val FLOATING_WIDTH_MIN_PERCENT = 50
    const val FLOATING_WIDTH_MAX_PERCENT = 150
    const val FLOATING_WIDTH_DEFAULT_PERCENT = 100

    /** 浮动键盘不透明度。保留 20% 底线：再低键盘看不见，用户无法回到设置里改回来。 */
    const val FLOATING_OPACITY_MIN_PERCENT = 20
    const val FLOATING_OPACITY_MAX_PERCENT = 100
    const val FLOATING_OPACITY_DEFAULT_PERCENT = 100

    /** 震动强度。0 表示关闭由单独的开关负责，这里只放宽到 1 到 100。 */
    const val HAPTIC_STRENGTH_MIN_PERCENT = 1
    const val HAPTIC_STRENGTH_MAX_PERCENT = 100
    const val HAPTIC_STRENGTH_DEFAULT_PERCENT = 100

    /** Portrait key row height on the reference design's 390-unit canvas. */
    const val KEY_ROW_HEIGHT_DP = 54

    /**
     * A key cap's corner radius. Every key class in the reference asks for the
     * same round: `cls_bk_white_circle` and `cls_bk_gray_circle` both set
     * `bkround="6"` (`style.xml:224-227`, `:276-281`), the symbol and history
     * cells round at 6 (`style.xml:1065`, `:1082`, `:1111`), the three back keys
     * spell out 6 as well (`style.xml:848`, `:854`, `:858`), and so does the
     * long-press bubble's own button (`style.xml:1137`).
     */
    const val KEY_RADIUS_DP = 6
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

    /** All boards keep the original six-unit gap between key caps. */
    const val KEY_MARGIN_X_DP = 3f
    const val KEY_MARGIN_Y_DP = 3f
    const val NINE_KEY_MARGIN_X_DP = 3f
    const val NINE_KEY_MARGIN_Y_DP = 3f
    const val KEY_INNER_MARGIN_DP = 3f

    /**
     * The expanded candidates page insets its content 2.5 units a side,
     * whatever board opened it (`cls_input_kbd_morecand_inset_adaptive`
     * `inset="2.5,0,2.5,0"`, `style.xml:731-733`, on the page itself at
     * `layout/input_more_cand.xml:3`), and draws no top or bottom inset at all.
     * The board area's own insets (`cls_input_kbd_inset_adaptive`, style.xml:727-729,
     * and `cls_input_kbd9_inset_adaptive`, :736-738) are deliberately not ported:
     * they resize the portrait keys, which the owner wants unchanged.
     */
    const val MORE_CAND_SIDE_INSET_DP = 2.5f
    const val KEY_POPUP_HEIGHT_DP = 52
    /**
     * The long-press bubble is rounder than a key and carries no edge of its
     * own: `cls_fusiyin_bubble_container` rounds at 8 and sets only a shadow
     * (`style.xml:1128-1133`).
     */
    const val KEY_POPUP_RADIUS_DP = 8
    /**
     * The tip that shows the pressed key enlarged is a class of its own, and
     * rounder: `cls_touch_tip_button` rounds at 12 with its own small shadow
     * (`style.xml:1168-1173`).
     */
    const val TOUCH_TIP_RADIUS_DP = 12
    const val KEY_POPUP_MIN_WIDTH_DP = 40
    const val KEY_POPUP_WIDTH_SCALE = 1.08f
    const val KEY_POPUP_VERTICAL_GAP_DP = 8
    const val LANDSCAPE_KEY_ROW_HEIGHT_DP = 48
    // The toolbar itself is compact; the top zone still reserves the larger
    // composed height so typing never moves the keyboard window.
    const val TOOLBAR_HEIGHT_DP = TOUCH_TARGET_DP
    const val TOP_BAR_HEIGHT_DP = 48
    const val ICON_SIZE_DP = 24
    // Same as a settings icon tile, so step text lines up with the rows below.
    const val STEP_MARK_SIZE_DP = 32
    const val HERO_MARK_SIZE_DP = 72
    const val COMPOSED_TOP_ZONE_HEIGHT_DP = 60
    // Landscape: keep the same composing/candidate hierarchy, compact enough to leave room for the editor.
    // Space under the bottom row (the keys sit 7dp higher than they used to).
    const val KEYBOARD_TOP_PAD_DP = 4
    const val KEYBOARD_BOTTOM_PAD_DP = 19
    // Landscape keys are short, so the gap under them shrinks (HeliBoard and fcitx5 use none).
    const val LANDSCAPE_BOTTOM_PAD_DP = 6
    const val LANDSCAPE_TOP_ZONE_HEIGHT_DP = 54
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

    /** The open Pinyin string above the candidates (`comp_font` size 40). */
    const val COMPOSITION_SP = 13f

    /** The pressed key shown enlarged: `key_touch_tip`, 87 units (`style.xml:129`). */
    const val TOUCH_TIP_SP = 29f

    /** One choice inside the long-press bubble: `fi_fusiyin_bubble_button`, 60 units. */
    const val POPUP_ITEM_SP = 20f
    const val TITLE_SP = 16f
    const val CANDIDATE_SP = 18f
    const val GLYPH_SP = 20f
    const val KEY_LETTER_SP = 21f
    /** Letters on the 26-key surface, one step below [KEY_LETTER_SP]. */
    const val KEY_LETTER_COMPACT_SP = 19f

    /**
     * English letters on the 26-key surface keep a face of their own: the
     * reference sets `key_letter_english` (60 units) on the lowercase rows and
     * the pinyin face `key_letter` (57) on the shifted ones
     * (`input_kbd_english26.xml:51`).
     */
    const val KEY_LETTER_ENGLISH_SP = 20f

    /** Nine-key letters: `key_letter9`, 48 units over the reference's step of 3. */
    const val NINE_KEY_LETTER_SP = 16f

    /** Every digit the reference draws, nine-key or numeric: `key_number9`, 54 units. */
    const val KEY_NUMBER_SP = 18f
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

    /**
     * The 1-unit line a key cap sits on: every key class in the reference sets
     * `shadow_y_offset="1"` with `keybutton_white_shadow` or
     * `keybutton_gray_shadow` — black at 30% on the light skin, black at 40% on
     * the dark one (`style.xml:224-227`, `:276-281`;
     * `values/colors.xml:27-28`, `values/dark_colors.xml:23-24`).
     */
    /**
     * A candidate cell fills with white when it is pressed: `canditem_pushedbk`
     * is opaque white on the light skin and white at 8% on the dark one
     * (`values/colors.xml:81`, `values/dark_colors.xml:79`). The cell under the
     * finger brightens, the way the key classes brighten a pressed key.
     */
    fun candidatePressed(tokens: ImeTheme.Tokens): Int =
        if (isDark(tokens)) {
            ImeDrawableFactory.blend(tokens.keyText, tokens.candidateBackground, 0.08f)
        } else {
            tokens.lightKeyBackground
        }

    /**
     * An expanded-candidate cell presses the other way: `morecands_button_pushedbk`
     * is black at 6% on the light skin and black at 4% on the dark one — a shade
     * both ways, never a lift (`values/colors.xml:117`,
     * `values/dark_colors.xml:111`).
     */
    fun moreCandsPressed(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(
            Color.BLACK,
            tokens.candidateBackground,
            if (isDark(tokens)) 0.04f else 0.06f,
        )

    /**
     * A symbol-category tab is a neutral pill, never an accent-tinted one: the
     * reference's own tab class wears no fill at rest and takes
     * `symbol_option_pushedbk` when it is pressed or chosen — black at 6% on the
     * light skin, white at 9% on the dark one (`cls_input_kbd_symbol_option`,
     * `style.xml:715-724`, `values/colors.xml:105`,
     * `values/dark_colors.xml:100`). The fill is translucent in the reference,
     * so it is handed over translucent here and lies on whatever the tab row
     * sits on.
     */
    fun symbolTabFill(tokens: ImeTheme.Tokens): Int =
        if (isDark(tokens)) Color.parseColor("#17FFFFFF") else Color.parseColor("#0F000000")

    /** The tab's label: 60% at rest, 85% when chosen (`symbol_option_text` /
     *  `symbol_option_selectedtext`, `values/colors.xml:16-17`). */
    fun symbolTabText(tokens: ImeTheme.Tokens, selected: Boolean): Int =
        ImeDrawableFactory.withAlpha(tokens.keyText, if (selected) 217 else 153)

    /**
     * A row of the reading or symbol rail sits flat on the rail's gray panel and
     * fills with the white key's own colour when pressed: the reference's
     * `cls_vertical_symbol_list_button` asks for `keybutton_bk`
     * (`style.xml:1025-1030`, `values/colors.xml:19`,
     * `values/dark_colors.xml:18`).
     */
    fun railItemPressed(tokens: ImeTheme.Tokens): Int = tokens.lightKeyBackground

    /**
     * The accent key — 确定/发送/搜索 — presses the way a white key looks: the
     * reference fills its action key with `theme_color` and swaps that for
     * `action_button_pushedbk` under the finger, white on the light skin and
     * white at 12% on the dark one, with the label moving to
     * `action_button_pushedtext` (black at 85%) on the light skin
     * (`cls_button_enter`, `style.xml:810-818`, `values/colors.xml:35-38`,
     * `values/dark_colors.xml:34-36`). Its rounded back key presses the same
     * way through `cls_bk_theme_circle` (`style.xml:282-287`).
     */
    fun actionKeyPressed(tokens: ImeTheme.Tokens): Int =
        if (isDark(tokens)) {
            ImeDrawableFactory.blend(tokens.keyText, tokens.keyboardBackground, 0.12f)
        } else {
            tokens.lightKeyBackground
        }

    /**
     * The corner glyph on a key — `keybutton_text_2`, black at 40% on the light
     * skin and white at 40% on the dark one (`cls_keybutton_fusiyin`,
     * `style.xml:207-212`; `values/colors.xml:14`, `values/dark_colors.xml:13`).
     */
    fun keyHint(tokens: ImeTheme.Tokens): Int = ImeDrawableFactory.withAlpha(tokens.keyText, 102)

    /**
     * A face that presses the way every surface in the reference does: the skin
     * writes each press colour as black at 6% on the light skin and white at 4%
     * on the dark one, laid over the ground the face sits on
     * (`toolbar_idle_button_pushedbk`, `values/colors.xml:45`,
     * `values/dark_colors.xml:43`; `setting_button_pushedbk`,
     * `values/colors.xml:89`, `values/dark_colors.xml:87`). A pressed tile or
     * toolbar key therefore sinks toward its page on both skins, where a shade
     * derived from the resting colour brightens it on the dark one.
     */
    fun pressedOnGround(ground: Int, tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(
            if (isDark(tokens)) Color.WHITE else Color.BLACK,
            ground,
            if (isDark(tokens)) 0.04f else 0.06f,
        )

    /** A card or tile that presses onto the page it lies on. */
    fun cardPressed(tokens: ImeTheme.Tokens): Int =
        pressedOnGround(tokens.keyboardBackground, tokens)

    /**
     * An idle-toolbar icon is half of the key text colour, never the key text
     * itself: `toolbar_idle_button_text` is a mid gray on the light skin and
     * half white on the dark one (`cls_toolbar_idle_button`,
     * `style.xml:237-248`, `values/colors.xml:30`, `values/dark_colors.xml:28`).
     */
    fun toolbarIcon(tokens: ImeTheme.Tokens): Int = ImeDrawableFactory.withAlpha(tokens.keyText, 128)

    /**
     * The panel header's close key. Both pages the reference gives a header —
     * the tools page and the keyboard switcher — open with one, drawn as a
     * circle filled `toolbar_idle_button_bk`: white at 80% on the light skin
     * and white at 12% on the dark one (`cld_toolbar_close_button`,
     * `style.xml:266-273`; `input_settings.xml:6`, `input_switch_keyboard.xml:6`;
     * `values/colors.xml:43`, `values/dark_colors.xml:41`). Its press is the
     * skin's usual one — `toolbar_idle_button_pushedbk`, black at 6% on the
     * light skin and white at 4% on the dark one (`values/colors.xml:45`,
     * `values/dark_colors.xml:43`) — the same shade [cardPressed] lays on the
     * header ground.
     */
    fun headerCloseKey(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(
            Color.WHITE,
            tokens.keyboardBackground,
            if (isDark(tokens)) 0.12f else 0.8f,
        )

    /**
     * The close key's glyph is `action_button_pushedtext`: black at 85% on the
     * light skin and full white on the dark one, darker than the idle toolbar's
     * own icon grey (`cld_toolbar_close_button`, `style.xml:266-273`;
     * `values/colors.xml:38`, `values/dark_colors.xml:36`).
     */
    fun headerCloseIcon(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.withAlpha(tokens.keyText, 217)

    fun keyShadow(tokens: ImeTheme.Tokens): Int =
        if (isDark(tokens)) Color.parseColor("#66000000") else Color.parseColor("#4D000000")

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

    /**
     * The tip banner's pill: the reference fills it with `keybutton_bk`, opaque
     * white on the light skin and white at 28% over the keyboard on the dark one
     * (`values/colors.xml:19`, `values/dark_colors.xml:18`).
     */
    fun tipBackground(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(
            Color.WHITE,
            tokens.keyboardBackground,
            if (isDark(tokens)) 0.28f else 1f,
        )

    /** The tip's close key, `toolbar_tip_close_button_bk` (black 4% / white 7% over the pill). */
    fun tipCloseBackground(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(
            if (isDark(tokens)) Color.WHITE else Color.BLACK,
            tipBackground(tokens),
            if (isDark(tokens)) 0.07f else 0.04f,
        )

    /** Its pressed face, `toolbar_tip_close_button_pushedbk` (black 14% / white 17%). */
    fun tipClosePressed(tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(
            if (isDark(tokens)) Color.WHITE else Color.BLACK,
            tipBackground(tokens),
            if (isDark(tokens)) 0.17f else 0.14f,
        )

    /**
     * The long-press bubble's fill, `fusiyin_bubble_container_bk`: an opaque
     * white on the light skin and an opaque gray (96,96,96) on the dark one —
     * not the keyboard's translucent white, which would let the key behind the
     * bubble show through the letter on it (`values/colors.xml:172`,
     * `values/dark_colors.xml:162`).
     */
    fun keyPopupBackground(tokens: ImeTheme.Tokens): Int =
        if (isDark(tokens)) Color.parseColor("#606060") else Color.WHITE

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

    /**
     * The expanded page's list sheet, laid over the page it opens on: the
     * reference names it `morecands_list_bk` — white at 40% on the light skin
     * and white at 6% on the dark one — with its 2-unit edge
     * `morecands_list_border` (black 6% / white 10%) (`cls_morecand_bk`,
     * `style.xml:1162-1166`, `values/colors.xml:121-123`,
     * `values/dark_colors.xml:115-117`). Each candidate cell's hairline is the
     * same border colour at 1 unit (`cls_morecands_button`, `style.xml:1141-1149`).
     */
    fun moreCandsSheet(ground: Int, tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(Color.WHITE, ground, if (isDark(tokens)) 0.06f else 0.4f)

    fun moreCandsSheetEdge(sheet: Int, tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(
            if (isDark(tokens)) Color.WHITE else Color.BLACK,
            sheet,
            if (isDark(tokens)) 0.10f else 0.06f,
        )

    /**
     * The 全部 / 单字 group's own pill, `morecands_filter_container_bk`: black
     * at 4% on the light skin and white at 6% on the dark one
     * (`cls_morecand_filter_containder`, `style.xml:1157-1159`,
     * `values/colors.xml:124`, `values/dark_colors.xml:118`).
     */
    fun filterTrack(ground: Int, tokens: ImeTheme.Tokens): Int =
        ImeDrawableFactory.blend(
            if (isDark(tokens)) Color.WHITE else Color.BLACK,
            ground,
            if (isDark(tokens)) 0.06f else 0.04f,
        )

    /**
     * The chosen option inside that group: opaque white on the light skin, and
     * black at 24% over the group's pill on the dark one, where the reference's
     * chosen chip sinks into its track (`filter_button_selectedbk`,
     * `values/colors.xml:125`, `values/dark_colors.xml:119`).
     */
    fun filterChip(track: Int, tokens: ImeTheme.Tokens): Int =
        if (isDark(tokens)) ImeDrawableFactory.blend(Color.BLACK, track, 0.24f)
        else Color.WHITE

    /** The chosen option's label: near-black on the light skin, white on the dark one. */
    fun filterChipText(tokens: ImeTheme.Tokens): Int =
        if (isDark(tokens)) Color.WHITE else Color.parseColor("#141414")

    /**
     * The expanded page's corner backspace: the reference gives that key its own
     * opaque greys, `morecands_backspace_bk` — rgb(233,233,233) on the light
     * skin, rgb(59,59,59) on the dark one — pressing to rgb(193,193,193) /
     * rgb(42,42,42) (`input_more_cand.xml:22-26`, `values/colors.xml:127-128`,
     * `values/dark_colors.xml:121-122`).
     */
    fun moreCandsBackspace(tokens: ImeTheme.Tokens): Int =
        if (isDark(tokens)) Color.parseColor("#3B3B3B") else Color.parseColor("#E9E9E9")

    fun moreCandsBackspacePressed(tokens: ImeTheme.Tokens): Int =
        if (isDark(tokens)) Color.parseColor("#2A2A2A") else Color.parseColor("#C1C1C1")

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

    /**
     * The first candidate's own colour. The light skin gives it an accent blue
     * (`first_cand_text_color`, `values/colors.xml:4`), but the dark skin's
     * entry for the same name is white at 95% — exactly `canditem_text`
     * (`values/dark_colors.xml:4`, `:75`) — so there the first candidate is set
     * apart by its weight alone, not by a tint.
     */
    fun firstCandidateText(tokens: ImeTheme.Tokens): Int =
        if (isDark(tokens)) tokens.candidateText else selectedText(tokens)

    /**
     * The line between the composing strip and the keys. The reference does not
     * stroke it with a key colour: `cls_candidate_split` is filled with
     * `@color/canditem_split` (`style.xml:504-506`), which is black at 12% on the
     * light skin (`values/colors.xml:83`) and white at 16% on the dark one
     * (`values/dark_colors.xml:81`), so it reads as a gap in the surface.
     */
    fun candidateSplit(tokens: ImeTheme.Tokens): Int =
        if (isDark(tokens)) Color.parseColor("#29FFFFFF") else Color.parseColor("#1F000000")
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
