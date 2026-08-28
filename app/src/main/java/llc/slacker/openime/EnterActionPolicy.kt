package llc.slacker.openime

import android.view.inputmethod.EditorInfo

/** Visible Enter-key contract and the editor action it actually dispatches. */
internal data class EnterKeyPresentation(
    val label: String,
    val editorAction: Int?,
)

/**
 * Resolve both the label and behavior from the same EditorInfo bits so the key
 * never promises “发送” while the service is actually going to emit raw Enter.
 */
internal fun enterKeyPresentationFor(imeOptions: Int): EnterKeyPresentation {
    if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
        return EnterKeyPresentation(label = "换行", editorAction = null)
    }
    return when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_SEND -> EnterKeyPresentation("发送", action)
        EditorInfo.IME_ACTION_SEARCH -> EnterKeyPresentation("搜索", action)
        EditorInfo.IME_ACTION_GO -> EnterKeyPresentation("前往", action)
        EditorInfo.IME_ACTION_NEXT -> EnterKeyPresentation("下一项", action)
        EditorInfo.IME_ACTION_PREVIOUS -> EnterKeyPresentation("上一项", action)
        EditorInfo.IME_ACTION_DONE -> EnterKeyPresentation("完成", action)
        // NONE/UNSPECIFIED means the IME emits raw Enter. The target editor may
        // interpret that as newline, submit, or another app-specific action, so
        // “回车” is the only label that does not make a false promise.
        else -> EnterKeyPresentation(label = "回车", editorAction = null)
    }
}

/** Resolve an editor action for an Enter press, or null when a real Enter is required. */
internal fun editorActionForEnter(imeOptions: Int): Int? =
    enterKeyPresentationFor(imeOptions).editorAction
