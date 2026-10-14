package llc.slacker.openime.voice

/**
 * 端点判断的纯逻辑。现有体验由手势松开结束录音；VAD 可用时用 sherpa VAD 的
 * isSpeechDetected 状态做更准的起止/端点判断，VAD 不可用时完全保持原有行为。
 */
internal object VoiceEndpointPolicy {
    /**
     * paraformer 收尾是否需要补 300 ms 零：VAD 可用且整段都未检测到语音时不需要；
     * 其余情况（包括没有 VAD）与现在完全一致。
     */
    fun tailPaddingNeeded(vadActive: Boolean, sawSpeech: Boolean): Boolean =
        !vadActive || sawSpeech
}

/**
 * 会话内累加的 VAD 状态。每块音频（20 ms）调用一次 [observe]：传
 * isSpeechDetected() 的结果；VAD 不可用或推理失败时传 null，立即退回原有判断。
 */
internal class VadSpeechTracker(
    private val minSilenceChunks: Int = DEFAULT_MIN_SILENCE_CHUNKS,
) {
    init {
        require(minSilenceChunks > 0) { "minSilenceChunks must be positive" }
    }

    /** 本会话是否真的跑过 VAD。 */
    var isVadActive: Boolean = false
        private set

    var hasSpeech: Boolean = false
        private set

    private var trailingSilenceChunks = 0

    fun observe(speech: Boolean?) {
        if (speech == null) {
            isVadActive = false
            trailingSilenceChunks = 0
            return
        }
        isVadActive = true
        if (speech) {
            hasSpeech = true
            trailingSilenceChunks = 0
        } else if (hasSpeech) {
            trailingSilenceChunks += 1
        }
    }

    /** 说过话后又静了 [minSilenceChunks] 块：更准的语音结束点，用于收尾诊断。 */
    fun speechEnded(): Boolean =
        isVadActive && hasSpeech && trailingSilenceChunks >= minSilenceChunks

    fun tailPaddingNeeded(): Boolean = VoiceEndpointPolicy.tailPaddingNeeded(isVadActive, hasSpeech)

    companion object {
        /** silero 默认 minSilenceDuration=0.5 s，按 20 ms 一块即 25 块。 */
        internal const val DEFAULT_MIN_SILENCE_CHUNKS = 25
    }
}
