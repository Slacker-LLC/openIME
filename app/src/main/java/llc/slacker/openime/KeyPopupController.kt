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

    init {
        host.addView(
            previewPopup,
            FrameLayout.LayoutParams(1, 1).apply {
                gravity = Gravity.TOP or Gravity.START
            },
        )
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
        val popupHeight = dp(
            if (text == "清空") 36 else ImeGeometryTokens.KEY_POPUP_HEIGHT_DP,
        )
        val popupBackground = if (text == "清空") t.destructive else t.keyBackground
        previewPopup.apply {
            this.text = text
            textSize = if (text.length > 1) {
                ImeTypographyTokens.BODY_SP
            } else {
                ImeTypographyTokens.DISPLAY_SP
            }
            setTextColor(if (text == "清空") contrastText(popupBackground) else t.keyText)
            background =
                if (text == "清空") {
                    rounded(popupBackground, dp(ImeGeometryTokens.CONTROL_RADIUS_DP))
                } else {
                    ImeDrawableFactory.rounded(
                        popupBackground,
                        dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                        ImeSurfacePolicy.divider(t),
                        dp(1).coerceAtLeast(1),
                    )
                }
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
