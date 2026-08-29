package llc.slacker.openime

import android.content.Context

/** Lightweight persistent IME settings. */
object ImeSettingsRepository {

    private const val PREFS = "ime_settings"
    private const val KEY_THEME = "theme"
    private const val KEY_APPEARANCE = "appearance"
    private const val KEY_SOUND = "sound"
    private const val KEY_HAPTIC = "haptic"
    private const val KEY_POPUP = "popup"
    private const val KEY_FUZZY = "fuzzy"
    private const val KEY_SKIN_OPACITY = "skin_opacity"
    private const val KEY_SKIN_RADIUS = "skin_radius"
    private const val KEY_SKIN_FONT = "skin_font"
    private const val KEY_SKIN_COLOR = "skin_color"

    /**
     * Every ImeTheme ships a complete token set in ImeDesignTokens, but the
     * getter used to hard-code IOS, which made four finished skins unreachable
     * and made a persisted choice impossible to restore.
     */
    fun loadTheme(context: Context): ImeTheme =
        runCatching {
            ImeTheme.valueOf(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_THEME, ImeTheme.IOS.name)
                    ?: ImeTheme.IOS.name,
            )
        }.getOrDefault(ImeTheme.IOS)

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
            .getBoolean(KEY_SOUND, true)

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
            .getInt(KEY_SKIN_OPACITY, 95)

    fun loadSkinRadius(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SKIN_RADIUS, 8)

    fun loadSkinFont(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SKIN_FONT, 17)

    fun loadSkinColor(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIN_COLOR, "#2563eb") ?: "#2563eb"

    fun saveSkin(context: Context, opacity: Int, radius: Int, fontSize: Int, primaryColor: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SKIN_OPACITY, opacity)
            .putInt(KEY_SKIN_RADIUS, radius)
            .putInt(KEY_SKIN_FONT, fontSize)
            .putString(KEY_SKIN_COLOR, primaryColor)
            .apply()
    }
}
