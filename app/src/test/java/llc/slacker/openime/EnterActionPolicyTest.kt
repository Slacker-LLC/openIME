package llc.slacker.openime

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnterActionPolicyTest {

    @Test
    fun noEnterActionForcesRealEnterInsteadOfSend() {
        val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertNull(editorActionForEnter(options))
        assertEquals("换行", enterKeyPresentationFor(options).label)
    }

    @Test
    fun noEnterActionForcesRealEnterInsteadOfDone() {
        val options = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertNull(editorActionForEnter(options))
        assertEquals("换行", enterKeyPresentationFor(options).label)
    }

    @Test
    fun explicitSingleLineActionsRemainActionsAndMatchTheirLabels() {
        val cases = listOf(
            EditorInfo.IME_ACTION_SEND to "发送",
            EditorInfo.IME_ACTION_DONE to "完成",
            EditorInfo.IME_ACTION_SEARCH to "搜索",
            EditorInfo.IME_ACTION_GO to "前往",
            EditorInfo.IME_ACTION_NEXT to "下一项",
            EditorInfo.IME_ACTION_PREVIOUS to "上一项",
        )
        cases.forEach { (action, label) ->
            assertEquals(action, editorActionForEnter(action))
            assertEquals(label, enterKeyPresentationFor(action).label)
            assertEquals(action, enterKeyPresentationFor(action).editorAction)
        }
    }

    @Test
    fun unrelatedImeFlagsDoNotSuppressActionOrChangeLabel() {
        val options = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        assertEquals(EditorInfo.IME_ACTION_SEARCH, editorActionForEnter(options))
        assertEquals("搜索", enterKeyPresentationFor(options).label)
    }

    @Test
    fun noActionFallsBackToRawEnterLabel() {
        listOf(EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED).forEach { options ->
            assertNull(editorActionForEnter(options))
            val presentation = enterKeyPresentationFor(options)
            assertEquals("回车", presentation.label)
            assertNull(presentation.editorAction)
        }
    }
}
