package llc.slacker.openime.data

import android.content.Context
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeTheme

/** Lightweight persistent IME settings. */
object ImeSettingsRepository {

    private const val PREFS = "ime_settings"
    private const val KEY_THEME = "theme"
    private const val KEY_APPEARANCE = "appearance"
    private const val KEY_SOUND = "sound"
    private const val KEY_HAPTIC = "haptic"
    private const val KEY_HAPTIC_STRENGTH = "haptic_strength_percent"
    private const val KEY_HAPTIC_STYLE = "haptic_style"
    private const val KEY_SOUND_STYLE = "key_sound_style"
    private const val KEY_POPUP = "popup"
    private const val KEY_FUZZY = "fuzzy"
    private const val KEY_SWIPE_UP_DIGITS = "swipe_up_digits"
    private const val KEY_LETTER_HINTS = "letter_hints"
    private const val KEY_EMOJI_ASSOCIATION = "emoji_association"
    private const val KEY_VOICE_STRIP_FILLERS = "voice_strip_fillers"
    private const val KEY_VOICE_PUNCTUATION_AS_SPACE = "voice_punctuation_as_space"
    private const val KEY_PREFERRED_CHINESE_MODE = "preferred_chinese_mode"
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
            if (parsed.isChineseLayout) parsed else KeyboardMode.PINYIN_26
        }.getOrDefault(KeyboardMode.PINYIN_26)

    fun savePreferredChineseMode(context: Context, mode: KeyboardMode) {
        if (!mode.isChineseLayout) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFERRED_CHINESE_MODE, mode.name).apply()
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

    /** Key-click strength, 10–100% of the full click. */
    fun loadHapticStrengthPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_HAPTIC_STRENGTH, 100)
            .coerceIn(10, 100)

    fun saveHapticStrengthPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_HAPTIC_STRENGTH, percent.coerceIn(10, 100)).apply()
    }

    /** 震动手感 key: "crisp" (default), "firm" or "system". */
    fun loadHapticStyle(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HAPTIC_STYLE, "crisp") ?: "crisp"

    fun saveHapticStyle(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HAPTIC_STYLE, key).apply()
    }

    /** 按键音效 sound key: "system" (default) or one of the bundled clicks. */
    fun loadKeySoundStyle(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOUND_STYLE, "system") ?: "system"

    fun saveKeySoundStyle(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SOUND_STYLE, key).apply()
    }

    fun loadSwipeUpDigits(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SWIPE_UP_DIGITS, true)

    fun saveSwipeUpDigits(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SWIPE_UP_DIGITS, enabled).apply()
    }

    /** Digits and symbols printed on the letter keys, typed by swipe up or long press. */
    fun loadLetterHints(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LETTER_HINTS, true)

    fun saveLetterHints(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LETTER_HINTS, enabled).apply()
    }

    /** Emoji suggestions in the association row after a word is committed. */
    fun loadEmojiAssociation(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EMOJI_ASSOCIATION, true)

    fun saveEmojiAssociation(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_EMOJI_ASSOCIATION, enabled).apply()
    }

    /** Drop hesitation sounds such as 嗯 / 呃 from voice input. */
    fun loadVoiceStripFillers(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOICE_STRIP_FILLERS, true)

    fun saveVoiceStripFillers(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VOICE_STRIP_FILLERS, enabled).apply()
    }

    /** Write a space instead of a punctuation mark between voice clauses. */
    fun loadVoicePunctuationAsSpace(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOICE_PUNCTUATION_AS_SPACE, false)

    fun saveVoicePunctuationAsSpace(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VOICE_PUNCTUATION_AS_SPACE, enabled).apply()
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
}
