package llc.slacker.openime

import llc.slacker.openime.engine.BigramIndex
import llc.slacker.openime.engine.LexiconIndex
import llc.slacker.openime.engine.PinyinEngine
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel

/**
 * Dumps the engine's candidate order for a list of readings so it can be
 * compared with the reference lexicon's own order (the volume run's readings).
 *
 * The comparison inputs are local, not part of the repository:
 * `build/imengine-compare/readings.tsv` (reading, expected word). Without it the
 * test skips. Run scripts/build_imengine_data.py first for the tables.
 */
class PinyinEngineRankCompareTest {

    private fun engineAsset(name: String): File? = sequenceOf(
        File("src/main/assets/imengine/$name"),
        File("app/src/main/assets/imengine/$name"),
    ).firstOrNull { it.isFile }

    private fun map(file: File) =
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        }

    @Test
    fun dumpCandidateOrderForTheVolumeReadings() {
        val lex = engineAsset("lex.bin")
        assumeTrue("imengine/lex.bin not built", lex != null)
        val name = System.getenv("OPENIME_COMPARE_READINGS") ?: "readings.tsv"
        val readings = sequenceOf(
            File("../build/imengine-compare/$name"),
            File("build/imengine-compare/$name"),
        ).firstOrNull { it.isFile }
        assumeTrue("build/imengine-compare/$name absent", readings != null)

        val bigram = engineAsset("bigram.bin")?.let { BigramIndex(map(it)) }
        val engine = PinyinEngine(LexiconIndex(map(lex!!)), bigram)
        val out = File(readings!!.parentFile, "ours.tsv")
        val reference = File(readings.parentFile, "doubao.tsv")
        // The reference's own order for the same readings, when it was dumped.
        val referenceOrder = if (reference.isFile) {
            reference.readLines().filter { it.isNotBlank() }.associate { line ->
                val (reading, _, top) = line.split("\t")
                reading to top.split("|").filter { it.isNotEmpty() }
            }
        } else {
            emptyMap()
        }
        var n = 0
        var agreed = 0
        val emptyWithReference = ArrayList<String>()
        out.bufferedWriter().use { writer ->
            readings.readLines().filter { it.isNotBlank() }.forEach { line ->
                val (reading, word) = line.split("\t")
                val top = engine.candidates(reading, limit = 6)
                writer.write("$reading\t$word\t${top.joinToString("|")}\n")
                referenceOrder[reading]?.takeIf { it.isNotEmpty() }?.let { expected ->
                    if (top.isEmpty()) {
                        emptyWithReference += reading
                    } else if (top.first() == expected.first()) {
                        agreed++
                    }
                }
                n++
            }
        }
        println("rank compare: wrote $n readings to ${out.absolutePath}")
        if (referenceOrder.isNotEmpty()) {
            println("rank compare: first candidate matches the reference for $agreed readings")
            assertTrue(
                "readings with a word but no candidate: $emptyWithReference",
                emptyWithReference.isEmpty(),
            )
        }
    }
}
