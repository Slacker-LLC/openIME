package llc.slacker.openime.keyboard

/**
 * The toolbar strip that lists the symbols the user has typed, as the reference
 * draws it while its symbol board is open (`input_toolbar_quickinput.xml:4-18`,
 * `toolbar_symbol_recent`).
 *
 * The row reads `[4] 历史符号(308) [1] [3] 关闭(40) [4]`, the same shape the
 * reference gives its candidate strip: a 4-unit lead inset, the scrolling list,
 * a hairline split, a 3-unit gap, the close key and a 4-unit tail. One cell is
 * 36 units wide with a radius-6 press face, candidate text in the candidate text
 * colour and 2.7% padding per side (`cls_history_symbollist_button`,
 * `style.xml:1061-1070`), and the list keeps 4 units between cells
 * (`cls_history_symbollist` childpadding, `style.xml:1055-1058`).
 */
internal object RecentSymbolStripPolicy {
    const val LEAD_UNITS = 4
    const val GAP_UNITS = 3
    const val TAIL_UNITS = 4

    /** `cls_btn_more_cand_button_adaptive`, the class the close key borrows (`style.xml:468`). */
    const val CLOSE_UNITS = 40

    /** `width1x="36"`, `bkround="6"` on the history cell. */
    const val CELL_UNITS = 36
    const val CELL_RADIUS_DP = 6

    /** `childpadding="4"` on the history list. */
    const val CELL_GAP_UNITS = 4

    /** The reference's own text padding per side on a cell (`textpadding="0.027,0,0.027,0"`). */
    const val CELL_PAD_DP = 3
}
