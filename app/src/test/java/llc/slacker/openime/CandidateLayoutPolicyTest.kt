package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateLayoutPolicyTest {
    @Test
    fun phrasesReceiveMoreRoomWithoutChangingOrder() {
        val words = listOf("你", "好", "今天天气真好", "这是一句超过八个字的长句子", "再见")
        val rows = expandedCandidateRows(words)
        assertEquals(words, rows.flatten())
        assertTrue(rows.all { it.sumOf(::candidateColumnSpan) <= 4 })
        assertEquals(4, candidateColumnSpan(words[3]))
        assertEquals(listOf(words[3]), rows[1])
    }

    @Test
    fun supplementaryCharactersCountAsOneCharacter() {
        assertEquals(1, candidateColumnSpan("😀😀😀😀"))
        assertTrue(expandedCandidateRows(emptyList()).isEmpty())
    }
}
