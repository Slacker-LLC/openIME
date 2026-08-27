package llc.slacker.openime

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.TextView

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

    init {
        adapter.afterPanelChanged = { panel ->
            if (panel == Panel.TEXT_EDITOR) {
                // The legacy renderer invokes onPanelChanged before renderPanel,
                // so defer capability filtering until the panel children exist.
                post { disableUnsupportedTextEditControls() }
            }
        }

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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
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
        var afterPanelChanged: ((Panel) -> Unit)? = null

        override fun onModeChanged(mode: KeyboardMode) = delegate.onModeChanged(mode)
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
        override fun onVoicePressChanged(pressed: Boolean) = delegate.onVoicePressChanged(pressed)
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
    }
}
