package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCorrectionTrackerTest {

    @Test
    fun deleteThenReplacementRecordsCorrectionPair() {
        var snapshot = snap("开放爱慕")
        val records = mutableListOf<Pair<String, String>>()
        val tracker = VoiceCorrectionTracker(
            snapshot = { snapshot },
            learningAllowed = { true },
            record = { original, corrected ->
                records += original to corrected
            },
        )

        tracker.begin("开放爱慕")
        snapshot = snap("开放爱")
        tracker.noteBackspace()
        snapshot = snap("开放爱幕")
        tracker.noteReplacementInput()
        tracker.finalizeIfNeeded()

        assertEquals(listOf("开放爱慕" to "开放爱幕"), records)
    }

    @Test
    fun ordinaryContinuationWithoutDeletionIsNotCorrection() {
        var snapshot = snap("语音原文")
        val records = mutableListOf<Pair<String, String>>()
        val tracker = VoiceCorrectionTracker(
            snapshot = { snapshot },
            learningAllowed = { true },
            record = { original, corrected ->
                records += original to corrected
            },
        )

        tracker.begin("语音原文")
        snapshot = snap("语音原文继续")
        tracker.noteReplacementInput()
        tracker.finalizeIfNeeded()

        assertTrue(records.isEmpty())
    }

    @Test
    fun learningDisabledNeverCreatesPendingCorrection() {
        var snapshot = snap("隐私文本")
        val records = mutableListOf<Pair<String, String>>()
        val tracker = VoiceCorrectionTracker(
            snapshot = { snapshot },
            learningAllowed = { false },
            record = { original, corrected ->
                records += original to corrected
            },
        )

        tracker.begin("隐私文本")
        snapshot = snap("隐私文")
        tracker.noteBackspace()
        snapshot = snap("隐私问")
        tracker.finalizeIfNeeded()

        assertTrue(records.isEmpty())
    }

    @Test
    fun missingCursorSnapshotClearsPendingCorrection() {
        var snapshot: InputConnectionGateway.AbsoluteCursorSnapshot? =
            snap("测试语音")
        val records = mutableListOf<Pair<String, String>>()
        val tracker = VoiceCorrectionTracker(
            snapshot = { snapshot },
            learningAllowed = { true },
            record = { original, corrected ->
                records += original to corrected
            },
        )

        tracker.begin("测试语音")
        snapshot = null
        tracker.noteBackspace()
        snapshot = snap("测试雨音")
        tracker.finalizeIfNeeded()

        assertTrue(records.isEmpty())
    }

    private fun snap(text: String) =
        InputConnectionGateway.AbsoluteCursorSnapshot(
            text = text,
            windowStart = 0,
            cursorAbsolute = text.length,
        )
}
