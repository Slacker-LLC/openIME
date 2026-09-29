package llc.slacker.openime

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView

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

            val clearHint = TextView(context).apply {
                text = "↑ 清空"
                textSize = ImeTypographyTokens.CAPTION_SP
                gravity = Gravity.CENTER
                includeFontPadding = false
                alpha = 0.72f
                setTextColor(Color.GRAY)
                isClickable = false
                isFocusable = false
                tag = "backspace-clear-hint"
                contentDescription = null
                visibility = View.INVISIBLE
            }
            addView(
                clearHint,
                FrameLayout.LayoutParams(toPx(30), toPx(14)).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = toPx(2)
                },
            )

            fun setClearHintActive(active: Boolean) {
                clearHint.visibility =
                    if (gestureController.active) View.VISIBLE else View.INVISIBLE
                if (active) {
                    val destructive = currentTokens().destructive
                    clearHint.text = "清空"
                    clearHint.setTextColor(
                        ImeDrawableFactory.contrastText(destructive),
                    )
                    clearHint.background = ImeDrawableFactory.rounded(
                        destructive,
                        toPx(ImeGeometryTokens.BADGE_RADIUS_DP),
                    )
                    clearHint.alpha = 1f
                } else {
                    val secondary = currentTokens().keySecondaryText
                    clearHint.text = "↑ 清空"
                    clearHint.setTextColor(secondary)
                    clearHint.background = null
                    clearHint.alpha = 0.72f
                }
            }

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        clearHint.alpha = 1f
                        gestureController.begin(
                            anchor = view,
                            pointerId = event.getPointerId(event.actionIndex),
                            rawX = event.rawX,
                            rawY = event.rawY,
                            onClearPreviewChanged = ::setClearHintActive,
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
