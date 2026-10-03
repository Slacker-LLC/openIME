package llc.slacker.openime

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

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
                // One coordinate canvas; the intrinsic size is one of the design's icon sizes.
                requireContains("""android:viewportWidth="24"""", "viewportWidth must be 24")
                requireContains("""android:viewportHeight="24"""", "viewportHeight must be 24")
                val size = Regex("""android:width="(\d+)dp"""").find(xml)?.groupValues?.get(1)?.toInt()
                if (size !in setOf(18, 20, 24) || !xml.contains("""android:height="${size}dp"""")) {
                    violations += "${file.name}: must be a square 18, 20 or 24dp icon"
                }
                // Outline icons share one stroke style; filled icons carry no stroke attributes at all.
                if (xml.contains("android:strokeWidth=")) {
                    if (!xml.contains("""android:strokeWidth="1.8"""")) {
                        violations += "${file.name}: outline icons use strokeWidth 1.8"
                    }
                    requireContains("""android:strokeLineCap="round"""", "outline icons use round caps")
                    requireContains("""android:strokeLineJoin="round"""", "outline icons use round joins")
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
