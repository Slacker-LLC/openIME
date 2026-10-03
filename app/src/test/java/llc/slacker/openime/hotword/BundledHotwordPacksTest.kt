package llc.slacker.openime.hotword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledHotwordPacksTest {
    private val files: List<File> = sequenceOf("src/main/assets/hotwords", "app/src/main/assets/hotwords")
        .map(::File).first { it.isDirectory }.listFiles { f -> f.extension == "txt" }!!.sortedBy { it.name }

    @Test
    fun everyBundledPackIsWellFormedAndNonTrivial() {
        assertTrue("expected bundled packs", files.size >= 3)
        files.forEach { file ->
            val parsed = HotwordParser.parse(file.readText(Charsets.UTF_8), file.nameWithoutExtension)
            assertEquals("${file.name} must not contain unusable lines", 0, parsed.rejectedLines)
            assertFalse("${file.name} must not hit the word cap", parsed.truncated)
            assertTrue("${file.name} needs a title", parsed.title != file.nameWithoutExtension)
            assertTrue("${file.name} is too small", parsed.words.size >= 30)
        }
    }

    @Test
    fun gamePackIsOptInWhileGeneralPacksAreOnByDefault() {
        val byName = files.associate {
            it.nameWithoutExtension to HotwordParser.parse(it.readText(Charsets.UTF_8), it.name)
        }
        assertFalse("homophone swaps for game slang must not surprise everyday chat", byName.getValue("games").defaultEnabled)
        assertTrue(byName.getValue("tech").defaultEnabled)
        assertTrue(byName.getValue("apps").defaultEnabled)
    }

    @Test
    fun noWordIsListedInTwoBundledPacksWithDifferentIntent() {
        val seen = HashMap<String, String>()
        files.forEach { file ->
            HotwordParser.parse(file.readText(Charsets.UTF_8), file.name).words.forEach { word ->
                val previous = seen.put(word, file.name)
                assertTrue("$word appears in both $previous and ${file.name}", previous == null)
            }
        }
    }
}
