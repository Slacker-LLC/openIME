package llc.slacker.openime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import llc.slacker.openime.candidate.PoemIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 诗句联想 against the shipped table: the reference's poetry dictionary
 * (`sys_poem_gram`, `poem/lines.tsv`) has to be present in the APK and read
 * back the way it was written.
 */
@RunWith(AndroidJUnit4::class)
class PoemAssociationInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theShippedTableCarriesTheReferencesLines() {
        val index = PoemIndex.obtain(context)
        assertTrue("the poem table must ship whole, got ${index.size}", index.size > 5_000)

        // The line spelled out brings the line that follows it in its poem.
        assertEquals(
            listOf("春眠不觉晓", "处处闻啼鸟"),
            index.suggestionsFor("chunmianbujuexiao"),
        )
        assertEquals(
            listOf("海上生明月", "天涯共此时"),
            index.suggestionsFor("haishangshengmingyue"),
        )
        // Three syllables are enough; two are every day's typing.
        assertEquals(listOf("春眠不觉晓"), index.suggestionsFor("chunmianbu"))
        assertTrue(index.suggestionsFor("chunmian").isEmpty())
        assertTrue(index.suggestionsFor("haishang").isEmpty())
    }
}
