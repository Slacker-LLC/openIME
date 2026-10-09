package llc.slacker.openime.keyboard

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** What the delete-key gesture currently offers; drives one shared hint bubble. */
internal enum class GestureHint {
    NONE,

    /** Moving up, not yet far enough to clear. */
    CLEAR_PREVIEW,

    /** Far enough: releasing now clears the whole field. */
    CLEAR_ARMED,
}

/**
 * Owns the held-backspace gesture: repeat delete and upward clear-all. A clear
 * is final; there is no downward restore. Editor mutations remain callbacks.
 *
 * Feedback is a single [GestureHint] stream driving one bubble.
 */
internal class BackspaceGestureController(
    private val toPx: (Int) -> Int,
    private val onDeleteOne: () -> Unit,
    private val onClearAll: () -> Unit,
    private val onPressFeedback: () -> Unit,
    private val onHapticFeedback: () -> Unit,
    private val onGestureHint: (View, GestureHint) -> Unit,
    private val onHidePopup: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()

    var active: Boolean = false
        private set

    var pointerId: Int = -1
        private set

    private var clearArmed = false
    private var repeatStarted = false
    private var repeatSuspended = false
    private var hint = GestureHint.NONE
    private var startX = 0f
    private var startY = 0f
    private var anchor: View? = null
    private var repeatStartAction: Runnable? = null

    private val repeatAction = object : Runnable {
        override fun run() {
            if (!active || clearArmed) return
            repeatStarted = true
            onDeleteOne()
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    fun begin(
        anchor: View,
        pointerId: Int,
        rawX: Float,
        rawY: Float,
    ) {
        if (active) finish(commit = false)
        handler.removeCallbacks(repeatAction)
        repeatStartAction?.let(handler::removeCallbacks)

        Log.d(TAG, "bs begin x=$rawX y=$rawY pointer=$pointerId")
        active = true
        clearArmed = false
        repeatStarted = false
        repeatSuspended = false
        hint = GestureHint.NONE
        startX = rawX
        startY = rawY
        this.anchor = anchor
        this.pointerId = pointerId

        anchor.isPressed = true
        anchor.parent?.requestDisallowInterceptTouchEvent(true)
        onHidePopup()
        onPressFeedback()

        val startRepeat = Runnable {
            if (active && !clearArmed) repeatAction.run()
        }
        repeatStartAction = startRepeat
        handler.postDelayed(startRepeat, longPressTimeoutMs)
    }

    fun update(rawX: Float, rawY: Float) {
        if (!active) return
        val upward = startY - rawY
        val horizontal = abs(rawX - startX)
        val armDistance = toPx(CLEAR_ARM_DP)
        val previewDistance = toPx(PREVIEW_DP)
        val onAxis = horizontal <= toPx(AXIS_TOLERANCE_DP)

        // Suspend repeat-delete as soon as the gesture clearly becomes a
        // vertical command. Otherwise a slow swipe could mutate text before
        // clear is armed.
        val verticalCommand = onAxis && upward >= previewDistance
        if (verticalCommand) {
            repeatSuspended = true
            repeatStartAction?.let(handler::removeCallbacks)
            handler.removeCallbacks(repeatAction)
        } else if (repeatSuspended && !clearArmed) {
            repeatSuspended = false
            repeatStartAction?.let {
                handler.postDelayed(
                    it,
                    if (repeatStarted) REPEAT_INTERVAL_MS else longPressTimeoutMs,
                )
            }
        }

        // Hysteresis so a wobble near the threshold cannot flip the state.
        val shouldClear = if (clearArmed) {
            upward > toPx(RELEASE_DP) && horizontal <= toPx(HYSTERESIS_DRIFT_DP)
        } else {
            upward >= armDistance && onAxis
        }
        if (shouldClear != clearArmed) {
            Log.d(TAG, "bs clearArmed=$shouldClear up=${upward.toInt()} h=${horizontal.toInt()}")
            clearArmed = shouldClear
            if (clearArmed) {
                repeatStartAction?.let(handler::removeCallbacks)
                handler.removeCallbacks(repeatAction)
            }
            onHapticFeedback()
        }

        setHint(
            when {
                clearArmed -> GestureHint.CLEAR_ARMED
                onAxis && upward >= previewDistance -> GestureHint.CLEAR_PREVIEW
                else -> GestureHint.NONE
            },
        )
    }

    /**
     * End the gesture. A release is treated as one last move first: the final
     * UP carries coordinates that no MOVE reported, and a quick flick can cross
     * the threshold exactly there.
     */
    fun finish(commit: Boolean, rawX: Float? = null, rawY: Float? = null) {
        if (!active) return
        if (commit && rawX != null && rawY != null) update(rawX, rawY)
        Log.d(TAG, "bs finish commit=$commit clearArmed=$clearArmed repeat=$repeatStarted")
        val clearAll = commit && clearArmed
        val deleteOnce = commit && !clearArmed && !repeatStarted

        handler.removeCallbacks(repeatAction)
        repeatStartAction?.let(handler::removeCallbacks)
        repeatStartAction = null
        setHint(GestureHint.NONE)
        anchor?.apply {
            isPressed = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        active = false
        pointerId = -1
        clearArmed = false
        repeatStarted = false
        repeatSuspended = false
        anchor = null
        onHidePopup()

        when {
            clearAll -> {
                // The arming tick already told the user; no second one on release.
                onClearAll()
            }
            deleteOnce -> onDeleteOne()
        }
    }

    fun shutdown() {
        if (active) {
            finish(commit = false)
        }
        handler.removeCallbacksAndMessages(null)
        repeatStartAction = null
        pointerId = -1
    }

    private fun setHint(next: GestureHint) {
        if (next == hint) return
        hint = next
        anchor?.let { onGestureHint(it, next) }
    }

    internal companion object {
        const val REPEAT_INTERVAL_MS = 60L
        private const val TAG = "OpenIme"

        /**
         * Upward travel from the press point that arms clear.
         * Half a key height past its edge: a normal thumb flick reaches it, a
         * wobble while holding repeat-delete does not. It used to be 56dp, more
         * than a full key above the key centre, so ordinary flicks fell through
         * to a single delete.
         */
        const val CLEAR_ARM_DP = 32

        /** The bubble appears (unarmed) once the thumb clearly moves vertically. */
        const val PREVIEW_DP = 8

        /** Once armed, backing off this far (from the press point) disarms. */
        private const val RELEASE_DP = 16

        /** Sideways drift allowed while arming, and while already armed. */
        private const val AXIS_TOLERANCE_DP = 96
        private const val HYSTERESIS_DRIFT_DP = 120
    }
}
