package llc.slacker.openime

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Temporarily silences media playback while the IME owns the microphone.
 *
 * The original volume and mute bit are captured once per recording session.
 * The controller is intentionally idempotent because a long-press, the
 * recognition backend, and an input-editor switch can all close the same
 * session through different callbacks.
 */
internal class VoiceMediaMuteController(context: Context) {
    companion object {
        private const val TAG = "OpenImeVoiceMedia"
    }

    private data class Snapshot(
        val volume: Int,
        val muted: Boolean,
    )

    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var snapshot: Snapshot? = null

    @Synchronized
    fun mute(): Boolean {
        if (snapshot != null) return true
        val manager = audioManager ?: return false
        val baseline = runCatching {
            Snapshot(
                volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC),
                muted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    manager.isStreamMute(AudioManager.STREAM_MUSIC)
                } else {
                    false
                },
            )
        }.getOrElse {
            Log.w(TAG, "snapshotFailed", it)
            return false
        }
        snapshot = baseline
        return runCatching {
            if (!baseline.muted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    manager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE,
                        AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    manager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        0,
                        AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE,
                    )
                }
            }
            true
        }.getOrElse {
            Log.w(TAG, "muteFailed", it)
            false
        }
    }

    @Synchronized
    fun restore() {
        val baseline = snapshot ?: return
        snapshot = null
        val manager = audioManager ?: return
        runCatching {
            // Restore the numeric volume without producing a volume beep, then
            // restore the mute bit exactly as it was before recording started.
            manager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                baseline.volume,
                AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val currentlyMuted = manager.isStreamMute(AudioManager.STREAM_MUSIC)
                if (baseline.muted && !currentlyMuted) {
                    manager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE,
                        AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE,
                    )
                } else if (!baseline.muted && currentlyMuted) {
                    manager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_UNMUTE,
                        AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE,
                    )
                }
            }
        }.onFailure {
            Log.w(TAG, "restoreFailed", it)
        }
    }
}
