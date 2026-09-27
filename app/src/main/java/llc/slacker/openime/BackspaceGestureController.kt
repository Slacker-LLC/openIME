package llc.slacker.openime

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Owns the held-backspace gesture: repeat delete, upward clear-all arming,
 * hysteresis, and pointer/session state. Editor mutations remain callbacks.
 */
internal class BackspaceGestureController(
    private val toPx: (Int) -> Int,
    private val onDeleteOne: () -> Unit,
    private val onClearAll: () -> Unit,
    private val onFeedback: () -> Unit,
    private val onShowClearPopup: (View) -> Unit,
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
    private var startX = 0f
    private var startY = 0f
    private var anchor: View? = null
    private var clearPreviewChanged: ((Boolean) -> Unit)? = null
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
        onClearPreviewChanged: (Boolean) -> Unit,
    ) {
        if (active) finish(commit = false)
        handler.removeCallbacks(repeatAction)
        repeatStartAction?.let(handler::removeCallbacks)

        active = true
        clearArmed = false
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
        onFeedback()

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

        // Suspend repeat-delete as soon as the gesture clearly points upward,
        // so a slow clear swipe stays atomic instead of deleting on the way.
        if (upward >= toPx(8) && horizontal <= toPx(96)) {
            repeatSuspended = true
            repeatStartAction?.let(handler::removeCallbacks)
            handler.removeCallbacks(repeatAction)
        } else if (repeatSuspended) {
            repeatSuspended = false
            repeatStartAction?.let {
                handler.postDelayed(it, if (repeatStarted) REPEAT_INTERVAL_MS else longPressTimeoutMs)
            }
        }

        val shouldArm = if (clearArmed) {
            upward > toPx(16) && horizontal <= toPx(120)
        } else {
            upward >= toPx(36) && horizontal <= toPx(96)
        }
        if (shouldArm == clearArmed) return

        clearArmed = shouldArm
        clearPreviewChanged?.invoke(shouldArm)
        if (shouldArm) {
            repeatStartAction?.let(handler::removeCallbacks)
            handler.removeCallbacks(repeatAction)
            anchor?.let(onShowClearPopup)
        } else {
            onHidePopup()
        }
        onFeedback()
    }

    fun finish(commit: Boolean) {
        if (!active) return
        val clearAll = commit && clearArmed
        val deleteOnce = commit && !clearArmed && !repeatStarted

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
        repeatStarted = false
        repeatSuspended = false
        anchor = null
        clearPreviewChanged = null
        onHidePopup()

        when {
            clearAll -> {
                onFeedback()
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

    private companion object {
        const val REPEAT_INTERVAL_MS = 60L
    }
}
