package llc.slacker.openime

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import llc.slacker.openime.core.FuzzyRule
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.rime.RimeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Each 模糊音 switch reaches librime: the rules file changes and librime re-deploys. */
@RunWith(AndroidJUnit4::class)
class FuzzyRulesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun awaitReady(rime: RimeEngine) {
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        while (!rime.isReady && rime.errorMessage.isBlank() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100L)
        }
        assertTrue("librime not ready: ${rime.errorMessage}", rime.isReady)
    }

    /** Re-deploy and wait for it: isReady drops while librime restarts. */
    private fun redeploy(rime: RimeEngine) {
        rime.invalidateSettingsCache()
        rime.applyFuzzyRules()
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (rime.isReady && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20L)
        awaitReady(rime)
    }

    @Test
    fun eachSwitchChangesWhatLibrimeOffers() {
        val originalFuzzy = ImeSettingsRepository.loadFuzzy(context)
        val originalRules = ImeSettingsRepository.loadFuzzyRules(context)
        val userRules = File(context.filesDir, "rime-user/openime_fuzzy.yaml")
        ImeSettingsRepository.saveFuzzy(context, true)
        ImeSettingsRepository.saveFuzzyRules(context, setOf(FuzzyRule.Z_ZH))
        val rime = RimeEngine(context = context)
        try {
            rime.start()
            awaitReady(rime)
            assertTrue("rules file written", userRules.isFile)
            assertTrue("z = zh: zi offers 知", "知" in rime.candidates("zi"))
            assertFalse("an = ang is off: shan does not offer 上", "上" in rime.candidates("shan").take(30))

            ImeSettingsRepository.saveFuzzyRules(context, setOf(FuzzyRule.AN_ANG))
            redeploy(rime)
            assertFalse("z = zh off: zi no longer offers 知", "知" in rime.candidates("zi").take(30))
            assertTrue("an = ang: shan offers 上", "上" in rime.candidates("shan"))

            // Every pair: both widen at once.
            ImeSettingsRepository.saveFuzzyRules(context, FuzzyRule.entries.toSet())
            redeploy(rime)
            assertTrue("zi offers 知", "知" in rime.candidates("zi"))
            assertTrue("shan offers 上", "上" in rime.candidates("shan"))

            // Back to the shipped defaults: nothing fuzzy even with 模糊音 on.
            ImeSettingsRepository.saveFuzzyRules(context, FuzzyRule.DEFAULTS)
            redeploy(rime)
            assertEquals(FuzzyRule.rimeYaml(FuzzyRule.DEFAULTS), userRules.readText())
            assertFalse("defaults: zi does not offer 知", "知" in rime.candidates("zi").take(30))

            // 模糊音 off: the plain schema, nothing fuzzy.
            ImeSettingsRepository.saveFuzzy(context, false)
            rime.invalidateSettingsCache()
            assertFalse("fuzzy off: zi does not offer 知", "知" in rime.candidates("zi").take(30))
        } finally {
            ImeSettingsRepository.saveFuzzy(context, originalFuzzy)
            ImeSettingsRepository.saveFuzzyRules(context, originalRules)
            rime.shutdown()
        }
    }
}
