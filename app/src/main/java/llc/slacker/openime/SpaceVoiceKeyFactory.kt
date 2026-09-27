package llc.slacker.openime

import android.view.MotionEvent

/**
 * Builds the combined space/voice key and binds it to
 * SpaceVoiceGestureController. Voice session ownership remains in the host.
 */
internal class SpaceVoiceKeyFactory(
    private val gestureController: SpaceVoiceGestureController,
    private val createBaseKey: (String, () -> Unit) -> ImeKeyView,
    private val canStartVoice: () -> Boolean,
    private val markWhiteKey: (ImeKeyView) -> Unit,
    private val onFeedback: () -> Unit,
    private val onAccessibilityLongPress: () -> Unit,
) {
    fun build(
        label: String,
        white: Boolean,
        onTap: () -> Unit,
    ): ImeKeyView {
        var suppressNextTap = false
        return createBaseKey(label) {
            if (suppressNextTap) {
                suppressNextTap = false
            } else {
                onTap()
            }
        }.apply {
            tag = "key-space"
            contentDescription = "$label，点击空格，长按语音输入"

            setOnLongClickListener {
                if (!canStartVoice()) return@setOnLongClickListener true

                // Physical touch timing is already owned by the gesture
                // controller. Consume the platform long-click to avoid double
                // starting the same voice session.
                if (gestureController.trackingTouch) {
                    return@setOnLongClickListener true
                }

                // Accessibility long-clicks do not deliver DOWN/UP events.
                onAccessibilityLongPress()
                true
            }

            if (white) markWhiteKey(this)

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        suppressNextTap = false
                        onFeedback()
                        gestureController.begin(
                            anchor = view,
                            pointerId = event.getPointerId(event.actionIndex),
                            rawY = event.rawY,
                        )
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val index = event.findPointerIndex(gestureController.pointerId)
                        if (index < 0) {
                            val wasTracking = gestureController.trackingTouch
                            gestureController.finish(cancelled = true)
                            if (wasTracking) suppressNextTap = true
                            wasTracking
                        } else {
                            val pointerY = event.rawY + event.getY(index) - event.y
                            gestureController.move(pointerY)
                        }
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (
                            event.getPointerId(event.actionIndex) !=
                            gestureController.pointerId
                        ) {
                            false
                        } else {
                            val wasTracking = gestureController.trackingTouch
                            val consumed = gestureController.finish(cancelled = false)
                            // View's click state follows the whole MotionEvent
                            // stream, not our owner pointer. If the owner lifts
                            // while another finger remains down, suppress the
                            // eventual ACTION_UP click from that other finger.
                            if (wasTracking) suppressNextTap = true
                            consumed || wasTracking
                        }
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                    -> {
                        gestureController.finish(
                            cancelled =
                                event.actionMasked == MotionEvent.ACTION_CANCEL,
                        )
                    }
                    else -> false
                }
            }
        }
    }
}
