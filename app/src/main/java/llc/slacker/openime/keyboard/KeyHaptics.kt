package llc.slacker.openime.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Key-press haptics: one short, full-strength click.
 *
 * `performHapticFeedback(KEYBOARD_TAP)` is the platform default, but since
 * Android 14 QPR3 the system plays it as PRIMITIVE_CLICK at the device's fixed
 * keyboard amplitude, which many phones tune low, and Gboard lost its own
 * strength slider to that system setting. Commercial IMEs (Sogou, Huawei 小艺,
 * HeliBoard's custom mode) drive the vibrator themselves instead. This does the
 * same with the crispest effect the actuator offers:
 *
 * - API 30+ with primitive support: PRIMITIVE_CLICK, scaled by [strengthPercent];
 * - otherwise with amplitude control: a 20 ms one-shot at that share of full amplitude;
 * - otherwise: API 29+ uses the actuator-tuned EFFECT_CLICK at full strength and a
 *   shorter pulse below it (motors without amplitude control only vary by time).
 *
 * The 震动强度 slider sets [strengthPercent] (10–100, like Sogou's and Huawei
 * 小艺's strength settings); 100 is the full click.
 *
 * The vibration is tagged as touch feedback, so the system "touch feedback"
 * intensity still applies; the in-app 触感震动 switch turns it off entirely.
 * A device without a vibrator falls back to the view's haptic feedback.
 */
internal class KeyHaptics(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** 10–100; the share of the full click. Changing it rebuilds the effect. */
    var strengthPercent: Int = MAX_STRENGTH
        set(value) {
            val bounded = value.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
            if (field == bounded) return
            field = bounded
            cachedClick = null
        }

    private var cachedClick: VibrationEffect? = null
    private val click: VibrationEffect?
        get() = cachedClick ?: buildClick().also { cachedClick = it }

    fun click(view: View) {
        val target = vibrator
        val effect = click
        if (target == null || effect == null || !target.hasVibrator()) {
            view.performHapticFeedback(FALLBACK_CONSTANT)
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                target.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                target.vibrate(effect, TOUCH_AUDIO_ATTRIBUTES)
            }
        }.onFailure { view.performHapticFeedback(FALLBACK_CONSTANT) }
    }

    private fun buildClick(): VibrationEffect? {
        val target = vibrator ?: return null
        val share = strengthPercent / 100f
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            target.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
        ) {
            return VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, share)
                .compose()
        }
        if (target.hasAmplitudeControl()) {
            return VibrationEffect.createOneShot(LEGACY_CLICK_MS, (255 * share).toInt().coerceIn(1, 255))
        }
        if (strengthPercent == MAX_STRENGTH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        }
        val durationMs = (MIN_PULSE_MS + (LEGACY_CLICK_MS - MIN_PULSE_MS) * share).toLong()
        return VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    companion object {
        const val MIN_STRENGTH = 10
        const val MAX_STRENGTH = 100
        private const val LEGACY_CLICK_MS = 20L
        private const val MIN_PULSE_MS = 6L

        /** KEYBOARD_TAP needs API 27; API 26 falls back to the virtual-key click. */
        private val FALLBACK_CONSTANT =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                HapticFeedbackConstants.KEYBOARD_TAP
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }

        private val TOUCH_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
