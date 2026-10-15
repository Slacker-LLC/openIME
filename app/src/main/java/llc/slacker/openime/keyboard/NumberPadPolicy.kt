package llc.slacker.openime.keyboard

/**
 * Geometry of the number / decimal / phone page.
 *
 * Every number is the reference's own, read from its number layout
 * (`input_kbd_number_from26.xml:5-42`), the page its 26-key `123` key opens
 * (`input_kbd_pinyin26.xml:134`). `width_weight` is normalised per row, so the
 * figures below are the ratio the row must keep; the space between cells comes
 * from each key's own margins (`cls_keyboard9_button_margin`,
 * `style.xml:373-374`: 2.5 sideways, 2.75 up and down).
 */
internal object NumberPadPolicy {
    /** The symbol rail column, wider than the digit columns (`input_kbd_number_from26.xml:9`). */
    const val RAIL_COLUMN_WEIGHT = 66f

    /** One digit column (`input_kbd_number_from26.xml:19,25,31`). */
    const val DIGIT_COLUMN_WEIGHT = 75f

    /** The delete / space / send column (`input_kbd_number_from26.xml:36`). */
    const val SEND_COLUMN_WEIGHT = 66f

    /** The rail spans `RAIL_ROWS` key rows and the return key the next; 156 against 52 is 3 against 1. */
    const val RAIL_ROWS = 3
    const val BACK_ROWS = 1

    /** Send is twice as tall as the keys above it (`height_weight` 2, `input_kbd_number_from26.xml:39`). */
    const val SEND_ROWS = 2

    /** Each digit column closes with 符号, 0 and `.` (`input_kbd_number_from26.xml:26-27,29,34`). */
    val COLUMN_TAILS = listOf("符号", "0", ".")

    /** The digits above each column's last cell, read down the reference's columns. */
    val DIGIT_COLUMN_TOPS = listOf(
        listOf("1", "4", "7"),
        listOf("2", "5", "8"),
        listOf("3", "6", "9"),
    )
}
