package llc.slacker.openime.voice

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import llc.slacker.openime.R
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.theme.ImeDrawableFactory
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeTheme
import llc.slacker.openime.theme.ImeTypographyTokens

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
    private val englishButton: TextView
    private val face: VoiceFaceView
    private val hero: FrameLayout
    private val gestureHint: TextView

    private var languageIndex =
        initialLanguageIndex.coerceIn(0, LANGUAGES.lastIndex)

    init {
        tag = "voice-panel"
        orientation = VERTICAL
        setPadding(toPx(12), toPx(14), toPx(12), toPx(10))
        gravity = Gravity.CENTER_HORIZONTAL
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val palette = ImeTheme.IOS.tokens(ImeSettingsRepository.loadAppearance(context), dark)
        val modelReady = initialModelState in setOf(VoiceModelLifecycleState.HOT, VoiceModelLifecycleState.RECORDING, VoiceModelLifecycleState.COOLDOWN)
        modelStatus.apply {
            text = if (modelReady) "离线模型已就绪 · 音频不出设备" else "离线模型准备中 · 音频不出设备"
            textSize = ImeTypographyTokens.SMALL_SP; gravity = Gravity.CENTER; tag = "voice-model-status"
            includeFontPadding = false; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        transcript.apply {
            tag = "voice-transcript"; visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(transcript, LayoutParams(0, 0))
        // The tag of the disc is what the panel's tests and the design reference
        // run look for; the character inside it answers to "voice-face", which is
        // what the theme pass repaints.
        hero = FrameLayout(context).apply {
            tag = "voice-mic"
            background = ImeDrawableFactory.rounded(ImeDrawableFactory.blend(palette.primary, palette.keyboardBackground, VoiceFaceView.HALO_MIX), toPx(ImeGeometryTokens.PILL_RADIUS_DP))
            contentDescription = "语音状态，长按空格开始"
        }
        face = VoiceFaceView(context, toPx).apply { applyTokens(palette) }
        hero.addView(face, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        addView(hero, LayoutParams(toPx(88), toPx(88)).apply { bottomMargin = toPx(14) })
        languageButton = createButton(LANGUAGES[0].first, ImeTypographyTokens.BODY_SP, true).apply {
            tag = "voice-language"
            setOnClickListener { languageIndex = 0; onLanguageChanged(languageIndex); updateLanguagePresentation(false) }
        }
        val languageTrack = LinearLayout(context).apply {
            orientation = HORIZONTAL; tag = "segmented-track"; setPadding(toPx(2), toPx(2), toPx(2), toPx(2))
        }
        languageTrack.addView(languageButton, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        englishButton = TextView(context).apply {
            text = LANGUAGES[1].first; textSize = ImeTypographyTokens.BODY_SP; gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            setOnClickListener {
                if (!languageButton.isEnabled) return@setOnClickListener
                onFeedback(); languageIndex = 1; onLanguageChanged(languageIndex)
                updateLanguagePresentation(false)
            }
        }
        languageTrack.addView(englishButton, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(languageTrack, LayoutParams(toPx(200), toPx(34)).apply { bottomMargin = toPx(14) })
        gestureHint = createButton("长按说话 · 松开上屏 · 上滑取消", ImeTypographyTokens.BODY_SP, false).apply {
            tag = "voice-gesture-hint"; isClickable = false; isFocusable = false
            background = null
            typeface = android.graphics.Typeface.DEFAULT
        }
        val hintRow = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(toPx(12), 0, toPx(12), 0)
            background = ImeDrawableFactory.rounded(palette.toolCardBackground, toPx(12))
            addView(TextView(context).apply {
                text = "空格"; textSize = ImeTypographyTokens.BODY_SP; gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = ImeDrawableFactory.rounded(palette.toolCardBackground, toPx(8), palette.border, toPx(1))
            }, LayoutParams(toPx(44), toPx(28)).apply { marginEnd = toPx(10) })
            addView(gestureHint, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(hintRow, LayoutParams(LayoutParams.MATCH_PARENT, toPx(44)).apply { marginStart = toPx(34); marginEnd = toPx(34); bottomMargin = toPx(12) })
        addView(modelStatus, LayoutParams(LayoutParams.MATCH_PARENT, toPx(22)))
        updateLanguagePresentation(false)
    }

    fun selectedLanguageCode(): String =
        LANGUAGES[languageIndex].second

    fun refreshLanguageControl(locked: Boolean) {
        englishButton.isEnabled = !locked
        englishButton.isClickable = !locked
        englishButton.alpha = if (locked) ImeSurfacePolicy.DISABLED_ALPHA else 1f
        languageButton.isEnabled = !locked
        languageButton.isClickable = !locked
        languageButton.alpha = if (locked) ImeSurfacePolicy.DISABLED_ALPHA else 1f
        updateLanguagePresentation(locked)
    }

    /**
     * Shows one step of the voice session: the character plays [state] and [description]
     * becomes what a screen reader reads for it.
     */
    fun setFaceState(state: VoiceFaceState, description: String) {
        face.setState(state)
        hero.contentDescription = description
    }

    /** Hands the microphone level of a listening session to the character. */
    fun setVoiceLevel(level: Float) {
        face.setLevel(level)
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
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val palette = ImeTheme.IOS.tokens(ImeSettingsRepository.loadAppearance(context), dark)
        listOf(languageButton, englishButton).forEachIndexed { index, button ->
            val active = index == languageIndex
            button.tag = if (active) "segment-selected" else "segment-option"
            button.setTextColor(if (active) palette.keyText else palette.keySecondaryText)
            button.typeface = if (active) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            button.background = if (active) ImeDrawableFactory.rounded(palette.toolCardBackground, toPx(10), palette.border, toPx(1)) else null
            button.isSelected = active
        }
        englishButton.contentDescription = if (locked) "语音语言：英文，识别进行中不可切换" else "语音语言：英文"
        languageButton.contentDescription = if (locked) {
            "语音语言：普通话，识别进行中不可切换"
        } else {
            "语音语言：普通话"
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
