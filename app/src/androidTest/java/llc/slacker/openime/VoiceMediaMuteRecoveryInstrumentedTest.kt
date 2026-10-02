package llc.slacker.openime

import android.content.Context
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceMediaMuteRecoveryInstrumentedTest {

    @Test
    fun mediaVolumeComesBackAfterTheKeyboardProcessDiesWhileRecording() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val originalVolume = audio.getStreamVolume(stream)
        val originalMuted = audio.isStreamMute(stream)
        try {
            if (originalMuted) audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            val volume = audio.getStreamMaxVolume(stream).coerceAtMost(6).coerceAtLeast(2)
            audio.setStreamVolume(stream, volume, 0)

            assertTrue(VoiceMediaMuteController(context).mute())
            assertTrue("media must be silent while recording", audio.isStreamMute(stream) || audio.getStreamVolume(stream) == 0)

            // The process dies here: the controller above is never asked to restore().
            VoiceMediaMuteController.recoverAfterCrash(context)

            assertFalse("media must not stay muted after an unclean exit", audio.isStreamMute(stream))
            assertEquals(volume, audio.getStreamVolume(stream))
            // The recovery is one-shot: a second start must not touch the volume again.
            audio.setStreamVolume(stream, 1, 0)
            VoiceMediaMuteController.recoverAfterCrash(context)
            assertEquals(1, audio.getStreamVolume(stream))
        } finally {
            audio.setStreamVolume(stream, originalVolume, 0)
            if (originalMuted) audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
        }
    }
}
