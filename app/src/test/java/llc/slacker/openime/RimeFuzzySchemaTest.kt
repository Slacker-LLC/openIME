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
    fun normalAndFuzzySchemasBothAcceptNineKeyDigitsWithSingleTransliteration() {
        listOf(
            "rime-data/luna_pinyin_simp.schema.yaml",
            "rime-data/luna_pinyin_simp_fuzzy.schema.yaml",
        ).forEach { path ->
            val schema = asset(path).readText()
            assertTrue("$path must include numeric speller alphabet", schema.contains("987654321"))
            assertTrue("$path must preserve a letter spelling branch", schema.contains("derive/^(.*)$/\\U$1/"))
            assertTrue(
                "$path must map the complete cloned spelling to T9 in one pass",
                schema.contains("xlit/ABCDEFGHIJKLMNOPQRSTUVWXYZ/22233344455566677778889999/"),
            )
            assertTrue("$path must not regress to eight high-fanout T9 derives", !schema.contains("derive/[abc]/2/"))
        }
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
        val schema = asset("rime-data/luna_pinyin_simp.schema.yaml").readText()
        listOf("zh_z_bufen", "n_l_bufen", "en_eng_bufen").forEach { rule ->
            assertTrue("normal schema unexpectedly enables $rule", !schema.contains("pinyin:/$rule"))
        }
    }
}
