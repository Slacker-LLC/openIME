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

    data class CursorSnapshot(
        val text: String,
        val cursor: Int,
    )

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

    /** Remove the active pre-edit text without committing it to the editor. */
    fun cancelComposing() {
        val ic = connection() ?: return
        if (isPassword()) {
            ic.finishComposingText()
            return
        }
        ic.setComposingText("", 1)
        ic.finishComposingText()
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

    /** Delete the active selection without falling back to one-character delete. */
    fun deleteSelection(): Boolean {
        if (isPassword()) return false
        val ic = connection() ?: return false
        val selected = runCatching { ic.getSelectedText(0)?.toString().orEmpty() }.getOrDefault("")
        if (selected.isEmpty()) return false
        return ic.commitText("", 1)
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
        ic.beginBatchEdit()
        return try {
            // Remove the active pre-edit instead of finishing (committing) it.
            // Query surrounding text only afterwards so delete counts cannot
            // include a stale composing span that an editor may resurrect.
            ic.setComposingText("", 1)
            ic.finishComposingText()
            val before = ic.getTextBeforeCursor(100_000, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(100_000, 0)?.toString().orEmpty()
            val selected = ic.getSelectedText(0)?.toString().orEmpty()
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

    fun cursorSnapshot(maxChars: Int = 8_192): CursorSnapshot? {
        if (isPassword()) return null
        val ic = connection() ?: return null
        val bounded = maxChars.coerceIn(64, 100_000)
        val before = runCatching { ic.getTextBeforeCursor(bounded, 0)?.toString().orEmpty() }
            .getOrDefault("")
        val after = runCatching { ic.getTextAfterCursor(bounded, 0)?.toString().orEmpty() }
            .getOrDefault("")
        return CursorSnapshot(before + after, before.length)
    }

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
        val safeContext = context ?: return ""
        val cm = safeContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        return runCatching {
            cm.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(safeContext)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")
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
