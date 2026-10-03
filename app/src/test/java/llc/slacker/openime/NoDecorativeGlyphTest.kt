package llc.slacker.openime

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class NoDecorativeGlyphTest {
    @Test
    fun interactiveUiDoesNotUseUnicodeAsIcons() {
        val root = repoRoot()
        val forbidden = listOf("⌄", "⌃", "✓", "⏹", "🎤", "☺", "›", "▲", "◀", "▶", "▼")
        val excluded = setOf("ImeData.kt")
        val violations = File(root, "app/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in excluded }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    val glyph = forbidden.firstOrNull(line::contains)
                    if (glyph != null) "${file.relativeTo(root)}:${index + 1}: $glyph" else null
                }
            }
            .toMutableList()

        File(root, "app/src/main/res/layout")
            .walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val glyph = forbidden.firstOrNull(line::contains)
                    if (glyph != null) {
                        violations += "${file.relativeTo(root)}:${index + 1}: $glyph"
                    }
                }
            }

        if (violations.isNotEmpty()) {
            fail("Interactive UI glyphs must use vector drawables:\n" + violations.joinToString("\n"))
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
