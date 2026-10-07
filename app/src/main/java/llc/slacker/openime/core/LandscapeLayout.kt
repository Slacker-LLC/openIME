package llc.slacker.openime.core

/**
 * How the keyboard sits when the phone is held sideways.
 *
 * WeChat Keyboard floats by itself in landscape and Gboard does the same; the
 * Chinese keyboards otherwise stretch the full width. Sogou also offers a split
 * keyboard, one half under each thumb. Floating is the default because a
 * full-width keyboard of four rows covers most of a landscape screen.
 */
enum class LandscapeLayout(val key: String, val label: String) {
    FLOATING("floating", "浮动"),
    FULL("full", "整宽"),
    SPLIT("split", "左右分离");

    companion object {
        // "side" was an earlier name of the split layout.
        fun fromKey(key: String?): LandscapeLayout =
            entries.firstOrNull { it.key == key } ?: if (key == "side") SPLIT else FLOATING
    }
}
