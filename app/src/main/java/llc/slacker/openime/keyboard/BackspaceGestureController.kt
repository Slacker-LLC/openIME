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

    /** Moving down while a clear can still be undone. */
    UNDO_PREVIEW,

    /** Far enough: releasing now restores the cleared text. */
    UNDO_ARMED,
}

/**
 * Owns the held-backspace gesture: repeat delete, upward clear-all, and the
 * one-shot downward restore that is available immediately after a successful
 * clear gesture. Editor mutations remain callbacks.
 *
 * Feedback is a single [GestureHint] stream. Clear and restore used to be two
 * unrelated popups (a red pill and a white card) plus a tiny label inside the
 * key; both are now the same bubble with a different label and fill.
 */
internal class BackspaceGestureController(
    private val toPx: (Int) -> Int,
    private val onDeleteOne: () -> Unit,
    private val onClearAll: () -> Unit,
    private val onUndoClear: () -> Boolean,
    /** The editor-side truth: a clear can be undone only while its snapshot is alive. */
    private val hasUndoSnapshot: () -> Boolean,
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
    private var undoArmed = false
    private var undoAvailable = false // re-read from the editor on every press
    private var repeatStarted = false
    private var repeatSuspended = false
    private var hint = GestureHint.NONE
    private var startX = 0f
    private var startY = 0f
    private var anchor: View? = null
    private var repeatStartAction: Runnable? = null

    private val repeatAction = object : Runnable {
        override fun run() {
            if (!active || clearArmed || undoArmed) return
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

        undoAvailable = hasUndoSnapshot()
        Log.d(TAG, "bs begin x=$rawX y=$rawY pointer=$pointerId undoAvailable=$undoAvailable")
        active = true
        clearArmed = false
        undoArmed = false
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
            if (active && !clearArmed && !undoArmed) repeatAction.run()
        }
        repeatStartAction = startRepeat
        handler.postDelayed(startRepeat, longPressTimeoutMs)
    }

    fun update(rawX: Float, rawY: Float) {
        if (!active) return
        val upward = startY - rawY
        val downward = rawY - startY
        val horizontal = abs(rawX - startX)
        val armDistance = toPx(CLEAR_ARM_DP)
        val previewDistance = toPx(PREVIEW_DP)
        val onAxis = horizontal <= toPx(AXIS_TOLERANCE_DP)

        // Suspend repeat-delete as soon as the gesture clearly becomes a
        // vertical command. Otherwise a slow swipe could mutate text before
        // clear/restore is armed.
        val verticalCommand =
            onAxis && (upward >= previewDistance || (undoAvailable && downward >= previewDistance))
        if (verticalCommand) {
            repeatSuspended = true
            repeatStartAction?.let(handler::removeCallbacks)
            handler.removeCallbacks(repeatAction)
        } else if (repeatSuspended && !clearArmed && !undoArmed) {
            repeatSuspended = false
            repeatStartAction?.let {
                handler.postDelayed(
                    it,
                    if (repeatStarted) REPEAT_INTERVAL_MS else longPressTimeoutMs,
                )
            }
        }

        // Restore is only offered right after a clear; the two commands are
        // mutually exclusive, with hysteresis so a wobble cannot flip them.
        val shouldUndo = undoAvailable && if (undoArmed) {
            downward > toPx(RELEASE_DP) && horizontal <= toPx(HYSTERESIS_DRIFT_DP)
        } else {
            downward >= armDistance && onAxis
        }
        if (shouldUndo != undoArmed) {
            Log.d(TAG, "bs undoArmed=$shouldUndo down=${downward.toInt()} h=${horizontal.toInt()}")
            undoArmed = shouldUndo
            if (undoArmed) {
                clearArmed = false
                repeatStartAction?.let(handler::removeCallbacks)
                handler.removeCallbacks(repeatAction)
            }
            onHapticFeedback()
        }

        if (!undoArmed) {
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
        }

        setHint(
            when {
                undoArmed -> GestureHint.UNDO_ARMED
                clearArmed -> GestureHint.CLEAR_ARMED
                undoAvailable && onAxis && downward >= previewDistance -> GestureHint.UNDO_PREVIEW
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
        Log.d(TAG, "bs finish commit=$commit clearArmed=$clearArmed undoArmed=$undoArmed repeat=$repeatStarted")
        val restoreClear = commit && undoArmed
        val clearAll = commit && clearArmed
        val deleteOnce = commit && !clearArmed && !undoArmed && !repeatStarted

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
        undoArmed = false
        repeatStarted = false
        repeatSuspended = false
        anchor = null
        onHidePopup()

        when {
            restoreClear -> {
                onHapticFeedback()
                onUndoClear()
                // The gateway validates the one-shot snapshot and consumes it.
            }
            clearAll -> {
                onHapticFeedback()
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
        undoAvailable = false
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
         * Travel from the press point that arms clear (up) or restore (down).
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
