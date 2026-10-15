package llc.slacker.openime

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 浮动卡片向系统报告"身后被遮住多少"：内容顶和可见顶都取卡片自己的下边缘，应用既不 resize
 * 也不 pan；同一份报告还必须配 `TOUCHABLE_INSETS_FRAME`，否则整块卡片收不到触摸。
 *
 * `super.onComputeInsets` 设的模式是 `TOUCHABLE_INSETS_VISIBLE`（"the area inside of the
 * visible insets can be touched"，`ViewTreeObserver.InternalInsetsInfo`）。可见顶报成卡片
 * 整高时，那块区域就是"卡片 frame 从顶部内缩掉整个高度"，一个空矩形——beta10 的浮动键盘
 * 因此整块点不动。AOSP 全屏分支做同一份报告时正是配 `TOUCHABLE_INSETS_FRAME`。
 */
@RunWith(AndroidJUnit4::class)
class FloatingInsetsInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withService(test: (LocalVoiceImeService) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val service = LocalVoiceImeService()
            // The service is constructed directly instead of being started by the
            // platform, so it has no base context yet.
            ContextWrapper::class.java
                .getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }
                .invoke(service, context)
            test(service)
        }
    }

    @Test
    fun aFloatingCardReportsItsOwnBottomEdgeAndStaysTouchableEverywhere() = withService { service ->
        val outInsets = InputMethodService.Insets()
        service.applyFloatingInsets(outInsets, CARD_HEIGHT_PX)

        assertEquals(CARD_HEIGHT_PX, outInsets.contentTopInsets)
        assertEquals(CARD_HEIGHT_PX, outInsets.visibleTopInsets)
        assertEquals(
            "The whole card must keep answering touches",
            InputMethodService.Insets.TOUCHABLE_INSETS_FRAME,
            outInsets.touchableInsets,
        )
    }

    @Test
    fun aCardThatHasNoHeightYetLeavesTheBaseReportAlone() = withService { service ->
        val outInsets = InputMethodService.Insets()
        outInsets.contentTopInsets = BASE_CONTENT_TOP_INSETS
        outInsets.visibleTopInsets = BASE_VISIBLE_TOP_INSETS
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE

        service.applyFloatingInsets(outInsets, 0)

        assertEquals(BASE_CONTENT_TOP_INSETS, outInsets.contentTopInsets)
        assertEquals(BASE_VISIBLE_TOP_INSETS, outInsets.visibleTopInsets)
        assertEquals(
            InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE,
            outInsets.touchableInsets,
        )
    }

    /**
     * 报告为什么必须配 FRAME：默认模式下可触摸区就是"可见内缩之内的区域"（
     * `ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_VISIBLE`），可见顶等于卡片
     * 高度时它被剪成空集，键盘一点反应都没有。这条把机制摆在修复旁边，别人就不能把
     * FRAME 那一行当冗余删掉。
     */
    @Test
    fun theDefaultModeWouldLeaveTheCardWithNoTouchableArea() {
        val touchable = Rect(0, 0, CARD_WIDTH_PX, CARD_HEIGHT_PX)
        touchable.inset(0, CARD_HEIGHT_PX, 0, 0)

        assertTrue("A full-height visible top leaves nothing touchable", touchable.isEmpty)
    }

    private companion object {
        const val CARD_WIDTH_PX = 1080
        const val CARD_HEIGHT_PX = 720
        const val BASE_CONTENT_TOP_INSETS = 111
        const val BASE_VISIBLE_TOP_INSETS = 222
    }
}
