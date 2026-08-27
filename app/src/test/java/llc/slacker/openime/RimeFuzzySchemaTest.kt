package llc.slacker.openime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RimeFuzzySchemaTest {

    private fun asset(relative: String): File = sequenceOf(
        File("src/main/assets/$relative"),
        File("app/src/main/assets/$relative"),
    ).firstOrNull { it.isFile } ?: error("missing test asset: $relative")

    @Test
    fun fuzzySettingMapsToDistinctNativeSchemas() {
        assertEquals("luna_pinyin_simp", rimeSchemaId(fuzzyEnabled = false))
        assertEquals("luna_pinyin_simp_fuzzy", rimeSchemaId(fuzzyEnabled = true))
    }

    @Test
    fun fuzzySchemaIsDeployedWithIndependentPrismAndRequiredRules() {
        val defaults = asset("rime-data/default.yaml").readText()
        val schema = asset("rime-data/luna_pinyin_simp_fuzzy.schema.yaml").readText()

        assertTrue(defaults.contains("- schema: luna_pinyin_simp_fuzzy"))
        assertTrue(schema.contains("schema_id: luna_pinyin_simp_fuzzy"))
        assertTrue(schema.contains("prism: luna_pinyin_simp_fuzzy"))
        listOf(
            "pinyin:/zh_z_bufen",
            "pinyin:/n_l_bufen",
            "pinyin:/en_eng_bufen",
            "pinyin:/abbreviation",
            "pinyin:/spelling_correction",
            "pinyin:/key_correction",
        ).forEach { rule ->
            assertTrue("missing fuzzy schema rule: $rule", schema.contains(rule))
        }
    }

    @Test
    fun normalSchemaDoesNotEnableFuzzyRules() {
        val schema = asset("rime-data/luna_pinyin.schema.yaml").readText()
        listOf("zh_z_bufen", "n_l_bufen", "en_eng_bufen").forEach { rule ->
            assertTrue("normal schema unexpectedly enables $rule", !schema.contains("pinyin:/$rule"))
        }
    }
}
