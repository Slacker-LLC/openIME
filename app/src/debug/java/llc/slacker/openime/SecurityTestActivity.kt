package llc.slacker.openime

import android.app.Activity
import android.os.Bundle

/** Debug-only activity used by the real-device privacy acceptance test. */
class SecurityTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_security_test)
        findViewById<android.widget.EditText>(R.id.security_password).requestFocus()
    }
}
