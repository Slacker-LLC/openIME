package llc.slacker.openime

internal object ImeBottomInsetPolicy {
    fun clampInset(reportedPx: Int, maxPx: Int): Int =
        reportedPx.coerceAtLeast(0).coerceAtMost(maxPx.coerceAtLeast(0))

    fun measuredHeight(
        baseHeightPx: Int,
        bottomInsetPx: Int,
        measureMode: Int,
        measureSizePx: Int,
    ): Int {
        val desired = (baseHeightPx + bottomInsetPx.coerceAtLeast(0)).coerceAtLeast(0)
        return when (measureMode) {
            android.view.View.MeasureSpec.AT_MOST -> minOf(desired, measureSizePx)
            android.view.View.MeasureSpec.EXACTLY -> minOf(desired, measureSizePx)
            else -> desired
        }.coerceAtLeast(0)
    }
}
