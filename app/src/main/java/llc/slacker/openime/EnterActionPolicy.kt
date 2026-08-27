package llc.slacker.openime

import android.view.inputmethod.EditorInfo

/** Resolve an editor action for an Enter press, or null when a real Enter is required. */
internal fun editorActionForEnter(imeOptions: Int): Int? {
    if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return null
    val action = imeOptions and EditorInfo.IME_MASK_ACTION
    return when (action) {
        EditorInfo.IME_ACTION_GO,
        EditorInfo.IME_ACTION_SEARCH,
        EditorInfo.IME_ACTION_SEND,
        EditorInfo.IME_ACTION_NEXT,
        EditorInfo.IME_ACTION_DONE,
        -> action
        else -> null
    }
}
