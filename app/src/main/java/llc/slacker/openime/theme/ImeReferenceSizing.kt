package llc.slacker.openime.theme

/** All reference dimensions scale together from the PDF's 390-unit canvas. */
internal object ImeReferenceSizing {
    fun scale(
        context: android.content.Context,
        widthPx: Int = 0,
        // The floating window keeps portrait proportions in landscape too.
        landscapeCompact: Boolean = true,
    ): Float {
        val config = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val widthDp = if (widthPx > 0) widthPx / metrics.density else config.screenWidthDp.toFloat()
        val widthScale = widthDp.coerceAtMost(600f) / 390f
        return if (landscapeCompact && config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            minOf(widthScale, config.screenHeightDp * 0.55f / 256f)
        } else widthScale
    }
}
