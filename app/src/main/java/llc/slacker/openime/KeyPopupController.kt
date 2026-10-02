package llc.slacker.openime

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Owns transient key popups without owning keyboard state.
 *
 * The host supplies current theme primitives so popup rendering stays on the
 * same token system as the rest of the IME.
 */
internal class KeyPopupController(
    private val host: FrameLayout,
    private val dp: (Int) -> Int,
    private val contentInsetPx: () -> Int,
    private val tokens: () -> ImeTheme.Tokens,
    private val rounded: (color: Int, radius: Int) -> Drawable,
    private val statefulRounded: (normal: Int, pressed: Int, radius: Int) -> Drawable,
    private val contrastText: (background: Int) -> Int,
    private val feedback: () -> Unit,
    private val onSymbolSelected: (String) -> Unit,
) {
    private val previewPopup = TextView(host.context).apply {
        visibility = View.GONE
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(6), dp(8), dp(6))
        elevation = dp(2).toFloat()
    }
    private var popupView: View? = null
    private var keepAfterKeyUp = false

    /** True while a key preview or a long-press choice popup is on screen. */
    val isShowing: Boolean get() = popupView != null

    /**
     * The one bubble for every delete-key gesture state (clear / restore, and
     * both before and after they arm). Purely visual and never touchable.
     */
    private val gestureHintView = TextView(host.context).apply {
        visibility = View.GONE
        includeFontPadding = false
        maxLines = 1
        gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(16), 0)
        elevation = dp(3).toFloat()
        textSize = ImeTypographyTokens.BODY_SP
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        host.addView(
            previewPopup,
            FrameLayout.LayoutParams(1, 1).apply {
                gravity = Gravity.TOP or Gravity.START
            },
        )
        host.addView(
            gestureHintView,
            FrameLayout.LayoutParams(1, 1).apply {
                gravity = Gravity.TOP or Gravity.START
            },
        )
    }

    /**
     * Show (or restyle) the delete-key gesture bubble. It sits beside the key
     * on the side with room, never above it: the thumb moves up over the key
     * to clear and would hide a bubble placed there. Armed states change only
     * the label and fill, so clear and restore read as the same control.
     */
    fun showGestureHint(anchor: View, hint: GestureHint) {
        if (hint == GestureHint.NONE) {
            hideGestureHint()
            return
        }
        val t = tokens()
        val clear = hint == GestureHint.CLEAR_PREVIEW || hint == GestureHint.CLEAR_ARMED
        val armed = hint == GestureHint.CLEAR_ARMED || hint == GestureHint.UNDO_ARMED
        val label = when (hint) {
            GestureHint.CLEAR_PREVIEW -> "上滑清空"
            GestureHint.CLEAR_ARMED -> "松手清空"
            GestureHint.UNDO_PREVIEW -> "下滑撤回"
            else -> "松手撤回"
        }
        val fill = when {
            !armed -> ImeDrawableFactory.withAlpha(t.keyText, 0xE0)
            clear -> t.destructive
            else -> t.primary
        }
        val textColor = if (armed) contrastText(fill) else t.keyBackground

        val height = dp(GESTURE_HINT_HEIGHT_DP)
        gestureHintView.text = label
        gestureHintView.setTextColor(textColor)
        gestureHintView.background = rounded(fill, height / 2)
        val width = (gestureHintView.paint.measureText(label) + dp(32))
            .toInt()
            .coerceAtLeast(dp(GESTURE_HINT_MIN_WIDTH_DP))

        val anchorLocation = IntArray(2)
        val hostLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        host.getLocationOnScreen(hostLocation)
        val anchorLeft = anchorLocation[0] - hostLocation[0]
        val anchorTop = anchorLocation[1] - hostLocation[1]
        val margin = dp(8)
        val gap = dp(8)
        val inset = contentInsetPx()
        val placeLeft = anchorLeft + anchor.width / 2 > host.width / 2
        val rawLeft = if (placeLeft) anchorLeft - gap - width else anchorLeft + anchor.width + gap
        val left = rawLeft.coerceIn(
            inset + margin,
            (host.width - width - inset - margin).coerceAtLeast(inset + margin),
        )
        val top = (anchorTop + (anchor.height - height) / 2).coerceAtLeast(dp(4))

        gestureHintView.layoutParams = FrameLayout.LayoutParams(width, height).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left
            topMargin = top
        }
        if (gestureHintView.visibility != View.VISIBLE) {
            gestureHintView.visibility = View.VISIBLE
            gestureHintView.bringToFront()
            gestureHintView.animate().cancel()
            gestureHintView.pivotX = width / 2f
            gestureHintView.pivotY = height / 2f
            gestureHintView.scaleX = 0.9f
            gestureHintView.scaleY = 0.9f
            gestureHintView.alpha = 0f
            gestureHintView.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(ImeMotionTokens.POPUP_ENTER_MS)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        }
    }

    fun hideGestureHint() {
        gestureHintView.animate().cancel()
        gestureHintView.visibility = View.GONE
    }

    fun show(anchor: View, text: String) {
        hide()
        keepAfterKeyUp = false

        val t = tokens()
        val minimumWidth = dp(ImeGeometryTokens.KEY_POPUP_MIN_WIDTH_DP)
        val desiredWidth = (anchor.width * ImeGeometryTokens.KEY_POPUP_WIDTH_SCALE)
            .toInt()
            .coerceAtLeast(minimumWidth)
        val availableWidth = (host.width - contentInsetPx() * 2)
            .coerceAtLeast(minimumWidth)
        val popupWidth = desiredWidth.coerceAtMost(availableWidth)
        val popupHeight = dp(ImeGeometryTokens.KEY_POPUP_HEIGHT_DP)
        previewPopup.apply {
            this.text = text
            textSize = if (text.length > 1) {
                ImeTypographyTokens.BODY_SP
            } else {
                ImeTypographyTokens.DISPLAY_SP
            }
            setTextColor(t.keyText)
            background = ImeDrawableFactory.rounded(
                t.keyBackground,
                dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                ImeSurfacePolicy.divider(t),
                dp(1).coerceAtLeast(1),
            )
        }

        positionAttachedPopup(anchor, previewPopup, popupWidth, popupHeight)
        previewPopup.visibility = View.VISIBLE
        previewPopup.alpha = 1f
        previewPopup.scaleX = 1f
        previewPopup.scaleY = 1f
        previewPopup.bringToFront()
        popupView = previewPopup
    }

    fun showChoices(anchor: View, choices: List<String>) {
        hide()
        keepAfterKeyUp = true

        val t = tokens()
        val row = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = ImeDrawableFactory.rounded(
                t.keyBackground,
                dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                ImeSurfacePolicy.divider(t),
                dp(1).coerceAtLeast(1),
            )
            elevation = dp(2).toFloat()
            contentDescription = "长按符号选择"
        }

        choices.forEach { symbol ->
            row.addView(
                TextView(host.context).apply {
                    text = symbol
                    textSize = ImeTypographyTokens.BODY_SP
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setTextColor(t.keyText)
                    background = statefulRounded(
                        Color.TRANSPARENT,
                        ImeSurfacePolicy.pressedSurface(t.keyBackground, t),
                        dp(ImeGeometryTokens.KEY_RADIUS_DP),
                    )
                    isClickable = true
                    isFocusable = true
                    contentDescription = "输入$symbol"
                    setPadding(dp(11), 0, dp(11), 0)
                    setOnClickListener {
                        hide()
                        feedback()
                        onSymbolSelected(symbol)
                    }
                },
                LinearLayout.LayoutParams(
                    dp(ImeGeometryTokens.TOUCH_TARGET_DP),
                    dp(ImeGeometryTokens.TOUCH_TARGET_DP),
                ),
            )
        }

        val popupWidth = dp(48 * choices.size + 10)
        val popupHeight = dp(58)
        placeAbove(anchor, row, popupWidth, popupHeight)
        popupView = row
        animateIn(row, popupWidth, popupHeight)
    }

    fun hideIfOutside(x: Float, y: Float) {
        val popup = popupView ?: return
        if (x < popup.left || x >= popup.right || y < popup.top || y >= popup.bottom) {
            hide()
        }
    }

    /**
     * Choice popups survive the key-up that follows the long press so the user
     * can select an item. Ordinary key popups close immediately.
     */
    fun consumeKeepAfterKeyUp(): Boolean {
        if (!keepAfterKeyUp) return false
        keepAfterKeyUp = false
        return true
    }

    fun hide() {
        popupView?.let { popup ->
            popup.animate().cancel()
            if (popup === previewPopup) {
                popup.visibility = View.GONE
            } else {
                host.removeView(popup)
            }
        }
        popupView = null
        keepAfterKeyUp = false
    }

    private companion object {
        const val GESTURE_HINT_HEIGHT_DP = 36
        const val GESTURE_HINT_MIN_WIDTH_DP = 88
    }

    private fun positionAttachedPopup(
        anchor: View,
        popup: View,
        popupWidth: Int,
        popupHeight: Int,
    ) {
        val placement = placementAbove(anchor, popupWidth, popupHeight)
        popup.layoutParams = FrameLayout.LayoutParams(popupWidth, popupHeight).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = placement.first
            topMargin = placement.second
        }
    }

    private fun placeAbove(anchor: View, popup: View, popupWidth: Int, popupHeight: Int) {
        val placement = placementAbove(anchor, popupWidth, popupHeight)
        host.addView(
            popup,
            FrameLayout.LayoutParams(popupWidth, popupHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = placement.first
                topMargin = placement.second
            },
        )
    }

    private fun placementAbove(anchor: View, popupWidth: Int, popupHeight: Int): Pair<Int, Int> {
        val anchorLocation = IntArray(2)
        val rootLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        host.getLocationOnScreen(rootLocation)

        val inset = contentInsetPx()
        val anchorLeft = anchorLocation[0] - rootLocation[0]
        val anchorTop = anchorLocation[1] - rootLocation[1]
        val centeredLeft = anchorLeft + (anchor.width - popupWidth) / 2
        val maxLeft = (host.width - popupWidth - inset).coerceAtLeast(inset)
        val left = centeredLeft.coerceIn(inset, maxLeft)
        val top = (
            anchorTop - popupHeight - dp(ImeGeometryTokens.KEY_POPUP_VERTICAL_GAP_DP)
            ).coerceAtLeast(dp(4))
        return left to top
    }

    private fun animateIn(view: View, popupWidth: Int, popupHeight: Int) {
        view.animate().cancel()
        view.pivotX = popupWidth / 2f
        view.pivotY = popupHeight.toFloat()
        view.scaleX = 0.88f
        view.scaleY = 0.88f
        view.alpha = 0f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(ImeMotionTokens.POPUP_ENTER_MS)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }
}
