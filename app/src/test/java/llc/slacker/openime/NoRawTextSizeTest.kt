package llc.slacker.openime

import java.io.File
import org.junit.Assert.fail
import org.junit.Test

class NoRawTextSizeTest {
    @Test
    fun productionKotlinUsesTypographyTokens() {
        val root = repoRoot()
        val violations = mutableListOf<String>()
        File(root, "app/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "ImeDesignTokens.kt" }
            .forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    if (Regex("""textSize\s*=\s*\d""").containsMatchIn(line)) {
                        violations += "${file.relativeTo(root)}:${index + 1}: ${line.trim()}"
                    }
                    if (
                        listOf("createKey(", "createButton(", "createPanelButton(", "labelText(")
                            .any(line::contains)
                    ) {
                        val window = lines.subList(index, minOf(lines.size, index + 6)).joinToString(" ")
                        val rawHelperSize = Regex(
                            """(?:createKey|createButton|createPanelButton|labelText)\([^)]*,\s*\d+(?:\.\d+)?f\b""",
                        )
                        if (rawHelperSize.containsMatchIn(window)) {
                            violations += "${file.relativeTo(root)}:${index + 1}: ${line.trim()}"
                        }
                    }
                }
            }

        if (violations.isNotEmpty()) {
            fail("Raw text sizes must use ImeTypographyTokens:\n" + violations.distinct().joinToString("\n"))
        }
    }

    @Test
    fun layoutXmlUsesTypographyDimens() {
        val root = repoRoot()
        val violations = File(root, "app/src/main/res/layout")
            .walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    if (
                        line.contains("android:textSize=") &&
                        !line.contains("@dimen/ime_text_")
                    ) {
                        "${file.relativeTo(root)}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        if (violations.isNotEmpty()) {
            fail("Layout text sizes must use ime_text_* dimens:\n" + violations.joinToString("\n"))
        }
    }

    private fun repoRoot(): File {
        var root = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            if (File(root, "app/src/main").isDirectory) return root
            root = root.parentFile ?: return@repeat
        }
        error("Cannot locate repository root")
    }
}
