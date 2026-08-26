package llc.slacker.openime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/**
 * Canvas handwriting pad matching the design's helper grid, smooth strokes,
 * undo / clear / space actions. Recognition goes through [HandwritingProvider].
 */
class HandwritingPadView(
    context: Context,
    val onStrokesChanged: (List<Stroke>) -> Unit,
) : View(context) {

    private val strokes = mutableListOf<Stroke>()
    private var currentPoints = mutableListOf<StrokePoint>()
    private val paint = Paint().apply {
        color = Color.rgb(37, 99, 235)
        style = Paint.Style.STROKE
        strokeWidth = 5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(60, 120, 120, 120)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        canvas.drawLine(cx, 0f, cx, h, gridPaint)
        canvas.drawLine(0f, cy, w, cy, gridPaint)
        canvas.drawLine(0f, 0f, w, h, gridPaint)
        canvas.drawLine(0f, h, w, 0f, gridPaint)

        strokes.forEach { drawStroke(canvas, it.points) }
        if (currentPoints.isNotEmpty()) drawStroke(canvas, currentPoints)
    }

    private fun drawStroke(canvas: Canvas, points: List<StrokePoint>) {
        if (points.isEmpty()) return
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val p = points[i - 1]
            val c = points[i]
            path.quadTo(p.x, p.y, (p.x + c.x) / 2f, (p.y + c.y) / 2f)
        }
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentPoints = mutableListOf(
                    StrokePoint(event.x, event.y, event.eventTime, event.pressure),
                )
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPoints.add(
                    StrokePoint(event.x, event.y, event.eventTime, event.pressure),
                )
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (currentPoints.isNotEmpty()) strokes.add(Stroke(currentPoints.toList()))
                currentPoints = mutableListOf()
                onStrokesChanged(strokes.toList())
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun undo() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
        onStrokesChanged(strokes.toList())
        invalidate()
    }

    fun clear() {
        strokes.clear()
        currentPoints.clear()
        onStrokesChanged(emptyList())
        invalidate()
    }

    fun candidate(result: HandwritingResult): List<String> =
        (result as? HandwritingResult.Success)?.candidates.orEmpty()
}
