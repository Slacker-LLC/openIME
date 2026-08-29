package llc.slacker.openime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Production wrapper around the legacy renderer. Business state still lives in
 * the service; this layer owns window geometry and production-only capability
 * filtering that should not leak into key layout/state code.
 */
class ImeKeyboardViewV2 private constructor(
    context: Context,
    private val adapter: Adapter,
) : ImeKeyboardView(context, adapter) {

    constructor(context: Context, listener: Listener) : this(context, Adapter(listener))

    private var navigationBottomInsetPx = 0

    /**
     * [syncProductionKeyPresentation] walks the whole key tree three times
     * (space geometry, Enter label, handwriting capability). onMeasure fires on
     * every key tap, every async candidate callback, every insets change and
     * every rotation, so the previous unconditional call made ~450 node visits
     * per keystroke. Geometry only depends on the editor's IME options and on
     * explicit invalidate calls; gate on those.
     */
    private var presentationDirty = true
    private var lastSyncedImeOptions: Int? = null

    /** Force the next measure pass to resynchronize key presentation. */
    fun invalidatePresentation() {
        presentationDirty = true
    }

    init {
        adapter.afterModeChanged = {
            presentationDirty = true
            post { syncProductionKeyPresentation() }
        }
        adapter.afterPanelChanged = { panel ->
            when (panel) {
                Panel.TEXT_EDITOR -> {
                    // The legacy renderer invokes onPanelChanged before renderPanel,
                    // so defer capability filtering until the panel children exist.
                    post {
                        disableUnsupportedTextEditControls()
                        syncProductionKeyPresentation()
                    }
                }
                Panel.CLIPBOARD -> post {
                    decorateClipboardRetentionControls()
                    syncProductionKeyPresentation()
                }
                else -> post { syncProductionKeyPresentation() }
            }
        }
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // The legacy space implementation reports only pressed=true/false to
        // the listener. Preserve whether the release was a cancellation so the
        // adapter can recover a 150..system-timeout hold as a normal space only
        // on a real ACTION_UP, never on ACTION_CANCEL.
        adapter.releaseWasCancel = event.actionMasked == MotionEvent.ACTION_CANCEL
        return super.dispatchTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presentationDirty = true
        requestApplyInsets()
        post { syncProductionKeyPresentation() }
    }

    override fun onDetachedFromWindow() {
        adapter.shutdown()
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
        normalizeSpaceRowGeometry()
        syncEnterKeyPresentation()
        syncHandwritingCapability()
    }

    /**
     * The old renderer centered each key's label but gave the left/right
     * function groups different total weights. Balance only the two outside
     * keys so the middle space key remains visually centered without changing
     * its touch target width.
     */
    private fun normalizeSpaceRowGeometry() {
        val space = findViewWithTag<View>("key-space") ?: return
        val row = space.parent as? LinearLayout ?: return
        val spaceIndex = row.indexOfChild(space)
        if (spaceIndex <= 0 || spaceIndex >= row.childCount - 1) return
        if (spaceIndex * 2 != row.childCount - 1) return

        fun paramsAt(index: Int): LinearLayout.LayoutParams? =
            row.getChildAt(index).layoutParams as? LinearLayout.LayoutParams

        val leftParams = (0 until spaceIndex).mapNotNull(::paramsAt)
        val rightParams = (spaceIndex + 1 until row.childCount).mapNotNull(::paramsAt)
        if (leftParams.size != spaceIndex || rightParams.size != row.childCount - spaceIndex - 1) return
        val leftTotal = leftParams.sumOf { it.weight.toDouble() }.toFloat()
        val rightTotal = rightParams.sumOf { it.weight.toDouble() }.toFloat()
        if (abs(leftTotal - rightTotal) < 0.001f) return

        val leftOuter = paramsAt(0) ?: return
        val rightOuter = paramsAt(row.childCount - 1) ?: return
        val balanced = ProductionKeyPolicy.balancedOuterWeights(
            leftTotal = leftTotal,
            rightTotal = rightTotal,
            leftOuter = leftOuter.weight,
            rightOuter = rightOuter.weight,
        )
        if (abs(leftOuter.weight - balanced.leftOuter) >= 0.001f) {
            leftOuter.weight = balanced.leftOuter
            row.getChildAt(0).layoutParams = leftOuter
        }
        if (abs(rightOuter.weight - balanced.rightOuter) >= 0.001f) {
            rightOuter.weight = balanced.rightOuter
            row.getChildAt(row.childCount - 1).layoutParams = rightOuter
        }
    }

    /** Keep every visible Enter key honest about what onEnter() will dispatch. */
    private fun syncEnterKeyPresentation() {
        val service = context as? InputMethodService ?: return
        val imeOptions = service.currentInputEditorInfo?.imeOptions ?: return
        val label = enterKeyPresentationFor(imeOptions).label
        val enterLabels = setOf(
            "发送", "搜索", "前往", "下一项", "上一项", "完成", "换行", "回车", "确定", "Go",
        )

        fun visit(view: View) {
            if (view is ImeKeyView) {
                val standardEnter = view.tag == "key-enter"
                val gamingEnter = view.tag == "game-mini" &&
                    view.contentDescription?.toString() in enterLabels
                if (standardEnter || gamingEnter) {
                    view.setMainText(label)
                    view.contentDescription = label
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(this)
    }

    /** Do not expose a dead handwriting flow while production has no recognizer. */
    private fun syncHandwritingCapability() {
        if (HandwritingFeaturePolicy.entryEnabled(UnavailableHandwritingProvider)) return

        fun markUnavailableLabel(view: View) {
            if (view is TextView && view.text.toString() == "手写输入") {
                view.text = "手写输入·未配置"
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) markUnavailableLabel(view.getChildAt(index))
            }
        }

        fun visit(view: View) {
            if (view.contentDescription?.toString() == "手写输入") {
                view.isEnabled = false
                view.isClickable = false
                view.isLongClickable = false
                view.alpha = 0.38f
                view.contentDescription = "手写输入（未配置）"
                markUnavailableLabel(view)
                return
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(this)
    }

    private fun disableUnsupportedTextEditControls() {
        fun visit(view: View) {
            if (view is TextView && TextEditControlPolicy.isUnavailableLabel(view.text.toString())) {
                view.isEnabled = false
                view.isClickable = false
                view.alpha = 0.38f
                view.contentDescription = "${view.text}（当前编辑器暂不支持）"
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(this)
    }

    private fun decorateClipboardRetentionControls() {
        if (findViewWithTag<View>("quick-phrase-add") != null) return
        val body = findViewWithTag<LinearLayout>("clipboard-panel") ?: return
        if (body.findViewWithTag<View>("clipboard-retention-actions") != null) return
        if (ClipboardHistoryRepository.load(context).isEmpty()) return

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "clipboard-retention-actions"
        }
        row.addView(
            clipboardRetentionAction("清除未固定") {
                ClipboardHistoryRepository.clearUnpinned(context)
                hideClipboardCards(includePinned = false)
                if (ClipboardHistoryRepository.load(context).isEmpty()) row.visibility = View.GONE
            },
            LinearLayout.LayoutParams(0, insetDp(36), 1f).apply { marginEnd = insetDp(6) },
        )
        row.addView(
            clipboardRetentionAction("清空全部") {
                ClipboardHistoryRepository.clearAll(context)
                hideClipboardCards(includePinned = true)
                row.visibility = View.GONE
            },
            LinearLayout.LayoutParams(0, insetDp(36), 1f),
        )
        body.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                insetDp(36),
            ).apply { topMargin = insetDp(6) },
        )
    }

    private fun clipboardRetentionAction(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            val backgroundValue = TypedValue()
            if (
                context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground,
                    backgroundValue,
                    true,
                ) && backgroundValue.resourceId != 0
            ) {
                setBackgroundResource(backgroundValue.resourceId)
            }
            setOnClickListener { onClick() }
        }

    private fun hideClipboardCards(includePinned: Boolean) {
        fun containsPinnedMarker(view: View): Boolean {
            if (view is TextView && view.text.toString() == "已置顶") return true
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (containsPinnedMarker(view.getChildAt(index))) return true
                }
            }
            return false
        }

        fun visit(view: View) {
            if (view.tag == "clip-card") {
                if (includePinned || !containsPinnedMarker(view)) view.visibility = View.GONE
                return
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(this)
    }

    private fun insetDp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    interface Listener {
        fun onModeChanged(mode: KeyboardMode)
        fun onPanelChanged(panel: Panel)
        fun onCharacter(char: String)
        fun onBackspace()
        fun onClearAll()
        fun onSpace()
        fun onFloatingKeyboardChanged(floating: Boolean)
        fun onFloatingKeyboardDragged(deltaX: Float, deltaY: Float)
        fun onVoiceToggle()
        fun onVoicePressChanged(pressed: Boolean) {
            if (pressed) onVoiceToggle()
        }
        fun onVoiceSessionStarted(autoCommitOnFinal: Boolean) {}
        fun onVoicePartial(text: String) {}
        fun onVoiceFinal(text: String) {}
        fun onVoiceError(message: String) {}
        fun onVoiceCommit() {}
        fun onVoiceCancel() {}
        fun voiceModelState(): VoiceModelLifecycleState = VoiceModelLifecycleState.COLD
        fun startVoiceRecognition(languageTag: String, events: VoiceRecognitionEvents) {
            events.onError("本地语音服务未连接")
        }
        fun stopVoiceRecognition() {}
        fun cancelVoiceRecognition() {}
        fun onEnter()
        fun onCompositionChanged(composition: String, candidates: List<String>)
        fun onNineKeyCompositionChanged(
            composition: String,
            digitBuffer: String,
            pinyinPaths: List<String>,
            candidates: List<String>,
        ) {
            onCompositionChanged(composition, candidates)
        }
        fun onCandidateSelected(candidate: String)

        /**
         * Association chips are shown after a commit, when no composition is
         * live. They must not be funnelled through [onCandidateSelected], which
         * is guarded on an active composition and silently drops them.
         */
        fun onAssociationSelected(text: String)

        fun onCompositionBackspace()
        fun onThemeChanged(theme: ImeTheme)
        fun onAppearanceChanged(appearance: ImeAppearance)
        fun onShiftStateChanged(state: ShiftState)
        fun onCandidateExpanded(open: Boolean)
        fun onSymbolSelected(symbol: String)
        fun onEmojiSelected(emoji: String)
        fun onTextEdit(action: String)
        fun onSoundChanged(enabled: Boolean)
        fun onHapticChanged(enabled: Boolean)
        fun onPopupChanged(enabled: Boolean)
        fun onFuzzyChanged(enabled: Boolean)
        fun onSkinChanged(opacity: Int, radius: Int, fontSize: Int, primaryColor: String)
    }

    private class Adapter(private val delegate: Listener) : ImeKeyboardView.Listener {
        var afterModeChanged: ((KeyboardMode) -> Unit)? = null
        var afterPanelChanged: ((Panel) -> Unit)? = null
        var releaseWasCancel: Boolean = false

        private val voiceHandler = Handler(Looper.getMainLooper())
        private var pendingVoiceStart: Runnable? = null
        private var voiceStartForwarded = false

        override fun onModeChanged(mode: KeyboardMode) {
            delegate.onModeChanged(mode)
            afterModeChanged?.invoke(mode)
        }
        override fun onPanelChanged(panel: Panel) {
            delegate.onPanelChanged(panel)
            afterPanelChanged?.invoke(panel)
        }
        override fun onCharacter(char: String) = delegate.onCharacter(char)
        override fun onBackspace() = delegate.onBackspace()
        override fun onClearAll() = delegate.onClearAll()
        override fun onSpace() = delegate.onSpace()
        override fun onFloatingKeyboardChanged(floating: Boolean) = delegate.onFloatingKeyboardChanged(floating)
        override fun onFloatingKeyboardDragged(deltaX: Float, deltaY: Float) =
            delegate.onFloatingKeyboardDragged(deltaX, deltaY)
        override fun onVoiceToggle() = delegate.onVoiceToggle()
        override fun onVoicePressChanged(pressed: Boolean) {
            if (pressed) {
                if (voiceStartForwarded || pendingVoiceStart != null) return
                val start = Runnable {
                    pendingVoiceStart = null
                    if (!releaseWasCancel) {
                        voiceStartForwarded = true
                        delegate.onVoicePressChanged(true)
                    }
                }
                pendingVoiceStart = start
                voiceHandler.postDelayed(
                    start,
                    ProductionKeyPolicy.remainingVoiceDelayMs(
                        ViewConfiguration.getLongPressTimeout().toLong(),
                    ),
                )
                return
            }

            val pending = pendingVoiceStart
            if (pending != null) {
                voiceHandler.removeCallbacks(pending)
                pendingVoiceStart = null
                // The legacy renderer has already consumed this release because
                // it crossed its old 150 ms threshold. Recover it as the normal
                // space action unless Android cancelled the gesture.
                if (!releaseWasCancel) delegate.onSpace()
                return
            }
            if (voiceStartForwarded) {
                voiceStartForwarded = false
                delegate.onVoicePressChanged(false)
            }
        }
        override fun onVoiceSessionStarted(autoCommitOnFinal: Boolean) =
            delegate.onVoiceSessionStarted(autoCommitOnFinal)
        override fun onVoicePartial(text: String) = delegate.onVoicePartial(text)
        override fun onVoiceFinal(text: String) = delegate.onVoiceFinal(text)
        override fun onVoiceError(message: String) = delegate.onVoiceError(message)
        override fun onVoiceCommit() = delegate.onVoiceCommit()
        override fun onVoiceCancel() = delegate.onVoiceCancel()
        override fun voiceModelState() = delegate.voiceModelState()
        override fun startVoiceRecognition(languageTag: String, events: VoiceRecognitionEvents) =
            delegate.startVoiceRecognition(languageTag, events)
        override fun stopVoiceRecognition() = delegate.stopVoiceRecognition()
        override fun cancelVoiceRecognition() = delegate.cancelVoiceRecognition()
        override fun onEnter() = delegate.onEnter()
        override fun onCompositionChanged(composition: String, candidates: List<String>) =
            delegate.onCompositionChanged(composition, candidates)
        override fun onNineKeyCompositionChanged(
            composition: String,
            digitBuffer: String,
            pinyinPaths: List<String>,
            candidates: List<String>,
        ) = delegate.onNineKeyCompositionChanged(composition, digitBuffer, pinyinPaths, candidates)
        override fun onCandidateSelected(candidate: String) = delegate.onCandidateSelected(candidate)
        override fun onAssociationSelected(text: String) = delegate.onAssociationSelected(text)
        override fun onCompositionBackspace() = delegate.onCompositionBackspace()
        override fun onThemeChanged(theme: ImeTheme) = delegate.onThemeChanged(theme)
        override fun onAppearanceChanged(appearance: ImeAppearance) = delegate.onAppearanceChanged(appearance)
        override fun onShiftStateChanged(state: ShiftState) = delegate.onShiftStateChanged(state)
        override fun onCandidateExpanded(open: Boolean) = delegate.onCandidateExpanded(open)
        override fun onSymbolSelected(symbol: String) = delegate.onSymbolSelected(symbol)
        override fun onEmojiSelected(emoji: String) = delegate.onEmojiSelected(emoji)
        override fun onTextEdit(action: String) = delegate.onTextEdit(action)
        override fun onSoundChanged(enabled: Boolean) = delegate.onSoundChanged(enabled)
        override fun onHapticChanged(enabled: Boolean) = delegate.onHapticChanged(enabled)
        override fun onPopupChanged(enabled: Boolean) = delegate.onPopupChanged(enabled)
        override fun onFuzzyChanged(enabled: Boolean) = delegate.onFuzzyChanged(enabled)

        /**
         * A pending voice-start runnable survives for the long-press delay. If
         * the editor goes away first it would still fire and begin recording,
         * writing partial recognition into whichever InputConnection became
         * current in the meantime.
         */
        fun shutdown() {
            pendingVoiceStart?.let { voiceHandler.removeCallbacks(it) }
            pendingVoiceStart = null
            voiceStartForwarded = false
            releaseWasCancel = false
        }
    }
}
