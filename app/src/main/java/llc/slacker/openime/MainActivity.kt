package llc.slacker.openime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.provider.Settings
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.ImageView
import android.widget.EditText
import android.widget.Toast
import android.graphics.drawable.StateListDrawable

/** Match the selected IME by the exact package component, never by substring. */
internal fun matchesSelectedInputMethod(defaultInputMethodId: String, packageName: String): Boolean {
    val separator = defaultInputMethodId.indexOf('/')
    return separator > 0 && defaultInputMethodId.substring(0, separator) == packageName
}

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
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                return@setupClick
            }
            val prefs = getPreferences(MODE_PRIVATE)
            prefs.edit().putBoolean("microphone_skipped", false).apply()
            val requested = prefs.getBoolean("microphone_requested", false)
            if (requested && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } else {
                prefs.edit().putBoolean("microphone_requested", true).apply()
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        setupClick(findViewById(R.id.voice_permission_skip)) {
            getPreferences(MODE_PRIVATE)
                .edit()
                .putBoolean("microphone_skipped", true)
                .apply()
            refreshSetupState()
        }
        setupClick(findViewById(R.id.test_step)) {
            val input = findViewById<EditText>(R.id.test_input)
            input.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(
                input,
                InputMethodManager.SHOW_IMPLICIT,
            )
        }
        findViewById<EditText>(R.id.test_input).addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    refreshSetupState()
                }

                override fun afterTextChanged(s: Editable?) = Unit
            },
        )
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
        val prefs = getPreferences(MODE_PRIVATE)
        val microphoneGranted =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val microphoneRequested = prefs.getBoolean("microphone_requested", false)
        val microphoneSkipped = prefs.getBoolean("microphone_skipped", false)
        val microphoneDeniedPermanently =
            microphoneRequested &&
                !microphoneGranted &&
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        val testInput = findViewById<EditText>(R.id.test_input)
        val testDone = testInput.text?.isNotBlank() == true

        testInput.background = SetupUi.focusRingBackground(this)
        SetupUi.styleCursor(this, testInput)

        findViewById<TextView>(R.id.status).setText(
            when {
                !enabled -> R.string.setup_enable
                !selected -> R.string.setup_choose
                testDone -> R.string.setup_ready
                else -> R.string.test_step
            },
        )

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
            alpha = if (enabled) 1f else ImeSurfacePolicy.DISABLED_ALPHA
        }

        val voiceDone = microphoneGranted || microphoneSkipped
        val voiceActive = selected && !voiceDone
        val voiceActiveText =
            if (microphoneDeniedPermanently) {
                getString(R.string.voice_permission_settings)
            } else {
                getString(R.string.voice_permission_enable)
            }
        val voiceDoneText =
            if (microphoneGranted) {
                getString(R.string.voice_permission_ready)
            } else {
                getString(R.string.voice_permission_skipped)
            }
        styleStep(
            row = findViewById(R.id.voice_permission),
            mark = findViewById(R.id.voice_permission_mark),
            label = findViewById(R.id.voice_permission_label),
            chevron = findViewById(R.id.voice_permission_chevron),
            done = voiceDone,
            active = voiceActive,
            doneText = voiceDoneText,
            activeText = voiceActiveText,
            markText = "3",
            accent = accent,
        )
        findViewById<View>(R.id.voice_permission).apply {
            isEnabled = selected && !microphoneGranted
            alpha = if (selected) 1f else ImeSurfacePolicy.DISABLED_ALPHA
        }
        findViewById<TextView>(R.id.voice_permission_description).setText(
            when {
                microphoneGranted -> R.string.voice_permission_ready_description
                microphoneDeniedPermanently -> R.string.voice_permission_denied
                else -> R.string.voice_permission_description
            },
        )
        findViewById<TextView>(R.id.voice_permission_skip).apply {
            visibility =
                if (selected && !microphoneGranted && !microphoneSkipped) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            isEnabled = visibility == View.VISIBLE
            background = SetupUi.secondaryBackground(this@MainActivity)
        }

        styleStep(
            row = findViewById(R.id.test_step),
            mark = findViewById(R.id.test_step_mark),
            label = findViewById(R.id.test_step_label),
            chevron = null,
            done = testDone,
            active = selected && !testDone,
            doneText = getString(R.string.test_step_done),
            activeText = getString(R.string.test_step),
            markText = "4",
            accent = accent,
        )
        findViewById<View>(R.id.test_step).apply {
            isEnabled = selected
            alpha = if (selected) 1f else ImeSurfacePolicy.DISABLED_ALPHA
        }
        testInput.isEnabled = selected
        testInput.alpha = if (selected) 1f else ImeSurfacePolicy.DISABLED_ALPHA

        val ready = enabled && selected
        findViewById<View>(R.id.open_app_settings).apply {
            isEnabled = ready
            alpha = if (ready) 1f else ImeSurfacePolicy.DISABLED_ALPHA
            background = SetupUi.secondaryBackground(this@MainActivity)
            contentDescription = getString(
                if (ready) R.string.open_app_settings else R.string.setup_need_switch,
            )
            if (Build.VERSION.SDK_INT >= 30) {
                stateDescription = if (ready) "可用" else "需先完成输入法设置"
            }
        }
    }

    private fun isImeReady(): Boolean {
        val status = imeStatus()
        return status.enabled && status.selected
    }

    private fun setupClick(view: View, onClick: () -> Unit) {
        view.setOnClickListener {
            if (!view.isEnabled) return@setOnClickListener
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
    }

    private data class ImeStatus(val enabled: Boolean, val selected: Boolean)

    private fun imeStatus(): ImeStatus {
        val manager = getSystemService(InputMethodManager::class.java)
        val defaultId = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val selected = matchesSelectedInputMethod(defaultId, packageName)
        val enabled = selected || manager.enabledInputMethodList.any { it.packageName == packageName }
        return ImeStatus(enabled = enabled, selected = selected)
    }

    private fun styleStep(
        row: View,
        mark: TextView,
        label: TextView,
        chevron: ImageView?,
        done: Boolean,
        active: Boolean,
        doneText: String,
        activeText: String,
        markText: String,
        accent: Int,
    ) {
        // A completed setup step remains an action: users may need to revisit
        // the system picker or input-method settings after initial setup.
        row.isEnabled = true
        row.background = when {
            active -> SetupUi.buttonBackground(this, accent)
            done -> completedBackground(accent)
            else -> SetupUi.secondaryBackground(this)
        }
        label.text = if (done) doneText else activeText
        label.setTextColor(
            if (active) contrastText(accent) else getColor(
                if (done) R.color.setup_body else R.color.setup_title,
            ),
        )
        // A completed step is easier to scan as a result than as an old
        // step number. Keep the number for the current step so the flow still
        // reads as 1 -> 2 while the completed state reads as a check.
        mark.text = if (done) "" else markText
        mark.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (done) R.drawable.ic_check else 0,
            0,
            0,
            0,
        )
        mark.compoundDrawableTintList = ColorStateList.valueOf(
            if (done) contrastText(getColor(R.color.setup_ready)) else accent,
        )
        mark.setBackgroundResource(
            when {
                active -> R.drawable.bg_setup_mark_active
                done -> R.drawable.bg_setup_mark_done
                else -> R.drawable.bg_setup_mark
            },
        )
        mark.setTextColor(
            when {
                active -> accent
                done -> contrastText(getColor(R.color.setup_ready))
                else -> accent
            },
        )
        chevron?.imageTintList = ColorStateList.valueOf(
            if (active) contrastText(accent) else getColor(R.color.setup_body),
        )
        chevron?.visibility = if (done) View.GONE else View.VISIBLE
        row.alpha = 1f
        row.contentDescription = when {
            done -> doneText
            active -> activeText
            else -> "$activeText，完成上一步后可用"
        }
        if (Build.VERSION.SDK_INT >= 30) {
            row.stateDescription = when {
                done -> "已完成，可再次打开"
                active -> "当前步骤"
                else -> "暂不可用"
            }
        }
    }

    private fun completedBackground(accent: Int): StateListDrawable {
        val surface = getColor(R.color.setup_muted)
        val tint = ImeDrawableFactory.blend(surface, accent, 0.10f)
        val pressed = ImeDrawableFactory.blend(surface, accent, 0.16f)
        val radius = SetupUi.dp(this, ImeGeometryTokens.CONTROL_RADIUS_DP).toFloat()
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                ImeDrawableFactory.rounded(pressed, radius),
            )
            addState(
                intArrayOf(android.R.attr.state_focused),
                ImeDrawableFactory.rounded(
                    color = tint,
                    radiusPx = radius,
                    strokeColor = accent,
                    strokeWidthPx = SetupUi.dp(this@MainActivity, 1),
                ),
            )
            addState(
                intArrayOf(),
                ImeDrawableFactory.rounded(tint, radius),
            )
        }
    }

    private fun contrastText(background: Int): Int {
        return ImeContrastPolicy.contrastText(background)
    }
}
