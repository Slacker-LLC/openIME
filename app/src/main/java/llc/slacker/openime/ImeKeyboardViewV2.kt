package llc.slacker.openime

import android.content.Context

/**
 * Compatibility alias. The production path uses [ImeKeyboardView] directly;
 * this class exists to keep the newer service listener contract name stable.
 */
class ImeKeyboardViewV2(
    context: Context,
    listener: Listener,
) : ImeKeyboardView(context, Adapter(listener)) {

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
        fun onVoicePartial(text: String) {}
        fun onVoiceFinal(text: String) {}
        fun onVoiceError(message: String) {}
        fun onEnter()
        fun onCompositionChanged(composition: String, candidates: List<String>)
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
        override fun onModeChanged(mode: KeyboardMode) = delegate.onModeChanged(mode)
        override fun onPanelChanged(panel: Panel) = delegate.onPanelChanged(panel)
        override fun onCharacter(char: String) = delegate.onCharacter(char)
        override fun onBackspace() = delegate.onBackspace()
        override fun onClearAll() = delegate.onClearAll()
        override fun onSpace() = delegate.onSpace()
        override fun onFloatingKeyboardChanged(floating: Boolean) = delegate.onFloatingKeyboardChanged(floating)
        override fun onFloatingKeyboardDragged(deltaX: Float, deltaY: Float) =
            delegate.onFloatingKeyboardDragged(deltaX, deltaY)
        override fun onVoiceToggle() = delegate.onVoiceToggle()
        override fun onVoicePressChanged(pressed: Boolean) = delegate.onVoicePressChanged(pressed)
        override fun onVoicePartial(text: String) = delegate.onVoicePartial(text)
        override fun onVoiceFinal(text: String) = delegate.onVoiceFinal(text)
        override fun onVoiceError(message: String) = delegate.onVoiceError(message)
        override fun onEnter() = delegate.onEnter()
        override fun onCompositionChanged(composition: String, candidates: List<String>) =
            delegate.onCompositionChanged(composition, candidates)
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
