package llc.slacker.openime.core

/**
 * How the keyboard sits when the phone is held sideways.
 *
 * The default keeps the docked keyboard surface and controls consistent with
 * portrait, using the extra width for larger, easier-to-reach keys. Floating
 * and split layouts remain optional alternatives for landscape use.
 */
enum class LandscapeLayout(val key: String, val label: String) {
    FLOATING("floating", "浮动"),
    FULL("full", "整宽"),
    SPLIT("split", "左右分离");

    companion object {
        // "side" was an earlier name of the split layout.
        fun fromKey(key: String?): LandscapeLayout =
            entries.firstOrNull { it.key == key } ?: when (key) {
                "side" -> SPLIT
                else -> FULL
            }
    }
}
