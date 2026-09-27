package llc.slacker.openime

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the voice session's presentation-side state and VoiceRecognitionEvents.
 *
 * Audio/model lifecycle remains behind ImeKeyboardView.Listener. Gesture
 * ownership remains in ImeKeyboardView/SpaceVoiceGestureController.
 */
internal class VoicePanelController(
    private val context: Context,
    private val expandedPanel: LinearLayout,
    private val toPx: (Int) -> Int,
    private val panelBodyHeightPx: () -> Int,
    private val createHeader: (String) -> LinearLayout,
    private val createButton: (String, Float, Boolean) -> TextView,
    private val listener: ImeKeyboardView.Listener,
    private val isGestureSessionActive: () -> Boolean,
    private val onInlineState: (String, Boolean, Boolean, Float?) -> Unit,
    private val onHideInlineLater: (Long) -> Unit,
    private val onFeedback: () -> Unit,
    private val onSessionTerminal: () -> Unit = {},
) {
    var active: Boolean = false
        private set

    var pending: Boolean = false
        private set

    private var stopRequested = false
    private var eventGeneration = 0L
    private var languageIndex = 0
    private var recognizedText = ""
    private var cancelled = false
    private var cancelPreview = false
    private var modelPrepared = false
    private var view: VoicePanelView? = null

    fun render() {
        expandedPanel.addView(
            createHeader("语音输入"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ),
        )
        val panelView = VoicePanelView(
            context = context,
            toPx = toPx,
            initialModelState = listener.voiceModelState(),
            initialLanguageIndex = languageIndex,
            createButton = createButton,
            onFeedback = onFeedback,
            onLanguageChanged = { index ->
                languageIndex = index
            },
        )
        view = panelView
        panelView.refreshLanguageControl(isLocked())
        syncViewPresentation()
        expandedPanel.addView(
            panelView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                panelBodyHeightPx(),
            ),
        )
    }

    fun detachView() {
        view = null
    }

    fun lockLanguageForGesture() {
        view?.refreshLanguageControl(locked = true)
    }

    fun start() {
        if (active) return

        val generation = ++eventGeneration
        recognizedText = ""
        cancelled = false
        cancelPreview = false
        modelPrepared = false
        active = true
        pending = true
        stopRequested = false

        refreshLanguageControl()
        onInlineState("正在准备麦克风…", false, false, null)
        setMicState("⏹", "语音输入进行中，松开空格结束，上滑取消")
        setGestureHint(
            "松开空格上屏 · 上滑取消",
            "松开空格结束语音并自动上屏，上滑取消",
        )
        setModelStatus("正在使用离线模型 · 音频不出设备")
        setTranscript("正在聆听… 松开空格结束")

        listener.onVoiceSessionStarted(true)
        listener.startVoiceRecognition(
            selectedLanguageCode(),
            recognitionEvents(generation),
        )
    }

    fun stop() {
        if (!active || stopRequested) return
        listener.stopVoiceRecognition()
        setMicState("🎤", "正在整理语音识别结果，请稍候")
        setGestureHint("整理识别结果…", "正在整理语音识别结果，请稍候")
        stopRequested = true
        refreshLanguageControl()
        setModelStatus("正在整理识别结果…")
        onInlineState("正在识别…", false, false, null)
    }

    fun cancel() {
        if (cancelled) return
        eventGeneration++
        cancelled = true
        pending = false
        cancelPreview = false
        active = false
        stopRequested = false
        refreshLanguageControl()
        listener.cancelVoiceRecognition()
        recognizedText = ""
        setMicState("🎤", "语音状态，已取消，仅支持长按空格启动")
        setGestureHint(
            "长按空格开始",
            "长按空格开始语音，松开自动上屏，上滑取消",
        )
        listener.onVoiceCancel()
        setTranscript("已取消语音输入")
        setModelStatus("语音已取消 · 音频未保存")
        onInlineState("已取消", false, false, null)
        onHideInlineLater(260L)
    }

    fun setCancelPreview(cancelling: Boolean) {
        cancelPreview = cancelling
        if (cancelling) {
            setMicState("⏹", "取消语音输入中，松开将丢弃本次语音")
            setGestureHint(
                "上滑取消 · 松开丢弃",
                "继续上滑取消语音，松开将丢弃本次语音",
            )
            setTranscript("上滑取消 · 松开丢弃本次语音")
            setModelStatus("取消状态 · 松开将丢弃")
            onInlineState("松开取消", true, false, null)
        } else {
            setMicState("⏹", "语音输入进行中，松开空格结束，上滑取消")
            setGestureHint(
                "松开空格上屏 · 上滑取消",
                "松开空格结束语音并自动上屏，上滑取消",
            )
            setTranscript(recognizedText.ifBlank { "正在聆听… 松开空格结束" })
            setModelStatus("正在聆听 · 松开空格结束")
            onInlineState(
                recognizedText.ifBlank { "正在聆听…" },
                false,
                false,
                null,
            )
        }
    }

    /**
     * Invalidates callbacks and cancels the runtime without emitting the
     * product-level onVoiceCancel callback; the host decides whether that
     * notification is required for the surrounding panel/gesture transition.
     */
    fun resetAndCancel() {
        eventGeneration++
        listener.cancelVoiceRecognition()
        active = false
        pending = false
        stopRequested = false
        cancelled = true
        cancelPreview = false
        modelPrepared = false
        recognizedText = ""
        refreshLanguageControl()
    }

    private fun recognitionEvents(generation: Long): VoiceRecognitionEvents =
        object : VoiceRecognitionEvents {
            private val rmsQueued = AtomicBoolean(false)

            @Volatile
            private var latestRms = 0f

            override fun onPartial(text: String) {
                expandedPanel.post {
                    if (generation != eventGeneration) return@post
                    if (!active || cancelled || cancelPreview) return@post
                    modelPrepared = true
                    if (text.isNotBlank()) recognizedText = text
                    setTranscript(text)

                    if (stopRequested) {
                        setModelStatus("正在整理识别结果…")
                        setMicState("⏹", "正在整理语音识别结果，请稍候")
                        setGestureHint(
                            "整理识别结果…",
                            "正在整理语音识别结果，请稍候",
                        )
                        onInlineState("正在识别…", false, false, null)
                    } else {
                        setModelStatus("正在聆听 · 松开空格结束")
                        setMicState("⏹", "语音输入进行中，松开空格结束，上滑取消")
                        setGestureHint(
                            "松开空格上屏 · 上滑取消",
                            "松开空格结束语音并自动上屏，上滑取消",
                        )
                        onInlineState(
                            text.ifBlank { "正在聆听…" },
                            false,
                            false,
                            null,
                        )
                    }
                    listener.onVoicePartial(text)
                }
            }

            override fun onFinal(text: String) {
                expandedPanel.post {
                    if (generation != eventGeneration) return@post
                    if (cancelled || cancelPreview) return@post
                    if (text.isNotBlank()) recognizedText = text
                    setTranscript(text)
                    setMicState("🎤", "语音状态，已完成识别，仅支持长按空格启动")
                    setGestureHint(
                        "长按空格开始",
                        "长按空格开始语音，松开自动上屏，上滑取消",
                    )
                    active = false
                    pending = false
                    stopRequested = false
                    onSessionTerminal()
                    refreshLanguageControl()
                    setModelStatus("离线识别完成 · 已自动上屏")
                    listener.onVoiceFinal(text)
                    onInlineState(
                        if (text.isBlank()) "没有识别到语音" else "已上屏",
                        false,
                        false,
                        null,
                    )
                    onHideInlineLater(if (text.isBlank()) 900L else 280L)
                }
            }

            override fun onRms(rms: Float) {
                latestRms = rms
                if (!rmsQueued.compareAndSet(false, true)) return
                expandedPanel.postDelayed({
                    rmsQueued.set(false)
                    if (generation != eventGeneration) return@postDelayed
                    if (
                        !active ||
                        stopRequested ||
                        cancelled ||
                        cancelPreview
                    ) {
                        return@postDelayed
                    }
                    val level = latestRms
                    view?.updateWaveformHeight(
                        toPx(
                            (8 + (level * 4f).coerceIn(0f, 52f))
                                .toInt(),
                        ),
                    )
                    onInlineState(
                        if (modelPrepared) {
                            "正在聆听…"
                        } else {
                            "正在录音 · 模型准备中…"
                        },
                        false,
                        false,
                        level,
                    )
                }, 32L)
            }

            override fun onError(message: String) {
                expandedPanel.post {
                    if (generation != eventGeneration || cancelled) return@post
                    recognizedText = ""
                    setTranscript(message)
                    setMicState("🎤", "语音状态，识别失败，仅支持长按空格重试")
                    setGestureHint(
                        "长按空格开始",
                        "长按空格重新开始语音，松开自动上屏，上滑取消",
                    )
                    active = false
                    pending = false
                    stopRequested = false
                    onSessionTerminal()
                    refreshLanguageControl()
                    setModelStatus("语音未完成 · 请检查本地模型和麦克风权限")
                    listener.onVoiceError(message)
                    onInlineState(
                        conciseVoiceError(message.ifBlank { "语音输入失败" }),
                        false,
                        true,
                        null,
                    )
                    onHideInlineLater(1_500L)
                }
            }

            override fun onReady() {
                expandedPanel.post {
                    if (generation != eventGeneration || cancelled) return@post
                    if (active && !stopRequested) {
                        setMicState("⏹", "语音输入进行中，松开空格结束，上滑取消")
                        setGestureHint(
                            "松开空格上屏 · 上滑取消",
                            "松开空格结束语音并自动上屏，上滑取消",
                        )
                        setModelStatus("正在录音 · 本地模型准备中")
                        onInlineState(
                            "正在录音 · 模型准备中…",
                            false,
                            false,
                            null,
                        )
                    } else {
                        setMicState("⏹", "正在整理语音识别结果，请稍候")
                        setGestureHint(
                            "整理识别结果…",
                            "正在整理语音识别结果，请稍候",
                        )
                        setModelStatus("正在整理识别结果…")
                        onInlineState("正在识别…", false, false, null)
                    }
                }
            }

            override fun onModelReady() {
                expandedPanel.post {
                    if (generation != eventGeneration) return@post
                    if (
                        !active ||
                        stopRequested ||
                        cancelled ||
                        cancelPreview
                    ) {
                        return@post
                    }
                    modelPrepared = true
                    setMicState("⏹", "语音输入进行中，松开空格结束，上滑取消")
                    setGestureHint(
                        "松开空格上屏 · 上滑取消",
                        "松开空格结束语音并自动上屏，上滑取消",
                    )
                    setModelStatus("正在识别 · 松开空格结束")
                    onInlineState("正在聆听…", false, false, null)
                }
            }
        }

    private fun selectedLanguageCode(): String =
        VoicePanelView.LANGUAGES[
            languageIndex.coerceIn(0, VoicePanelView.LANGUAGES.lastIndex)
        ].second

    private fun isLocked(): Boolean =
        isGestureSessionActive() || active || pending

    private fun refreshLanguageControl() {
        view?.refreshLanguageControl(isLocked())
    }

    private fun syncViewPresentation() {
        when {
            active && stopRequested -> {
                setMicState("🎤", "正在整理语音识别结果，请稍候")
                setGestureHint(
                    "整理识别结果…",
                    "正在整理语音识别结果，请稍候",
                )
                setModelStatus("正在整理识别结果…")
                setTranscript(
                    recognizedText.ifBlank { "正在整理识别结果…" },
                )
            }
            active -> {
                setMicState("⏹", "语音输入进行中，松开空格结束，上滑取消")
                setGestureHint(
                    "松开空格上屏 · 上滑取消",
                    "松开空格结束语音并自动上屏，上滑取消",
                )
                setModelStatus("正在聆听 · 松开空格结束")
                setTranscript(
                    recognizedText.ifBlank { "正在聆听… 松开空格结束" },
                )
            }
        }
    }

    private fun setMicState(icon: String, description: String) {
        view?.setMicState(icon, description)
    }

    private fun setGestureHint(label: String, description: String) {
        view?.setGestureHint(label, description)
    }

    private fun setModelStatus(value: String) {
        view?.modelStatus?.text = value
    }

    private fun setTranscript(value: String) {
        view?.transcript?.text = value
    }

    private fun conciseVoiceError(message: String): String = when {
        message.contains("模型") -> "语音不可用 · 请检查本地模型"
        message.contains("麦克风") || message.contains("权限") ->
            "语音不可用 · 请检查麦克风权限"
        else -> "语音失败 · 长按空格重试"
    }
}
