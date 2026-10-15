package llc.slacker.openime.keyboard

import android.view.MotionEvent
import llc.slacker.openime.floating.LongPressDragTarget

/**
 * 停靠键盘的“键盘调节”：顶栏按住后上下拖动，实时改变键盘高度。
 * 与浮动键盘的移动共用一个长按入口（[LongPressDragTarget]），
 * 但只在顶栏范围内起手（[ImeTopZone.setLongPressDrag] 的 toolbarOnly），
 * 不抢候选与输入区的长按。
 */
internal class KeyboardHeightDragController(
    private val onDragBy: (Float) -> Unit,
    /** 手势结束（松手或被系统收走）时收尾：拖动中只预览，最终值这时才落地。 */
    private val onDragEnd: () -> Unit,
) : LongPressDragTarget {
    private var active = false
    private var pointerId = -1
    private var lastRawY = 0f

    override fun begin(event: MotionEvent) {
        active = true
        pointerId = event.getPointerId(event.actionIndex)
        lastRawY = event.rawY
    }

    override fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true
                pointerId = event.getPointerId(event.actionIndex)
                lastRawY = event.rawY
            }

            MotionEvent.ACTION_MOVE -> {
                if (!active) return true
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                val rawY = event.rawY + event.getY(index) - event.y
                val deltaY = rawY - lastRawY
                lastRawY = rawY
                if (deltaY != 0f) onDragBy(deltaY)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish()
        }
        return true
    }

    /** 拖动收尾：真的拖过（[active]）才报告结束，一次轻点不算。 */
    private fun finish() {
        val wasDragging = active
        reset()
        if (wasDragging) onDragEnd()
    }

    override fun reset() {
        active = false
        pointerId = -1
        lastRawY = 0f
    }
}
