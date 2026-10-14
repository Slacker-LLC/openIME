package llc.slacker.openime.keyboard

/**
 * 停靠键盘的高度拖拽调节（“键盘调节”）：在顶栏按住并上下拖动，实时改变键盘高度。
 *
 * 固定灵敏度：向上拖增大、向下拖减小；拖动整屏高度的 [POINTS_PER_SCREEN]%
 * 即走完约两倍于当前区间（60%..160%，共 100 点）的距离。
 * 换算成手感：拖动约 20% 屏高 ≈ 40 个百分点。
 */
internal object KeyboardHeightDragPolicy {
    /** 拖动整屏高度对应的百分点数。 */
    private const val POINTS_PER_SCREEN = 200f

    fun percentDelta(deltaY: Float, screenHeightPx: Int): Int {
        if (screenHeightPx <= 0 || deltaY == 0f) return 0
        val perPx = POINTS_PER_SCREEN / screenHeightPx
        return Math.round(-deltaY * perPx)
    }

    fun applyTo(startPercent: Int, deltaY: Float, screenHeightPx: Int): Int =
        startPercent + percentDelta(deltaY, screenHeightPx)
}
