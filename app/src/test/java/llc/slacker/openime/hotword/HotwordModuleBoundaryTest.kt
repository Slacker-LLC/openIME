package llc.slacker.openime.hotword

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Keeps the hotword module a module: pure logic stays free of Android, and the
 * rest of the app talks to it only through [HotwordRuntime] and the screen.
 */
class HotwordModuleBoundaryTest {
    private val root: File = sequenceOf(File("."), File(".."))
        .first { File(it, "app/src/main/java").isDirectory }
    private val sources = File(root, "app/src/main/java/llc/slacker/openime")
    private val module = File(sources, "hotword")

    @Test
    fun pureLogicDoesNotDependOnAndroid() {
        val pure = listOf("HotwordPack", "HotwordParser", "PinyinReadings", "HomophoneCorrector")
        val violations = pure.flatMap { name ->
            File(module, "$name.kt").readLines()
                .filter { it.startsWith("import android.") || it.startsWith("import llc.slacker.openime.") }
                .map { "$name.kt: $it" }
        }
        if (violations.isNotEmpty()) fail("Pure hotword logic must stay Android-free:\n" + violations.joinToString("\n"))
    }

    @Test
    fun theRestOfTheAppOnlyUsesTheModuleEntryPoints() {
        val allowed = setOf("HotwordRuntime", "HotwordPacksActivity")
        val violations = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.contains("/hotword/") }
            .flatMap { file ->
                file.readLines().filter { it.startsWith("import llc.slacker.openime.hotword.") }
                    .filter { it.substringAfterLast('.') !in allowed }
                    .map { "${file.name}: $it" }
            }.toList()
        if (violations.isNotEmpty()) fail("Reach into the hotword module only via its entry points:\n" + violations.joinToString("\n"))
    }
}
