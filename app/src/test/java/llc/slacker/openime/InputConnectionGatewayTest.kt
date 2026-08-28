package llc.slacker.openime

import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputConnectionGatewayTest {

    private class FakeInputConnection(
        var beforeText: String = "",
        var afterText: String = "",
        var selectedText: String = "",
        var extractedText: ExtractedText? = ExtractedText().apply {
            text = ""
            selectionStart = 0
            selectionEnd = 0
            startOffset = 0
            partialStartOffset = -1
            partialEndOffset = -1
        },
        var contextMenuResult: Boolean = false,
    ) : InputConnection {
        val events = mutableListOf<String>()

        override fun beginBatchEdit(): Boolean = false
        override fun clearMetaKeyStates(states: Int): Boolean = false
        override fun closeConnection() = Unit
        override fun commitCompletion(text: CompletionInfo?): Boolean = false
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            events += "commit:${text?.toString().orEmpty()}"
            return true
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            events += "delete:$beforeLength:$afterLength"
            return true
        }
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            events += "deleteCodePoints:$beforeLength:$afterLength"
            return true
        }
        override fun endBatchEdit(): Boolean = false
        override fun finishComposingText(): Boolean {
            events += "finish"
            return true
        }
        override fun getCursorCapsMode(reqType: Int): Int = 0
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
            extractedText
        override fun getHandler(): Handler? = null
        override fun getSelectedText(flags: Int): CharSequence? = selectedText
        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? = afterText.take(length)
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? = beforeText.takeLast(length)
        override fun performContextMenuAction(id: Int): Boolean {
            events += "context:$id"
            return contextMenuResult
        }
        override fun performEditorAction(editorAction: Int): Boolean {
            events += "action:$editorAction"
            return true
        }
        override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false
        override fun reportFullscreenMode(monochrome: Boolean): Boolean = false
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            events += "key:${event?.keyCode}"
            return true
        }
        override fun setComposingRegion(start: Int, end: Int): Boolean = false
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            events += "compose:${text?.toString().orEmpty()}"
            return true
        }
        override fun setSelection(start: Int, end: Int): Boolean {
            events += "selection:$start:$end"
            return true
        }
    }

    private fun extracted(
        text: String,
        startOffset: Int,
        selectionStart: Int,
        selectionEnd: Int,
        partialStartOffset: Int = -1,
        partialEndOffset: Int = -1,
    ) = ExtractedText().apply {
        this.text = text
        this.startOffset = startOffset
        this.selectionStart = selectionStart
        this.selectionEnd = selectionEnd
        this.partialStartOffset = partialStartOffset
        this.partialEndOffset = partialEndOffset
    }

    @Test
    fun compositionUsesSetComposingText() {
        val fake = FakeInputConnection()
        val gateway = InputConnectionGateway(null, { fake })
        gateway.setComposingText("nihao")
        assertEquals(listOf("compose:nihao"), fake.events)
    }

    @Test
    fun candidateCommitUsesCommitText() {
        val fake = FakeInputConnection()
        val gateway = InputConnectionGateway(null, { fake })
        gateway.commitText("你好")
        assertEquals(listOf("commit:你好"), fake.events)
    }

    @Test
    fun backspaceUsesCodePointDelete() {
        val fake = FakeInputConnection()
        val gateway = InputConnectionGateway(null, { fake })
        gateway.deleteBackwards()
        // Android unit-test environment exposes Build.VERSION.SDK_INT as a stub,
        // so this accepts the P+ codepoint path and the pre-P fallback path.
        assertTrue(
            fake.events.any {
                it == "deleteCodePoints:1:0" || it == "delete:1:0"
            },
        )
    }

    @Test
    fun editorActionIsForwarded() {
        val fake = FakeInputConnection()
        val gateway = InputConnectionGateway(null, { fake })
        gateway.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
        assertEquals(listOf("action:3"), fake.events)
    }

    @Test
    fun unicodeEmojiIsCommittedAsWholeString() {
        val fake = FakeInputConnection()
        val gateway = InputConnectionGateway(null, { fake })
        val emoji = "👨‍👩‍👧‍👦"
        gateway.commitText(emoji)
        assertEquals(listOf("commit:$emoji"), fake.events)
    }

    @Test
    fun extractedSelectionUsesAbsoluteStartOffsetWithoutLocalClamp() {
        val fake = FakeInputConnection(
            extractedText = extracted(
                text = "0123456789",
                startOffset = 1_000,
                selectionStart = 5,
                selectionEnd = 5,
            ),
        )
        val gateway = InputConnectionGateway(null, { fake })

        assertEquals(1_005, gateway.currentSelectionStart())
        assertEquals(1_005, gateway.currentSelectionEnd())
        assertEquals(-1, gateway.currentTextLength())

        gateway.selectStartEnd(1_004, 1_004)
        assertTrue(fake.events.contains("selection:1004:1004"))
    }

    @Test
    fun copySelectionMapsAbsoluteSelectionBackIntoExtractedWindow() {
        val fake = FakeInputConnection(
            selectedText = "",
            extractedText = extracted(
                text = "0123456789",
                startOffset = 100,
                selectionStart = 2,
                selectionEnd = 5,
            ),
        )
        val gateway = InputConnectionGateway(null, { fake })

        assertEquals("234", gateway.copySelection())
    }

    @Test
    fun boundedFallbackExposesRelativeCursorWithoutFabricatingSelection() {
        val fake = FakeInputConnection(
            beforeText = "x".repeat(20_000),
            afterText = "tail",
            extractedText = null,
        )
        val gateway = InputConnectionGateway(null, { fake })

        assertEquals(8_192, gateway.currentSelectionStart())
        assertEquals(8_192, gateway.currentSelectionEnd())
        gateway.selectStartEnd(8_192, 8_192)
        assertTrue(fake.events.none { it.startsWith("selection:") })
    }

    @Test
    fun relativeCursorDeltaMapsToDpadKeys() {
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, relativeCursorKeyCode(-1))
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, relativeCursorKeyCode(1))
        assertNull(relativeCursorKeyCode(0))
        assertNull(relativeCursorKeyCode(2))
    }

    @Test
    fun selectAllPrefersEditorContextAction() {
        val fake = FakeInputConnection(contextMenuResult = true)
        val gateway = InputConnectionGateway(null, { fake })

        gateway.selectAll()

        assertTrue(fake.events.contains("context:${android.R.id.selectAll}"))
        assertTrue(fake.events.none { it.startsWith("selection:") })
    }

    @Test
    fun selectAllDoesNotTreatPartialWindowAsWholeDocument() {
        val fake = FakeInputConnection(
            extractedText = extracted(
                text = "window",
                startOffset = 0,
                selectionStart = 0,
                selectionEnd = 0,
                partialStartOffset = 0,
                partialEndOffset = 6,
            ),
        )
        val gateway = InputConnectionGateway(null, { fake })

        gateway.selectAll()

        assertTrue(fake.events.contains("context:${android.R.id.selectAll}"))
        assertTrue(fake.events.none { it.startsWith("selection:") })
        assertEquals(-1, gateway.currentTextLength())
    }

    @Test
    fun clearAllUsesEditorSelectAllThenSingleReplacement() {
        val fake = FakeInputConnection(
            selectedText = "旧😀选中文本",
            contextMenuResult = true,
        )
        val gateway = InputConnectionGateway(null, { fake })

        assertTrue(gateway.clearAllText())

        assertEquals("compose:", fake.events[0])
        assertEquals("finish", fake.events[1])
        assertTrue(fake.events.contains("context:${android.R.id.selectAll}"))
        assertEquals(1, fake.events.count { it == "commit:" })
        assertTrue(fake.events.none { it.startsWith("delete") })
    }

    @Test
    fun clearAllHandlesOver200kCharactersThroughEditorOwnedSelection() {
        val fake = FakeInputConnection(
            selectedText = "x".repeat(200_001),
            contextMenuResult = true,
        )
        val gateway = InputConnectionGateway(null, { fake })

        assertTrue(gateway.clearAllText())
        assertEquals(1, fake.events.count { it == "commit:" })
        assertTrue(fake.events.none { it.startsWith("delete") })
    }

    @Test
    fun clearAllCanUseCompleteExtractedDocumentWithoutBoundedDelete() {
        val document = "x".repeat(200_001)
        val fake = FakeInputConnection(
            contextMenuResult = false,
            extractedText = extracted(
                text = document,
                startOffset = 0,
                selectionStart = 100_000,
                selectionEnd = 100_000,
            ),
        )
        val gateway = InputConnectionGateway(null, { fake })

        assertTrue(gateway.clearAllText())
        assertTrue(fake.events.contains("selection:0:200001"))
        assertEquals(1, fake.events.count { it == "commit:" })
        assertTrue(fake.events.none { it.startsWith("delete") })
    }

    @Test
    fun clearAllRefusesPartialWindowInsteadOfSilentlyDeletingNearbyText() {
        val fake = FakeInputConnection(
            beforeText = "a".repeat(100_000),
            afterText = "b".repeat(100_000),
            contextMenuResult = false,
            extractedText = extracted(
                text = "window",
                startOffset = 0,
                selectionStart = 3,
                selectionEnd = 3,
                partialStartOffset = 0,
                partialEndOffset = 6,
            ),
        )
        val gateway = InputConnectionGateway(null, { fake })

        assertFalse(gateway.clearAllText())
        assertTrue(fake.events.none { it.startsWith("delete") })
        assertTrue(fake.events.none { it == "commit:" })
    }

    @Test
    fun passwordBlocksCompositionAndClipboard() {
        val fake = FakeInputConnection()
        val gateway = InputConnectionGateway(null, { fake }, isPassword = { true })
        gateway.setComposingText("SECRET_IME_TEST_739251")
        assertEquals(emptyList<String>(), fake.events)
        assertEquals("", gateway.copySelection())
        assertEquals("", gateway.readClipboard())
    }
}
