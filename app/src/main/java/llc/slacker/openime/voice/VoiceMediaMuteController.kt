package llc.slacker.openime.voice

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
        private const val PREFS = "openime_voice_media_mute"

        /** No recording session lasts this long; if nobody restored the volume by then, do it. */
        private const val MAX_MUTE_MS = 2 * 60 * 1000L

        /**
         * The original volume is also written to disk while media is muted. If the
         * keyboard process dies mid-recording (crash, low-memory kill, force stop)
         * nothing in memory can undo the mute, and the user's music and video stay
         * silent until they notice. The next start puts the volume back.
         */
        fun recoverAfterCrash(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("pending", false)) return
            val controller = VoiceMediaMuteController(context)
            controller.snapshot = Snapshot(prefs.getInt("volume", -1), prefs.getBoolean("muted", false))
                .takeIf { it.volume >= 0 }
            controller.restore()
            prefs.edit().clear().commit()
            Log.w(TAG, "restored media volume after an unclean exit")
        }
    }

    private data class Snapshot(
        val volume: Int,
        val muted: Boolean,
    )

    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable { restore() }
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
        prefs.edit()
            .putBoolean("pending", true)
            .putInt("volume", baseline.volume)
            .putBoolean("muted", baseline.muted)
            .commit()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, MAX_MUTE_MS)
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
        handler.removeCallbacks(watchdog)
        prefs.edit().clear().commit()
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
        Log.i(TAG, "mediaRestored")
    }
}
