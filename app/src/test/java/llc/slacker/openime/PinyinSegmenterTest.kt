package llc.slacker.openime

import llc.slacker.openime.engine.PinyinSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lattice keeps its best paths, not its first ones.
 *
 * The syllable catalogue holds the single letters as syllables (they are the
 * letter entries of the compiled table), so a reading like `luanan` also has
 * the splits `lu+a+nan` and `l+u+a+nan`. With a cap that simply dropped later
 * paths, the real reading `luan+an` never reached the word lookup and the
 * keyboard showed no candidates at all for a reading the lexicon has.
 */
class PinyinSegmenterTest {

    /** The letters double as syllables, exactly like the compiled catalogue. */
    private val syllables = listOf(
        "a", "an", "ao", "e", "ei", "g", "ge", "l", "le", "lei", "li", "lu", "luan", "m", "mi",
        "miao", "n", "nan", "u", "zai", "bao",
    )

    private fun paths(reading: String, maxPaths: Int): List<String> {
        val segmenter = PinyinSegmenter(syllables)
        return segmenter.segment(reading, maxPaths = maxPaths).map { path ->
            path.ids.joinToString("+") { syllables[it] }
        }
    }

    @Test
    fun theFewestSyllableReadingSurvivesASmallPathCap() {
        val found = paths("luanan", maxPaths = 4)
        assertEquals("luan+an", found.first())
        assertTrue("luan+an missing from $found", "luan+an" in found)
    }

    @Test
    fun pathsOfOneNodeComeBackFewestSyllablesFirst() {
        val found = paths("luanan", maxPaths = 8)
        val counts = found.map { it.count { char -> char == '+' } + 1 }
        assertEquals("not sorted by syllable count: $found", counts.sorted(), counts)
    }

    @Test
    fun aReadingSplitIntoMoreSyllablesDoesNotCrowdOutTheWord() {
        // 4 syllables against the 5+ syllable splits the letter entries allow.
        assertTrue("ge+lei+mi+ao" in paths("geleimiao", maxPaths = 16))
    }

    @Test
    fun everyPathConsumesTheWholeInput() {
        for (reading in listOf("luanan", "geleimiao", "baozai")) {
            for (path in paths(reading, maxPaths = 16)) {
                val consumed = path.split("+").sumOf { it.length }
                assertEquals("$path does not cover $reading", reading.length, consumed)
            }
        }
    }

    @Test
    fun aSplitNoWordContinuesDoesNotTakeASlot() {
        val segmenter = PinyinSegmenter(syllables)
        // The caller's lookup only asks for readings a word starts with, so the
        // paths it can use are the ones the lattice should keep: here only
        // luan+an leads anywhere, and the four-slot cap must not lose it.
        val alive = { ids: IntArray ->
            ids.size == 1 || (syllables[ids[0]] == "luan" && syllables[ids[1]] == "an")
        }
        val kept = segmenter.segment("luanan", maxPaths = 4, alive = alive)
            .map { path -> path.ids.joinToString("+") { syllables[it] } }
        assertEquals(listOf("luan+an"), kept)
    }
}
