package llc.slacker.openime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.provider.Settings
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import kotlin.math.pow

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
        setupClick(findViewById(R.id.open_ime_settings)) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        setupClick(findViewById(R.id.choose_ime)) {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        setupClick(findViewById(R.id.open_app_settings)) {
            if (!isImeReady()) {
                Toast.makeText(this, R.string.setup_need_switch, Toast.LENGTH_SHORT).show()
                return@setupClick
            }
            startActivity(Intent(this, ImeSettingsActivity::class.java))
        }
        setupClick(findViewById(R.id.voice_permission)) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return@setupClick
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
        val accent = AccentPalette.parse(ImeSettingsRepository.loadSkinColor(this))
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
            accent = accent,
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
            accent = accent,
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

    private fun setupClick(view: View, onClick: () -> Unit) {
        view.setOnClickListener {
            if (view.isEnabled) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
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
        accent: Int,
    ) {
        row.isEnabled = !done
        if (active) row.background = primaryPill(accent) else row.setBackgroundResource(R.drawable.bg_setup_muted_pill)
        label.text = if (done) doneText else activeText
        label.setTextColor(
            getColor(
                when {
                    active -> contrastText(accent)
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
            if (active) accent else getColor(if (done) R.color.setup_ready else R.color.setup_primary),
        )
        if (active) {
            mark.background = oval(contrastText(accent))
        } else if (done) {
            mark.setBackgroundResource(R.drawable.bg_setup_mark_done)
        } else {
            mark.setBackgroundResource(R.drawable.bg_setup_mark)
        }
        chevron?.setTextColor(
            if (active) contrastText(accent) else getColor(R.color.setup_body),
        )
        chevron?.visibility = if (done) View.GONE else View.VISIBLE
        row.alpha = if (done) 0.72f else 1f
        row.contentDescription = when {
            done -> doneText
            active -> activeText
            else -> "$activeText，完成上一步后可用"
        }
    }

    private fun primaryPill(color: Int): StateListDrawable {
        val pressed = dim(color, 0.86f)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, 28f))
            addState(intArrayOf(), rounded(color, 28f))
        }
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dim(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )

    private fun contrastText(background: Int): Int {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        val luminance = 0.2126 * channel(Color.red(background)) +
            0.7152 * channel(Color.green(background)) +
            0.0722 * channel(Color.blue(background))
        val whiteContrast = 1.05 / (luminance + 0.05)
        val blackContrast = (luminance + 0.05) / 0.05
        return if (whiteContrast >= blackContrast) Color.WHITE else Color.rgb(7, 19, 29)
    }
}
