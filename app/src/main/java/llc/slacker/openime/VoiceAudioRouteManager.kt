package llc.slacker.openime

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps device routing policy separate from capture and ASR inference. */
class VoiceAudioRouteManager(context: Context) {
    companion object {
        private const val TAG = "OpenImeVoiceRoute"
    }

    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun beginSession(): Session {
        val previousMode = audioManager.mode
        if (Build.VERSION.SDK_INT < 31) {
            // VOICE_RECOGNITION follows the system-selected wired/SCO route on
            // old Android versions. Do not force SCO and introduce a 500 ms
            // startup gap; keep the policy isolated here for later tuning.
            Log.i(TAG, "routeSession api=${Build.VERSION.SDK_INT} managed=false")
            return Session(onClose = {})
        }

        val previousDevice = runCatching { audioManager.communicationDevice }.getOrNull()
        val preferred = if (previousDevice == null) preferredExternalDevice() else null
        var changedMode = false
        var changedDevice = false
        if (preferred != null) {
            runCatching {
                if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    changedMode = true
                }
                changedDevice = audioManager.setCommunicationDevice(preferred)
            }.onFailure {
                Log.w(TAG, "routeSelectionFailed type=${preferred.type}")
            }
        }
        val activeType = runCatching { audioManager.communicationDevice?.type ?: 0 }.getOrDefault(0)
        Log.i(TAG, "routeSession api=${Build.VERSION.SDK_INT} type=$activeType managed=$changedDevice")

        return Session {
            if (changedDevice) {
                runCatching {
                    if (previousDevice != null) {
                        audioManager.setCommunicationDevice(previousDevice)
                    } else {
                        audioManager.clearCommunicationDevice()
                    }
                }
            }
            if (changedMode) runCatching { audioManager.mode = previousMode }
        }
    }

    private fun preferredExternalDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < 31) return null
        val priority = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
        )
        val devices = runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
        return priority.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
    }

    class Session internal constructor(private val onClose: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) onClose()
        }
    }
}
