package llc.slacker.openime.hotword

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Bundled packs, user import, switches and the real correction path on a device. */
@RunWith(AndroidJUnit4::class)
class HotwordPacksInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = HotwordPackStore(context)
    private val created = mutableListOf<HotwordPack>()

    @Before
    fun cleanSlate() {
        store.packs().filter { it.origin == HotwordPack.Origin.IMPORTED }.forEach(store::delete)
        context.getSharedPreferences("hotword_packs", 0).edit().clear().apply()
        HotwordRuntime.reload(context)
    }

    @After
    fun tearDown() {
        created.forEach(store::delete)
        context.getSharedPreferences("hotword_packs", 0).edit().clear().apply()
        HotwordRuntime.reload(context)
    }

    private fun tempFile(text: String): Uri {
        val file = File(context.cacheDir, "hotwords-test-${System.nanoTime()}.txt")
        file.writeText(text, Charsets.UTF_8)
        return Uri.fromFile(file)
    }

    private fun bundled(name: String): HotwordPack =
        store.packs().first { it.id == "bundled-$name" }

    @Test
    fun bundledPacksLoadWithTheirDefaultSwitches() {
        val ids = store.packs().filter { it.origin == HotwordPack.Origin.BUNDLED }.map { it.id }
        assertTrue(ids.containsAll(listOf("bundled-tech", "bundled-apps", "bundled-games")))
        assertTrue(store.isEnabled(bundled("tech")))
        assertFalse("game slang is opt-in", store.isEnabled(bundled("games")))
    }

    @Test
    fun switchingAPackChangesWhatTheVoicePathCorrects() {
        assertEquals("他是大爷", HotwordRuntime.apply("他是大爷"))

        store.setEnabled(bundled("games"), true)
        HotwordRuntime.reload(context)
        assertEquals("他是打野", HotwordRuntime.apply("他是大爷"))

        store.setEnabled(bundled("games"), false)
        HotwordRuntime.reload(context)
        assertEquals("他是大爷", HotwordRuntime.apply("他是大爷"))
    }

    @Test
    fun enabledPacksAlsoRankWhileTyping() {
        val before = listOf("大爷", "大爷们")
        assertEquals("a disabled pack must not touch typing", before, HotwordRuntime.boost("daye", before))

        store.setEnabled(bundled("games"), true)
        HotwordRuntime.reload(context)
        assertEquals(listOf("大爷", "打野", "大爷们"), HotwordRuntime.boost("daye", before))
        assertEquals("other input is untouched", before, HotwordRuntime.boost("nihao", before))
    }

    @Test
    fun importedListIsStoredPrivatelyAndStartsEnabled() {
        val result = store.importFrom(tempFile("# title: 我的词表\n# 注释\n对抗路\nopenIME\n"))
        val imported = result as HotwordPackStore.ImportResult.Imported
        created += imported.pack
        assertEquals("我的词表", imported.pack.title)
        assertEquals(listOf("对抗路"), imported.pack.words)
        assertEquals(1, imported.pack.rejectedLines)
        assertTrue(store.isEnabled(imported.pack))
        assertTrue(File(context.filesDir, "hotwords/${imported.pack.id}.txt").isFile)

        HotwordRuntime.reload(context)
        assertEquals("走队对抗路", HotwordRuntime.apply("走队对抗陆"))
    }

    @Test
    fun importWithoutUsableWordsFailsAndStoresNothing() {
        val before = store.packs().size
        val result = store.importFrom(tempFile("# only comments\nab\n x \n"))
        assertTrue(result is HotwordPackStore.ImportResult.Failed)
        assertEquals(before, store.packs().size)
    }

    @Test
    fun oversizedImportIsRefused() {
        val big = "打野\n".repeat(HotwordParser.MAX_BYTES / 4 + 100)
        val result = store.importFrom(tempFile(big))
        assertTrue(result is HotwordPackStore.ImportResult.Failed)
    }

    @Test
    fun deleteRemovesImportsButNeverBundledPacks() {
        val imported = (store.importFrom(tempFile("# title: 临时\n打野\n")) as HotwordPackStore.ImportResult.Imported).pack
        assertTrue(store.delete(imported))
        assertTrue(store.packs().none { it.id == imported.id })
        assertFalse(store.delete(bundled("tech")))
        assertNotNull(bundled("tech"))
    }
}
