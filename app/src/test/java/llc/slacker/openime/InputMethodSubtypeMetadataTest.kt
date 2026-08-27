package llc.slacker.openime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputMethodSubtypeMetadataTest {

    private fun methodXml(): File = sequenceOf(
        File("src/main/res/xml/method.xml"),
        File("app/src/main/res/xml/method.xml"),
    ).firstOrNull { it.isFile } ?: error("missing method.xml")

    @Test
    fun bothDeclaredSubtypesAreAsciiCapableIncludingLegacyExtra() {
        val xml = methodXml().readText()
        assertTrue(xml.contains("android:imeSubtypeLocale=\"zh_CN\""))
        assertTrue(xml.contains("android:imeSubtypeLocale=\"en_US\""))
        assertEquals(2, Regex("android:isAsciiCapable=\\\"true\\\"").findAll(xml).count())
        assertEquals(
            2,
            Regex("android:imeSubtypeExtraValue=\\\"AsciiCapable\\\"").findAll(xml).count(),
        )
    }
}
