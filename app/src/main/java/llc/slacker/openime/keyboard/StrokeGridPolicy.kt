package llc.slacker.openime.keyboard

import llc.slacker.openime.candidate.Stroke

/**
 * The reference's stroke keyboard grid.
 *
 * Three columns of three cells, read from `input_kbd_stroke.xml:16-66`: the
 * five strokes in their usual order (一 丨 丿 丶 折), 通配, and the three cells
 * the reference puts beside them. Every cell carries a corner digit, and an up
 * swipe types it: the skin gives each one `ficon_text` with a matching
 * `fusiyin_keycode` (一 is `ficon_text="1" fusiyin_keycode="0x3031"`,
 * `input_kbd_stroke.xml:19-22`, 通配 `ficon_text="6"`, line 57-61).
 */
internal object StrokeGridPolicy {
    /**
     * One cell. A cell with [stroke] feeds the stroke engine; a cell with
     * [commit] types its character at once, as a `commitstr` key does.
     */
    data class Cell(
        val label: String,
        val corner: String,
        val tag: String,
        val stroke: String? = null,
        val commit: String? = null,
    )

    val ROWS: List<List<Cell>> = listOf(
        listOf(
            strokeCell(Stroke.HENG),
            strokeCell(Stroke.SHU),
            strokeCell(Stroke.PIE),
        ),
        listOf(
            strokeCell(Stroke.DIAN),
            strokeCell(Stroke.ZHE),
            Cell("通配", "6", "key-stroke:wildcard", stroke = Stroke.WILDCARD_GLYPH),
        ),
        listOf(
            // The reference's three extra cells: @# with the corner 7, then the
            // two punctuation marks it commits at once (`commitstr="："` /
            // `"；"`, `input_kbd_stroke.xml:46,62`).
            Cell("@#", "7", "key-stroke:at", commit = "7"),
            Cell("：", "8", "key-stroke:colon", commit = "："),
            Cell("；", "9", "key-stroke:semicolon", commit = "；"),
        ),
    )

    /** The @# cell's own list (`long_press_bubble_text`, `input_kbd_stroke.xml:32`). */
    val AT_SYMBOL_CHOICES = listOf("/", "-", "_", "@", "#", ".", "7")

    private fun strokeCell(stroke: Stroke) =
        Cell(stroke.glyph, stroke.digit.toString(), "key-stroke:${stroke.code}", stroke = stroke.glyph)
}
