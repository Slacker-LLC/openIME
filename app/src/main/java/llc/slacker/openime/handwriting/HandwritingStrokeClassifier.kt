package llc.slacker.openime.handwriting

import android.content.Context
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Classifies one drawn stroke as h 横, s 竖, p 撇, n 捺 or z 折.
 *
 * The stroke is simplified (Ramer-Douglas-Peucker), split into straight
 * segments and judged by its net direction; a sharp turn anywhere in the path
 * makes it a 折. This mirrors the five-stroke taxonomy of the 笔画 keyboard,
 * so the same table can serve both.
 */
internal object HandwritingStrokeClassifier {
    private const val MIN_DISPLACEMENT = 6f
    private const val TURN_DEGREES = 58.0

    /** Returns the stroke class, or null when the gesture is too small to read. */
    fun classify(points: List<StrokePoint>): Char? {
        val shape = simplify(points)
        if (shape.size < 2) return null
        val dx = shape.last().x - shape.first().x
        val dy = shape.last().y - shape.first().y
        if (hypot(dx, dy) < MIN_DISPLACEMENT) return null

        val segments = ArrayList<FloatArray>(shape.size - 1)
        for (index in 0 until shape.size - 1) {
            val sx = shape[index + 1].x - shape[index].x
            val sy = shape[index + 1].y - shape[index].y
            if (hypot(sx, sy) >= MIN_DISPLACEMENT / 2f) segments += floatArrayOf(sx, sy)
        }
        if (segments.isEmpty()) return null

        var maxTurn = 0.0
        for (index in 0 until segments.size - 1) {
            var turn = abs(angleDegrees(segments[index]) - angleDegrees(segments[index + 1]))
            if (turn > 180.0) turn = 360.0 - turn
            if (turn > maxTurn) maxTurn = turn
        }
        if (maxTurn > TURN_DEGREES) return 'z'
        return direction(dx, dy)
    }

    private fun angleDegrees(segment: FloatArray): Double =
        Math.toDegrees(atan2(segment[1].toDouble(), segment[0].toDouble()))

    /** With dy positive downward: 横 across, 竖 down, 撇 down-left, 捺 down-right. */
    private fun direction(dx: Float, dy: Float): Char = when {
        abs(dx) >= abs(dy) -> 'h'
        abs(dx) <= 0.35f * abs(dy) -> 's'
        dx > 0 -> 'n'
        else -> 'p'
    }

    private fun simplify(points: List<StrokePoint>): List<StrokePoint> {
        if (points.size <= 2) return points
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (point in points) {
            if (point.x < minX) minX = point.x
            if (point.x > maxX) maxX = point.x
            if (point.y < minY) minY = point.y
            if (point.y > maxY) maxY = point.y
        }
        val epsilon = maxOf(4f, 0.04f * hypot(maxX - minX, maxY - minY))
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        keepSimplified(points, 0, points.size - 1, epsilon, keep)
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun keepSimplified(
        points: List<StrokePoint>,
        start: Int,
        end: Int,
        epsilon: Float,
        keep: BooleanArray,
    ) {
        if (end - start < 2) return
        var farthest = -1
        var farthestDistance = 0f
        for (index in start + 1 until end) {
            val distance = distanceToSegment(points[index], points[start], points[end])
            if (distance > farthestDistance) {
                farthestDistance = distance
                farthest = index
            }
        }
        if (farthest > 0 && farthestDistance > epsilon) {
            keep[farthest] = true
            keepSimplified(points, start, farthest, epsilon, keep)
            keepSimplified(points, farthest, end, epsilon, keep)
        }
    }

    private fun distanceToSegment(point: StrokePoint, a: StrokePoint, b: StrokePoint): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lengthSquared = abx * abx + aby * aby
        if (lengthSquared <= 0.0001f) return hypot(point.x - a.x, point.y - a.y)
        var t = ((point.x - a.x) * abx + (point.y - a.y) * aby) / lengthSquared
        t = t.coerceIn(0f, 1f)
        return hypot(point.x - (a.x + t * abx), point.y - (a.y + t * aby))
    }
}
