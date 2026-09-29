package llc.slacker.openime

import java.io.File
import org.junit.Assert.fail
import org.junit.Test

class NoRawColorTest {
    @Test
    fun productionKotlinDoesNotDefineRawColorsOutsideDesignTokens() {
        val root = repoRoot()
        val violations = File(root, "app/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "ImeDesignTokens.kt" }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    val hasHex = Regex("""#[0-9A-Fa-f]{6,8}""").containsMatchIn(line)
                    val hasParse = line.contains("Color.parseColor(")
                    if (hasHex || hasParse) "${file.relativeTo(root)}:${index + 1}: ${line.trim()}" else null
                }
            }
            .toList()

        if (violations.isNotEmpty()) {
            fail("Raw production colors must live in ImeDesignTokens.kt:\n" + violations.joinToString("\n"))
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
