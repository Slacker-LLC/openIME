package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.core.Panel
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.keyboard.ImeTopZone
import llc.slacker.openime.setup.SetupUi
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class PanelAlignmentInstrumentedTest {
    private fun withKeyboard(test: (DirectActivityHarness<DebugKeyboardActivity>, ImeKeyboardView) -> Unit) {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val keyboard = harness.awaitMain { activity ->
                val listener = Proxy.newProxyInstance(
                    ImeKeyboardView.Listener::class.java.classLoader,
                    arrayOf(ImeKeyboardView.Listener::class.java),
                ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null } as ImeKeyboardView.Listener
                ImeKeyboardView(activity, listener).also {
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(it)
                }
            }
            try {
                harness.awaitMain<Boolean> { if (keyboard.width > 0) true else null }
                test(harness, keyboard)
            } finally {
                harness.awaitMain<Boolean> {
                    keyboard.shutdown()
                    (keyboard.parent as? ViewGroup)?.removeView(keyboard)
                    true
                }
            }
        }
    }

    private fun center(view: View): Float {
        val location = IntArray(2).also(view::getLocationOnScreen)
        return location[0] + view.width / 2f
    }

    @Test
    fun launcherRowsStayCenteredWhenTheWindowChangesWidth() = withKeyboard { harness, keyboard ->
        val fullWidth = keyboard.width
        for (panel in listOf(Panel.KEYBOARD_SELECT, Panel.TOOLS)) {
            harness.awaitMain<Boolean> { keyboard.showPanel(panel); true }
            for (width in listOf(fullWidth, fullWidth * 3 / 4, fullWidth)) {
                harness.awaitMain<Boolean> {
                    keyboard.layoutParams = keyboard.layoutParams.apply { this.width = width }
                    true
                }
                harness.awaitMain<Boolean> {
                    val grid = keyboard.findViewWithTag<ViewGroup>("tile-grid")
                    // The page slides in on its own translation; measure it at rest,
                    // or the row reads a few px off the window's centre.
                    val overlay = keyboard.findViewWithTag<View>("panel-overlay")
                    if (keyboard.width != width || grid == null || grid.width <= 0 || grid.isLayoutRequested ||
                        overlay?.translationX != 0f
                    ) {
                        null
                    } else {
                        val offset = keyRowOffset(keyboard, grid)
                        for (rowIndex in 0 until grid.childCount) {
                            val row = grid.getChildAt(rowIndex) as ViewGroup
                            val first = (row.getChildAt(0) as ViewGroup).getChildAt(0)
                            val last = (row.getChildAt(row.childCount - 1) as ViewGroup).getChildAt(0)
                            val left = IntArray(2).also(first::getLocationOnScreen)[0]
                            val right = IntArray(2).also(last::getLocationOnScreen)[0] + last.width
                            assertEquals(
                                "$panel row $rowIndex at width $width ($offset)",
                                center(keyboard),
                                (left + right) / 2f,
                                2f,
                            )
                            assertTrue("Tiles fit inside their grid", first.width > 0 && last.right <= last.parent.let { it as View }.width)
                        }
                        true
                    }
                }
            }
        }
    }

    /** A one-line read of where the grid sits, for an assertion failure. */
    private fun keyRowOffset(keyboard: ImeKeyboardView, grid: View): String {
        fun box(view: View?): String = view?.let {
            val at = IntArray(2).also(it::getLocationOnScreen)[0]
            "$at+${it.width}p${it.paddingLeft}/${it.paddingRight}"
        } ?: "gone"
        return "keyboard=${box(keyboard)}" +
            " overlay=${box(keyboard.findViewWithTag("panel-overlay"))}" +
            " grid=${box(grid)}"
    }

    @Test
    fun toolbarKeepsTheOriginalOrderInNormalAndCompactModes() = withKeyboard { harness, keyboard ->
        harness.awaitMain<Boolean> {
            val top = keyboard.findViewWithTag<ImeTopZone>("ime_toolbar")
                ?: (0 until keyboard.childCount).map { keyboard.getChildAt(it) }.filterIsInstance<ImeTopZone>().first()
            val strip = keyboard.findViewWithTag<LinearLayout>("toolbar-icon-strip")
            fun labels() = (0 until strip.childCount).map { strip.getChildAt(it) }
                .filter { it.visibility == View.VISIBLE && it.contentDescription != null }
                .map { it.contentDescription.toString() }
            val normal = listOf("切换键盘", "剪贴板", "表情", "文本编辑", "撤销", "更多")
            assertEquals(normal, labels())
            top.setCompactToolbar(true)
            assertEquals(listOf("切换键盘", "常用语", "表情", "更多"), labels())
            top.setCompactToolbar(false)
            assertEquals(normal, labels())
            true
        }
    }

    @Test
    fun keyboardAndAppUseTheSameAccentAndToolbarIconsHaveFullerWeight() = withKeyboard { harness, keyboard ->
        for (appearance in listOf(ImeAppearance.LIGHT, ImeAppearance.DARK)) {
            harness.awaitMain<Boolean> {
                keyboard.applyPersistedSettings(ImeTheme.IOS, appearance, false, false, false, false)
                val tokens = ImeTheme.IOS.tokens(appearance)
                val configuration = android.content.res.Configuration(keyboard.resources.configuration).apply {
                    uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (appearance == ImeAppearance.DARK) android.content.res.Configuration.UI_MODE_NIGHT_YES
                        else android.content.res.Configuration.UI_MODE_NIGHT_NO
                }
                val page = keyboard.context.createConfigurationContext(configuration)
                assertEquals(SetupUi.accent(page), tokens.primary)
                val icon = keyboard.findViewWithTag<ImageView>("keyboard-selector")
                assertEquals(ImeSurfacePolicy.toolbarIcon(tokens), icon.imageTintList!!.defaultColor)
                assertEquals(217, android.graphics.Color.alpha(icon.imageTintList!!.defaultColor))
                true
            }
        }
    }
}
