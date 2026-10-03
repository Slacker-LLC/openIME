package llc.slacker.openime

import llc.slacker.openime.core.CrashGuard
import llc.slacker.openime.core.RimeStartupRecovery
import llc.slacker.openime.editor.COMMIT_CHUNK_CHARS
import llc.slacker.openime.editor.chunksForCommit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CrashResilienceTest {

    @Test
    fun threeCrashesInTenMinutesIsACrashLoop() {
        val now = 1_000_000_000L
        val minute = 60_000L
        assertFalse(CrashGuard.inCrashLoop(emptyList(), now))
        assertFalse(CrashGuard.inCrashLoop(listOf(now - minute, now - 2 * minute), now))
        assertTrue(CrashGuard.inCrashLoop(listOf(now - minute, now - 2 * minute, now - 3 * minute), now))
    }

    @Test
    fun oldCrashesAgeOutSoSafeModeEnds() {
        val now = 1_000_000_000L
        val old = now - CrashGuard.LOOP_WINDOW_MS - 1
        assertFalse(CrashGuard.inCrashLoop(listOf(old, old - 1, old - 2, now - 1), now))
    }

    @Test
    fun crashTimesRoundTripAndIgnoreGarbage() {
        assertEquals(listOf(1L, 22L, 333L), CrashGuard.parseTimes(CrashGuard.formatTimes(listOf(1L, 22L, 333L))))
        assertEquals(listOf(5L), CrashGuard.parseTimes("x,5,,y"))
        assertEquals(emptyList<Long>(), CrashGuard.parseTimes(null))
    }

    private fun tempDir(): File = Files.createTempDirectory("rime-recovery").toFile().also { it.deleteOnExit() }

    @Test
    fun aCleanStartupLeavesNoTraceAndNeverEscalates() {
        val recovery = RimeStartupRecovery(tempDir())
        repeat(5) {
            assertEquals(RimeStartupRecovery.Action.NORMAL, recovery.begin())
            recovery.succeeded()
        }
        assertEquals(0, recovery.failures())
        assertFalse(recovery.lastStartupDiedNatively())
    }

    @Test
    fun everyNativeDeathEscalatesUntilNativeIsSkipped() {
        val dir = tempDir()
        // Each begin() without succeeded() is a start that died inside native code.
        assertEquals(RimeStartupRecovery.Action.NORMAL, RimeStartupRecovery(dir).begin())
        assertEquals(RimeStartupRecovery.Action.CLEAN_BUILD, RimeStartupRecovery(dir).begin())
        assertEquals(RimeStartupRecovery.Action.RESET_USER_DATA, RimeStartupRecovery(dir).begin())
        assertEquals(RimeStartupRecovery.Action.SKIP_NATIVE, RimeStartupRecovery(dir).begin())
        // Skipping disarms the marker, so skipped sessions are not counted again.
        assertFalse(RimeStartupRecovery(dir).lastStartupDiedNatively())
    }

    @Test
    fun aSuccessfulStartAfterTroubleResetsTheEscalation() {
        val dir = tempDir()
        RimeStartupRecovery(dir).begin()
        assertEquals(RimeStartupRecovery.Action.CLEAN_BUILD, RimeStartupRecovery(dir).begin())
        RimeStartupRecovery(dir).succeeded()
        assertEquals(RimeStartupRecovery.Action.NORMAL, RimeStartupRecovery(dir).begin())
    }

    @Test
    fun aJavaExceptionDuringStartupIsNotCountedAsACrash() {
        val dir = tempDir()
        val recovery = RimeStartupRecovery(dir)
        recovery.begin()
        recovery.failedWithoutCrash()
        assertEquals(RimeStartupRecovery.Action.NORMAL, RimeStartupRecovery(dir).begin())
    }

    @Test
    fun smallTextIsCommittedInOnePiece() {
        assertEquals(listOf("你好"), chunksForCommit("你好"))
        val limit = "a".repeat(COMMIT_CHUNK_CHARS)
        assertEquals(listOf(limit), chunksForCommit(limit))
    }

    @Test
    fun hugeTextIsChunkedLosslesslyAndNeverInsideASurrogatePair() {
        val emoji = "😀" // one surrogate pair
        // Put a pair across the first boundary: the high surrogate would be the last unit of a full chunk.
        val text = "a".repeat(COMMIT_CHUNK_CHARS - 1) + emoji + "b".repeat(COMMIT_CHUNK_CHARS * 2)
        val chunks = chunksForCommit(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= COMMIT_CHUNK_CHARS })
        chunks.forEach { chunk ->
            assertFalse("chunk ends with a lone high surrogate", Character.isHighSurrogate(chunk.last()))
            assertFalse("chunk starts with a lone low surrogate", Character.isLowSurrogate(chunk.first()))
        }
        assertTrue(chunks.size >= 3)
    }
}
