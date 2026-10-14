package llc.slacker.openime

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.core.Panel
import llc.slacker.openime.handwriting.StrokeMatchHandwritingProvider
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.panel.TileGridPolicy
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeReferenceSizing
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/**
 * The launcher pages — tools and the keyboard switcher — draw the reference's
 * own tile grid (`input_settings.xml:13-45`, `input_switch_keyboard.xml:13-29`).
 */
@RunWith(AndroidJUnit4::class)
class LauncherTileInstrumentedTest {
    private val listener = Proxy.newProxyInstance(
        ImeKeyboardView.Listener::class.java.classLoader,
        arrayOf(ImeKeyboardView.Listener::class.java),
    ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null } as ImeKeyboardView.Listener

    private fun withKeyboard(test: (ImeKeyboardView) -> Unit) {
        DirectActivityHarness(DebugKeyboardActivity::class.java).use { harness ->
            harness.launch()
            val keyboard = harness.awaitMain { activity ->
                ImeKeyboardView(activity, listener).also {
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(it)
                }
            }
            try {
                harness.awaitMain { if (keyboard.width > 0) true else null }
                harness.awaitMain {
                    // The tile colours are the palette's, so pin the palette.
                    keyboard.applyPersistedSettings(
                        newTheme = ImeTheme.IOS,
                        newAppearance = ImeAppearance.LIGHT,
                        sound = false,
                        haptic = true,
                        popup = true,
                        fuzzy = false,
                    )
                    true
                }
                harness.awaitMain { test(keyboard); true }
            } finally {
                harness.awaitMain {
                    keyboard.shutdown()
                    (keyboard.parent as? ViewGroup)?.removeView(keyboard)
                    true
                }
            }
        }
    }

    /** The default fill of a stateful rounded face. */
    private fun faceColor(view: View): Int? =
        ((view.background as StateListDrawable).current as GradientDrawable).color?.defaultColor

    private fun faceRadius(view: View): Float =
        ((view.background as StateListDrawable).current as GradientDrawable).cornerRadius

    @Test
    fun theToolsPageIsTheReferencesFourByTwoGrid() {
        withKeyboard { keyboard ->
            keyboard.showPanel(Panel.TOOLS)
            val grid = keyboard.findViewWithTag<View>("tile-grid")
            assertNotNull("The tools page must lay its tiles out as a grid", grid)
            val rows = (grid as ViewGroup)
            assertEquals("The reference's grid is two rows deep", TileGridPolicy.ROWS, rows.childCount)
            // The reference fills a row with four tiles and leaves a short last
            // row short (`input_settings.xml:13-45` packs its own buttons four
            // to a row); the cells keep the grid's own column width either way.
            assertEquals(
                "The first row carries the reference's four columns",
                TileGridPolicy.COLUMNS,
                (rows.getChildAt(0) as ViewGroup).childCount,
            )
            val cellWidths = (0 until rows.childCount).flatMap { index ->
                val cells = rows.getChildAt(index) as ViewGroup
                (0 until cells.childCount).map { cells.getChildAt(it).width }
            }
            assertEquals(
                "Every cell keeps the page's one column width: $cellWidths",
                1,
                cellWidths.distinct().size,
            )
            // Every tile of a page is the same size, as `parent_average` makes it.
            val sizes = (0 until rows.childCount).flatMap { row ->
                val cells = rows.getChildAt(row) as ViewGroup
                (0 until cells.childCount).map { column ->
                    val tile = (cells.getChildAt(column) as ViewGroup).getChildAt(0)
                    "${tile.width}x${tile.height}"
                }
            }.distinct()
            assertEquals("One page's tiles share one size", 1, sizes.size)
        }
    }

