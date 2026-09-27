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
    private var popupView: View? = null
    private var keepAfterKeyUp = false

    fun show(anchor: View, text: String) {
        hide()
        keepAfterKeyUp = false

        val t = tokens()
        val popupWidth = (anchor.width * 1.08f).toInt().coerceIn(dp(40), dp(64))
        val popupHeight = dp(if (text == "清空") 36 else 48)
        val popup = TextView(host.context).apply {
            this.text = text
            textSize = if (text.length > 1) 13f else 16f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            val popupBackground = if (text == "清空") t.destructive else t.keyBackground
            setTextColor(if (text == "清空") contrastText(popupBackground) else t.keyText)
            background = rounded(popupBackground, dp(ImeGeometryTokens.CONTROL_RADIUS_DP))
            elevation = dp(2).toFloat()
        }

        placeAbove(anchor, popup, popupWidth, popupHeight)
        popupView = popup
        animateIn(popup, popupWidth, popupHeight)
    }

    fun showChoices(anchor: View, choices: List<String>) {
        hide()
        keepAfterKeyUp = true

        val t = tokens()
        val row = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = rounded(t.keyBackground, dp(ImeGeometryTokens.CONTROL_RADIUS_DP))
            elevation = dp(2).toFloat()
            contentDescription = "长按符号选择"
        }

        choices.forEach { symbol ->
            row.addView(
                TextView(host.context).apply {
                    text = symbol
                    textSize = 16f
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setTextColor(t.keyText)
                    background = statefulRounded(
                        Color.TRANSPARENT,
                        t.keyPressedBackground,
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
                LinearLayout.LayoutParams(dp(48), dp(48)),
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
        popupView?.let {
            it.animate().cancel()
            host.removeView(it)
        }
        popupView = null
        keepAfterKeyUp = false
    }

    private fun placeAbove(anchor: View, popup: View, popupWidth: Int, popupHeight: Int) {
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
        val top = (anchorTop - popupHeight - dp(8)).coerceAtLeast(dp(4))

        host.addView(
            popup,
            FrameLayout.LayoutParams(popupWidth, popupHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = left
                topMargin = top
            },
        )
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
