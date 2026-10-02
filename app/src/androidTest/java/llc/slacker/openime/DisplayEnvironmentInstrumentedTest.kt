package llc.slacker.openime

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keyboard has to stay usable in every display environment, not just the
 * default portrait phone: landscape (content box narrower than the window),
 * large system fonts, and every layout.
 */
@RunWith(AndroidJUnit4::class)
class DisplayEnvironmentInstrumentedTest {

    private val listener: ImeKeyboardView.Listener = Proxy.newProxyInstance(
        ImeKeyboardView.Listener::class.java.classLoader,
        arrayOf(ImeKeyboardView.Listener::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            else -> null
        }
    } as ImeKeyboardView.Listener

    private fun configured(base: Context, change: Configuration.() -> Unit): Context =
        base.createConfigurationContext(Configuration(base.resources.configuration).apply(change))

    private fun allViews(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(allViews(root.getChildAt(index)))
        }
    }

    /** Show a keyboard built from [contextFor] and hand it to [check] once the first layout is done. */
    private fun withKeyboard(
        mode: KeyboardMode,
        contextFor: (Context) -> Context,
        check: (ImeKeyboardView) -> Unit,
    ) {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val keyboard = harness.awaitMain { activity ->
                ImeKeyboardView(contextFor(activity), listener).also {
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(
                        it,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
            }
            try {
                harness.awaitMain {
                    keyboard.setMode(mode, notifyListener = false)
                    true
                }
                // First layout done: some visible key has a size.
                harness.awaitMain {
                    val shown = allViews(keyboard).filterIsInstance<ImeKeyView>().filter { it.isShown }.toList()
                    if (keyboard.width > 0 && shown.isNotEmpty() && shown.all { it.width > 0 }) true else null
                }
                // Geometry corrections are applied around the first layout; give
                // any deferred relayout time to run before looking at sizes.
                Thread.sleep(600)
                harness.awaitMain {
                    check(keyboard)
                    true
                }
            } finally {
                harness.awaitMain {
                    keyboard.shutdown()
                    (keyboard.parent as? ViewGroup)?.removeView(keyboard)
                    true
                }
            }
        }
    }

    @Test
    fun landscapeContentBoxKeepsEveryRowInsideIt() {
        // A landscape configuration makes the reference scale follow the height,
        // so the content box (padding on both sides) is narrower than the window.
        // Rows used to keep the width they were measured with before that padding
        // was applied: as wide as the window, clipped to the box.
        for (mode in listOf(KeyboardMode.PINYIN_26, KeyboardMode.ENGLISH_26, KeyboardMode.PINYIN_9, KeyboardMode.DIGITS)) {
            withKeyboard(
                mode = mode,
                contextFor = { base ->
                    configured(base) {
                        orientation = Configuration.ORIENTATION_LANDSCAPE
                        screenWidthDp = 914
                        screenHeightDp = 411
                    }
                },
            ) { keyboard ->
                val body = keyboard.findViewWithTag<ViewGroup>("keyboard-body")
                assertNotNull("keyboard body", body)
                val content = body.width - body.paddingLeft - body.paddingRight
                assertTrue("$mode must be laid out in a clamped content box (padding ${body.paddingLeft})", body.paddingLeft > 0)
                for (index in 0 until body.childCount) {
                    val row = body.getChildAt(index)
                    assertTrue(
                        "$mode row $index is ${row.width}px wide inside a ${content}px content box",
                        row.width <= content + 1,
                    )
                }
                val toolbar = keyboard.findViewWithTag<ViewGroup>("toolbar-row")
                if (toolbar != null) {
                    val toolbarContent = toolbar.width - toolbar.paddingLeft - toolbar.paddingRight
                    var used = 0
                    for (index in 0 until toolbar.childCount) {
                        val child = toolbar.getChildAt(index)
                        if (child.visibility == View.VISIBLE) used += child.width
                    }
                    assertTrue(
                        "$mode toolbar items need ${used}px but the content box is ${toolbarContent}px",
                        used <= toolbarContent + 1,
                    )
                }
            }
        }
    }

    @Test
    fun keyLabelsStayReadableAtEverySystemFontSize() {
        // Key labels follow the system font only up to 1.3x; beyond that a key
        // cannot grow, and "m" turned into an ellipsis while "中/英" lost a glyph.
        for (fontScale in listOf(1.0f, 1.3f, 1.5f, 2.0f)) {
            for (mode in listOf(KeyboardMode.PINYIN_26, KeyboardMode.ENGLISH_26, KeyboardMode.PINYIN_9, KeyboardMode.DIGITS)) {
                withKeyboard(
                    mode = mode,
                    contextFor = { base -> configured(base) { this.fontScale = fontScale } },
                ) { keyboard ->
                    val clipped = allViews(keyboard)
                        .filterIsInstance<ImeKeyView>()
                        .filter { !it.mainLabelFits() }
                        .map { "'${it.currentMainText}'" }
                        .toList()
                    assertEquals("labels cut off at font scale $fontScale in $mode", emptyList<String>(), clipped)
                }
            }
        }
    }
}
