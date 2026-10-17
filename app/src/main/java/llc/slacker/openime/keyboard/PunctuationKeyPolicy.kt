package llc.slacker.openime.keyboard

/**
 * Content of the three punctuation keys on the 26-key bottom row.
 *
 * [Spec.merged] is the wide key left of the space bar, the reference's
 * `ButtonPeriodComma`: Chinese draws `text="，"` with the always-visible corner
 * `ficon_text="。"` (`input_kbd_pinyin26.xml:145-152`), English keeps the Beta10 comma-first order
 * with `","` as the main mark and `"."` in the corner. An up swipe and a
 * long press commit that corner mark in both modes.
 *
 * [Spec.comma] and [Spec.period] are the two symbol keys the reference ships
 * visible either side of the space bar (`input_kbd_pinyin26.xml:153,156`;
 * `input_kbd_english26.xml:162-164,174-175`). Each shows its class's own text:
 * full-width `，` and `。` in Chinese (`style.xml:335-356`), `,` and `.` in
 * English. In an address or e-mail field the class's `uri_adapter_symbol`
 * replaces the pair with `"/"` and `"."`.
 */
internal object PunctuationKeyPolicy {
    data class Spec(
        val merged: String,
        val mergedCorner: String,
        val comma: String,
        val period: String,
    )

    private val CHINESE = Spec(merged = "，", mergedCorner = "。", comma = "，", period = "。")
    private val ENGLISH = Spec(merged = ",", mergedCorner = ".", comma = ",", period = ".")

    /** The address-field marks, from `uri_adapter_symbol` on both symbol keys. */
    private const val URI_COMMA = "/"

    fun spec(english: Boolean, uriField: Boolean = false): Spec {
        val base = if (english) ENGLISH else CHINESE
        // The merged key carries no uri_adapter_symbol, so it keeps its marks.
        return if (uriField) base.copy(comma = URI_COMMA, period = ".") else base
    }
}
