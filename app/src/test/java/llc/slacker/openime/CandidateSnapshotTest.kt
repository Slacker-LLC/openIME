package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateSnapshotTest {

    @Test
    fun fallbackFirstCommitUsesExactlyRenderedText() {
        val snapshot = CandidateSnapshot.rendered(
            generation = 7,
            composition = "ni",
            mode = KeyboardMode.PINYIN_26,
            candidates = listOf("你", "呢"),
        )

        val entry = snapshot.firstForCommit(7, "ni", KeyboardMode.PINYIN_26)

        assertEquals("你", entry?.text)
        assertNull(entry?.nativeReference)
    }

    @Test
    fun nativeEntryCarriesRenderedReference() {
        val reference = NativeCandidateReference(input = "ni", nativeIndex = 3)
        val snapshot = CandidateSnapshot.rendered(
            generation = 8,
            composition = "ni",
            mode = KeyboardMode.PINYIN_26,
            candidates = listOf("拟", "你"),
            nativeReferences = mapOf("拟" to reference),
        )

        val entry = snapshot.firstForCommit(8, "ni", KeyboardMode.PINYIN_26)

        assertEquals("拟", entry?.text)
        assertEquals(reference, entry?.nativeReference)
    }

    @Test
    fun staleGenerationCannotCommit() {
        val snapshot = CandidateSnapshot.rendered(
            generation = 9,
            composition = "ni",
            mode = KeyboardMode.PINYIN_26,
            candidates = listOf("你"),
        )

        assertNull(snapshot.firstForCommit(10, "ni", KeyboardMode.PINYIN_26))
        assertNull(snapshot.candidateForCommit("你", 10, "ni", KeyboardMode.PINYIN_26))
    }

    @Test
    fun staleCompositionCannotCommitSameCandidateLabel() {
        val snapshot = CandidateSnapshot.rendered(
            generation = 11,
            composition = "ni",
            mode = KeyboardMode.PINYIN_26,
            candidates = listOf("你"),
        )

        assertNull(snapshot.candidateForCommit("你", 11, "nimen", KeyboardMode.PINYIN_26))
    }

    @Test
    fun candidateClickMustExistInRenderedSnapshot() {
        val snapshot = CandidateSnapshot.rendered(
            generation = 12,
            composition = "7464",
            mode = KeyboardMode.PINYIN_9,
            candidates = listOf("是", "时"),
        )

        assertEquals(
            "时",
            snapshot.candidateForCommit("时", 12, "7464", KeyboardMode.PINYIN_9)?.text,
        )
        assertNull(snapshot.candidateForCommit("市", 12, "7464", KeyboardMode.PINYIN_9))
    }
}
