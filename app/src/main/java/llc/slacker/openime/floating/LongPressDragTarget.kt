package llc.slacker.openime.floating

import android.view.MotionEvent

/**
 * 顶栏长按后接收拖动手势的目标：浮动键盘的移动、停靠键盘的高度调节都实现它，
 * 顶栏只负责"长按触发 + 转发触摸"。
 */
internal interface LongPressDragTarget {
    /** 长按成立那一刻起手（手势中途开始拖动）。 */
    fun begin(event: MotionEvent)

    fun onTouch(event: MotionEvent): Boolean

    fun reset()
}
