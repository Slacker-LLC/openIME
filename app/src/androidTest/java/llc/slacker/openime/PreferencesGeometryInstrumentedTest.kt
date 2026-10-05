package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.theme.ImeGeometryTokens
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The preferences page keeps one grid: icon tiles centred on their row, every
 * label on one text column, and every trailing control ending on one line.
 */
@RunWith(AndroidJUnit4::class)
class PreferencesGeometryInstrumentedTest {

    @Test
    fun rowsShareOneTextColumnAndOneTrailingEdge() {
        DirectActivityHarness(ImeSettingsActivity::class.java).use { harness ->
            harness.launch()
            harness.awaitMain { activity ->
                val root = activity.window.decorView
                val rows = collect(root) { it.tag == "setting-row" }.filterIsInstance<ViewGroup>()
                if (rows.size < 10 || rows.any { it.width == 0 }) return@awaitMain null
                val density = activity.resources.displayMetrics.density
                val slack = 1

                val labelLefts = rows.flatMap { row -> collect(row) { it.tag == "setting-label" } }.map { left(it) }
                assertTrue("Every label starts on one text column: $labelLefts", labelLefts.max() - labelLefts.min() <= slack)
                val textColumn = labelLefts.min()

                rows.forEach { row ->
                    val trailingEdge = left(row) + row.width - row.paddingRight
                    collect(row) { it.tag == "setting-icon" }.forEach { icon ->
                        val iconCentre = top(icon) + icon.height / 2
                        val rowCentre = top(row) + row.height / 2
                        assertTrue("Icon is centred on its row (${row.contentDescription})", abs(iconCentre - rowCentre) <= slack)
                    }
                    collect(row) { it.tag == "toggle" }.forEach { toggle ->
                        assertTrue("Switch ends on the row's 16dp line", abs(left(toggle) + toggle.width - trailingEdge) <= slack)
                    }
                    collect(row) { it.tag == "setting-chevron" }.forEach { chevron ->
                        val glyphEnd = left(chevron) + chevron.width - (ImeGeometryTokens.CHEVRON_GLYPH_END_INSET_DP * density).toInt()
                        assertTrue("Chevron glyph ends on the row's 16dp line", abs(glyphEnd - trailingEdge) <= slack + 1)
                    }
                    collect(row) { it is SeekBar }.forEach { bar ->
                        val trackStart = left(bar) + bar.paddingLeft
                        val trackEnd = left(bar) + bar.width - bar.paddingRight
                        assertTrue("Slider track starts on the text column", abs(trackStart - textColumn) <= slack)
                        assertTrue("Slider track ends on the row's 16dp line", abs(trackEnd - trailingEdge) <= slack)
                    }
                }
                true
            }
        }
    }

    private fun left(view: View): Int = IntArray(2).also { view.getLocationInWindow(it) }[0]

    private fun top(view: View): Int = IntArray(2).also { view.getLocationInWindow(it) }[1]

    private fun collect(root: View, match: (View) -> Boolean): List<View> {
        val found = mutableListOf<View>()
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (match(view)) found += view
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(root)
        return found
    }
}
