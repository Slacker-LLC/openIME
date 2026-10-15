package llc.slacker.openime

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import llc.slacker.openime.data.ImeSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 按键音量 keeps the reference's own scale: `key_sound_volume` is an
 * IntPreference with `defaultValue=50`, so the stored percent is 0-100 and the
 * default is 50.
 */
@RunWith(AndroidJUnit4::class)
class KeySoundVolumeInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theVolumeDefaultsToHalfAndClampsWhatIsSaved() {
        context.getSharedPreferences("ime_settings", Context.MODE_PRIVATE)
            .edit().remove("key_sound_volume").commit()
        assertEquals(50, ImeSettingsRepository.loadKeySoundVolume(context))

        ImeSettingsRepository.saveKeySoundVolume(context, 120)
        assertEquals(100, ImeSettingsRepository.loadKeySoundVolume(context))
        ImeSettingsRepository.saveKeySoundVolume(context, -5)
        assertEquals(0, ImeSettingsRepository.loadKeySoundVolume(context))

        ImeSettingsRepository.saveKeySoundVolume(context, ImeSettingsRepository.SOUND_VOLUME_DEFAULT)
    }
}
