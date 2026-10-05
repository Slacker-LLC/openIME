package llc.slacker.openime.data

import llc.slacker.openime.R

/** How a key press feels. Labels are what the 震动手感 setting shows. */
enum class HapticStyle(val key: String, val label: String) {
    /** The shortest, sharpest pulse: PRIMITIVE_TICK / EFFECT_TICK / an 8 ms tap. */
    CRISP("crisp", "清脆"),

    /** A fuller click: PRIMITIVE_CLICK / EFFECT_CLICK / a 12 ms tap. */
    FIRM("firm", "有力"),

    /** Whatever the phone maker tuned for keyboards (KEYBOARD_TAP); strength does not apply. */
    SYSTEM("system", "系统"),
    ;

    companion object {
        fun fromKey(key: String?): HapticStyle = entries.firstOrNull { it.key == key } ?: CRISP
    }
}

/**
 * The built-in key-click sounds. [SYSTEM] is the platform keypress sound; the
 * rest are short clicks synthesized by scripts/generate_key_sounds.py, so they
 * carry no third-party rights.
 */
enum class KeySoundStyle(val key: String, val label: String, val rawRes: Int) {
    SYSTEM("system", "系统", 0),
    CRISP("crisp", "清脆", R.raw.key_sound_crisp),
    MECHANICAL("mechanical", "机械", R.raw.key_sound_mechanical),
    WOOD("wood", "木质", R.raw.key_sound_wood),
    TYPEWRITER("typewriter", "打字机", R.raw.key_sound_typewriter),
    BUBBLE("bubble", "气泡", R.raw.key_sound_bubble),
    ;

    companion object {
        fun fromKey(key: String?): KeySoundStyle = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
