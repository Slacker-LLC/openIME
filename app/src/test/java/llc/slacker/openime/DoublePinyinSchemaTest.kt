package llc.slacker.openime

import llc.slacker.openime.core.DoublePinyinScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 双拼方案的结构验收：两个方案随包分发、注册在 default.yaml、键位表与补全显示齐全，
 * 且不引入数字字母（那是九键 T9 的约定，双拼只吃字母）。
 */
class DoublePinyinSchemaTest {

    private fun asset(relative: String): File = sequenceOf(
        File("src/main/assets/$relative"),
        File("app/src/main/assets/$relative"),
    ).firstOrNull { it.isFile } ?: error("missing test asset: $relative")

    @Test
    fun bothSchemasShipAndShareThePinyinDictionary() {
        val flypy = asset("rime-data/double_pinyin_flypy.schema.yaml").readText()
        val natural = asset("rime-data/double_pinyin.schema.yaml").readText()
        for ((text, id, prism) in listOf(
            Triple(flypy, "double_pinyin_flypy", "double_pinyin_flypy"),
            Triple(natural, "double_pinyin", "double_pinyin"),
        )) {
            assertTrue("missing schema_id $id", text.contains("schema_id: $id"))
            assertTrue("missing prism $prism", text.contains("prism: $prism"))
            // 词库沿用全拼，不重复一份 dict。
            assertTrue("must include the shared luna schema", text.contains("__include: luna_pinyin.schema:/"))
            // 键位表 + 拼音显示补全都存在。
            assertTrue("missing chord table", text.contains("xlit/ⓆⓌⓇⓉ"))
            assertTrue("missing preedit expansion", text.contains("xform/(\\w)q/\$1iu/") || text.contains("xform/(\\w)q/"))
            // 双拼只吃字母：不继承九键数字约定。
            assertTrue("alphabet must not carry T9 digits", !text.contains("987654321"))
        }
        val defaults = asset("rime-data/default.yaml").readText()
        assertTrue(defaults.contains("- schema: double_pinyin_flypy"))
        assertTrue(defaults.contains("- schema: double_pinyin"))
    }

    @Test
    fun schemeLabelsAndIdsMatchTheReferenceProduct() {
        assertEquals("小鹤双拼", DoublePinyinScheme.FLYPY.label)
        assertEquals("自然码双拼", DoublePinyinScheme.NATURAL.label)
        assertEquals("double_pinyin_flypy", DoublePinyinScheme.FLYPY.schemaId)
        assertEquals("double_pinyin", DoublePinyinScheme.NATURAL.schemaId)
        assertEquals(DoublePinyinScheme.FLYPY, DoublePinyinScheme.fromKey(null))
        assertEquals(DoublePinyinScheme.FLYPY, DoublePinyinScheme.fromKey("unknown"))
        assertEquals(DoublePinyinScheme.NATURAL, DoublePinyinScheme.fromKey("natural"))
    }
}
