package llc.slacker.openime.keyboard

import llc.slacker.openime.core.OneHandMode

/**
 * 单手模式的横向留白：把键面收窄到窗口宽度的一个比例，并贴向用户选择的一侧。
 * 返回 (leftInsetPx, rightInsetPx)；关闭时返回 null（走对称留白）。
 *
 * 留白按"先保留正常内容边距，再把空出来的宽度全部放到另一侧"分配：
 * 左手时键盘贴左、右侧空出；右手时相反。
 */
internal object OneHandPolicy {
    /** 单手键盘宽度占窗口宽度的比例。 */
    const val WIDTH_FRACTION = 0.76f

    fun insets(
        measuredWidthPx: Int,
        mode: OneHandMode,
        baseInsetPx: Int,
        widthFraction: Float = WIDTH_FRACTION,
    ): Pair<Int, Int>? {
        if (mode == OneHandMode.OFF || measuredWidthPx <= 0) return null
        val fraction = widthFraction.coerceIn(0.5f, 1f)
        val keyboardWidth = (measuredWidthPx * fraction).toInt()
        val base = baseInsetPx.coerceIn(0, measuredWidthPx / 2)
        val outer = (measuredWidthPx - keyboardWidth - base).coerceAtLeast(base)
        return when (mode) {
            OneHandMode.LEFT -> base to outer
            OneHandMode.RIGHT -> outer to base
            OneHandMode.OFF -> null
        }
    }
}
