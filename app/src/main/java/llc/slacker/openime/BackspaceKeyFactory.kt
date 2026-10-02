package llc.slacker.openime

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Builds and binds the production backspace key. Gesture state and thresholds
 * live in BackspaceGestureController; this class owns only view wiring.
 */
internal class BackspaceKeyFactory(
    private val context: Context,
    private val toPx: (Int) -> Int,
    private val gestureController: BackspaceGestureController,
    private val createBaseKey: (() -> Unit) -> ImeKeyView,
    private val currentTokens: () -> ImeTheme.Tokens,
    private val onDeleteOne: () -> Unit,
    private val onFeedback: () -> Unit,
    private val onClearAll: () -> Unit,
    private val debugLogging: () -> Boolean,
) {
    fun build(): ImeKeyView =
        createBaseKey(onDeleteOne).apply {
            tag = "key-backspace"
            contentDescription = "删除，向上滑清空；清空后向下滑撤回"
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(
                        AccessibilityNodeInfo.AccessibilityAction(
                            R.id.accessibility_clear_all,
                            "清空全部",
                        ),
                    )
                }

                override fun performAccessibilityAction(
                    host: View,
                    action: Int,
                    args: android.os.Bundle?,
                ): Boolean {
                    if (action == R.id.accessibility_clear_all) {
                        if (!host.isEnabled) return false
                        onFeedback()
                        onClearAll()
                        return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        gestureController.begin(
                            anchor = view,
                            pointerId = event.getPointerId(event.actionIndex),
                            rawX = event.rawX,
                            rawY = event.rawY,
                        )
                        if (debugLogging()) {
                            Log.d(
                                "OpenIme",
                                "backspace-touch-down x=${event.rawX} y=${event.rawY}",
                            )
                        }
                        true
                    }
                    // Root dispatch owns MOVE/UP so the gesture survives when
                    // the pointer leaves the key rectangle.
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                    -> true
                    else -> true
                }
            }
        }
}
