package llc.slacker.openime.voice

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import com.lollipop.grokbot.GrokBotConfig
import com.lollipop.grokbot.GrokBotView
import com.lollipop.grokbot.GrokMode
import com.lollipop.grokbot.GrokMood
import llc.slacker.openime.theme.ImeTheme

/**
 * What the voice panel's character is doing, one state per step of a session.
 *
 * The states are the voice product's vocabulary - a session that is preparing,
 * listening, thinking, finished - and each one carries the [GrokMood] that
 * shows it.
 */
internal enum class VoiceFaceState(internal val mood: GrokMood) {
    /** Waiting for the space-bar gesture. */
    IDLE(GrokMood.IDLE),

    /** The microphone and the model are coming up, nothing is heard yet. */
    PREPARING(GrokMood.LOADING),

    /** Capturing speech. */
    LISTENING(GrokMood.LISTENING),

    /** The finger was lifted; the audio is being transcribed. */
    THINKING(GrokMood.THINKING),

    /** A transcript was committed to the editor. */
    DONE(GrokMood.CELEBRATE),

    /** The session ended without any speech. */
    EMPTY(GrokMood.CONFUSED),

    /** The finger moved up, so releasing would throw the session away. */
    CANCELLING(GrokMood.SURPRISED),

    /** The session was thrown away. */
    CANCELLED(GrokMood.SHY),

    /** Recognition failed. */
    ERROR(GrokMood.ALERTING),
}

/**
 * The face of the voice panel.
 *
 * It owns a [GrokBotView] and drives it from the states of a voice session, so
 * the character is the panel's status display: it loads while the model does,
 * listens while the microphone does, thinks while the transcript is decoded and
 * celebrates when the text reaches the editor.
 *
 * While a session listens, the microphone level is handed to [setLevel], and
 * the character hops on the onsets of speech, one hop per syllable as it is
 * heard. That is the only thing here that touches the audio path, and it is
 * rate limited, so a loud room cannot turn the face into a strobe.
 */
internal class VoiceFaceView(
    context: Context,
    private val toPx: (Int) -> Int,
) : FrameLayout(context) {

    val face = GrokBotView(context)

    private var state = VoiceFaceState.IDLE
    private val levels = VoiceFaceLevelPolicy()

    /**
     * Whether the platform animates at all, read once: the engine takes
     * `reduceMotion` when it is built and cannot switch it afterwards.
     */
    private val reduceMotion = !animationsEnabled(context)

    init {
        tag = "voice-face"
        addView(
            face,
            LayoutParams(toPx(FACE_SIZE_DP), toPx(FACE_SIZE_DP), Gravity.CENTER),
        )
    }

    /** Shows [state] on the face. */
    fun setState(state: VoiceFaceState) {
        if (this.state == state) return
        this.state = state
        face.botState.show(state.mood)
        levels.reset()
        if (state == VoiceFaceState.DONE) face.botState.burst()
    }

    /**
     * Hands one microphone level of a listening session to the face; levels are
     * the normalised RMS of the capture, so speech sits around `0.06` and above
     * and room noise below it.
     */
    fun setLevel(level: Float) {
        if (state != VoiceFaceState.LISTENING) return
        if (levels.onLevel(level, SystemClock.uptimeMillis())) face.botState.bounce()
    }

    /**
     * Paints the character in [tokens].
     *
     * Called once when the panel is built and again on every theme change.
     * `reduceMotion` is deliberately not part of the palette: the engine reads
     * it when it is created, so it is fixed for the life of this view.
     */
    fun applyTokens(tokens: ImeTheme.Tokens) {
        face.config = GrokBotConfig(
            mode = GrokMode.HOLD,
            mood = state.mood,
            followPointer = false,
            reduceMotion = reduceMotion,
            sizePx = toPx(FACE_SIZE_DP),
            flatInk = tokens.primary,
            eyeColor = tokens.keyboardBackground,
            badgeColor = tokens.primary,
        )
    }

    companion object {
        /**
         * How much of the accent the disc behind the character is tinted with,
         * the `blend` the panel builds it from and the theme pass repeats.
         */
        internal const val HALO_MIX = 0.16f

        /** Side of the face, inside the panel's disc. */
        private const val FACE_SIZE_DP = 64

        /**
         * Whether the platform is set to animate. `ANIMATOR_DURATION_SCALE` is
         * what the accessibility "remove animations" switch and the developer
         * animation scale both write.
         */
        private fun animationsEnabled(context: Context): Boolean = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        }.getOrDefault(true)
    }
}
