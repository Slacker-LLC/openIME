package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditControlPolicyTest {

    @Test
    fun onlyPasswordClipboardControlsAreUnavailable() {
        listOf("撤销", "▲", "▼").forEach { label ->
            assertFalse(label, TextEditControlPolicy.isUnavailableLabel(label))
        }
        listOf("全选", "复制", "剪切", "粘贴", "◀", "▶").forEach { label ->
            assertFalse(label, TextEditControlPolicy.isUnavailableLabel(label))
        }
    }

    @Test
    fun passwordFieldsDisableClipboardActionsBeforeTheUserCanTriggerADeadAction() {
        listOf("全选", "复制", "剪切", "粘贴").forEach { label ->
            assertTrue(TextEditControlPolicy.isUnavailableLabel(label, passwordField = true))
            assertEquals("密码输入中不可用", TextEditControlPolicy.unavailableReason(label, passwordField = true))
        }
        assertFalse(TextEditControlPolicy.isUnavailableLabel("◀", passwordField = true))
    }
}