    @Test
    fun everyTileCarriesTheReferencesRoundedFace() {
        withKeyboard { keyboard ->
            keyboard.showPanel(Panel.TOOLS)
            val tokens = ImeTheme.IOS.tokens(ImeAppearance.LIGHT)
            val tile = keyboard.findViewWithTag<View>("tool:表情")!!
            assertTrue("A tile's face is a stateful card", tile.background is StateListDrawable)
            assertEquals(
                "The tile takes the card colour the reference fills it with",
                tokens.toolCardBackground,
                faceColor(tile),
            )
            // The radius goes through the view's own `dp`: the reference's units
            // scale with the page (`ImeKeyboardView.dp`), so the test measures
            // the tile with the same reference scale.
            assertEquals(
                "`bkround=\"14\"` on cls_settings_button",
                with(keyboard.resources.displayMetrics) {
                    density * ImeReferenceSizing.scale(keyboard.context, keyboard.width) * TileGridPolicy.TILE_RADIUS_DP
                },
                faceRadius(tile),
                1f,
            )
        }
    }

    @Test
    fun theChosenKeyboardKeepsTheCardAndAnswersInTheAccent() {
        withKeyboard { keyboard ->
            keyboard.showPanel(Panel.KEYBOARD_SELECT)
            val tokens = ImeTheme.IOS.tokens(ImeAppearance.LIGHT)
            val selected = keyboard.findViewWithTag<View>("keyboard-choice-selected")
            assertNotNull("The open keyboard's tile must be drawn selected", selected)
            // `cls_settings_option_mode_switch_button` declares no selected
            // fill: the chosen tile wears the same card as its neighbours
            // (`setting_button_bk`, `style.xml:649-668`), where the settings
            // page's `cls_settings_button` fills with the accent.
            val others = keyboard.findViewWithTag<View>("keyboard-choice")
            assertNotNull("Other keyboards keep the plain tile", others)
            assertEquals(
                "The chosen tile keeps the card",
                tokens.toolCardBackground,
                faceColor(selected!!),
            )
            assertEquals(
                "Every tile of the page shares that face",
                faceColor(others!!),
                faceColor(selected),
            )
            // The choice shows in the name: `selectedtextcolor` is
            // `theme_color`, read here as the accent the palette derives on
            // the card.
            val selectedLabel = (selected as ViewGroup).getChildAt(1) as TextView
            val plainLabel = (others as ViewGroup).getChildAt(1) as TextView
            assertEquals(
                "`selectedtextcolor=\"@color/theme_color\"` on cls_settings_option_mode_switch_button",
                ImeSurfacePolicy.accentTextOn(tokens.primary, tokens.toolCardBackground),
                selectedLabel.currentTextColor,
            )
            assertEquals(
                "The other names keep the tile's neutral label",
                tokens.keySecondaryText,
                plainLabel.currentTextColor,
            )
        }
    }

    @Test
    fun theGridCarriesEveryKeyboardAndTheHandwritingEntry() {
        withKeyboard { keyboard ->
            keyboard.showPanel(Panel.KEYBOARD_SELECT)
            val grid = keyboard.findViewWithTag<View>("tile-grid") as ViewGroup
            val labels = (0 until grid.childCount).flatMap { row ->
                val cells = grid.getChildAt(row) as ViewGroup
                (0 until cells.childCount).map { column ->
                    val tile = (cells.getChildAt(column) as ViewGroup).getChildAt(0)
                    (tile as ViewGroup).getChildAt(1)?.let { (it as TextView).text.toString() }
                }
            }
            assertTrue("The switcher keeps the pinyin keyboards", labels.contains("拼音 26 键"))
            // The handwriting tile follows the strokes table, which only local
            // builds ship (`StrokeMatchHandwritingProvider.assetPresent`).
            assertEquals(
                "The switcher carries the handwriting entry with its strokes table",
                StrokeMatchHandwritingProvider.assetPresent(keyboard.context),
                labels.contains("手写输入"),
            )
            keyboard.setMode(KeyboardMode.PINYIN_9, notifyListener = false)
            keyboard.showPanel(Panel.KEYBOARD_SELECT)
            assertNotNull(
                "Switching keyboards moves the selected tile",
                keyboard.findViewWithTag<View>("keyboard-choice-selected"),
            )
        }
    }
}
