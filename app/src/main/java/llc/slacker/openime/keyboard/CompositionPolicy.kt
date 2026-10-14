package llc.slacker.openime.keyboard

/**
 * The typing toolbar's pre-edit block: a scrollable composition at most half
 * the page wide, with the rest of its row left empty for the surface beside it
 * (`layout/input_toolbar_typing.xml:5-19`).
 */
internal object CompositionPolicy {
    /**
     * `maxwidth="halfMaxWidth"` on the composition's wrapper, the block that
     * holds the two labels (`input_toolbar_typing.xml:7`; the row leaves the
     * other half to an empty control, line 18).
     */
    const val MAX_WIDTH_FRACTION = 0.5f

    /** The block's own left inset, `padding="5,0,0,0"` (`input_toolbar_typing.xml:7`). */
    const val LEAD_UNITS = 5

    fun maxWidthPx(zoneWidth: Int): Int = (zoneWidth * MAX_WIDTH_FRACTION).toInt()
}
