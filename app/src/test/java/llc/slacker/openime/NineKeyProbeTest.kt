package llc.slacker.openime

import llc.slacker.openime.candidate.CandidateEngine
import llc.slacker.openime.candidate.NineKeyLocalDecoder
import llc.slacker.openime.candidate.PinyinLexicon
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 临时探针（验证后删除）：打印九键解码在典型输入下的读数与候选。 */
class NineKeyProbeTest {
    private fun asset(name: String): File? = sequenceOf(
        File("src/main/assets/$name"),
        File("app/src/main/assets/$name"),
    ).firstOrNull { it.isFile }

    @Test
    fun probe() {
        val phrases = asset("pinyin_phrases.tsv")
        assumeTrue(phrases != null)
        val engine = CandidateEngine(emptyMap())
        val decoder = NineKeyLocalDecoder(engine)
        for (digits in listOf("94", "64", "9426", "94'26", "9536", "744", "242", "2536", "836", "94264", "64'426")) {
            val options = decoder.readingOptions(digits, null, 6)
            val digitsOfReadings = options.joinToString(" | ") { it.syllables.joinToString("'") }
            println("digits=$digits readings=[$digitsOfReadings]")
        }
        for (reading in listOf("ni", "hao", "xian", "zai", "xi'an")) {
            println("digitsFor($reading) = ${NineKeyLocalDecoder.digitsForPinyin(reading)}")
        }
    }
}
