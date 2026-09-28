package llc.slacker.openime

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewConfiguration

/**
 * Owns the touch state for the combined space/voice key.
 *
 * A short press remains the key's normal click. Holding past Android's
 * configured long-press timeout arms voice; sliding upward toggles cancel
 * preview, and release either stops or cancels the active voice gesture.
 */
internal class SpaceVoiceGestureController(
    private val toPx: (Int) -> Int,
    private val canStartVoice: () -> Boolean,
    private val onArmFeedback: () -> Unit,
    private val onVoiceStart: () -> Unit,
    private val onVoiceStop: () -> Unit,
    private val onVoiceCancel: () -> Unit,
    private val onCancelPreviewChanged: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()

    var trackingTouch: Boolean = false
        private set

    var active: Boolean = false
        private set

    var pointerId: Int = -1
        private set

    private var downY = 0f
    private var cancelPreview = false
    private var anchor: View? = null

    private val armVoice = Runnable {
        if (!trackingTouch || active || !canStartVoice()) return@Runnable
        active = true
        cancelPreview = false
        onArmFeedback()
        onVoiceStart()
    }

    fun begin(
        anchor: View,
        pointerId: Int,
        rawY: Float,
    ) {
        reset()
        trackingTouch = true
        active = false
        this.anchor = anchor
        this.pointerId = pointerId
        downY = rawY
        cancelPreview = false
        handler.postDelayed(armVoice, longPressTimeoutMs)
    }

    /**
     * @return true once voice owns the gesture, so the normal key click is
     * suppressed for MOVE/UP after the long press has armed.
     */
    fun move(rawY: Float): Boolean {
        if (!trackingTouch || !active) return false
        val cancelNow = downY - rawY >= toPx(CANCEL_DISTANCE_DP)
        if (cancelNow != cancelPreview) {
            cancelPreview = cancelNow
            onCancelPreviewChanged(cancelNow)
        }
        return true
    }

    /**
     * @return true when an armed voice gesture consumed this release; false
     * for a short press so the normal View click can commit a space.
     */
    fun finish(cancelled: Boolean): Boolean {
        if (!trackingTouch) return false
        handler.removeCallbacks(armVoice)
        val wasActive = active
        val shouldCancel = wasActive && (cancelPreview || cancelled)
        if (wasActive) anchor?.isPressed = false

        trackingTouch = false
        active = false
        pointerId = -1
        downY = 0f
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
        if (active) anchor?.isPressed = false
        trackingTouch = false
        active = false
        pointerId = -1
        downY = 0f
        cancelPreview = false
        anchor = null
    }

    fun shutdown() {
        reset()
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val CANCEL_DISTANCE_DP = 48
    }
}
