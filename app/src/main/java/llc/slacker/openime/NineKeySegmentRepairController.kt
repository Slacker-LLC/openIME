package llc.slacker.openime

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText

/**
 * Repairs edits made inside an earlier Chinese 9-key segment.
 *
 * The renderer supplies explicit digit-key references for accessibility repair;
 * this controller no longer scans the rebuilt keyboard hierarchy.
 */
internal class NineKeySegmentRepairController(
    private val context: Context,
    private val composition: () -> EditText,
    private val isNineKeyActive: () -> Boolean,
    private val listener: ImeKeyboardView.Listener,
) {
    private var repairing = false

    fun bindDigitKey(digit: String, key: ImeKeyView) {
        if (digit.length != 1 || digit[0] !in '2'..'9') return
        key.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun performAccessibilityAction(
                hostView: View,
                action: Int,
                args: Bundle?,
            ): Boolean {
                val handled = super.performAccessibilityAction(hostView, action, args)
                if (handled && action == AccessibilityNodeInfo.ACTION_CLICK) {
                    hostView.post(::repairIfNeeded)
                }
                return handled
            }
        }
    }

    fun repairIfNeeded() {
        if (repairing || !isNineKeyActive()) return
        val editor = composition()
        val text = editor.text?.toString().orEmpty()
        if (text.isEmpty()) return

        val lastSpace = text.lastIndexOf(' ')
        if (lastSpace < 0) return

        val rawCursor = editor.selectionStart
            .takeIf { it >= 0 }
            ?.coerceIn(0, text.length)
            ?: text.length

        if (rawCursor > lastSpace) return

        val segmentAnchor = when {
            rawCursor <= 0 -> 0
            rawCursor < text.length && text[rawCursor] == ' ' -> rawCursor - 1
            else -> (rawCursor - 1).coerceAtLeast(0)
        }
        val segmentStart = text.lastIndexOf(' ', segmentAnchor)
            .let { if (it < 0) 0 else it + 1 }
        val segmentEnd = text.indexOf(' ', segmentStart)
            .let { if (it < 0) text.length else it }
        if (segmentEnd <= segmentStart) return

        val segment = text.substring(segmentStart, segmentEnd)
        val digitCode = buildString(segment.length) {
            segment.forEach { ch ->
                when {
                    ch in '2'..'9' -> append(ch)
                    ch in 'a'..'z' || ch in 'A'..'Z' || ch == 'ü' || ch == 'Ü' -> {
                        val digit = NineKeyLocalDecoder.digitsForPinyin(ch.toString()) ?: return
                        append(digit)
                    }
                    else -> return
                }
            }
        }
        if (digitCode.isEmpty()) return

        val resolver = context as? CandidateResolver ?: return
        val preferred = segment
            .takeIf { candidate -> candidate.none(Char::isDigit) }
            ?.lowercase()
        val fuzzy = ImeSettingsRepository.loadFuzzy(context)
        val resolution = resolver.resolveNineKey(
            digits = digitCode,
            segmentPrefix = "",
            preferredSuffix = preferred,
            fuzzy = fuzzy,
        )
        val decodedSegment = resolution.preview.ifBlank { return }
        val repairedText =
            text.substring(0, segmentStart) + decodedSegment + text.substring(segmentEnd)
        val repairedCursor = (segmentStart + (rawCursor - segmentStart).coerceAtLeast(0))
            .coerceAtMost(segmentStart + decodedSegment.length)
        val nativeCode = NineKeyLocalDecoder.nativeCode(repairedText, "") ?: return
        val localCandidates = resolver
            .candidatesFor(KeyboardMode.PINYIN_9, repairedText, fuzzy)
            .filter { candidate -> candidate.isNotBlank() && candidate.none(Char::isDigit) }
            .take(96)

        repairing = true
        try {
            if (repairedText != text) editor.setText(repairedText)
            editor.setSelection(repairedCursor.coerceIn(0, repairedText.length))
            listener.onNineKeyCompositionChanged(
                composition = repairedText,
                digitBuffer = digitCode,
                pinyinPaths = listOf(nativeCode),
                candidates = localCandidates,
            )
        } finally {
            repairing = false
        }
    }
}
