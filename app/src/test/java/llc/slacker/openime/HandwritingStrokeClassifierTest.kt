package llc.slacker.openime

import llc.slacker.openime.handwriting.HandwritingStrokeClassifier
import llc.slacker.openime.handwriting.Stroke
import llc.slacker.openime.handwriting.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandwritingStrokeClassifierTest {

    private fun stroke(vararg points: Pair<Float, Float>): Stroke =
        Stroke(points.mapIndexed { index, (x, y) -> StrokePoint(x, y, index * 10L) })

    private fun classify(vararg points: Pair<Float, Float>) =
        HandwritingStrokeClassifier.classify(stroke(*points).points)

    @Test
    fun straightStrokesReadTheirDirection() {
        assertEquals('h', classify(10f to 50f, 120f to 52f))
        assertEquals('s', classify(50f to 10f, 52f to 120f))
        assertEquals('p', classify(120f to 10f, 30f to 110f))
        assertEquals('n', classify(30f to 10f, 120f to 110f))
    }

    @Test
    fun aSharpTurnMakesAZhe() {
        assertEquals('z', classify(10f to 10f, 110f to 12f, 112f to 110f))
        assertEquals('z', classify(110f to 10f, 112f to 110f, 12f to 112f))
    }

    @Test
    fun gentleCurvesStayStraight() {
        assertEquals('h', classify(10f to 10f, 60f to 14f, 110f to 12f))
        assertEquals('s', classify(10f to 10f, 14f to 60f, 12f to 110f))
    }

    @Test
    fun noiseIsRejected() {
        assertNull(classify(10f to 10f, 12f to 11f))
        assertNull(classify(10f to 10f))
    }
}
