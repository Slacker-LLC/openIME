package llc.slacker.openime

import java.io.File
import org.junit.Assert.fail
import org.junit.Test

class IconLintTest {
    @Test
    fun productIconsUseCanonicalVectorContract() {
        val root = repoRoot()
        val violations = mutableListOf<String>()
        File(root, "app/src/main/res/drawable")
            .listFiles()
            .orEmpty()
            .filter {
                it.isFile &&
                    it.extension == "xml" &&
                    it.name.startsWith("ic_") &&
                    !it.name.startsWith("ic_launcher") &&
                    it.name != "ic_brand_mark.xml"
            }
            .forEach { file ->
                val xml = file.readText()
                fun requireContains(value: String, message: String) {
                    if (!xml.contains(value)) violations += "${file.name}: $message"
                }
                requireContains("""android:width="24dp"""", "width must be 24dp")
                requireContains("""android:height="24dp"""", "height must be 24dp")
                requireContains("""android:viewportWidth="24"""", "viewportWidth must be 24")
                requireContains("""android:viewportHeight="24"""", "viewportHeight must be 24")
                if (xml.contains("android:strokeWidth=")) {
                    violations += "${file.name}: strokeWidth is forbidden"
                }
                val colors = Regex("""android:(?:fillColor|strokeColor)="(#[0-9A-Fa-f]{8})"""")
                    .findAll(xml)
                    .map { it.groupValues[1].uppercase() }
                    .toList()
                if (colors.isEmpty()) {
                    violations += "${file.name}: icon must define a fill color"
                } else if (colors.any { it != "#FF000000" }) {
                    violations += "${file.name}: all vector colors must be #FF000000, got $colors"
                }
            }

        if (violations.isNotEmpty()) {
            fail("Icon contract violations:\n" + violations.joinToString("\n"))
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
