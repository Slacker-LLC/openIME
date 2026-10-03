package llc.slacker.openime

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ContrastTest {
    @Test
    fun canonicalTextPairsMeetWcagAa() {
        listOf(
            "#1C1C1E" to "#FFFFFF",
            "#6D6D72" to "#EEF0F3",
            "#1F2023" to "#F7F8FA",
            "#F2F2F7" to "#3A3A3C",
            "#AEAEB2" to "#242426",
            "#07131D" to "#1D9BF0",
            "#07131D" to "#6EC3F7",
        ).forEach { (foreground, background) ->
            val ratio = contrastRatio(foreground, background)
            assertTrue(
                "$foreground on $background must be >= 4.5:1 but was $ratio",
                ratio >= 4.5,
            )
        }
    }

    private fun contrastRatio(first: String, second: String): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun luminance(hex: String): Double {
        val value = hex.removePrefix("#")
        fun component(offset: Int): Double {
            val raw = value.substring(offset, offset + 2).toInt(16) / 255.0
            return if (raw <= 0.04045) raw / 12.92 else ((raw + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * component(0) + 0.7152 * component(2) + 0.0722 * component(4)
    }
}
