package llc.slacker.openime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * A compact side-symbol rail: tap, press-drag across cells, or swipe to the
 * next symbol group. Releasing outside the rail cancels the selection.
 */
class SymbolRailView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 19f * density
    }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var symbols: List<String> = emptyList()
    private var selected = -1
    private var downX = 0f
    private var downY = 0f
    private var active = false

    var onSymbolCommit: ((String) -> Unit)? = null
    var onGroupSwipe: ((Int) -> Unit)? = null

    init {
        isClickable = true
        contentDescription = "侧边滑动符号"
        cellPaint.color = 0x18000000
        selectedPaint.color = 0xFF4E79FF.toInt()
        labelPaint.color = 0xFF202124.toInt()
        setBackgroundColor(0x00000000)
    }

    fun setSymbols(value: List<String>) {
        symbols = value.distinct().take(10)
        selected = -1
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (48 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (symbols.isEmpty()) return
        val cellWidth = width.toFloat() / symbols.size
        val radius = 10f * density
        symbols.forEachIndexed { index, symbol ->
            val left = index * cellWidth + 2f * density
            val right = (index + 1) * cellWidth - 2f * density
            val rect = RectF(left, 3f * density, right, height - 3f * density)
            canvas.drawRoundRect(rect, radius, radius, if (index == selected) selectedPaint else cellPaint)
            labelPaint.color = if (index == selected) 0xFFFFFFFF.toInt() else 0xFF202124.toInt()
            val baseline = height / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f
            canvas.drawText(symbol, rect.centerX(), baseline, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (symbols.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                active = true
                selected = indexAt(event.x, event.y)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active) return true
                val deltaX = event.x - downX
                val deltaY = event.y - downY
                if (abs(deltaX) >= 72f * density && abs(deltaX) > abs(deltaY) * 1.25f) {
                    onGroupSwipe?.invoke(if (deltaX < 0) 1 else -1)
                    downX = event.x
                    selected = indexAt(event.x, event.y)
                } else {
                    selected = indexAt(event.x, event.y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (active) {
                    val index = indexAt(event.x, event.y)
                    if (index >= 0 && index == selected) onSymbolCommit?.invoke(symbols[index])
                }
                active = false
                selected = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                active = false
                selected = -1
                invalidate()
                return true
            }
        }
        return true
    }

    private fun indexAt(x: Float, y: Float): Int {
        if (x < 0f || y < 0f || x > width || y > height) return -1
        return (x / (width.toFloat() / symbols.size)).toInt().coerceIn(0, symbols.lastIndex)
    }
}
