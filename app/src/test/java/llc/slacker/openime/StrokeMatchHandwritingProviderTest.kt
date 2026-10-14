package llc.slacker.openime

import llc.slacker.openime.handwriting.HandwritingResult
import llc.slacker.openime.handwriting.Stroke
import llc.slacker.openime.handwriting.StrokeMatchHandwritingProvider
import llc.slacker.openime.handwriting.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeMatchHandwritingProviderTest {

    private val provider = StrokeMatchHandwritingProvider(
        mapOf("h" to "一", "hspn" to "木朩"),
        listOf("h", "hspn"),
    )

    private fun stroke(vararg points: Pair<Float, Float>): Stroke =
        Stroke(points.mapIndexed { index, (x, y) -> StrokePoint(x, y, index * 10L) })

    private fun recognized(strokes: List<Stroke>): List<String> =
        (provider.recognize(strokes) as HandwritingResult.Success).candidates

    @Test
    fun exactCodeComesBeforeContinuations() {
        val result = recognized(
            listOf(
                stroke(10f to 20f, 100f to 22f), // h
                stroke(30f to 10f, 31f to 110f), // s
                stroke(100f to 10f, 40f to 110f), // p
                stroke(40f to 10f, 100f to 110f), // n
            ),
        )
        assertEquals(listOf("木", "朩"), result)
    }

    @Test
    fun shorterGestureFindsContinuations() {
        val result = recognized(listOf(stroke(10f to 20f, 100f to 22f))) // h
        assertEquals(listOf("一", "木", "朩"), result)
    }

    @Test
    fun unknownGestureReturnsNothing() {
        val result = recognized(listOf(stroke(100f to 10f, 40f to 110f))) // p
        assertTrue(result.isEmpty())
    }

    @Test
    fun availabilityFollowsTheTable() {
        assertTrue(provider.isAvailable)
        val empty = StrokeMatchHandwritingProvider(emptyMap(), emptyList())
        assertFalse(empty.isAvailable)
    }
}
