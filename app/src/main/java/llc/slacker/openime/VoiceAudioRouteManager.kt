package llc.slacker.openime

import android.annotation.TargetApi
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps one original baseline across overlapping sessions and allows only the
 * latest owner to restore it. Preparation and restoration run under the same
 * lock so a new owner cannot snapshot a half-restored route.
 */
internal class VoiceRouteOwnership<T : Any> {
    data class Lease(val owner: Long, val firstOwner: Boolean)

    private val lock = Any()
    private var generation = 0L
    private var baseline: T? = null

    fun acquire(prepareBaseline: () -> T): Lease = synchronized(lock) {
        generation += 1
        val firstOwner = baseline == null
        if (firstOwner) baseline = prepareBaseline()
        Lease(owner = generation, firstOwner = firstOwner)
    }

    fun release(owner: Long, restoreBaseline: (T) -> Unit): Boolean = synchronized(lock) {
        if (owner != generation) return@synchronized false
        val value = baseline ?: return@synchronized false
        // Invalidate this owner before restoration. A future acquire cannot
        // interleave because restoreBaseline still runs under this same lock.
        generation += 1
        baseline = null
        restoreBaseline(value)
        true
    }
}

/** Keeps device routing policy separate from capture and ASR inference. */
class VoiceAudioRouteManager(context: Context) {
    companion object {
        private const val TAG = "OpenImeVoiceRoute"
    }

    private data class RouteBaseline(
        val previousMode: Int,
        val previousDevice: AudioDeviceInfo?,
        val changedMode: Boolean,
        val changedDevice: Boolean,
    )

    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val ownership = VoiceRouteOwnership<RouteBaseline>()

    fun beginSession(): Session {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // VOICE_RECOGNITION follows the system-selected wired/SCO route on
            // old Android versions. Do not force SCO and introduce a 500 ms
            // startup gap; keep the policy isolated here for later tuning.
            Log.i(TAG, "routeSession api=${Build.VERSION.SDK_INT} managed=false")
            return Session(onClose = {})
        }

        val lease = ownership.acquire { prepareManagedRoute() }
        val activeType = runCatching { audioManager.communicationDevice?.type ?: 0 }.getOrDefault(0)
        Log.i(
            TAG,
            "routeSession api=${Build.VERSION.SDK_INT} type=$activeType firstOwner=${lease.firstOwner}",
        )

        return Session {
            val restored = ownership.release(lease.owner, ::restoreManagedRoute)
            if (!restored) {
                Log.d(TAG, "ignoreStaleRouteClose owner=${lease.owner}")
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun prepareManagedRoute(): RouteBaseline {
        val previousMode = audioManager.mode
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
        return RouteBaseline(
            previousMode = previousMode,
            previousDevice = previousDevice,
            changedMode = changedMode,
            changedDevice = changedDevice,
        )
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun restoreManagedRoute(baseline: RouteBaseline) {
        if (baseline.changedDevice) {
            runCatching {
                if (baseline.previousDevice != null) {
                    audioManager.setCommunicationDevice(baseline.previousDevice)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            }
        }
        if (baseline.changedMode) {
            runCatching { audioManager.mode = baseline.previousMode }
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun preferredExternalDevice(): AudioDeviceInfo? {
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
