package llc.slacker.openime

import android.app.Activity
import android.os.Bundle
import android.widget.EditText

/** Debug-only editor matrix used by the formal IME SOP. */
class ImeTestLabActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ime_test_lab)

        findViewById<EditText>(R.id.lab_long_text).setText(LONG_TEXT)
        findViewById<EditText>(R.id.lab_replace).apply {
            setText("请把中间这段文字替换掉")
            setSelection(2, 8)
        }
        val requestedName = intent.getStringExtra(EXTRA_FOCUS_ID).orEmpty()
        val requestedId = resources.getIdentifier(requestedName, "id", packageName)
        findViewById<EditText>(requestedId.takeIf { it != 0 } ?: R.id.lab_single).apply {
            requestFocus()
            post { bringPointIntoView(selectionStart.coerceAtLeast(0)) }
        }
    }

    private companion object {
        const val EXTRA_FOCUS_ID = "focus_id"
        val LONG_TEXT: String = buildString(10_000) {
            while (length < 10_000) append("openIME 长文本输入测试。")
        }.take(10_000)
    }
}
