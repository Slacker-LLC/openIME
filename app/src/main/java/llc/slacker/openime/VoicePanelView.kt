package llc.slacker.openime

import android.content.Context
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Concrete visual surface for the voice panel.
 *
 * Recognition/session ownership stays in ImeKeyboardView and the voice runtime.
 * This view owns only language selection and presentation widgets.
 */
internal class VoicePanelView(
    context: Context,
    private val toPx: (Int) -> Int,
    initialModelState: VoiceModelLifecycleState,
    initialLanguageIndex: Int,
    createButton: (String, Float, Boolean) -> TextView,
    private val onFeedback: () -> Unit,
    private val onLanguageChanged: (Int) -> Unit,
) : LinearLayout(context) {
    val modelStatus = TextView(context)
    val transcript = TextView(context)
    private val waveform = LinearLayout(context)
    private val waves = mutableListOf<View>()
    private val languageButton: TextView
    private val micButton: TextView
    private val gestureHint: TextView

    private var languageIndex =
        initialLanguageIndex.coerceIn(0, LANGUAGES.lastIndex)

    init {
        tag = "voice-panel"
        orientation = VERTICAL
        setPadding(toPx(12), toPx(10), toPx(12), toPx(10))

        val modelReady = initialModelState in setOf(
            VoiceModelLifecycleState.HOT,
            VoiceModelLifecycleState.RECORDING,
            VoiceModelLifecycleState.COOLDOWN,
        )
        modelStatus.apply {
            text = if (modelReady) {
                "离线模型已就绪 · 音频不出设备"
            } else {
                "离线模型后台准备中 · 未启用联网识别"
            }
            textSize = 11f
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            tag = "voice-model-status"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(
            modelStatus,
            LayoutParams(LayoutParams.MATCH_PARENT, toPx(22)),
        )

        transcript.apply {
            text = "只需长按空格；松开自动上屏，上滑取消"
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(toPx(12), 0, toPx(12), 0)
            tag = "voice-transcript"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(
            transcript,
            LayoutParams(LayoutParams.MATCH_PARENT, toPx(52)),
        )

        waveform.apply {
            tag = "voice-waveform"
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(10) {
            val bar = View(context).apply {
                tag = "voice-wave-bar"
            }
            waves += bar
            waveform.addView(
                bar,
                LayoutParams(toPx(4), toPx(12)).apply {
                    marginEnd = toPx(5)
                },
            )
        }
        addView(
            waveform,
            LayoutParams(LayoutParams.MATCH_PARENT, toPx(52)),
        )

        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }
        languageButton = createButton(
            LANGUAGES[languageIndex].first,
            13f,
            true,
        ).apply {
            tag = "voice-language"
            setOnClickListener {
                onFeedback()
                languageIndex = (languageIndex + 1) % LANGUAGES.size
                onLanguageChanged(languageIndex)
                updateLanguagePresentation(locked = false)
            }
        }
        updateLanguagePresentation(locked = false)
        controls.addView(
            languageButton,
            LayoutParams(
                0,
                toPx(ImeGeometryTokens.VOICE_CONTROL_HEIGHT_DP),
                1f,
            ),
        )

        micButton = createButton("🎤", 18f, false).apply {
            tag = "voice-mic"
            isEnabled = false
            contentDescription = "语音状态，当前未开始，仅支持长按空格启动"
        }
        controls.addView(
            micButton,
            LayoutParams(
                toPx(ImeGeometryTokens.VOICE_CONTROL_HEIGHT_DP),
                toPx(ImeGeometryTokens.VOICE_CONTROL_HEIGHT_DP),
            ),
        )

        gestureHint = createButton("长按空格开始", 13f, true).apply {
            tag = "voice-gesture-hint"
            isEnabled = false
            contentDescription = "长按空格开始语音，松开自动上屏，上滑取消"
        }
        controls.addView(
            gestureHint,
            LayoutParams(
                0,
                toPx(ImeGeometryTokens.VOICE_CONTROL_HEIGHT_DP),
                1f,
            ),
        )
        addView(
            controls,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.VOICE_CONTROL_HEIGHT_DP),
            ),
        )
    }

    fun selectedLanguageCode(): String =
        LANGUAGES[languageIndex].second

    fun refreshLanguageControl(locked: Boolean) {
        languageButton.isEnabled = !locked
        languageButton.isClickable = !locked
        languageButton.alpha = if (locked) 0.52f else 1f
        updateLanguagePresentation(locked)
    }

    fun setMicState(icon: String, description: String) {
        micButton.text = icon
        micButton.contentDescription = description
    }

    fun setGestureHint(label: String, description: String) {
        gestureHint.text = label
        gestureHint.contentDescription = description
    }

    fun updateWaveformHeight(heightPx: Int) {
        if (!waveform.isShown) return
        waves.forEach { bar ->
            if (bar.layoutParams.height != heightPx) {
                bar.layoutParams = bar.layoutParams.apply {
                    height = heightPx
                }
            }
        }
    }

    private fun updateLanguagePresentation(locked: Boolean) {
        val selected = LANGUAGES[languageIndex].first
        languageButton.text = selected
        languageButton.contentDescription = if (locked) {
            "语音语言：$selected，识别进行中不可切换"
        } else {
            "语音语言：$selected，点击切换"
        }
        if (Build.VERSION.SDK_INT >= 30) {
            languageButton.stateDescription = if (locked) {
                "当前$selected，识别进行中不可切换"
            } else {
                "当前$selected"
            }
        }
    }

    companion object {
        val LANGUAGES = listOf(
            "普通话" to "zh-CN",
            "英文" to "en-US",
        )
    }
}
