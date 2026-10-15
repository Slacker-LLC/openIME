package llc.slacker.openime

import android.media.AudioDeviceInfo
import llc.slacker.openime.voice.VoiceInputRoutePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 使用耳机麦克风录音: which microphone voice input records from. */
class VoiceInputRoutePolicyTest {

    private val phone = AudioDeviceInfo.TYPE_BUILTIN_MIC
    private val bluetooth = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    private val ble = AudioDeviceInfo.TYPE_BLE_HEADSET
    private val wired = AudioDeviceInfo.TYPE_WIRED_HEADSET
    private val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

    @Test
    fun theSwitchOnTakesTheBestHeadsetThatIsThere() {
        assertEquals(
            ble,
            VoiceInputRoutePolicy.preferredType(listOf(phone, bluetooth, ble), phone, preferHeadsetMic = true),
        )
        assertEquals(
            bluetooth,
            VoiceInputRoutePolicy.preferredType(listOf(phone, bluetooth), phone, preferHeadsetMic = true),
        )
        assertEquals(
            wired,
            VoiceInputRoutePolicy.preferredType(listOf(phone, wired), phone, preferHeadsetMic = true),
        )
    }

    @Test
    fun theSwitchOnWithoutAHeadsetLeavesTheRouteAlone() {
        assertNull(VoiceInputRoutePolicy.preferredType(listOf(phone), phone, preferHeadsetMic = true))
        assertNull(VoiceInputRoutePolicy.preferredType(emptyList(), null, preferHeadsetMic = true))
    }

    @Test
    fun theSwitchOffKeepsThePhoneMicrophone() {
        // Recording was going to a headset (another app, or a previous session):
        // the reference's 「默认使用手机录音」 takes it back.
        assertEquals(
            phone,
            VoiceInputRoutePolicy.preferredType(listOf(phone, bluetooth), bluetooth, preferHeadsetMic = false),
        )
        assertEquals(
            phone,
            VoiceInputRoutePolicy.preferredType(listOf(phone, ble), ble, preferHeadsetMic = false),
        )
    }

    @Test
    fun theSwitchOffTouchesNothingThatIsAlreadyThePhone() {
        // Already on the phone's microphone: no reason to set the route again.
        assertNull(VoiceInputRoutePolicy.preferredType(listOf(phone), phone, preferHeadsetMic = false))
        // A speaker is not a microphone and not a headset.
        assertNull(VoiceInputRoutePolicy.preferredType(listOf(phone, speaker), speaker, preferHeadsetMic = false))
        assertNull(VoiceInputRoutePolicy.preferredType(listOf(phone, speaker), null, preferHeadsetMic = false))
    }
}
