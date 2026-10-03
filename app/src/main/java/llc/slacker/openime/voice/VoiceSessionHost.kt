package llc.slacker.openime.voice

/**
 * What the voice presentation layer needs from whoever owns the editor and the
 * recognizer. The keyboard's listener extends it, so voice code depends on this
 * narrow contract instead of on the keyboard view.
 */
interface VoiceSessionHost {
    /** Kept for compatibility; production voice always commits on release of space. */
    fun onVoiceSessionStarted(autoCommitOnFinal: Boolean) {}
    fun onVoicePartial(text: String) {}
    fun onVoiceFinal(text: String) {}
    fun onVoiceError(message: String) {}
    fun onVoiceCancel() {}
    fun voiceModelState(): VoiceModelLifecycleState = VoiceModelLifecycleState.COLD
    fun startVoiceRecognition(languageTag: String, events: VoiceRecognitionEvents) {
        events.onError("本地语音服务未连接")
    }
    fun stopVoiceRecognition() {}
    fun cancelVoiceRecognition() {}
}
