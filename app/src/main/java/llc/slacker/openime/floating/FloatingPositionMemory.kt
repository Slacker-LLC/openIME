package llc.slacker.openime.floating

import android.content.res.Configuration

/**
 * Remembers the floating card's top-left position per orientation.
 *
 * Rotating the device swaps the coordinate space under the card; keeping the
 * old numbers puts it in a random-looking place. Each orientation therefore
 * keeps its own last position, so rotating back restores where the user left
 * the keyboard there.
 */
internal class FloatingPositionMemory {
    private var portrait: IntArray? = null
    private var landscape: IntArray? = null

    fun save(orientation: Int, x: Int, y: Int) {
        val slot = slot(orientation)
        slot[0] = x
        slot[1] = y
    }

    /** The stored position for [orientation], or null when it was never placed. */
    fun restore(orientation: Int): IntArray? {
        val slot = slot(orientation)
        return if (slot[0] < 0 && slot[1] < 0) null else slot.copyOf()
    }

    private fun slot(orientation: Int): IntArray = when (orientation) {
        Configuration.ORIENTATION_LANDSCAPE ->
            landscape ?: IntArray(2) { UNKNOWN }.also { landscape = it }
        else ->
            portrait ?: IntArray(2) { UNKNOWN }.also { portrait = it }
    }

    private companion object {
        const val UNKNOWN = -1
    }
}
