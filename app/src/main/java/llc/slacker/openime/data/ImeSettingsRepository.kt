package llc.slacker.openime.data

import android.content.Context
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.theme.AccentPalette
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeTheme

enum class ImeHandedness(val label: String) {
    STANDARD("标准"),
    LEFT("左手"),
    RIGHT("右手"),
}

/** Lightweight persistent IME settings. */
object ImeSettingsRepository {

    private const val PREFS = "ime_settings"
    private const val KEY_THEME = "theme"
    private const val KEY_APPEARANCE = "appearance"
    private const val KEY_SOUND = "sound"
    private const val KEY_HAPTIC = "haptic"
    private const val KEY_POPUP = "popup"
    private const val KEY_FUZZY = "fuzzy"
    private const val KEY_SWIPE_UP_DIGITS = "swipe_up_digits"
    private const val KEY_SKIN_OPACITY = "skin_opacity"
    private const val KEY_SKIN_RADIUS = "skin_radius"
    private const val KEY_SKIN_FONT = "skin_font"
    private const val KEY_SKIN_COLOR = "skin_color"
    private const val KEY_PREFERRED_CHINESE_MODE = "preferred_chinese_mode"
    private const val KEY_HANDEDNESS = "handedness"
    private const val KEY_KEYBOARD_HEIGHT = "keyboard_height_percent"
    private const val KEY_FLOATING_WIDTH = "floating_width_percent"
    private const val KEY_FLOATING_OPACITY = "floating_opacity_percent"

    /**
     * The user's preferred Chinese layout (26-key vs 9-key). Only PINYIN_26 and
     * PINYIN_9 are valid; anything else falls back to 26-key.
     */
    fun loadPreferredChineseMode(context: Context): KeyboardMode =
        runCatching {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PREFERRED_CHINESE_MODE, KeyboardMode.PINYIN_26.name)
                ?: KeyboardMode.PINYIN_26.name
            val parsed = KeyboardMode.valueOf(name)
            if (parsed == KeyboardMode.PINYIN_9 || parsed == KeyboardMode.PINYIN_26) parsed
            else KeyboardMode.PINYIN_26
        }.getOrDefault(KeyboardMode.PINYIN_26)

    fun savePreferredChineseMode(context: Context, mode: KeyboardMode) {
        if (mode != KeyboardMode.PINYIN_26 && mode != KeyboardMode.PINYIN_9) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFERRED_CHINESE_MODE, mode.name).apply()
    }

    fun loadHandedness(context: Context): ImeHandedness =
        runCatching {
            ImeHandedness.valueOf(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_HANDEDNESS, ImeHandedness.STANDARD.name)
                    ?: ImeHandedness.STANDARD.name,
            )
        }.getOrDefault(ImeHandedness.STANDARD)

    fun saveHandedness(context: Context, handedness: ImeHandedness) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HANDEDNESS, handedness.name).apply()
    }

    fun loadKeyboardHeightPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_KEYBOARD_HEIGHT, 100)
            .coerceIn(80, 120)

    fun saveKeyboardHeightPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_KEYBOARD_HEIGHT, percent.coerceIn(80, 120)).apply()
    }

    fun loadFloatingWidthPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FLOATING_WIDTH, 100)
            .coerceIn(72, 100)

    fun saveFloatingWidthPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FLOATING_WIDTH, percent.coerceIn(72, 100)).apply()
    }

    fun loadFloatingOpacityPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FLOATING_OPACITY, 100)
            .coerceIn(82, 100)

    fun saveFloatingOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FLOATING_OPACITY, percent.coerceIn(82, 100)).apply()
    }

    internal fun parseTheme(value: String?): ImeTheme =
        runCatching { ImeTheme.valueOf(value ?: ImeTheme.IOS.name) }
            .getOrDefault(ImeTheme.IOS)

    /**
     * Historical theme names can still exist in SharedPreferences after an
     * upgrade. They intentionally fall back to the only supported skin.
     */
    fun loadTheme(context: Context): ImeTheme =
        parseTheme(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, ImeTheme.IOS.name),
        )

    fun saveTheme(context: Context, theme: ImeTheme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, theme.name).apply()
    }

    fun loadAppearance(context: Context): ImeAppearance =
        runCatching {
            ImeAppearance.valueOf(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_APPEARANCE, ImeAppearance.SYSTEM.name)
                    ?: ImeAppearance.SYSTEM.name,
            )
        }.getOrDefault(ImeAppearance.SYSTEM)

    fun saveAppearance(context: Context, appearance: ImeAppearance) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_APPEARANCE, appearance.name).apply()
    }

    fun loadSound(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SOUND, false)

    fun saveSound(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SOUND, enabled).apply()
    }

    fun loadHaptic(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAPTIC, true)

    fun saveHaptic(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HAPTIC, enabled).apply()
    }

    fun loadSwipeUpDigits(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SWIPE_UP_DIGITS, true)

    fun saveSwipeUpDigits(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SWIPE_UP_DIGITS, enabled).apply()
    }

    fun loadPopup(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_POPUP, true)

    fun savePopup(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_POPUP, enabled).apply()
    }

    fun loadFuzzy(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FUZZY, false)

    fun saveFuzzy(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FUZZY, enabled).apply()
    }

    fun loadSkinOpacity(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SKIN_OPACITY, 100)

    fun loadSkinRadius(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SKIN_RADIUS, 8)

    fun loadSkinFont(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SKIN_FONT, 21)

    fun loadSkinColor(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIN_COLOR, AccentPalette.DEFAULT) ?: AccentPalette.DEFAULT

    fun saveSkin(context: Context, opacity: Int, radius: Int, fontSize: Int, primaryColor: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SKIN_OPACITY, opacity)
            .putInt(KEY_SKIN_RADIUS, radius)
            .putInt(KEY_SKIN_FONT, fontSize)
            .putString(KEY_SKIN_COLOR, AccentPalette.normalize(primaryColor))
            .apply()
    }
}
