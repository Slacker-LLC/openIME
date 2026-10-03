package llc.slacker.openime

import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.theme.ImeTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ImeThemePersistenceTest {
    @Test
    fun legacyThemeValuesFallBackToIos() {
        listOf("DARK", "CYBERPUNK", "CLASSIC", "MACOS", "unknown", null).forEach { stored ->
            assertEquals(ImeTheme.IOS, ImeSettingsRepository.parseTheme(stored))
        }
    }

    @Test
    fun iosThemeValueStillRestoresNormally() {
        assertEquals(ImeTheme.IOS, ImeSettingsRepository.parseTheme("IOS"))
    }
}
