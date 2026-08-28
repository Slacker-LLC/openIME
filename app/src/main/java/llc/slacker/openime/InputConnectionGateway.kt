package llc.slacker.openime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo

internal fun relativeCursorKeyCode(delta: Int): Int? = when (delta) {
    -1 -> KeyEvent.KEYCODE_DPAD_LEFT
    1 -> KeyEvent.KEYCODE_DPAD_RIGHT
    else -> null
}

internal fun collapseSelectionForAdjacentArrow(
    currentStart: Int,
    currentEnd: Int,
    requestedStart: Int,
    requestedEnd: Int,
): Pair<Int, Int> {
    if (requestedStart != requestedEnd || currentStart == currentEnd) {
        return requestedStart to requestedEnd
    }
    val left = minOf(currentStart, currentEnd)
    val right = maxOf(currentStart, currentEnd)
    return when (requestedStart) {
        left - 1 -> left to left
        right + 1 -> right to right
        else -> requestedStart to requestedEnd
    }
}

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

    data class AbsoluteCursorSnapshot(
        val text: String,
        val windowStart: Int,
        val cursorAbsolute: Int,
    ) {
        fun textInAbsoluteRange(startAbsolute: Int, endAbsolute: Int): String? {
            if (endAbsolute < startAbsolute) return null
            val localStart = startAbsolute - windowStart
            val localEnd = endAbsolute - windowStart
            if (localStart < 0 || localEnd < localStart || localEnd > text.length) return null
            return text.substring(localStart, localEnd)
        }
    }

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
            // API 26/27 only exposes UTF-16-unit deletion. Inspect the two
            // units before the cursor so one backspace never leaves half of a
            // supplementary code point (emoji / extension Han) behind.
            val before = runCatching { ic.getTextBeforeCursor(2, 0) }.getOrNull()
            val utf16Units = previousCodePointUtf16Length(before).coerceAtLeast(1)
            ic.deleteSurroundingText(utf16Units, 0)
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

    /**
     * Clear the complete editor document in one batch operation.
     *
     * The editor-owned select-all action is authoritative and handles documents
     * far larger than any surrounding-text query. If that capability is absent,
     * manual selection is used only when ExtractedText explicitly represents the
     * complete document. A bounded/local window is never partially deleted while
     * returning success: callers receive false instead and can present an
     * unsupported-capability state.
     */
    fun clearAllText(): Boolean {
        if (isPassword()) return false
        val ic = connection() ?: return false
        ic.beginBatchEdit()
        return try {
            // Remove active pre-edit rather than committing it before selecting.
            ic.setComposingText("", 1)
            ic.finishComposingText()

            if (runCatching { ic.performContextMenuAction(android.R.id.selectAll) }.getOrDefault(false)) {
                val selected = runCatching { ic.getSelectedText(0)?.toString().orEmpty() }
                    .getOrDefault("")
                if (selected.isNotEmpty()) {
                    val cleared = runCatching { ic.commitText("", 1) }.getOrDefault(false)
                    ic.finishComposingText()
                    return cleared
                }

                // Empty selection can mean either an empty document or an editor
                // that claimed select-all without exposing/creating a selection.
                // Only the complete extracted state can distinguish those safely.
                val selectedWindow = extractedWindow(ic)
                if (selectedWindow?.isCompleteDocument == true) {
                    if (selectedWindow.text.isEmpty()) {
                        ic.finishComposingText()
                        return true
                    }
                    val fullSelection = selectedWindow.selectionStartAbsolute == 0 &&
                        selectedWindow.selectionEndAbsolute == selectedWindow.text.length
                    if (fullSelection) {
                        val cleared = runCatching { ic.commitText("", 1) }.getOrDefault(false)
                        ic.finishComposingText()
                        return cleared
                    }
                }
                return false
            }

            val window = extractedWindow(ic) ?: return false
            if (!window.isCompleteDocument) return false
            if (window.text.isEmpty()) {
                ic.finishComposingText()
                return true
            }
            if (!runCatching { ic.setSelection(0, window.text.length) }.getOrDefault(false)) {
                return false
            }
            val cleared = runCatching { ic.commitText("", 1) }.getOrDefault(false)
            ic.finishComposingText()
            cleared
        } finally {
            ic.endBatchEdit()
        }
    }

    fun performEditorAction(action: Int) {
        connection()?.performEditorAction(action)
    }

    fun sendKeyDownUp(keyCode: Int) {
        val ic = connection() ?: return
        sendKeyDownUp(ic, keyCode)
    }

    /**
     * Prefer the editor's own select-all implementation. A bounded extracted
     * window must never be mistaken for the complete document.
     */
    fun selectAll() {
        if (isPassword()) return
        val ic = connection() ?: return
        if (runCatching { ic.performContextMenuAction(android.R.id.selectAll) }.getOrDefault(false)) return
        val window = extractedWindow(ic) ?: return
        if (window.isCompleteDocument) {
            ic.setSelection(0, window.text.length)
        }
    }

    /**
     * Set an editor selection when absolute coordinates are available. If the
     * editor exposes only a bounded before/after window, a one-character move
     * is delegated back to the editor through DPAD instead of fabricating an
     * absolute document coordinate from that local window.
     *
     * The text-editor panel currently expresses left/right movement as one
     * position beyond the current edge. With a non-empty selection Android's
     * normal arrow semantics collapse to that edge first, so intercept exactly
     * those adjacent requests instead of moving one extra character.
     */
    fun selectStartEnd(start: Int, end: Int) {
        if (isPassword()) return
        val ic = connection() ?: return
        when (val selection = selectionSnapshot(ic)) {
            is SelectionSnapshot.Absolute -> {
                val (targetStart, targetEnd) = collapseSelectionForAdjacentArrow(
                    currentStart = selection.start,
                    currentEnd = selection.end,
                    requestedStart = start,
                    requestedEnd = end,
                )
                val safeStart = targetStart.coerceAtLeast(0)
                val safeEnd = targetEnd.coerceAtLeast(safeStart)
                ic.setSelection(safeStart, safeEnd)
            }
            is SelectionSnapshot.Relative -> {
                if (start != end) return
                relativeCursorKeyCode(start - selection.cursor)?.let { keyCode ->
                    sendKeyDownUp(ic, keyCode)
                }
            }
            null -> Unit
        }
    }

    fun currentSelectionStart(): Int = when (val selection = selectionSnapshot()) {
        is SelectionSnapshot.Absolute -> selection.start
        is SelectionSnapshot.Relative -> selection.cursor
        null -> 0
    }

    fun currentSelectionEnd(): Int = when (val selection = selectionSnapshot()) {
        is SelectionSnapshot.Absolute -> selection.end
        is SelectionSnapshot.Relative -> selection.cursor
        null -> currentSelectionStart()
    }

    /** Returns -1 unless ExtractedText explicitly represents the complete document. */
    fun currentTextLength(): Int {
        if (isPassword()) return -1
        val ic = connection() ?: return -1
        val window = extractedWindow(ic) ?: return -1
        return if (window.isCompleteDocument) window.text.length else -1
    }

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

    /**
     * Snapshot a collapsed cursor using ExtractedText.startOffset so every
     * coordinate is tied to the document rather than to a sliding local window.
     */
    fun absoluteCursorSnapshot(): AbsoluteCursorSnapshot? {
        if (isPassword()) return null
        val ic = connection() ?: return null
        val window = extractedWindow(ic) ?: return null
        if (window.selectionStartAbsolute != window.selectionEndAbsolute) return null
        val localCursor = window.selectionEndAbsolute - window.windowStart
        if (localCursor !in 0..window.text.length) return null
        return AbsoluteCursorSnapshot(
            text = window.text,
            windowStart = window.windowStart,
            cursorAbsolute = window.selectionEndAbsolute,
        )
    }

    fun copySelection(): String {
        if (isPassword()) return ""
        val ic = connection() ?: return ""
        val selected = runCatching { ic.getSelectedText(0)?.toString() }.getOrNull()
        if (!selected.isNullOrEmpty()) return selected

        val window = extractedWindow(ic) ?: return ""
        val localStart = window.selectionStartAbsolute - window.windowStart
        val localEnd = window.selectionEndAbsolute - window.windowStart
        if (localStart < 0 || localEnd <= localStart || localEnd > window.text.length) return ""
        return window.text.substring(localStart, localEnd)
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

    private fun selectionSnapshot(): SelectionSnapshot? {
        if (isPassword()) return null
        val ic = connection() ?: return null
        return selectionSnapshot(ic)
    }

    private fun selectionSnapshot(ic: InputConnection): SelectionSnapshot? {
        if (isPassword()) return null
        val window = extractedWindow(ic)
        if (window != null) {
            return SelectionSnapshot.Absolute(
                start = window.selectionStartAbsolute,
                end = window.selectionEndAbsolute,
            )
        }

        val before = runCatching { ic.getTextBeforeCursor(FALLBACK_WINDOW_CHARS, 0)?.toString() }
            .getOrNull() ?: return null
        return SelectionSnapshot.Relative(cursor = before.length)
    }

    private fun extractedWindow(ic: InputConnection): ExtractedWindow? {
        if (isPassword()) return null
        val request = ExtractedTextRequest().apply {
            token = 1
            flags = 0
            hintMaxLines = 1
            hintMaxChars = 0
        }
        val extracted: ExtractedText = runCatching { ic.getExtractedText(request, 0) }.getOrNull()
            ?: return null
        val rawText = extracted.text ?: return null
        if (extracted.selectionStart < 0 || extracted.selectionEnd < 0) return null

        val windowStart = extracted.startOffset.coerceAtLeast(0)
        return ExtractedWindow(
            text = rawText.toString(),
            windowStart = windowStart,
            selectionStartAbsolute = windowStart + extracted.selectionStart,
            selectionEndAbsolute = windowStart + extracted.selectionEnd,
            isCompleteDocument = windowStart == 0 &&
                extracted.partialStartOffset < 0 &&
                extracted.partialEndOffset < 0,
        )
    }

    private fun sendKeyDownUp(ic: InputConnection, keyCode: Int) {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private sealed class SelectionSnapshot {
        data class Absolute(val start: Int, val end: Int) : SelectionSnapshot()
        data class Relative(val cursor: Int) : SelectionSnapshot()
    }

    private data class ExtractedWindow(
        val text: String,
        val windowStart: Int,
        val selectionStartAbsolute: Int,
        val selectionEndAbsolute: Int,
        val isCompleteDocument: Boolean,
    )

    private companion object {
        const val FALLBACK_WINDOW_CHARS = 8_192
    }
}
