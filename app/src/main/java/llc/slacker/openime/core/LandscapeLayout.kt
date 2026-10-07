package llc.slacker.openime.core

/**
 * How the keyboard sits when the phone is held sideways.
 *
 * WeChat Keyboard floats by itself in landscape and Gboard does the same; the
 * Chinese keyboards otherwise stretch the full width. Floating is the default
 * because a full-width keyboard of four rows covers most of a landscape screen.
 */
enum class LandscapeLayout(val key: String, val label: String) {
    FLOATING("floating", "浮动"),
    FULL("full", "整宽"),
    SIDE("side", "左右栏");

    companion object {
        fun fromKey(key: String?): LandscapeLayout = entries.firstOrNull { it.key == key } ?: FLOATING
    }
}
