package llc.slacker.openime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenDriftTest {
    @Test
    fun canonicalTokenScaleDoesNotDrift() {
        val source = source("app/src/main/java/llc/slacker/openime/ImeDesignTokens.kt")
        listOf(
            "const val CAPTION_SP = 11f",
            "const val BODY_SP = 14f",
            "const val TITLE_SP = 16f",
            "const val CANDIDATE_SP = 18f",
            "const val KEY_LETTER_SP = 21f",
            "const val DISPLAY_SP = 28f",
            "const val XXS_DP = 2",
            "const val XS_DP = 4",
            "const val SM_DP = 8",
            "const val MD_DP = 12",
            "const val LG_DP = 16",
            "const val XL_DP = 24",
            "const val XXL_DP = 32",
            "const val KEY_GAP_DP = 6",
        ).forEach { expected ->
            assertTrue("Missing canonical token: $expected", source.contains(expected))
        }

        val enumBlock = source.substringAfter("enum class ImeTheme").substringBefore("data class Tokens")
        assertTrue(enumBlock.contains("IOS("))
        listOf("DARK(", "CYBERPUNK(", "CLASSIC(", "MACOS(").forEach {
            assertFalse("Legacy theme must stay removed: $it", enumBlock.contains(it))
        }
    }

    @Test
    fun setupResourcePaletteMatchesNativeTokens() {
        val light = source("app/src/main/res/values/colors.xml")
        val dark = source("app/src/main/res/values-night/colors.xml")

        mapOf(
            "setup_page_bg" to "#D5D8DE",
            "setup_surface" to "#EEF0F3",
            "setup_title" to "#1C1C1E",
            "setup_body" to "#6D6D72",
            "setup_primary" to "#1D9BF0",
            "setup_input_line" to "#B7BCC5",
        ).forEach { (name, value) ->
            assertTrue("$name drifted from the light IME palette", light.contains("<color name=\"$name\">$value</color>"))
        }
        mapOf(
            "setup_page_bg" to "#1C1C1E",
            "setup_surface" to "#242426",
            "setup_title" to "#F2F2F7",
            "setup_body" to "#AEAEB2",
            "setup_primary" to "#6EC3F7",
            "setup_input_line" to "#48484A",
        ).forEach { (name, value) ->
            assertTrue("$name drifted from the dark IME palette", dark.contains("<color name=\"$name\">$value</color>"))
        }
    }

    @Test
    fun setupDimensionsMirrorDesignTokens() {
        val dimens = source("app/src/main/res/values/dimens.xml")
        listOf(
            """<dimen name="ime_space_xxs">2dp</dimen>""",
            """<dimen name="ime_space_xs">4dp</dimen>""",
            """<dimen name="ime_space_sm">8dp</dimen>""",
            """<dimen name="ime_space_md">12dp</dimen>""",
            """<dimen name="ime_space_lg">16dp</dimen>""",
            """<dimen name="ime_space_xl">24dp</dimen>""",
            """<dimen name="ime_space_xxl">32dp</dimen>""",
            """<dimen name="setup_touch_target">48dp</dimen>""",
            """<dimen name="setup_top_bar_height">56dp</dimen>""",
            """<dimen name="setup_icon_size">24dp</dimen>""",
            """<dimen name="setup_step_mark_size">28dp</dimen>""",
            """<dimen name="setup_card_radius">16dp</dimen>""",
            """<dimen name="setup_hero_mark_size">72dp</dimen>""",
        ).forEach { expected ->
            assertTrue("Resource dimension drifted: $expected", dimens.contains(expected))
        }
    }

    @Test
    fun productVectorIconsUseOneCanvas() {
        val drawableDir = file("app/src/main/res/drawable")
        drawableDir.listFiles()
            .orEmpty()
            .filter {
                it.name.startsWith("ic_") &&
                    it.extension == "xml" &&
                    !it.name.startsWith("ic_launcher") &&
                    it.name != "ic_brand_mark.xml"
            }
            .forEach { icon ->
                val xml = icon.readText()
                assertTrue("${icon.name} width must be 24dp", xml.contains("android:width=\"24dp\""))
                assertTrue("${icon.name} height must be 24dp", xml.contains("android:height=\"24dp\""))
                assertTrue("${icon.name} viewport width must be 24", xml.contains("android:viewportWidth=\"24\""))
                assertTrue("${icon.name} viewport height must be 24", xml.contains("android:viewportHeight=\"24\""))
            }
    }

    private fun source(path: String): String = file(path).readText()

    private fun file(path: String): File {
        var root = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val candidate = File(root, path)
            if (candidate.isFile || candidate.isDirectory) return candidate
            root = root.parentFile ?: return@repeat
        }
        error("Cannot locate repository path: $path from ${System.getProperty("user.dir")}")
    }
}
