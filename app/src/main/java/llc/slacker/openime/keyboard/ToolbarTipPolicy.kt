package llc.slacker.openime.keyboard

/**
 * The reference's toolbar tip banner ("引导"): a pill that fills the toolbar
 * row with a lead space, a left-aligned label and a round close key
 * (`layout/input_toolbar_tip.xml:44-49`).
 */
internal object ToolbarTipPolicy {
    /** The container's own horizontal padding, 3.333% of the row (`style.xml:442`). */
    const val CONTAINER_PAD_FRACTION = 0.03333f

    /** `cls_toolbar_tip_leading_space_adaptive` width_weight (`style.xml:1277`). */
    const val LEAD_WEIGHT = 0.03571f

    /** `cls_toolbar_tip_label_adaptive` padding_decimal (`style.xml:1265`). */
    const val LABEL_PAD_FRACTION = 0.012f

    /**
     * The pill's height. The container is the full row minus its own vertical
     * padding, 0.17857 + 0.10714 of the row (`style.xml:442`), which leaves
     * 0.71429 of a 48-unit row — the 34dp our painted toolbar controls use. The
     * close key is a square of that height (`width="match_height"`,
     * `style.xml:1307`).
     */
    const val PILL_UNITS = 34

    /** `bkround="999"` on the container and on the close key (`style.xml:1275`, `:1310`). */
    const val RADIUS_UNITS = 999
}
