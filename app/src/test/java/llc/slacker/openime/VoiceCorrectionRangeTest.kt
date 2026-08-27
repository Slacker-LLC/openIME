package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class VoiceCorrectionRangeTest {

    @Test
    fun longDocumentAnchorUsesAbsoluteEditorCoordinates() {
        val original = "开放爱慕"
        val localPrefix = "x".repeat(8_190)
        val windowStart = 25_000
        val text = localPrefix + original
        val snapshot = InputConnectionGateway.AbsoluteCursorSnapshot(
            text = text,
            windowStart = windowStart,
            cursorAbsolute = windowStart + text.length,
        )

        val range = assertNotNull(voiceCorrectionRange(original, snapshot)) as VoiceCorrectionRange

        assertEquals(33_190, range.startAbsolute)
        assertEquals(33_194, range.endAbsolute)
    }

    @Test
    fun shiftedSurroundingWindowExtractsOnlyTheActualReplacement() {
        val original = "彭拜系统"
        val originalWindowStart = 24_000
        val originalLocalStart = 8_100
        val originalText = "a".repeat(originalLocalStart) + original
        val initial = InputConnectionGateway.AbsoluteCursorSnapshot(
            text = originalText,
            windowStart = originalWindowStart,
            cursorAbsolute = originalWindowStart + originalText.length,
        )
        val range = assertNotNull(voiceCorrectionRange(original, initial)) as VoiceCorrectionRange

        val corrected = "澎湃系统"
        val shiftedWindowStart = originalWindowStart + 37
        val shiftedLocalStart = range.startAbsolute - shiftedWindowStart
        val shiftedText = "b".repeat(shiftedLocalStart) + corrected + "tail"
        val shifted = InputConnectionGateway.AbsoluteCursorSnapshot(
            text = shiftedText,
            windowStart = shiftedWindowStart,
            cursorAbsolute = range.startAbsolute + corrected.length,
        )

        assertEquals(corrected, correctedVoiceText(range, shifted))
    }

    @Test
    fun learningIsSkippedWhenShiftedWindowNoLongerContainsAnchor() {
        val range = VoiceCorrectionRange(
            original = "语音原文",
            startAbsolute = 31_000,
            endAbsolute = 31_004,
        )
        val shifted = InputConnectionGateway.AbsoluteCursorSnapshot(
            text = "修正文本",
            windowStart = 31_001,
            cursorAbsolute = 31_004,
        )

        assertNull(correctedVoiceText(range, shifted))
    }
}
