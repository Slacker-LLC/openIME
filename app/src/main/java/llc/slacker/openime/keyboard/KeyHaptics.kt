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
import llc.slacker.openime.data.HapticStyle

/**
 * Key-press haptics: one short click in the chosen [HapticStyle].
 *
 * `performHapticFeedback(KEYBOARD_TAP)` is the platform default, but since
 * Android 14 QPR3 the system plays it as PRIMITIVE_CLICK at the device's fixed
 * keyboard amplitude, which many phones tune low, and Gboard lost its own
 * strength slider to that system setting. Commercial IMEs (Sogou, Huawei 小艺,
 * HeliBoard's custom mode) drive the vibrator themselves instead. This does the
 * same with the crispest effect the actuator offers:
 *
 * - API 30+ with primitive support: the style's primitive, scaled by [strengthPercent];
 * - otherwise with amplitude control: a short one-shot at that share of full amplitude;
 * - otherwise: API 29+ uses the actuator-tuned predefined effect at full strength and a
 *   shorter pulse below it (motors without amplitude control only vary by time).
 *
 * Long pulses ring on linear motors such as the X-axis motor in Xiaomi phones,
 * which feels muddy; the one-shot fallbacks are kept to 8–12 ms for that reason.
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

    var style: HapticStyle = HapticStyle.CRISP
        set(value) {
            if (field == value) return
            field = value
            cachedClick = null
        }

    private var cachedClick: VibrationEffect? = null
    private val click: VibrationEffect?
        get() = cachedClick ?: buildClick().also { cachedClick = it }

    fun click(view: View) {
        if (style == HapticStyle.SYSTEM) {
            view.performHapticFeedback(FALLBACK_CONSTANT)
            return
        }
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
        val crisp = style == HapticStyle.CRISP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val primitive =
                if (crisp) VibrationEffect.Composition.PRIMITIVE_TICK else VibrationEffect.Composition.PRIMITIVE_CLICK
            if (target.areAllPrimitivesSupported(primitive)) {
                return VibrationEffect.startComposition().addPrimitive(primitive, share).compose()
            }
        }
        val pulseMs = if (crisp) CRISP_PULSE_MS else FIRM_PULSE_MS
        if (target.hasAmplitudeControl()) {
            return VibrationEffect.createOneShot(pulseMs, (255 * share).toInt().coerceIn(1, 255))
        }
        if (strengthPercent == MAX_STRENGTH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return VibrationEffect.createPredefined(if (crisp) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK)
        }
        val durationMs = (MIN_PULSE_MS + (pulseMs - MIN_PULSE_MS) * share).toLong()
        return VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    companion object {
        const val MIN_STRENGTH = 10
        const val MAX_STRENGTH = 100
        private const val CRISP_PULSE_MS = 8L
        private const val FIRM_PULSE_MS = 12L
        private const val MIN_PULSE_MS = 4L

        /** One line for 关于 → 诊断: what this phone's vibrator can do. */
        fun describe(context: Context): String {
            val vibrator: Vibrator? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            if (vibrator == null || !vibrator.hasVibrator()) return "振动马达：未检测到"
            val parts = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val tick = vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
                val click = vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
                parts += when {
                    tick && click -> "支持 TICK/CLICK 原语"
                    click -> "支持 CLICK 原语"
                    tick -> "支持 TICK 原语"
                    else -> "不支持原语"
                }
            }
            parts += if (vibrator.hasAmplitudeControl()) "可调振幅" else "不可调振幅"
            return "振动马达：" + parts.joinToString("，") + "（" + Build.MANUFACTURER + " " + Build.MODEL + "）"
        }

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
