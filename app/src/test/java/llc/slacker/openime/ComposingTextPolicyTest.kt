package llc.slacker.openime

import llc.slacker.openime.core.PinyinLocation
import llc.slacker.openime.editor.ComposingTextPolicy
import llc.slacker.openime.editor.EditorInfoAdapter.EditorKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposingTextPolicyTest {

    @Test
    fun theCandidateBarSettingNeverWritesIntoTheEditor() {
        EditorKind.entries.forEach { kind ->
            assertFalse(
                "$kind",
                ComposingTextPolicy.composesInEditor(PinyinLocation.CANDIDATE_BAR, false, kind),
            )
        }
    }

    @Test
    fun textFieldsTakeTheComposition() {
        listOf(
            EditorKind.TEXT,
            EditorKind.MULTILINE,
            EditorKind.EMAIL,
            EditorKind.URL,
            EditorKind.UNKNOWN,
        ).forEach { kind ->
            assertTrue(
                "$kind",
                ComposingTextPolicy.composesInEditor(PinyinLocation.INPUT_BOX, false, kind),
            )
        }
    }

    @Test
    fun fieldsThatCannotHoldPinyinKeepItOnTheKeyboard() {
        listOf(
            EditorKind.PASSWORD,
            EditorKind.NUMBER,
            EditorKind.DECIMAL,
            EditorKind.PHONE,
            EditorKind.DATETIME,
            EditorKind.RAW_KEYS,
        ).forEach { kind ->
            assertFalse(
                "$kind",
                ComposingTextPolicy.composesInEditor(PinyinLocation.INPUT_BOX, false, kind),
            )
        }
    }

    /** 密码框有时报 TEXT，只能靠 passwordField 这个标志挡住。 */
    @Test
    fun aPasswordFlagBlocksEvenATextField() {
        assertFalse(
            ComposingTextPolicy.composesInEditor(PinyinLocation.INPUT_BOX, true, EditorKind.TEXT),
        )
        assertFalse(
            ComposingTextPolicy.composesInEditor(
                PinyinLocation.INPUT_BOX,
                true,
                EditorKind.MULTILINE,
            ),
        )
    }
}
