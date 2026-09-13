package llc.slacker.openime

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAutoPreloadPolicyTest {
    @Test
    fun ordinaryTextAndMultilineMayPreload() {
        assertTrue(VoiceAutoPreloadPolicy.shouldPreload(EditorInfoAdapter.EditorKind.TEXT, 0))
        assertTrue(VoiceAutoPreloadPolicy.shouldPreload(EditorInfoAdapter.EditorKind.MULTILINE, 0))
    }

    @Test
    fun structuredAndPrivateEditorsDoNotPreloadLargeModel() {
        listOf(
            EditorInfoAdapter.EditorKind.PASSWORD,
            EditorInfoAdapter.EditorKind.NUMBER,
            EditorInfoAdapter.EditorKind.DECIMAL,
            EditorInfoAdapter.EditorKind.PHONE,
            EditorInfoAdapter.EditorKind.EMAIL,
            EditorInfoAdapter.EditorKind.URL,
            EditorInfoAdapter.EditorKind.UNKNOWN,
        ).forEach { kind ->
            assertFalse(kind.name, VoiceAutoPreloadPolicy.shouldPreload(kind, 0))
        }
    }

    @Test
    fun noPersonalizedLearningDisablesAutomaticPreload() {
        assertFalse(
            VoiceAutoPreloadPolicy.shouldPreload(
                EditorInfoAdapter.EditorKind.TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ),
        )
    }
}
