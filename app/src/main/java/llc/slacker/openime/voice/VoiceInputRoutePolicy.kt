package llc.slacker.openime.voice

import android.media.AudioDeviceInfo

/**
 * 使用耳机麦克风录音: which microphone voice input records from.
 *
 * The reference ships the switch as `enable_bluetooth_input` with its own
 * title and subtitle — `string/enable_bluetooth_input_show` 「使用耳机麦克风录音」
 * and `string/enable_bluetooth_input_show_sub` 「默认使用手机录音。佩戴耳机时，
 * 使用耳机麦克风」 — so the default is the phone's own microphone and a headset
 * is used only when the user asked for it.
 */
internal object VoiceInputRoutePolicy {

    /** The device types the reference calls 耳机, best first. */
    val HEADSET_TYPES = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
    )

    /**
     * The device type this session should record from, or null to leave the
     * route as the system has it. [available] holds the types the platform
     * offers for communication, [current] the type in use right now.
     *
     * With the switch on, the best available headset wins. With it off the
     * phone's own microphone wins, but only when something else had routed
     * recording to a headset: there is no reason to touch the route otherwise.
     */
    fun preferredType(available: List<Int>, current: Int?, preferHeadsetMic: Boolean): Int? {
        if (preferHeadsetMic) {
            return HEADSET_TYPES.firstOrNull { it in available }
        }
        if (current == null || current !in HEADSET_TYPES) return null
        return AudioDeviceInfo.TYPE_BUILTIN_MIC.takeIf { it in available }
    }
}
