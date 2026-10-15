package llc.slacker.openime

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 五笔方案的结构验收：参考键盘的五笔设置里「四码唯一直接上屏」默认打开，
 * 对应 librime speller 的 auto_select；字根码最长四码，所以 max_code_length 是 4。
 */
class WubiSchemaTest {

    private fun asset(relative: String): File = sequenceOf(
        File("src/main/assets/$relative"),
        File("app/src/main/assets/$relative"),
    ).firstOrNull { it.isFile } ?: error("missing test asset: $relative")

    @Test
    fun theWubiSchemaCommitsAUniqueFourCodeCandidate() {
        val text = asset("rime-data/wubi86.schema.yaml").readText()
        assertTrue("missing schema_id wubi86", text.contains("schema_id: wubi86"))
        assertTrue("missing dictionary", text.contains("dictionary: wubi86"))
        assertTrue("missing speller", text.contains("max_code_length: 4"))
        // 「四码唯一直接上屏」：四码打完只剩一个候选时立即上屏。
        assertTrue("auto_select must be on", text.contains("auto_select: true"))
        // 五笔是字词表，不做整句。
        assertTrue("wubi must not build sentences", text.contains("enable_sentence: false"))
    }
}
