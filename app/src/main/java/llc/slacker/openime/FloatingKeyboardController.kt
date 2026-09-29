package llc.slacker.openime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

/**
 * Owns floating-keyboard chrome and drag-handle interaction.
 *
 * Window bounds and panel orchestration stay with the host/service. This class
 * only owns the local presentation state that makes the same keyboard surface
 * behave as a floating card.
 */
internal class FloatingKeyboardController(
    context: Context,
    private val toPx: (Int) -> Int,
    private val mainDock: LinearLayout,
    private val canDrag: () -> Boolean,
    onDragBy: (Float, Float) -> Unit,
    private val onDock: () -> Unit,
) {
    private val dragController = FloatingDragController(
        toPx = toPx,
        onDragBy = onDragBy,
        onDock = onDock,
    )

    val handle: View = DragHandleView(context, toPx).apply {
        tag = "floating-drag-handle"
        contentDescription = "浮动键盘未启用"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = View.GONE
        isClickable = true
        isFocusable = false
        setOnClickListener {
            if (enabled) onDock()
        }
        setOnTouchListener { _, event ->
            handleTouch(event)
        }
    }

    var enabled: Boolean = false
        private set

    fun setEnabled(value: Boolean) {
        enabled = value
        handle.isEnabled = value
        handle.isFocusable = value
        handle.contentDescription = if (value) {
            "拖动浮动键盘，点击贴底显示"
        } else {
            "浮动键盘未启用"
        }
        if (Build.VERSION.SDK_INT >= 30) {
            handle.stateDescription =
                if (value) "可拖动，点击可贴底显示" else "不可用"
        }
        handle.visibility = if (value) View.VISIBLE else View.GONE
        if (!value) dragController.reset()
    }

    fun reset() {
        dragController.reset()
    }

    fun applyTheme(tokens: ImeTheme.Tokens) {
        (handle as? DragHandleView)?.setDotColor(tokens.border)
        if (enabled) {
            mainDock.background = ImeDrawableFactory.rounded(
                tokens.keyboardBackground,
                toPx(ImeGeometryTokens.CARD_RADIUS_DP),
            )
            mainDock.clipToOutline = true
        } else {
            mainDock.setBackgroundColor(tokens.keyboardBackground)
            mainDock.clipToOutline = false
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (!enabled || !canDrag()) return false
        return dragController.onTouch(event)
    }

    private class DragHandleView(
        context: Context,
        private val toPx: (Int) -> Int,
    ) : View(context) {
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            style = Paint.Style.FILL
        }

        fun setDotColor(color: Int) {
            dotPaint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = toPx(2)
            val gapX = toPx(7)
            val gapY = toPx(7)
            val startX = width / 2f - gapX
            val startY = height / 2f - gapY / 2f
            for (row in 0..1) {
                for (column in 0..2) {
                    canvas.drawCircle(
                        startX + column * gapX,
                        startY + row * gapY,
                        radius.toFloat(),
                        dotPaint,
                    )
                }
            }
        }
    }
}
