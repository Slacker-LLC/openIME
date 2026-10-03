package llc.slacker.openime

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout

/** Debug-only login form for the inline autofill end-to-end check. */
class AutofillTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val username = EditText(this).apply {
            tag = "af_username"
            hint = "用户名"
            setAutofillHints(View.AUTOFILL_HINT_USERNAME)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val password = EditText(this).apply {
            tag = "af_password"
            hint = "密码"
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val plain = EditText(this).apply {
            tag = "af_plain"
            hint = "普通输入框（无自动填充）"
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 96, 32, 32)
                addView(username)
                addView(password)
                addView(plain)
            },
        )
        when (intent.getStringExtra("focus")) {
            "password" -> password.requestFocus()
            "plain" -> plain.requestFocus()
            else -> username.requestFocus()
        }
    }
}
