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
    ): ImeKeyView =
        createBaseKey(label, onTap).apply {
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
                        onFeedback()
                        gestureController.begin(
                            anchor = view,
                            pointerId = event.getPointerId(event.actionIndex),
                            rawY = event.rawY,
                        )
                        false
                    }
                    MotionEvent.ACTION_MOVE ->
                        gestureController.move(event.rawY)

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
