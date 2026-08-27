package llc.slacker.openime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditControlPolicyTest {

    @Test
    fun onlyKnownNoOpControlsAreUnavailable() {
        listOf("撤销", "▲", "▼").forEach { label ->
            assertTrue(label, TextEditControlPolicy.isUnavailableLabel(label))
        }
        listOf("全选", "复制", "剪切", "粘贴", "◀", "▶").forEach { label ->
            assertFalse(label, TextEditControlPolicy.isUnavailableLabel(label))
        }
    }
}
