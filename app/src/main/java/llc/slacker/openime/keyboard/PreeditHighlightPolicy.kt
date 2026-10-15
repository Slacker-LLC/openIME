package llc.slacker.openime.keyboard

/**
 * Which part of the pre-edit the accent colour covers.
 *
 * The reference splits the open Pinyin string into two labels: `cls_comp_half`
 * paints the part the user has already fixed — 半选 and the nine-key reading the
 * user chose — in the theme colour (`style.xml:580-583`, `@color/theme_color`),
 * and `cls_comp_right` paints the still-unconverted tail in the key text colour
 * (`style.xml:585-587`, `@color/keybutton_text`).
 *
 * Only a plain prefix of the shown text counts as fixed: a syllable boundary the
 * decoder guessed is not one the user chose, so it stays in the plain colour.
 */
internal object PreeditHighlightPolicy {
    fun fixedPrefixLength(text: String, fixed: String): Int =
        if (fixed.isNotEmpty() && text.startsWith(fixed)) fixed.length else 0
}
