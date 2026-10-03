package llc.slacker.openime.widget

import kotlin.math.abs

/**
 * Recognizes an upward swipe that starts on a key. Pure: feed it raw pointer
 * coordinates and it answers, once per gesture, whether the finger has moved
 * far enough up (and more up than sideways) to count as the swipe.
 */
internal class SwipeUpDetector(private val thresholdPx: Float) {
    private var downX = 0f
    private var downY = 0f
    private var fired = false

    fun down(x: Float, y: Float) {
        downX = x
        downY = y
        fired = false
    }

    /** True exactly once per gesture: the first move that qualifies as the swipe. */
    fun move(x: Float, y: Float): Boolean {
        if (fired) return false
        val up = downY - y
        if (up >= thresholdPx && up > abs(x - downX)) {
            fired = true
            return true
        }
        return false
    }
}
