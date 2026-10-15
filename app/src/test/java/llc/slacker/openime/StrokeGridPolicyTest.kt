package llc.slacker.openime

import llc.slacker.openime.keyboard.StrokeGridPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeGridPolicyTest {
    /** The reference's grid reads 一 丨 丿 / 丶 折 通配 / @# ：； (`input_kbd_stroke.xml:16-66`). */
    @Test
    fun theGridCarriesTheReferencesNineCellsInItsOrder() {
        assertEquals(
            listOf(
                listOf("一", "丨", "丿"),
                listOf("丶", "乛", "通配"),
                listOf("@#", "：", "；"),
            ),
            StrokeGridPolicy.ROWS.map { row -> row.map { it.label } },
        )
    }

    /** Every cell carries the skin's own corner digit, 1-9 down the grid. */
    @Test
    fun everyCellCarriesItsCornerDigit() {
        assertEquals(
            listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
            ),
            StrokeGridPolicy.ROWS.map { row -> row.map { it.corner } },
        )
    }

    /** The five strokes and 通配 feed the engine; the other three commit themselves. */
    @Test
    fun theCellsSplitBetweenTheEngineAndDirectCommits() {
        val cells = StrokeGridPolicy.ROWS.flatten()
        val strokes = cells.filter { it.stroke != null }
        val commits = cells.filter { it.commit != null }
        assertEquals(6, strokes.size)
        assertEquals(
            listOf("一", "丨", "丿", "丶", "乛", "通配"),
            strokes.map { it.label },
        )
        // ：and ；type themselves, and @# types the digit its corner names.
        assertEquals(listOf("@#", "：", "；"), commits.map { it.label })
        assertEquals(listOf("7", "：", "；"), commits.map { it.commit })
        cells.forEach {
            assertTrue(
                "${it.label} must not both feed the engine and commit",
                (it.stroke == null) != (it.commit == null),
            )
        }
        assertNull("no cell of the grid is both", cells.first { it.label == "通配" }.commit)
    }

    /** The @# cell's bubble is its own, with the 7 the reference puts last. */
    @Test
    fun theAtSymbolCellOffersTheReferencesOwnList() {
        assertEquals(listOf("/", "-", "_", "@", "#", ".", "7"), StrokeGridPolicy.AT_SYMBOL_CHOICES)
        assertEquals(
            "key-stroke:at",
            StrokeGridPolicy.ROWS.flatten().first { it.label == "@#" }.tag,
        )
    }

    /** The tags the volume harness types stay the stroke codes plus the wildcard. */
    @Test
    fun theEngineCellsKeepTheStrokeCodeTags() {
        val tags = StrokeGridPolicy.ROWS.flatten().filter { it.stroke != null }.map { it.tag }
        assertEquals(
            listOf(
                "key-stroke:h",
                "key-stroke:s",
                "key-stroke:p",
                "key-stroke:n",
                "key-stroke:z",
                "key-stroke:wildcard",
            ),
            tags,
        )
    }
}
