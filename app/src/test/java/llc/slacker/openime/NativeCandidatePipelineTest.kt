package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCandidatePipelineTest {

    @Test
    fun ambiguousNineKeyPathsAreInterleavedByNativeRank() {
        val merged = NativeCandidatePipeline.mergeRoundRobin(
            listOf(
                "nihao" to listOf(
                    RimeCandidateEntry("你好", 0),
                    RimeCandidateEntry("你号", 1),
                ),
                "mijam" to listOf(
                    RimeCandidateEntry("迷", 0),
                    RimeCandidateEntry("米", 1),
                ),
            ),
        )

        assertEquals(listOf("你好", "迷", "你号", "米"), merged.map { it.text })
        assertEquals("mijam", merged[1].reference.input)
        assertEquals(1, merged[3].reference.nativeIndex)
    }

    @Test
    fun duplicateLabelsKeepTheHighestScoredPathReference() {
        val merged = NativeCandidatePipeline.mergeRoundRobin(
            listOf(
                "path-one" to listOf(RimeCandidateEntry("同词", 3)),
                "path-two" to listOf(RimeCandidateEntry("同词", 0)),
            ),
        )

        assertEquals(1, merged.size)
        assertEquals("path-one", merged.single().reference.input)
        assertEquals(3, merged.single().reference.nativeIndex)
    }
}
