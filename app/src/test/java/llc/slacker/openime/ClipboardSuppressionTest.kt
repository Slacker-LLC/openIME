package llc.slacker.openime

import llc.slacker.openime.data.ClipboardSuppression
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSuppressionTest {
    @Test
    fun sameTextFromADifferentCopyIsNotSuppressed() {
        // 清空的是第一次复制（时间戳 1000）。同样文字再次复制（时间戳 2000）必须能收下。
        val marker = ClipboardSuppression.fingerprint(1000L, "hello")
        assertFalse(ClipboardSuppression.isSuppressed(marker, 2000L, "hello"))
    }

    @Test
    fun clearedClipIsStillSuppressedOnRereads() {
        val marker = ClipboardSuppression.fingerprint(1000L, "hello")
        assertTrue(ClipboardSuppression.isSuppressed(marker, 1000L, "hello"))
    }

    @Test
    fun hashCollisionDoesNotSuppressADifferentText() {
        // "Aa" 与 "BB" 的 String.hashCode 相同、长度相同，旧指纹会误判。
        assertTrue("Aa".hashCode() == "BB".hashCode())
        val marker = ClipboardSuppression.fingerprint(0L, "Aa")
        assertFalse(ClipboardSuppression.isSuppressed(marker, 0L, "BB"))
        assertNotEquals(
            ClipboardSuppression.fingerprint(0L, "Aa"),
            ClipboardSuppression.fingerprint(0L, "BB"),
        )
    }

    @Test
    fun noMarkerNeverSuppresses() {
        assertFalse(ClipboardSuppression.isSuppressed(null, 1000L, "hello"))
    }
}
