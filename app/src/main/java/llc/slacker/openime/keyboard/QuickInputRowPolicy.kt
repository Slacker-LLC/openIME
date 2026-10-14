package llc.slacker.openime.keyboard

/**
 * The reference's quick-input rows for the app's own suggestions: the text at
 * the candidate font with the origin glyph riding above its right shoulder as a
 * superscript (`layout/input_toolbar_quickinput.xml:41-46`, the clipboard row).
 */
internal object QuickInputRowPolicy {
    /**
     * The text's own box inside the row: `margin_decimal="0,0.055,0,0.111"` on
     * `btn_quickinput_clipboard` (`input_toolbar_quickinput.xml:42`). The
     * smaller top share lifts the line slightly above the row's centre.
     */
    const val TEXT_TOP_FRACTION = 0.055f
    const val TEXT_BOTTOM_FRACTION = 0.111f

    /**
     * The badge's height against the candidate font: `fi_toolbar_tip_superscript`
     * is 30 units where `cand_font` is 56 (`style.xml:138`, `:83`), so the glyph
     * is a little over half the text it marks.
     */
    const val BADGE_FONT_RATIO = 30f / 56f

    /**
     * The badge keeps the row's top edge with the reference's own lede:
     * `margin_decimal="0,0.08,0,0.111"` with `align="top"`
     * (`input_toolbar_quickinput.xml:44-45`).
     */
    const val BADGE_TOP_FRACTION = 0.08f
}
