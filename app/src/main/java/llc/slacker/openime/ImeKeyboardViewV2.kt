package llc.slacker.openime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Production wrapper around the legacy renderer. Business state still lives in
 * the service; this layer owns window geometry and production-only capability
 * filtering that should not leak into key layout/state code.
 */
class ImeKeyboardViewV2(
    context: Context,
    private val listenerDelegate: ImeKeyboardView.Listener,
) : ImeKeyboardView(context, listenerDelegate) {

    private var navigationBottomInsetPx = 0
    private var repairingEarlierNineKeySegment = false

    /**
     * Production presentation sync is gated because onMeasure runs frequently.
     * Enter labeling depends on the current hierarchy/editor; do not rescan
     * the tree when neither changed.
     */
    private var presentationDirty = true
    private var lastSyncedImeOptions: Int? = null

    init {
        post { syncProductionKeyPresentation() }

        // Insets already consumed by the IME window arrive as zero, so this
        // adds safe area only when Android actually reports an unconsumed nav
        // region. The value is bounded to avoid pathological OEM geometry.
        setOnApplyWindowInsetsListener { _, insets ->
            val reported = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            val next = ImeBottomInsetPolicy.clampInset(reported, insetDp(32))
            if (next != navigationBottomInsetPx) {
                navigationBottomInsetPx = next
                requestLayout()
            }
            insets
        }
    }

    override fun onViewHierarchyRebuilt() {
        super.onViewHierarchyRebuilt()
        presentationDirty = true
        post {
            NineKeySymbolRailDecorator.decorate(
                root = this,
                onCommit = { symbol -> listenerDelegate.onCharacter(symbol) },
                onFeedback = ::feedback,
            )
            installNineKeyAccessibilityRepair()
            syncProductionKeyPresentation()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            repairEarlierNineKeySegmentIfNeeded()
        }
        return handled
    }

    /**
     * The legacy 9-key renderer only owns a digit buffer for the final Pinyin
     * segment. Editing an earlier segment therefore used to insert literal
     * digits (for example `n2i hao`) or fall back to a full-Pinyin query.
     *
     * Reconstruct the edited segment's digit code from the visible preedit,
     * decode that segment again, then publish one complete native T9 code. This
     * keeps middle-segment insertion/deletion inside the same Rime 9-key model
     * without coupling the legacy renderer to the new decoder.
     */
    private fun repairEarlierNineKeySegmentIfNeeded() {
        if (repairingEarlierNineKeySegment) return
        if (findViewWithTag<View>("pinyin9-layout") == null) return
        val editor = findViewWithTag<EditText>("pinyin-composition-editor") ?: return
        val text = editor.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val lastSpace = text.lastIndexOf(' ')
        if (lastSpace < 0) return

        val rawCursor = editor.selectionStart.takeIf { it >= 0 }?.coerceIn(0, text.length) ?: text.length
        // The final segment is already handled losslessly by the legacy digit
        // buffer; only an earlier segment needs this bridge.
        if (rawCursor > lastSpace) return

        val segmentAnchor = when {
            rawCursor <= 0 -> 0
            rawCursor < text.length && text[rawCursor] == ' ' -> rawCursor - 1
            else -> (rawCursor - 1).coerceAtLeast(0)
        }
        val segmentStart = text.lastIndexOf(' ', segmentAnchor).let { if (it < 0) 0 else it + 1 }
        val segmentEnd = text.indexOf(' ', segmentStart).let { if (it < 0) text.length else it }
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
        val segmentResolution = resolver.resolveNineKey(
            digits = digitCode,
            segmentPrefix = "",
            preferredSuffix = preferred,
            fuzzy = fuzzy,
        )
        val decodedSegment = segmentResolution.preview.ifBlank { return }
        val repaired = text.substring(0, segmentStart) + decodedSegment + text.substring(segmentEnd)
        val repairedCursor = (segmentStart + (rawCursor - segmentStart).coerceAtLeast(0))
            .coerceAtMost(segmentStart + decodedSegment.length)
        val nativeCode = NineKeyLocalDecoder.nativeCode(repaired, "") ?: return
        val localCandidates = resolver.candidatesFor(KeyboardMode.PINYIN_9, repaired, fuzzy)
            .filter { candidate -> candidate.isNotBlank() && candidate.none(Char::isDigit) }
            .take(96)

        repairingEarlierNineKeySegment = true
        try {
            if (repaired != text) editor.setText(repaired)
            editor.setSelection(repairedCursor.coerceIn(0, repaired.length))
            adapter.onNineKeyCompositionChanged(
                composition = repaired,
                digitBuffer = digitCode,
                pinyinPaths = listOf(nativeCode),
                candidates = localCandidates,
            )
        } finally {
            repairingEarlierNineKeySegment = false
        }
    }

    /** Accessibility clicks do not emit root ACTION_UP; run the same earlier-segment repair explicitly. */
    private fun installNineKeyAccessibilityRepair() {
        for (digit in '2'..'9') {
            val key = findViewWithTag<View>("key-9:$digit") ?: continue
            key.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    val handled = super.performAccessibilityAction(host, action, args)
                    if (handled && action == AccessibilityNodeInfo.ACTION_CLICK) {
                        host.post { repairEarlierNineKeySegmentIfNeeded() }
                    }
                    return handled
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presentationDirty = true
        requestApplyInsets()
        post { syncProductionKeyPresentation() }
    }

    override fun onDetachedFromWindow() {
        shutdown()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // EditorInfo can change while Android reuses the same input view. Keep
        // the visible Enter label and bottom-row geometry synchronized before
        // children are measured instead of relying on one-time construction.
        syncProductionKeyPresentation()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (navigationBottomInsetPx <= 0) return
        val targetHeight = ImeBottomInsetPolicy.measuredHeight(
            baseHeightPx = measuredHeight,
            bottomInsetPx = navigationBottomInsetPx,
            measureMode = View.MeasureSpec.getMode(heightMeasureSpec),
            measureSizePx = View.MeasureSpec.getSize(heightMeasureSpec),
        )
        if (targetHeight != measuredHeight) {
            // The inherited keyboard/panel remains at its existing 296dp body
            // height. Extra measured height becomes a bottom safe area, so the
            // last key row is not compressed upward or covered by navigation.
            setMeasuredDimension(measuredWidth, targetHeight)
        }
    }

    private fun syncProductionKeyPresentation() {
        val imeOptions = (context as? InputMethodService)?.currentInputEditorInfo?.imeOptions
        if (!presentationDirty && imeOptions == lastSyncedImeOptions) return
        presentationDirty = false
        lastSyncedImeOptions = imeOptions
        syncEnterKeyPresentation()
    }

    /** Keep every visible Enter key honest about what onEnter() will dispatch. */
    private fun syncEnterKeyPresentation() {
        val service = context as? InputMethodService ?: return
        val imeOptions = service.currentInputEditorInfo?.imeOptions ?: return
        val label = enterKeyPresentationFor(imeOptions).label

        fun visit(view: View) {
            if (view is ImeKeyView && view.tag == "key-enter") {
                view.setMainText(label)
                view.contentDescription = label
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(this)
    }

    private fun insetDp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

}
