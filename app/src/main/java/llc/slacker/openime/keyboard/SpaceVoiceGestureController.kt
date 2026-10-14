package llc.slacker.openime.keyboard

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Owns the touch state for the combined space/voice key.
 *
 * A short press remains space. A deliberate horizontal drag enters cursor
 * movement before the long-press timeout. Holding still past Android's
 * configured long-press timeout arms voice; sliding upward then toggles the
 * voice cancel preview. Upward movement before the timeout swipes the key's
 * corner mark (the reference keyboard prints 0 on the nine-key and stroke
 * space bar) on the surfaces that carry one.
 */
internal class SpaceVoiceGestureController(
    private val toPx: (Int) -> Int,
    private val canStartVoice: () -> Boolean,
    private val onArmFeedback: () -> Unit,
    private val onVoiceStart: () -> Unit,
    private val onVoiceStop: () -> Unit,
    private val onVoiceCancel: () -> Unit,
    private val onCancelPreviewChanged: (Boolean) -> Unit,
    private val onCursorStep: (Int) -> Unit,
    /** Called with the space key when a cursor drag starts and when it ends. */
    private val onCursorModeChanged: (anchor: View, active: Boolean) -> Unit = { _, _ -> },
    /** True on a surface whose space bar carries a corner mark. */
    private val hintSwipeEnabled: () -> Boolean = { false },
    private val onHintSwipe: () -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()

    var trackingTouch: Boolean = false
        private set

    var active: Boolean = false
        private set

    var pointerId: Int = -1
        private set

    var cursorMode: Boolean = false
        private set

    /** True when an upward swipe owns this gesture (it types the corner mark). */
    var hintMode: Boolean = false
        private set

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var cursorTravel = 0f
    private var cancelPreview = false
    private var anchor: View? = null

    private val armVoice = Runnable {
        if (!trackingTouch || active || cursorMode || !canStartVoice()) return@Runnable
        active = true
        cancelPreview = false
        onArmFeedback()
        onVoiceStart()
    }

    fun begin(
        anchor: View,
        pointerId: Int,
        rawX: Float,
        rawY: Float,
    ) {
        reset()
        trackingTouch = true
        active = false
        cursorMode = false
        hintMode = false
        this.anchor = anchor
        this.pointerId = pointerId
        downX = rawX
        downY = rawY
        lastX = rawX
        cursorTravel = 0f
        cancelPreview = false
        handler.postDelayed(armVoice, longPressTimeoutMs)
    }

    /**
     * @return true after either cursor movement or voice owns the gesture.
     */
    fun move(rawX: Float, rawY: Float): Boolean {
        if (!trackingTouch) return false

        if (!active) {
            val horizontal = rawX - downX
            val vertical = rawY - downY
            // Upward movement belongs to the corner mark on the surfaces that
            // have one: it must win before the cursor drag is considered.
            if (
                !cursorMode &&
                !hintMode &&
                hintSwipeEnabled() &&
                -vertical >= toPx(HINT_SWIPE_START_DP) &&
                -vertical > abs(horizontal) * 1.25f
            ) {
                hintMode = true
                handler.removeCallbacks(armVoice)
                anchor?.isPressed = false
                return true
            }

            if (
                !cursorMode &&
                abs(horizontal) >= toPx(CURSOR_MODE_START_DP) &&
                abs(horizontal) > abs(vertical) * 1.25f
            ) {
                cursorMode = true
                cursorTravel = 0f
                lastX = rawX
                handler.removeCallbacks(armVoice)
                anchor?.let {
                    it.isPressed = false
                    onCursorModeChanged(it, true)
                }
            }

            if (cursorMode) {
                cursorTravel += rawX - lastX
                lastX = rawX
                val step = toPx(CURSOR_STEP_DP).coerceAtLeast(1).toFloat()
                while (abs(cursorTravel) >= step) {
                    val direction = if (cursorTravel > 0f) 1 else -1
                    onCursorStep(direction)
                    cursorTravel -= step * direction
                }
                return true
            }
            return false
        }

        val upwardDistance = downY - rawY
        val cancelNow = if (cancelPreview) {
            upwardDistance >= toPx(CANCEL_EXIT_DISTANCE_DP)
        } else {
            upwardDistance >= toPx(CANCEL_ENTER_DISTANCE_DP)
        }
        if (cancelNow != cancelPreview) {
            cancelPreview = cancelNow
            onCancelPreviewChanged(cancelNow)
        }
        return true
    }

    /**
     * @return true when cursor/voice owned this release; false for a short tap
     * so the normal View click can commit one space.
     */
    fun finish(cancelled: Boolean): Boolean {
        if (!trackingTouch) return false
        handler.removeCallbacks(armVoice)

        if (hintMode) {
            hintMode = false
            trackingTouch = false
            active = false
            pointerId = -1
            downX = 0f
            downY = 0f
            lastX = 0f
            cursorTravel = 0f
            cancelPreview = false
            anchor = null
            if (!cancelled) onHintSwipe()
            return true
        }

        if (cursorMode) {
            endCursorMode()
            trackingTouch = false
            active = false
            pointerId = -1
            downX = 0f
            downY = 0f
            lastX = 0f
            cursorTravel = 0f
            cancelPreview = false
            anchor = null
            return true
        }

        val wasActive = active
        val shouldCancel = wasActive && (cancelPreview || cancelled)
        if (wasActive) anchor?.isPressed = false

        trackingTouch = false
        active = false
        cursorMode = false
        pointerId = -1
        downX = 0f
        downY = 0f
        lastX = 0f
        cursorTravel = 0f
        cancelPreview = false
        anchor = null

        if (wasActive) {
            if (shouldCancel) onVoiceCancel() else onVoiceStop()
        }
        return wasActive
    }

    /**
     * Clears touch ownership without emitting stop/cancel callbacks. Session
     * owners use this after they have already resolved voice lifecycle.
     */
    fun reset() {
        handler.removeCallbacks(armVoice)
        endCursorMode()
        if (active) anchor?.isPressed = false
        trackingTouch = false
        active = false
        cursorMode = false
        hintMode = false
        pointerId = -1
        downX = 0f
        downY = 0f
        lastX = 0f
        cursorTravel = 0f
        cancelPreview = false
        anchor = null
    }

    /** Leaves cursor mode, telling the host to unlock whatever it locked. */
    private fun endCursorMode() {
        if (!cursorMode) return
        cursorMode = false
        anchor?.let { onCursorModeChanged(it, false) }
    }

    fun shutdown() {
        reset()
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val CANCEL_ENTER_DISTANCE_DP = 72
        const val CANCEL_EXIT_DISTANCE_DP = 48
        const val CURSOR_MODE_START_DP = 18
        const val HINT_SWIPE_START_DP = 18
        const val CURSOR_STEP_DP = 12
    }
}
