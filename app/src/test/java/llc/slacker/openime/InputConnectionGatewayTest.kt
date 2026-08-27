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
import org.junit.Assert.assertTrue
import org.junit.Test

class InputConnectionGatewayTest {

    private class FakeInputConnection(
        var beforeText: String = "",
        var afterText: String = "",
        var selectedText: String = "",
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
            ExtractedText().apply {
                text = ""
                selectionStart = 0
                selectionEnd = 0
                startOffset = 0
            }
        override fun getHandler(): Handler? = null
        override fun getSelectedText(flags: Int): CharSequence? = selectedText
        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? = afterText
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? = beforeText
        override fun performContextMenuAction(id: Int): Boolean = false
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
    fun clearAllCancelsCompositionThenUsesOneBatchDelete() {
        val fake = FakeInputConnection(
            beforeText = "旧😀",
            selectedText = "选中",
            afterText = "文本",
        )
        val gateway = InputConnectionGateway(null, { fake })

        assertTrue(gateway.clearAllText())

        assertEquals("compose:", fake.events[0])
        assertEquals("finish", fake.events[1])
        assertEquals(1, fake.events.count { it.startsWith("delete") })
        assertTrue(
            fake.events.any {
                it == "deleteCodePoints:2:2" || it == "delete:3:2"
            },
        )
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
