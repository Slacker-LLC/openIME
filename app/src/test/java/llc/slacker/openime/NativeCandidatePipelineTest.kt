package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeCandidatePipelineTest {

    @Test
    fun nativeBatchesRemainInterleavedForGenericMultiInputCallers() {
        val merged = NativeCandidatePipeline.mergeRoundRobin(
            listOf(
                "path-one" to listOf(
                    RimeCandidateEntry("你好", 0),
                    RimeCandidateEntry("你号", 1),
                ),
                "path-two" to listOf(
                    RimeCandidateEntry("迷", 0),
                    RimeCandidateEntry("米", 1),
                ),
            ),
        )

        assertEquals(listOf("你好", "迷", "你号", "米"), merged.map { it.text })
        assertEquals("path-two", merged[1].reference.input)
        assertEquals(1, merged[3].reference.nativeIndex)
    }

    @Test
    fun duplicateLabelsKeepFirstBatchReference() {
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

    @Test
    fun deferredReferenceRoundTripsExactInputAndVisibleCandidate() {
        val reference = NativeCandidateReference.deferred("64'426", "你好")

        assertEquals(NativeCandidateReference.DEFERRED_INDEX, reference.nativeIndex)
        assertEquals("64'426" to "你好", NativeCandidateReference.decodeDeferred(reference.input))
        assertNull(NativeCandidateReference.decodeDeferred("64'426"))
    }

    @Test
    fun nativeNineKeyRefreshKeepsMissingImmediateChoicesLearnable() {
        NineKeyFallbackRegistry.remember("64426", listOf("你好", "你号", "拟好"))

        val merged = NativeCandidatePipeline.mergeRoundRobin(
            listOf(
                "64426" to listOf(
                    RimeCandidateEntry("你好", 0),
                    RimeCandidateEntry("泥好", 1),
                ),
            ),
        )

        assertEquals(listOf("你好", "泥好", "你号", "拟好"), merged.map { it.text })
        assertEquals(0, merged[0].reference.nativeIndex)
        assertEquals(
            "64426" to "你号",
            NativeCandidateReference.decodeDeferred(merged[2].reference.input),
        )
        assertEquals(NativeCandidateReference.DEFERRED_INDEX, merged[2].reference.nativeIndex)
    }
}
