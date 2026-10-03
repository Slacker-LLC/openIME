package llc.slacker.openime.keyboard

/**
 * Content of the comma/period key beside the 26-key space bar.
 *
 * Tap commits [comma]; long press commits [period], which is also the corner
 * hint. Chinese mode uses the full-width marks.
 */
internal object PunctuationKeyPolicy {
    data class Spec(
        val comma: String,
        val period: String,
    )

    private val CHINESE = Spec(comma = "，", period = "。")
    private val ENGLISH = Spec(comma = ",", period = ".")

    fun spec(english: Boolean): Spec = if (english) ENGLISH else CHINESE
}
