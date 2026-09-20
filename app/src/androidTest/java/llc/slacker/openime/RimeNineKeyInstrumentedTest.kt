package llc.slacker.openime

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RimeNineKeyInstrumentedTest {

    @Test
    fun nativeRimeDecodesNineKeyDigitsInNormalAndFuzzySchemas() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalFuzzy = ImeSettingsRepository.loadFuzzy(context)
        ImeSettingsRepository.saveFuzzy(context, false)
        val rime = RimeEngine(context)
        try {
            rime.start()
            // The first run deploys the bundled dictionaries on-device. Cold
            // CI emulators can take over ten minutes for that one-time
            // compile; keep the wait bounded without mistaking slow deployment
            // for a schema failure.
            val startupTimeoutMs = 900_000L
            val deadline = SystemClock.elapsedRealtime() + startupTimeoutMs
            while (!rime.isReady && rime.errorMessage.isBlank() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100L)
            }
            assertTrue(
                "librime failed to start within ${startupTimeoutMs}ms: ${rime.errorMessage}",
                rime.isReady,
            )

            val continuous = rime.candidates("64426")
            assertTrue("64426 should resolve 你好, got ${continuous.take(12)}", "你好" in continuous)

            val segmented = rime.candidates("64'426")
            assertTrue("64'426 should resolve 你好, got ${segmented.take(12)}", "你好" in segmented)

            ImeSettingsRepository.saveFuzzy(context, true)
            rime.invalidateSettingsCache()
            val fuzzy = rime.candidates("64426")
            assertTrue(
                "fuzzy schema must keep native T9 decoding for 64426, got ${fuzzy.take(12)}",
                "你好" in fuzzy,
            )
        } finally {
            ImeSettingsRepository.saveFuzzy(context, originalFuzzy)
            rime.invalidateSettingsCache()
            rime.shutdown()
        }
    }
}
