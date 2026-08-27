package llc.slacker.openime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryAssetTest {

    private fun asset(relative: String): File = sequenceOf(
        File("src/main/assets/$relative"),
        File("app/src/main/assets/$relative"),
    ).firstOrNull { it.isFile } ?: error("missing test asset: $relative")

    @Test
    fun maintainedCoreAndExtendedLexiconsAreBundled() {
        val base = asset("rime-data/openime_dicts/base.dict.yaml")
        val ext = asset("rime-data/openime_dicts/ext.dict.yaml")
        val chars = asset("rime-data/openime_dicts/8105.dict.yaml")
        val fast = asset("pinyin_phrases.tsv")
        val license = asset("licenses/rime-ice-GPL-3.0.txt")

        assertTrue("基础词库不应退化成小样本", base.length() > 15_000_000)
        assertTrue("扩展词库不应退化成小样本", ext.length() > 10_000_000)
        assertTrue("常用字表必须完整", chars.length() > 100_000)
        assertTrue("冷启动高频词层不应退化成小样本", fast.length() > 250_000)
        assertTrue("第三方词库许可证必须随包分发", license.length() > 30_000)

        val wantedBase = setOf("你好", "西安", "微信", "人工智能", "中华人民共和国")
        val foundBase = base.useLines { lines ->
            lines.mapNotNull { line -> line.substringBefore('\t').takeIf(wantedBase::contains) }
                .toSet()
        }
        assertEquals(wantedBase, foundBase)
        assertTrue(
            "扩展词库应包含输入法技术词汇",
            ext.useLines { lines -> lines.any { it.startsWith("输入法引擎\t") } },
        )
        assertTrue(
            "冷启动首选层应包含高频整词",
            fast.useLines { lines -> lines.any { it.startsWith("woxiang\t我想\t") } },
        )
    }

    @Test
    fun lunaDictionaryImportsTheMaintainedLexicons() {
        val config = asset("rime-data/luna_pinyin.dict.yaml").readText()
        listOf("8105", "base", "ext", "others").forEach { name ->
            assertTrue("missing imported table: $name", config.contains("openime_dicts/$name"))
        }
    }
}
