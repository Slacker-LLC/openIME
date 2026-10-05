package llc.slacker.openime.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import llc.slacker.openime.data.KeySoundStyle

/**
 * Plays one key click in the chosen [style]. Bundled clicks go through a small
 * [SoundPool] tagged as UI sonification, so their loudness follows the system
 * sound volume. The system click uses the volume overload of
 * playSoundEffect, which plays even when the system "touch sounds" setting is
 * off; the in-app switch alone decides whether keys click.
 */
internal class KeySounds(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var pool: SoundPool? = null
    private val loaded = HashMap<KeySoundStyle, Int>()
    private val ready = HashSet<Int>()

    var style: KeySoundStyle = KeySoundStyle.SYSTEM
        set(value) {
            field = value
            if (value != KeySoundStyle.SYSTEM) ensureLoaded(value)
        }

    fun play() {
        val current = style
        if (current == KeySoundStyle.SYSTEM) {
            runCatching { audioManager?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, DEFAULT_VOLUME) }
            return
        }
        val sample = ensureLoaded(current) ?: return
        // A click requested before its sample finished decoding is dropped,
        // not queued: a late click would sound like lag.
        if (sample in ready) pool?.play(sample, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        pool?.release()
        pool = null
        loaded.clear()
        ready.clear()
    }

    private fun ensureLoaded(target: KeySoundStyle): Int? {
        loaded[target]?.let { return it }
        val soundPool = pool ?: SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
            .also { created ->
                created.setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0) ready += sampleId
                }
                pool = created
            }
        return runCatching { soundPool.load(appContext, target.rawRes, 1) }
            .getOrNull()
            ?.also { loaded[target] = it }
    }

    private companion object {
        /** Fast typing overlaps a few clicks; more would only pile up noise. */
        const val MAX_STREAMS = 4

        /** playSoundEffect's "use the default UI click volume" value. */
        const val DEFAULT_VOLUME = -1f
    }
}
