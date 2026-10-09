package llc.slacker.openime.keyboard

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import llc.slacker.openime.theme.ImeDrawableFactory
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeMotionTokens
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeTheme
import llc.slacker.openime.theme.ImeTypographyTokens

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
    private val hoverFeedback: () -> Unit,
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
    private var choiceAnchor: View? = null
    private val choiceCells = mutableListOf<Pair<TextView, String>>()
    private var hoveredCell: TextView? = null

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
        val armed = hint == GestureHint.CLEAR_ARMED
        val label = if (armed) "松手清空" else "上滑清空"
        val fill = if (armed) t.destructive else ImeDrawableFactory.withAlpha(t.keyText, 0xE0)
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

    fun showChoices(anchor: View, choices: List<String>) = showChoiceRows(anchor, listOf(choices))

    /** Long-press selector with one horizontal row per entry of [rows]. */
    fun showChoiceRows(anchor: View, requestedRows: List<List<String>>) {
        if (requestedRows.isEmpty() || requestedRows.all { it.isEmpty() }) return
        hide()
        // The popup must sit above the key inside the IME window. A landscape
        // keyboard has too little room for stacked rows, and a popup covering the
        // key would put the pressed finger on a cell. Fall back to one row then,
        // nearest letters first.
        val anchorTop = IntArray(2).also { anchor.getLocationOnScreen(it) }[1] -
            IntArray(2).also { host.getLocationOnScreen(it) }[1]
        val needed = dp(CELL_HEIGHT_DP * requestedRows.size + 2 * POPUP_PAD_DP) + dp(ImeGeometryTokens.KEY_POPUP_VERTICAL_GAP_DP) + dp(4)
        val rows = if (requestedRows.size > 1 && needed > anchorTop) {
            listOf(requestedRows.reversed().flatten())
        } else {
            requestedRows
        }
        keepAfterKeyUp = true
        choiceAnchor = anchor

        val t = tokens()
        val column = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(POPUP_PAD_DP), dp(POPUP_PAD_DP), dp(POPUP_PAD_DP), dp(POPUP_PAD_DP))
            background = ImeDrawableFactory.rounded(
                t.keyBackground,
                dp(ImeGeometryTokens.CONTROL_RADIUS_DP),
                ImeSurfacePolicy.divider(t),
                dp(1).coerceAtLeast(1),
            )
            elevation = dp(2).toFloat()
            contentDescription = "长按符号选择"
        }

        rows.forEach { choices ->
            val row = LinearLayout(host.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            choices.forEach { symbol ->
                row.addView(
                    TextView(host.context).apply {
                        choiceCells += this to symbol
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
                    LinearLayout.LayoutParams(dp(CELL_WIDTH_DP), dp(CELL_HEIGHT_DP)),
                )
            }
            column.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(CELL_HEIGHT_DP),
                ),
            )
        }

        val widest = rows.maxOf { it.size }
        val popupWidth = dp(CELL_WIDTH_DP * widest + 2 * POPUP_PAD_DP)
        val popupHeight = dp(CELL_HEIGHT_DP * rows.size + 2 * POPUP_PAD_DP)
        placeAbove(anchor, column, popupWidth, popupHeight)
        popupView = column
        animateIn(column, popupWidth, popupHeight)
        scheduleAutoDismiss(OPEN_DISMISS_MS)
        // The one buzz that says the long press worked (the platform's own is switched off).
        hoverFeedback()
    }

    /**
     * Slide-to-select for a choice popup. The finger that opened it is still
     * down, so the host forwards its moves and its lift here. Moving over a cell
     * highlights it; lifting over a cell types it. Lifting anywhere else leaves
     * the popup open when the finger is back on its key (tap a cell instead) and
     * closes it otherwise. Returns true when the lift typed a cell.
     */
    fun trackChoiceTouch(rawX: Float, rawY: Float, lifted: Boolean): Boolean {
        if (choiceCells.isEmpty() || popupView == null) return false
        // A cell drawn over the key itself is not a choice yet: the finger has not left the key.
        val onAnchor = choiceAnchor?.let { containsRaw(it, rawX, rawY) } == true
        val hit = if (onAnchor) null else choiceCells.firstOrNull { (cell, _) -> containsRaw(cell, rawX, rawY) }
        if (!lifted) {
            val cell = hit?.first
            if (cell !== hoveredCell) {
                hoveredCell?.isPressed = false
                cell?.isPressed = true
                hoveredCell = cell
            }
            return false
        }
        if (hit != null) {
            // Silent, like the other open-source keyboards: the long press already buzzed once.
            hide()
            onSymbolSelected(hit.second)
            return true
        }
        hoveredCell?.isPressed = false
        hoveredCell = null
        val anchor = choiceAnchor
        if (anchor == null || !containsRaw(anchor, rawX, rawY)) hide() else scheduleAutoDismiss(LIFT_DISMISS_MS)
        return false
    }

    private fun containsRaw(view: View, rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] && rawX < location[0] + view.width &&
            rawY >= location[1] && rawY < location[1] + view.height
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

    /** A popup nobody picks from closes by itself. */
    private fun scheduleAutoDismiss(delayMs: Long) {
        host.removeCallbacks(autoDismiss)
        host.postDelayed(autoDismiss, delayMs)
    }

    private val autoDismiss = Runnable { if (choiceCells.isNotEmpty()) hide() }

    fun hide() {
        host.removeCallbacks(autoDismiss)
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
        choiceAnchor = null
        choiceCells.clear()
        hoveredCell = null
    }

    private companion object {
        // Long-press cells and the key preview share one height (44 + 2 x 4 = 52).
        const val CELL_WIDTH_DP = 40
        const val CELL_HEIGHT_DP = 44
        const val POPUP_PAD_DP = 4
        const val OPEN_DISMISS_MS = 4000L
        const val LIFT_DISMISS_MS = 1500L
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
