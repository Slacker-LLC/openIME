package llc.slacker.openime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The package layout is the module map (see docs/ARCHITECTURE.md). This test
 * pins the allowed dependency directions so the code cannot drift back into a
 * tangle: a package may only import the packages listed for it here, and the
 * listed edges must form a DAG.
 *
 * To add a new dependency, change it here deliberately and say why in the PR.
 */
class ArchitectureLayeringTest {
    private val base = "llc.slacker.openime"

    /** package -> packages it may import. "app" is the root package (Android entry points). */
    private val allowed: Map<String, Set<String>> = mapOf(
        "theme" to emptySet(),
        "core" to setOf("theme"),
        "editor" to setOf("core"),
        "data" to setOf("core", "editor", "theme"),
        "setup" to setOf("data", "theme"),
        "widget" to setOf("theme"),
        "floating" to setOf("theme"),
        "handwriting" to setOf("data", "theme"),
        "rime" to setOf("core", "data"),
        "candidate" to setOf("core", "rime"),
        "voice" to setOf("data", "editor", "theme"),
        "panel" to setOf("core", "data", "handwriting", "setup", "theme", "widget"),
        "keyboard" to setOf(
            "candidate", "core", "data", "editor", "floating", "handwriting",
            "panel", "setup", "theme", "voice", "widget",
        ),
        "app" to setOf(
            "candidate", "core", "data", "editor", "floating", "keyboard",
            "rime", "setup", "theme", "voice",
        ),
    )

    /**
     * Upward references to root-package Android components. Activities are
     * launched by class, and RimeNative is the JNI boundary whose package name
     * is part of the native symbol names.
     */
    private val rootExceptions: Set<Pair<String, String>> = setOf(
        "rime" to "RimeNative",
        "panel" to "ImeSettingsActivity",
        "panel" to "SymbolManagerActivity",
        "keyboard" to "QuickPhraseEditActivity",
        "keyboard" to "RailSymbolsActivity",
    )

    private val sources: File = sequenceOf(File("."), File(".."))
        .map { File(it, "app/src/main/java/llc/slacker/openime") }
        .first { it.isDirectory }

    private data class Source(val file: File, val pkg: String, val imports: List<String>)

    private fun scan(): List<Source> = sources.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { file ->
            val lines = file.readLines()
            val dir = file.parentFile.relativeTo(sources).path
            Source(
                file = file,
                pkg = if (dir.isEmpty()) "app" else dir,
                imports = lines.filter { it.startsWith("import $base.") }.map { it.removePrefix("import $base.").trim() },
            )
        }
        .toList()

    @Test
    fun everyFileDeclaresThePackageOfItsDirectory() {
        val wrong = scan().filter { source ->
            val declared = source.file.useLines { lines -> lines.first { it.startsWith("package ") } }.removePrefix("package ").trim()
            val expected = if (source.pkg == "app") base else "$base.${source.pkg}"
            declared != expected
        }.map { it.file.relativeTo(sources).path }
        assertTrue("package/directory mismatch: $wrong", wrong.isEmpty())
    }

    @Test
    fun everyPackageIsKnownToTheLayerMap() {
        val unknown = scan().map { it.pkg }.toSet() - allowed.keys
        assertTrue("new package needs a place in the layer map: $unknown", unknown.isEmpty())
    }

    @Test
    fun importsOnlyFollowTheAllowedDirections() {
        val violations = mutableListOf<String>()
        for (source in scan()) {
            val permitted = allowed.getValue(source.pkg)
            for (import in source.imports) {
                val head = import.substringBefore('.')
                val target = if (head.first().isLowerCase() && import.contains('.')) head else "app"
                val symbol = if (target == "app") head else import.substringAfter('.').substringBefore('.')
                if (target == "app" && symbol in setOf("R", "BuildConfig")) continue
                if (target == source.pkg) continue
                if (target == "app" && source.pkg != "app") {
                    if ((source.pkg to symbol) !in rootExceptions) {
                        violations += "${source.file.name}: ${source.pkg} must not use root class $symbol"
                    }
                } else if (target !in permitted) {
                    violations += "${source.file.name}: ${source.pkg} must not import $target ($import)"
                }
            }
        }
        if (violations.isNotEmpty()) fail("Layering violations:\n" + violations.distinct().sorted().joinToString("\n"))
    }

    @Test
    fun theAllowedEdgesFormAnAcyclicGraph() {
        val state = HashMap<String, Int>() // 1 = visiting, 2 = done
        fun visit(node: String, path: List<String>) {
            when (state[node]) {
                2 -> return
                1 -> fail("dependency cycle: ${(path + node).joinToString(" -> ")}")
            }
            state[node] = 1
            allowed.getValue(node).forEach { visit(it, path + node) }
            state[node] = 2
        }
        allowed.keys.forEach { visit(it, emptyList()) }
        assertEquals(allowed.keys, state.keys)
    }
}
