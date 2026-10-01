package llc.slacker.openime

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.Spanned
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Debug-only stand-in for editors that are not an EditText (Compose, custom
 * canvas, web): it answers before/after/selected-text queries and the basic
 * edit calls, but offers no select-all action and no ExtractedText. Real chat
 * apps behave like this, and "clear all" used to fail silently in them.
 */
class CustomEditorTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editor = MinimalEditorView(this)
        editor.isClickable = true
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 96, 32, 32)
                addView(TextView(context).apply { text = "自绘编辑器（无全选 / 无 ExtractedText）" })
                addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 320))
            },
        )
        editor.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        // A non-EditText view must ask for the keyboard itself.
        editor.setOnClickListener { imm.showSoftInput(editor, 0) }
        editor.post { imm.showSoftInput(editor, 0) }
    }
}

/** A focusable view whose InputConnection exposes only the basic editing calls. */
private class MinimalEditorView(context: Context) : FrameLayout(context) {
    private val shown = TextView(context).apply {
        textSize = 20f
        gravity = Gravity.TOP or Gravity.START
        contentDescription = "custom-editor-text"
    }
    private val backing: Editable = Editable.Factory.getInstance().newEditable("")

    // BaseInputConnection(fullEditor = true) edits `backing` directly and, like
    // a minimal custom editor, implements neither select-all nor ExtractedText.
    private val connection: BaseInputConnection = object : BaseInputConnection(this, true) {
        override fun getEditable(): Editable = backing
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0xFFEEEEEE.toInt())
        addView(shown, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        backing.setSpan(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    shown.text = s?.toString().orEmpty()
                }
            },
            0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = Selection.getSelectionStart(backing)
        outAttrs.initialSelEnd = Selection.getSelectionEnd(backing)
        return connection
    }
}
