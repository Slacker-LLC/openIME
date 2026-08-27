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
    }

    @Test
    fun noEnterActionForcesRealEnterInsteadOfDone() {
        val options = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertNull(editorActionForEnter(options))
    }

    @Test
    fun explicitSingleLineActionsRemainActions() {
        assertEquals(EditorInfo.IME_ACTION_SEND, editorActionForEnter(EditorInfo.IME_ACTION_SEND))
        assertEquals(EditorInfo.IME_ACTION_DONE, editorActionForEnter(EditorInfo.IME_ACTION_DONE))
        assertEquals(EditorInfo.IME_ACTION_SEARCH, editorActionForEnter(EditorInfo.IME_ACTION_SEARCH))
    }

    @Test
    fun unrelatedImeFlagsDoNotSuppressAction() {
        val options = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        assertEquals(EditorInfo.IME_ACTION_SEARCH, editorActionForEnter(options))
    }

    @Test
    fun noActionFallsBackToRealEnter() {
        assertNull(editorActionForEnter(EditorInfo.IME_ACTION_NONE))
        assertNull(editorActionForEnter(EditorInfo.IME_ACTION_UNSPECIFIED))
    }
}
