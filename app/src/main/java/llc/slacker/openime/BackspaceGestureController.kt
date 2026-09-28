package llc.slacker.openime

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Owns the held-backspace gesture: repeat delete, upward clear-all, and the
 * one-shot downward restore that is available immediately after a successful
 * clear gesture. Editor mutations remain callbacks.
 */
internal class BackspaceGestureController(
    private val toPx: (Int) -> Int,
    private val onDeleteOne: () -> Unit,
    private val onClearAll: () -> Unit,
    private val onUndoClear: () -> Boolean,
    private val onPressFeedback: () -> Unit,
    private val onHapticFeedback: () -> Unit,
    private val onShowClearPopup: (View) -> Unit,
    private val onShowUndoPopup: (View) -> Unit,
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
    private var undoAvailable = false
    private var repeatStarted = false
    private var repeatSuspended = false
    private var startX = 0f
    private var startY = 0f
    private var anchor: View? = null
    private var clearPreviewChanged: ((Boolean) -> Unit)? = null
    private var repeatStartAction: Runnable? = null

    private val repeatAction = object : Runnable {
        override fun run() {
            if (!active || clearArmed || undoArmed) return
            repeatStarted = true
            undoAvailable = false
            onDeleteOne()
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    fun begin(
        anchor: View,
        pointerId: Int,
        rawX: Float,
        rawY: Float,
        onClearPreviewChanged: (Boolean) -> Unit,
    ) {
        if (active) finish(commit = false)
        handler.removeCallbacks(repeatAction)
        repeatStartAction?.let(handler::removeCallbacks)

        active = true
        clearArmed = false
        undoArmed = false
        repeatStarted = false
        repeatSuspended = false
        startX = rawX
        startY = rawY
        this.anchor = anchor
        this.pointerId = pointerId
        clearPreviewChanged = onClearPreviewChanged

        anchor.isPressed = true
        anchor.parent?.requestDisallowInterceptTouchEvent(true)
        onClearPreviewChanged(false)
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

        // Suspend repeat-delete as soon as the gesture clearly becomes a
        // vertical command. Otherwise a slow swipe could mutate text before
        // clear/restore is armed.
        val verticalCommand =
            horizontal <= toPx(96) &&
                (upward >= toPx(8) || (undoAvailable && downward >= toPx(8)))
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

        val shouldUndo = undoAvailable && if (undoArmed) {
            downward > toPx(16) && horizontal <= toPx(120)
        } else {
            downward >= toPx(36) && horizontal <= toPx(96)
        }
        if (shouldUndo != undoArmed) {
            undoArmed = shouldUndo
            if (undoArmed) {
                clearArmed = false
                clearPreviewChanged?.invoke(false)
                repeatStartAction?.let(handler::removeCallbacks)
                handler.removeCallbacks(repeatAction)
                anchor?.let(onShowUndoPopup)
                onHapticFeedback()
                return
            } else {
                onHidePopup()
                onHapticFeedback()
            }
        }
        if (undoArmed) return

        val shouldClear = if (clearArmed) {
            upward > toPx(16) && horizontal <= toPx(120)
        } else {
            upward >= toPx(36) && horizontal <= toPx(96)
        }
        if (shouldClear == clearArmed) return

        clearArmed = shouldClear
        clearPreviewChanged?.invoke(shouldClear)
        if (shouldClear) {
            repeatStartAction?.let(handler::removeCallbacks)
            handler.removeCallbacks(repeatAction)
            anchor?.let(onShowClearPopup)
        } else {
            onHidePopup()
        }
        onHapticFeedback()
    }

    fun finish(commit: Boolean) {
        if (!active) return
        val restoreClear = commit && undoArmed
        val clearAll = commit && clearArmed
        val deleteOnce = commit && !clearArmed && !undoArmed && !repeatStarted

        handler.removeCallbacks(repeatAction)
        repeatStartAction?.let(handler::removeCallbacks)
        repeatStartAction = null
        anchor?.apply {
            isPressed = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        active = false
        pointerId = -1
        clearPreviewChanged?.invoke(false)
        clearArmed = false
        undoArmed = false
        repeatStarted = false
        repeatSuspended = false
        anchor = null
        clearPreviewChanged = null
        onHidePopup()

        when {
            restoreClear -> {
                onHapticFeedback()
                onUndoClear()
                // The gateway independently validates whether the one-shot
                // snapshot is still legal. Either way this gesture consumes
                // our local affordance so stale undo is never offered twice.
                undoAvailable = false
            }
            clearAll -> {
                onHapticFeedback()
                onClearAll()
                undoAvailable = true
            }
            deleteOnce -> {
                undoAvailable = false
                onDeleteOne()
            }
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

    private companion object {
        const val REPEAT_INTERVAL_MS = 60L
    }
}
