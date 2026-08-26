package llc.slacker.openime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo

/**
 * Single funnel for all editor side effects. Password fields are never logged,
 * uploaded or added to history; direct typing is still forwarded to the editor.
 */
class InputConnectionGateway(
    private val context: Context?,
    private val connection: () -> InputConnection?,
    private val isPassword: () -> Boolean = { false },
) {

    fun commitText(text: String) {
        if (text.isEmpty()) return
        connection()?.commitText(text, 1)
    }

    fun setComposingText(text: String) {
        if (isPassword()) return
        val ic = connection() ?: return
        if (text.isEmpty()) {
            ic.finishComposingText()
        } else {
            ic.setComposingText(text, 1)
        }
    }

    fun finishComposing() {
        connection()?.finishComposingText()
    }

    fun clearComposition() {
        finishComposing()
    }

    fun deleteBackwards() {
        val ic = connection() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ic.deleteSurroundingTextInCodePoints(1, 0)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    fun deleteForwards() {
        val ic = connection() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ic.deleteSurroundingTextInCodePoints(0, 1)
        } else {
            ic.deleteSurroundingText(0, 1)
        }
    }

    /** Clear the current editor text around the cursor, including a selection. */
    fun clearAllText(): Boolean {
        if (isPassword()) return false
        val ic = connection() ?: return false
        val before = ic.getTextBeforeCursor(100_000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(100_000, 0)?.toString().orEmpty()
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        ic.beginBatchEdit()
        return try {
            if (selected.isNotEmpty()) ic.commitText("", 1)
            val beforeCount = before.codePointCount(0, before.length)
            val afterCount = after.codePointCount(0, after.length)
            if (beforeCount > 0 || afterCount > 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ic.deleteSurroundingTextInCodePoints(beforeCount, afterCount)
                } else {
                    ic.deleteSurroundingText(before.length, after.length)
                }
            }
            ic.finishComposingText()
            true
        } finally {
            ic.endBatchEdit()
        }
    }

    fun performEditorAction(action: Int) {
        connection()?.performEditorAction(action)
    }

    fun sendKeyDownUp(keyCode: Int) {
        val ic = connection() ?: return
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }

    fun selectAll() {
        val info = surroundingText() ?: return
        connection()?.setSelection(0, info.text.length)
    }

    fun selectStartEnd(start: Int, end: Int) {
        val info = surroundingText() ?: return
        val safeStart = start.coerceIn(0, info.text.length)
        val safeEnd = end.coerceIn(safeStart, info.text.length)
        connection()?.setSelection(safeStart, safeEnd)
    }

    fun currentSelectionStart(): Int = surroundingText()?.selectionStart ?: 0

    fun currentSelectionEnd(): Int = surroundingText()?.selectionEnd ?: currentSelectionStart()

    fun currentTextLength(): Int = surroundingText()?.text?.length ?: 0

    fun copySelection(): String {
        if (isPassword()) return ""
        val ic = connection() ?: return ""
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) return selected
        val info = surroundingText() ?: return ""
        val start = info.selectionStart
        val end = info.selectionEnd
        if (start < 0 || end <= start || end > info.text.length) return ""
        return info.text.substring(start, end)
    }

    fun copyToClipboard(text: String) {
        if (text.isEmpty() || isPassword()) return
        val cm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("ime", text))
    }

    fun readClipboard(): String {
        if (isPassword()) return ""
        val cm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        return cm.primaryClip?.getItemAt(0)?.coerceToText(context!!)?.toString().orEmpty()
    }

    fun pasteClipboard(): String {
        val text = readClipboard()
        if (text.isNotEmpty()) commitText(text)
        return text
    }

    fun commitContent(info: InputContentInfo): Boolean =
        connection()?.commitContent(info, 0, null) == true

    private fun surroundingText(): SurroundingText? {
        if (isPassword()) return null
        val ic = connection() ?: return null
        val request = ExtractedTextRequest().apply {
            token = 1
            flags = 0
            hintMaxLines = 1
            hintMaxChars = 0
        }
        val extracted: ExtractedText? = runCatching { ic.getExtractedText(request, 0) }.getOrNull()
        if (extracted?.text != null) {
            val text = extracted.text.toString()
            val offset = extracted.startOffset.coerceAtLeast(0)
            val start = (extracted.selectionStart + offset).coerceAtLeast(0)
            val end = (extracted.selectionEnd + offset).coerceAtLeast(start)
            return SurroundingText(text, start, end)
        }
        val before = ic.getTextBeforeCursor(8192, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(8192, 0)?.toString() ?: ""
        val start = before.length
        return SurroundingText(before + after, start, start)
    }

    private data class SurroundingText(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
    )
}
