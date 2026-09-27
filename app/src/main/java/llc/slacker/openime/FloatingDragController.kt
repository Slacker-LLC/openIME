package llc.slacker.openime

import android.view.MotionEvent
import kotlin.math.abs

/**
 * Owns pointer state for dragging the floating keyboard handle.
 *
 * Window coordinates and safe bounds remain owned by FloatingWindowController;
 * this class only converts the handle gesture into drag deltas or a dock tap.
 */
internal class FloatingDragController(
    private val toPx: (Int) -> Int,
    private val onDragBy: (Float, Float) -> Unit,
    private val onDock: () -> Unit,
) {
    private var active = false
    private var moved = false
    private var pointerId = -1
    private var lastX = 0f
    private var lastY = 0f

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true
                moved = false
                pointerId = event.getPointerId(event.actionIndex)
                lastX = event.rawX
                lastY = event.rawY
            }

            MotionEvent.ACTION_MOVE -> {
                if (!active) return true
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                val rawX = event.rawX + event.getX(index) - event.x
                val rawY = event.rawY + event.getY(index) - event.y
                val deltaX = rawX - lastX
                val deltaY = rawY - lastY
                if (abs(deltaX) + abs(deltaY) >= toPx(DRAG_THRESHOLD_DP)) {
                    moved = true
                }
                lastX = rawX
                lastY = rawY
                onDragBy(deltaX, deltaY)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (active && event.getPointerId(event.actionIndex) == pointerId) {
                    finish(dockIfTap = true)
                }
            }

            MotionEvent.ACTION_UP -> finish(dockIfTap = true)
            MotionEvent.ACTION_CANCEL -> finish(dockIfTap = false)
        }
        return true
    }

    fun reset() {
        active = false
        moved = false
        pointerId = -1
        lastX = 0f
        lastY = 0f
    }

    private fun finish(dockIfTap: Boolean) {
        val shouldDock = dockIfTap && active && !moved
        reset()
        if (shouldDock) onDock()
    }

    private companion object {
        const val DRAG_THRESHOLD_DP = 3
    }
}
