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
        // librime is one per process. When this app is also the active IME its
        // service may already hold the session, and a second engine would reuse
        // it as deployed: take it down first so this engine deploys the rules.
        llc.slacker.openime.RimeNative.nativeShutdown()
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
            // The four pairs the reference carries beyond the eleven: librime
            // has to take their rules too, not only the Kotlin fallback.
            assertTrue("eng = ong: dong offers 灯", "灯" in rime.candidates("dong").take(40))
            assertTrue("un = ong: chun offers 冲", "冲" in rime.candidates("chun").take(40))
            assertTrue("huang = wang: wang offers 黄", "黄" in rime.candidates("wang").take(40))
            assertTrue("ei = ui: hui offers 飞", "飞" in rime.candidates("hui").take(40))

            // Back to the shipped defaults: the reference's own six pairs stay on
            // (z = zh among them), and the nine it does not default to fall away.
            ImeSettingsRepository.saveFuzzyRules(context, FuzzyRule.DEFAULTS)
            redeploy(rime)
            assertEquals(FuzzyRule.rimeYaml(FuzzyRule.DEFAULTS), userRules.readText())
            assertTrue("defaults: zi offers 知", "知" in rime.candidates("zi").take(30))
            assertFalse("defaults: hui does not offer 飞", "飞" in rime.candidates("hui").take(40))

            // 模糊音 off: the plain schema, nothing fuzzy.
            ImeSettingsRepository.saveFuzzy(context, false)
            rime.invalidateSettingsCache()
            assertFalse("fuzzy off: zi does not offer 知", "知" in rime.candidates("zi").take(30))
        } finally {
            ImeSettingsRepository.saveFuzzy(context, originalFuzzy)
            ImeSettingsRepository.saveFuzzyRules(context, originalRules)
            rime.shutdown()
            // Leave no prism built for these rules behind for later tests.
            userRules.delete()
            File(context.filesDir, "rime-user/build").listFiles().orEmpty().forEach { it.delete() }
        }
    }
}
