package llc.slacker.openime

import android.app.Activity
import android.os.Bundle

/** Debug-only host used to verify editor/IME lifecycle behavior on real devices. */
class LifecycleTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lifecycle_test)
        findViewById<android.widget.EditText>(R.id.lifecycle_a).requestFocus()
    }
}
