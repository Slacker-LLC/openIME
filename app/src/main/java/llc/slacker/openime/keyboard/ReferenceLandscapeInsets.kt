package llc.slacker.openime.keyboard

/**
 * 横屏内容留白，取自参考键盘的横屏皮肤（其 APK 内 assets/skin/default/land/style.xml）：
 * 键区左右各 6.485%（九键 9.5%）、顶栏 8.2%、展开候选 9.5%。竖屏不变（0）。
 *
 * 留白是"内容距窗口边"的比例，窗口本身仍是整宽；背景保持整宽铺满，
 * 键位比竖屏略往中间收，远离横屏两侧的手掌边缘。
 */
internal object ReferenceLandscapeInsets {
    const val KEYBOARD_FRACTION = 0.0648508f
    const val NINE_KEY_FRACTION = 0.095f
    const val TOOLBAR_FRACTION = 0.082f
    const val EXPANDED_CANDIDATES_FRACTION = 0.095f

    fun keyboardFraction(nineKey: Boolean): Float =
        if (nineKey) NINE_KEY_FRACTION else KEYBOARD_FRACTION
}
