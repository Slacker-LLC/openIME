package llc.slacker.openime

/**
 * Handwriting recognition pipeline boundary.
 *
 * The IME collects strokes and asks a provider. Until a real recognizer is
 * configured, [UnavailableHandwritingProvider] returns an explicit "not
 * configured" result — never fake candidates.
 */
data class StrokePoint(val x: Float, val y: Float, val timestamp: Long, val pressure: Float = 1f)
data class Stroke(val points: List<StrokePoint>)

interface HandwritingProvider {
    fun recognize(strokes: List<Stroke>): HandwritingResult
}

sealed class HandwritingResult {
    data class Success(val candidates: List<String>) : HandwritingResult()
    object NotConfigured : HandwritingResult()
    data class Error(val message: String) : HandwritingResult()
}

object UnavailableHandwritingProvider : HandwritingProvider {
    override fun recognize(strokes: List<Stroke>): HandwritingResult =
        HandwritingResult.NotConfigured
}
