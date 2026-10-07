package llc.slacker.openime

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import llc.slacker.openime.data.ImeSettingsRepository

/**
 * Asks for the text-message permission the toolbar's verification-code chip
 * needs. A keyboard cannot show a permission dialog itself, so the setting opens
 * this invisible screen. Refusing turns the setting back off.
 */
class SmsPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.READ_SMS), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ImeSettingsRepository.saveSmsCodeChip(this, granted)
        finish()
    }

    private companion object {
        const val REQUEST = 7
    }
}
