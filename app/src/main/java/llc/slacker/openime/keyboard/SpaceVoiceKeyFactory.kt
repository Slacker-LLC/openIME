package llc.slacker.openime.keyboard

import android.view.MotionEvent
import llc.slacker.openime.widget.ImeKeyView

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
        var physicalTouchSequenceActive = false
        return createBaseKey(label) {
            if (suppressNextTap) {
                suppressNextTap = false
            } else {
                onTap()
            }
        }.apply {
            tag = "key-space"
            contentDescription = "$label，点击空格，左右滑动移动光标，长按语音输入"

            setOnLongClickListener {
                if (!canStartVoice()) return@setOnLongClickListener true

                // Physical touch timing is already owned by the gesture
                // controller. Keep consuming it until the whole pointer stream
                // ends: after the owner lifts, another pointer may still hold
                // this View and a platform long-click must not look like an
                // accessibility action.
                if (physicalTouchSequenceActive || gestureController.trackingTouch) {
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
                        physicalTouchSequenceActive = true
                        onFeedback()
                        gestureController.begin(
                            anchor = view,
                            pointerId = event.getPointerId(event.actionIndex),
                            rawX = event.rawX,
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
                            val pointerX = event.rawX + event.getX(index) - event.x
                            val pointerY = event.rawY + event.getY(index) - event.y
                            gestureController.move(pointerX, pointerY)
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
                        val consumed = gestureController.finish(
                            cancelled =
                                event.actionMasked == MotionEvent.ACTION_CANCEL,
                        )
                        physicalTouchSequenceActive = false
                        consumed
                    }
                    else -> false
                }
            }
        }
    }
}
