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

    @Volatile
    private var knownSelectionStart: Int = -1
    @Volatile
    private var knownSelectionEnd: Int = -1

    fun updateSelection(start: Int, end: Int) {
        knownSelectionStart = start
        knownSelectionEnd = end
    }

    fun deleteBackwards() {
        val ic = connection() ?: return
        if (deleteSelection()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val deleted = runCatching { ic.deleteSurroundingTextInCodePoints(1, 0) }.getOrDefault(false)
            if (!deleted) {
                val before = runCatching { ic.getTextBeforeCursor(2, 0) }.getOrNull()
                val utf16Units = previousCodePointUtf16Length(before).coerceAtLeast(1)
                val fallbackDeleted = runCatching { ic.deleteSurroundingText(utf16Units, 0) }.getOrDefault(false)
                if (!fallbackDeleted) {
                    sendKeyDownUp(ic, KeyEvent.KEYCODE_DEL)
                }
            }
        } else {
            val before = runCatching { ic.getTextBeforeCursor(2, 0) }.getOrNull()
            val utf16Units = previousCodePointUtf16Length(before).coerceAtLeast(1)
            val deleted = runCatching { ic.deleteSurroundingText(utf16Units, 0) }.getOrDefault(false)
            if (!deleted) {
                sendKeyDownUp(ic, KeyEvent.KEYCODE_DEL)
            }
        }
    }

    /** Delete the active selection without falling back to one-character delete. */
    fun deleteSelection(): Boolean {
        val ic = connection() ?: return false
        if (isPassword()) {
            if (knownSelectionStart >= 0 && knownSelectionEnd >= 0 && knownSelectionStart != knownSelectionEnd) {
                val collapsed = minOf(knownSelectionStart, knownSelectionEnd)
                knownSelectionStart = collapsed
                knownSelectionEnd = collapsed
                val committed = runCatching { ic.commitText("", 1) }.getOrDefault(false)
                if (!committed) {
                    sendKeyDownUp(ic, KeyEvent.KEYCODE_DEL)
                }
                return true
            }
            return false
        }
        val selected = runCatching { ic.getSelectedText(0)?.toString().orEmpty() }.getOrDefault("")
        if (selected.isNotEmpty()) {
            if (knownSelectionStart >= 0 && knownSelectionEnd >= 0) {
                val collapsed = minOf(knownSelectionStart, knownSelectionEnd)
                knownSelectionStart = collapsed
                knownSelectionEnd = collapsed
            } else {
                knownSelectionStart = -1
                knownSelectionEnd = -1
            }
            val committed = runCatching { ic.commitText("", 1) }.getOrDefault(false)
            if (!committed) {
                sendKeyDownUp(ic, KeyEvent.KEYCODE_DEL)
            }
            return true
        }
        val window = extractedWindow(ic)
        if (window != null && window.selectionStartAbsolute != window.selectionEndAbsolute) {
            val collapsed = minOf(window.selectionStartAbsolute, window.selectionEndAbsolute)
            knownSelectionStart = collapsed
            knownSelectionEnd = collapsed
            val committed = runCatching { ic.commitText("", 1) }.getOrDefault(false)
            if (!committed) {
                sendKeyDownUp(ic, KeyEvent.KEYCODE_DEL)
            }
            return true
        }
        if (knownSelectionStart >= 0 && knownSelectionEnd >= 0 && knownSelectionStart != knownSelectionEnd) {
            val collapsed = minOf(knownSelectionStart, knownSelectionEnd)
            knownSelectionStart = collapsed
            knownSelectionEnd = collapsed
            val committed = runCatching { ic.commitText("", 1) }.getOrDefault(false)
            if (!committed) {
                sendKeyDownUp(ic, KeyEvent.KEYCODE_DEL)
            }
            return true
        }
        return false
    }

    fun deleteForwards() {
        val ic = connection() ?: return
        if (deleteSelection()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val deleted = runCatching { ic.deleteSurroundingTextInCodePoints(0, 1) }.getOrDefault(false)
            if (!deleted) {
                val after = runCatching { ic.getTextAfterCursor(2, 0) }.getOrNull()
                val utf16Units = nextCodePointUtf16Length(after).coerceAtLeast(1)
                val fallbackDeleted = runCatching { ic.deleteSurroundingText(0, utf16Units) }.getOrDefault(false)
                if (!fallbackDeleted) {
                    sendKeyDownUp(ic, KeyEvent.KEYCODE_FORWARD_DEL)
                }
            }
        } else {
            val after = runCatching { ic.getTextAfterCursor(2, 0) }.getOrNull()
            val utf16Units = nextCodePointUtf16Length(after).coerceAtLeast(1)
            val deleted = runCatching { ic.deleteSurroundingText(0, utf16Units) }.getOrDefault(false)
            if (!deleted) {
                sendKeyDownUp(ic, KeyEvent.KEYCODE_FORWARD_DEL)
            }
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
     *
     * Nothing in this method mutates composing text before a full-document
     * selection has been established. That matters because setComposingText("")
     * can replace an ordinary user selection in editors that expose no active
     * composing span. A failed clear therefore leaves document content intact.
     */
    fun clearAllText(): Boolean {
        if (isPassword()) return false
        val ic = connection() ?: return false
        ic.beginBatchEdit()
        return try {
            val originalSelection = selectionBeforeDestructiveSelectAll(ic)

            if (runCatching { ic.performContextMenuAction(android.R.id.selectAll) }.getOrDefault(false)) {
                val selected = runCatching { ic.getSelectedText(0)?.toString().orEmpty() }
                    .getOrDefault("")
                if (selected.isNotEmpty()) {
                    val cleared = runCatching { ic.commitText("", 1) }.getOrDefault(false)
                    if (!cleared) {
                        restoreSelectionAfterFailedClear(ic, originalSelection)
                    } else {
                        ic.finishComposingText()
                    }
                    return cleared
                }

                // Empty selection can mean either an empty document or an editor
                // that claimed select-all without exposing/creating a selection.
                // Only the complete extracted state can distinguish those safely.
                val selectedWindow = extractedWindow(ic)
                if (selectedWindow?.isCompleteDocument == true) {
                    if (selectedWindow.text.isEmpty()) return true
                    val fullSelection = selectedWindow.selectionStartAbsolute == 0 &&
                        selectedWindow.selectionEndAbsolute == selectedWindow.text.length
                    if (fullSelection) {
                        val cleared = runCatching { ic.commitText("", 1) }.getOrDefault(false)
                        if (!cleared) {
                            restoreSelectionAfterFailedClear(ic, originalSelection)
                        } else {
                            ic.finishComposingText()
                        }
                        return cleared
                    }
                }
                restoreSelectionAfterFailedClear(ic, originalSelection)
                return false
            }

            val window = extractedWindow(ic) ?: return false
            if (!window.isCompleteDocument) return false
            if (window.text.isEmpty()) return true
            if (!runCatching { ic.setSelection(0, window.text.length) }.getOrDefault(false)) {
                return false
            }
            val cleared = runCatching { ic.commitText("", 1) }.getOrDefault(false)
            if (!cleared) {
                restoreSelectionAfterFailedClear(ic, originalSelection)
            } else {
                ic.finishComposingText()
            }
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
    fun selectAll(): Boolean {
        if (isPassword()) return false
        val ic = connection() ?: return false
        if (runCatching { ic.performContextMenuAction(android.R.id.selectAll) }.getOrDefault(false)) return true
        val window = extractedWindow(ic) ?: return false
        if (window.isCompleteDocument) {
            return runCatching { ic.setSelection(0, window.text.length) }.getOrDefault(false)
        }
        return false
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

    /** Let the editor handle character boundaries, reversed selections and document edges. */
    fun moveCursorHorizontally(direction: Int) {
        if (isPassword()) return
        val keyCode = relativeCursorKeyCode(direction) ?: return
        val ic = connection() ?: return
        sendKeyDownUp(ic, keyCode)
    }

    /** Let the target editor handle vertical cursor movement when supported. */
    fun moveCursorVertically(direction: Int) {
        if (isPassword()) return
        val keyCode = when (direction) {
            -1 -> KeyEvent.KEYCODE_DPAD_UP
            1 -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> return
        }
        connection()?.let { sendKeyDownUp(it, keyCode) }
    }

    /** Use the editor's native undo stack instead of exposing a dead button. */
    fun undo(): Boolean {
        if (isPassword()) return false
        val ic = connection() ?: return false
        return runCatching { ic.performContextMenuAction(android.R.id.undo) }.getOrDefault(false)
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
        val localStart = minOf(window.selectionStartAbsolute, window.selectionEndAbsolute) - window.windowStart
        val localEnd = maxOf(window.selectionStartAbsolute, window.selectionEndAbsolute) - window.windowStart
        if (localStart < 0 || localEnd <= localStart || localEnd > window.text.length) return ""
        return window.text.substring(localStart, localEnd)
    }

    /** Whether the target editor currently exposes a non-empty selection. */
    fun hasSelection(): Boolean {
        if (isPassword()) return false
        val ic = connection() ?: return false
        if (runCatching { ic.getSelectedText(0)?.isNotEmpty() == true }.getOrDefault(false)) {
            return true
        }
        val window = extractedWindow(ic)
        if (window != null && window.selectionStartAbsolute != window.selectionEndAbsolute) {
            return true
        }
        return knownSelectionStart >= 0 &&
            knownSelectionEnd >= 0 &&
            knownSelectionStart != knownSelectionEnd
    }

    /** Whether a non-empty text clip is available for the current editor. */
    fun hasClipboardText(): Boolean {
        val safeContext = context ?: return false
        val cm = safeContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return runCatching {
            cm.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(safeContext)
                ?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    fun copyToClipboard(text: String) {
        if (text.isEmpty() || isPassword()) return
        val cm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("ime", text))
    }

    fun readClipboard(): String {
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

    fun pasteClipboard(onPasted: (ClipData) -> Unit = {}): String {
        val safeContext = context ?: return ""
        val cm = safeContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return ""
        return pasteClipSnapshot(clip, onPasted)
    }

    /** Paste an already-read immutable clipboard snapshot without re-reading the system clip. */
    internal fun pasteClipSnapshot(clip: ClipData, onPasted: (ClipData) -> Unit = {}): String {
        val safeContext = context ?: return ""
        val text = runCatching {
            clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(safeContext)?.toString().orEmpty()
        }.getOrDefault("")
        if (text.isEmpty()) return ""
        val committed = connection()?.commitText(text, 1) == true
        if (!committed) return ""
        onPasted(clip)
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

    /** Capture the best absolute selection available before a select-all mutates editor state. */
    private fun selectionBeforeDestructiveSelectAll(ic: InputConnection): SelectionSnapshot? {
        val snapshot = selectionSnapshot(ic)
        if (snapshot is SelectionSnapshot.Absolute) return snapshot
        if (knownSelectionStart >= 0 && knownSelectionEnd >= 0) {
            return SelectionSnapshot.Absolute(knownSelectionStart, knownSelectionEnd)
        }
        return snapshot
    }

    /**
     * A failed clear must never leave the target document selected. Restore the
     * original absolute range when possible. Editors that expose only a local
     * relative window cannot be restored exactly, so collapse any accidental
     * select-all at its right edge as the safe fallback.
     */
    private fun restoreSelectionAfterFailedClear(
        ic: InputConnection,
        snapshot: SelectionSnapshot?,
    ) {
        when (snapshot) {
            is SelectionSnapshot.Absolute -> {
                if (runCatching { ic.setSelection(snapshot.start, snapshot.end) }.getOrDefault(false)) {
                    knownSelectionStart = snapshot.start
                    knownSelectionEnd = snapshot.end
                    return
                }
                sendKeyDownUp(ic, KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            is SelectionSnapshot.Relative,
            null,
            -> sendKeyDownUp(ic, KeyEvent.KEYCODE_DPAD_RIGHT)
        }
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
