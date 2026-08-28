package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefixRangeIndexTest {
    @Test
    fun returnsMatchesInOriginalSourceOrder() {
        val source = linkedMapOf(
            "nzz" to 1,
            "naa" to 2,
            "mxx" to 3,
            "nab" to 4,
        )
        val result = PrefixRangeIndex(source).lookup("na")
        assertEquals(listOf(2, 4), result.values)
    }

    @Test
    fun largeSyntheticDictionaryScansOnlyMatchingRange() {
        val source = linkedMapOf<String, Int>()
        repeat(10_000) { index ->
            val group = when {
                index < 4_900 -> "aa"
                index < 5_020 -> "ni"
                else -> "zz"
            }
            source["$group${index.toString().padStart(5, '0')}"] = index
        }

        val result = PrefixRangeIndex(source).lookup("ni")
        assertEquals(120, result.values.size)
        assertTrue(
            "prefix lookup should inspect the matching interval, not all 10k keys",
            result.inspectedKeys <= 121,
        )
        assertEquals((4_900 until 5_020).toList(), result.values)
    }

    @Test
    fun absentPrefixStopsAfterAtMostOneBoundaryProbe() {
        val source = (0 until 10_000).associate { index ->
            "k${index.toString().padStart(5, '0')}" to index
        }
        val result = PrefixRangeIndex(source).lookup("q")
        assertTrue(result.values.isEmpty())
        assertTrue(result.inspectedKeys <= 1)
    }
}
