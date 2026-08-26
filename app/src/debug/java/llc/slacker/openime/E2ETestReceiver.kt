package llc.slacker.openime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only bridge used by adb-driven Real IME E2E tests.
 * The command drives the actual click listeners inside the live
 * InputMethodService; it never substitutes a fake InputConnection.
 */
class E2ETestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
        val service = LocalVoiceImeService.activeInstance
        if (command == "state" && service != null) {
            Log.i(
                TAG,
                "STATE mode=${service.currentMode()} voice=${service.isVoiceActive()}",
            )
        }
        val ok = service?.handleTestCommand(command) == true
        val redacted = if (
            command.startsWith("type:") || command.startsWith("type64:")
        ) "type:<redacted>" else command
        Log.i(TAG, "cmd=$redacted ok=$ok")
    }

    private companion object {
        const val TAG = "MinisImeE2E"
        const val EXTRA_COMMAND = "cmd"
    }
}
