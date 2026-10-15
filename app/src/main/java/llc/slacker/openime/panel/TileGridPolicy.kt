package llc.slacker.openime.panel

/**
 * The launcher grid the reference draws on both its settings page and its
 * keyboard switcher (`input_settings.xml:13-45`, `input_switch_keyboard.xml:13-29`).
 *
 * The container takes 88.9% of the page with its own paddings (5.6% at each
 * side, 7.2% on top and 10.1% at the foot) and lays its tiles out 4 columns by
 * 2 rows, its height capped at 192 units (`cls_settings_bk_adaptive`,
 * `style.xml:616-619`). Each tile fills its cell with a radius-14 face filled
 * with `setting_button_bk`, `setting_button_pushedbk` while pressed and the
 * theme colour when it is the chosen one, its name in `keybutton_text_3` and
 * white on the chosen tile (`cls_settings_button`, `style.xml:626-647`;
 * `values/colors.xml:15,87-93`).
 */
internal object TileGridPolicy {
    const val COLUMNS = 4
    const val ROWS = 2
    const val PAGE_SIZE = COLUMNS * ROWS

    /** `width_weight="0.88888"` and the container's own `padding_decimal`. */
    const val WIDTH_FRACTION = 0.88888f
    const val PAD_H_FRACTION = 0.05555f
    const val PAD_TOP_FRACTION = 0.0721f
    const val PAD_BOTTOM_FRACTION = 0.10096f

    /** `maxheight="192"`, the cap on the two rows together. */
    const val MAX_HEIGHT_UNITS = 192

    /** `childpadding_decimal="0.05"`, `childvpadding_decimal="0.11111"`. */
    const val CHILD_PAD_FRACTION = 0.05f
    const val CHILD_V_PAD_FRACTION = 0.11111f

    /** `bkround="14"` on `cls_settings_button`. */
    const val TILE_RADIUS_DP = 14

    /** Our own share: the name sits under a glyph half the tile tall. */
    const val ICON_FRACTION = 0.52f

    /** The rows a list of [entries] needs, never fewer than the reference's two. */
    fun rows(entries: Int): Int {
        if (entries <= 0) return ROWS
        val needed = (entries + COLUMNS - 1) / COLUMNS
        return maxOf(ROWS, needed)
    }
}
