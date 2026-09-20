package llc.slacker.openime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.provider.Settings
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.main_scroll).setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        listOf(
            R.id.open_ime_settings,
            R.id.choose_ime,
            R.id.open_app_settings,
            R.id.voice_permission,
        ).forEach { installSetupFeedback(findViewById(it)) }
        findViewById<View>(R.id.open_ime_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<View>(R.id.choose_ime).setOnClickListener {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        findViewById<View>(R.id.open_app_settings).setOnClickListener {
            if (!isImeReady()) {
                Toast.makeText(this, R.string.setup_need_switch, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, ImeSettingsActivity::class.java))
        }
        findViewById<View>(R.id.voice_permission).setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return@setOnClickListener
            val requested = getPreferences(MODE_PRIVATE).getBoolean("microphone_requested", false)
            if (requested && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } else {
                getPreferences(MODE_PRIVATE).edit().putBoolean("microphone_requested", true).apply()
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetupState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSetupState()
    }

    private fun refreshSetupState() {
        val status = imeStatus()
        val enabled = status.enabled
        val selected = status.selected
        findViewById<TextView>(R.id.status).setText(when {
            selected -> R.string.setup_ready
            enabled -> R.string.setup_choose
            else -> R.string.setup_enable
        })
        styleStep(
            row = findViewById(R.id.open_ime_settings),
            mark = findViewById(R.id.open_ime_settings_mark),
            label = findViewById(R.id.open_ime_settings_label),
            chevron = findViewById(R.id.open_ime_settings_chevron),
            done = enabled,
            active = !enabled,
            doneText = getString(R.string.open_ime_settings_done),
            activeText = getString(R.string.open_ime_settings),
            markText = "1",
        )
        styleStep(
            row = findViewById(R.id.choose_ime),
            mark = findViewById(R.id.choose_ime_mark),
            label = findViewById(R.id.choose_ime_label),
            chevron = findViewById(R.id.choose_ime_chevron),
            done = selected,
            active = enabled && !selected,
            doneText = getString(R.string.choose_ime_done),
            activeText = getString(R.string.choose_ime),
            markText = "2",
        )
        findViewById<View>(R.id.choose_ime).apply {
            isEnabled = enabled
            alpha = when {
                selected -> 0.72f
                enabled -> 1f
                else -> 0.55f
            }
        }
        val microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        findViewById<View>(R.id.voice_permission).apply {
            isEnabled = !microphoneGranted
            alpha = if (microphoneGranted) 0.72f else 1f
        }
        findViewById<TextView>(R.id.voice_permission_label).apply {
            setText(if (microphoneGranted) R.string.voice_permission_ready else R.string.voice_permission_enable)
            setTextColor(getColor(if (microphoneGranted) R.color.setup_muted_text else R.color.setup_title))
        }
        findViewById<TextView>(R.id.voice_permission_chevron).visibility =
            if (microphoneGranted) View.GONE else View.VISIBLE
        findViewById<View>(R.id.voice_permission).contentDescription = getString(
            if (microphoneGranted) R.string.voice_permission_ready else R.string.voice_permission_enable,
        )
        val ready = enabled && selected
        findViewById<View>(R.id.open_app_settings).apply {
            isEnabled = ready
            alpha = if (ready) 1f else 0.45f
            contentDescription = getString(
                if (ready) R.string.open_app_settings else R.string.setup_need_switch,
            )
        }
    }


    private fun isImeReady(): Boolean {
        val status = imeStatus()
        return status.enabled && status.selected
    }

    private fun installSetupFeedback(view: View) {
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && view.isEnabled) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            false
        }
    }

    private data class ImeStatus(val enabled: Boolean, val selected: Boolean)

    private fun imeStatus(): ImeStatus {
        val manager = getSystemService(InputMethodManager::class.java)
        val defaultId = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val selected = defaultId.contains(packageName)
        val enabled = selected || manager.enabledInputMethodList.any { it.packageName == packageName }
        return ImeStatus(enabled = enabled, selected = selected)
    }

    private fun styleStep(
        row: View,
        mark: TextView,
        label: TextView,
        chevron: TextView?,
        done: Boolean,
        active: Boolean,
        doneText: String,
        activeText: String,
        markText: String,
    ) {
        row.isEnabled = !done
        row.setBackgroundResource(
            if (active) R.drawable.bg_setup_primary_pill else R.drawable.bg_setup_muted_pill,
        )
        label.text = if (done) doneText else activeText
        label.setTextColor(
            getColor(
                when {
                    active -> R.color.setup_on_primary
                    done -> R.color.setup_body
                    else -> R.color.setup_title
                },
            ),
        )
        mark.text = markText
        mark.setBackgroundResource(
            when {
                active -> R.drawable.bg_setup_mark_active
                done -> R.drawable.bg_setup_mark_done
                else -> R.drawable.bg_setup_mark
            },
        )
        mark.setTextColor(
            getColor(
                when {
                    active -> R.color.setup_primary
                    done -> R.color.setup_ready
                    else -> R.color.setup_primary
                },
            ),
        )
        chevron?.setTextColor(
            getColor(if (active) R.color.setup_on_primary else R.color.setup_body),
        )
        chevron?.visibility = if (done) View.GONE else View.VISIBLE
        row.alpha = if (done) 0.72f else 1f
        row.contentDescription = when {
            done -> doneText
            active -> activeText
            else -> "$activeText，完成上一步后可用"
        }
    }
}
