package llc.slacker.openime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import llc.slacker.openime.core.CrashGuard
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.setup.SetupUi
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeContrastPolicy
import llc.slacker.openime.theme.ImeDrawableFactory
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeTypographyTokens

/** Match the selected IME by the exact package component, never by substring. */
internal fun matchesSelectedInputMethod(defaultInputMethodId: String, packageName: String): Boolean {
    val separator = defaultInputMethodId.indexOf('/')
    return separator > 0 && defaultInputMethodId.substring(0, separator) == packageName
}

class MainActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(SetupUi.appearanceContext(newBase))
    }

    private var appliedAppearance = ImeAppearance.SYSTEM

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedAppearance = ImeSettingsRepository.loadAppearance(this)
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
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
        findViewById<View>(R.id.main_scroll).addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val width = minOf(view.width - view.paddingLeft - view.paddingRight, SetupUi.dp(this, 600))
            val content = findViewById<View>(R.id.main_content)
            (content.layoutParams as android.widget.FrameLayout.LayoutParams).let { params ->
                if (params.width != width) { params.width = width; content.layoutParams = params }
            }
            val button = findViewById<View>(R.id.open_app_settings)
            (button.layoutParams as android.widget.LinearLayout.LayoutParams).let { params ->
                val buttonWidth = (width - SetupUi.dp(this, 32)).coerceAtLeast(0)
                if (params.width != buttonWidth) {
                    params.width = buttonWidth
                    params.gravity = android.view.Gravity.CENTER_HORIZONTAL
                    button.layoutParams = params
                }
            }
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
        setupClick(findViewById(R.id.voice_permission_authorize)) { findViewById<View>(R.id.voice_permission).performClick() }
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
        if (appliedAppearance != ImeSettingsRepository.loadAppearance(this)) {
            recreate()
            return
        }
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
        val accent = SetupUi.accent(this)
        val tokens = SetupUi.tokens(this)
        findViewById<View>(R.id.main_scroll).setBackgroundColor(tokens.keyboardBackground)
        window.statusBarColor = tokens.keyboardBackground
        window.navigationBarColor = tokens.keyboardBackground
        findViewById<View>(R.id.voice_permission_card).background = SetupUi.keyBackground(this)
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

        testInput.background = SetupUi.keyBackground(this)
        testInput.setPadding(SetupUi.dp(this, 16), SetupUi.dp(this, 8), SetupUi.dp(this, 16), SetupUi.dp(this, 8))
        SetupUi.styleCursor(this, testInput)

        findViewById<TextView>(R.id.status).setText(
            when {
                !enabled -> R.string.setup_enable
                !selected -> R.string.setup_choose
                else -> R.string.setup_ready
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
            doneText = "已切换到 openIME",
            activeText = getString(R.string.choose_ime),
            markText = "2",
            accent = accent,
        )
        findViewById<View>(R.id.choose_ime).apply {
            isEnabled = enabled
            alpha = 1f
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
            markText = "",
            accent = accent,
        )
        findViewById<View>(R.id.voice_permission).apply {
            isEnabled = selected && !microphoneGranted
            alpha = 1f
        }
        findViewById<TextView>(R.id.voice_permission_description).setText(
            when {
                microphoneGranted -> R.string.voice_permission_ready_description
                microphoneDeniedPermanently -> R.string.voice_permission_denied
                else -> R.string.voice_permission_description
            },
        )
        findViewById<View>(R.id.voice_permission_actions).visibility = if (microphoneGranted || microphoneSkipped) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.voice_permission_authorize).background = SetupUi.secondaryBackground(this)
        findViewById<TextView>(R.id.voice_permission_authorize).setTextColor(SetupUi.secondaryTextColor(this))
        findViewById<TextView>(R.id.voice_permission_skip).apply {
            visibility =
                if (selected && !microphoneGranted && !microphoneSkipped) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            isEnabled = visibility == View.VISIBLE
            (this as TextView).setTextColor(SetupUi.secondaryTextColor(this@MainActivity))
            background = null
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
            markText = "3",
            accent = accent,
        )
        findViewById<View>(R.id.test_step).apply {
            isEnabled = selected
            alpha = 1f
        }
        testInput.isEnabled = selected
        testInput.alpha = 1f

        val ready = enabled && selected
        findViewById<View>(R.id.open_app_settings).apply {
            isEnabled = ready
            alpha = if (ready) 1f else ImeSurfacePolicy.DISABLED_ALPHA
            background = SetupUi.buttonBackground(this@MainActivity, SetupUi.primaryButtonColor(this@MainActivity))
            (this as TextView).setTextColor(android.graphics.Color.WHITE)
            contentDescription = getString(
                if (ready) R.string.open_app_settings else R.string.setup_need_switch,
            )
            if (Build.VERSION.SDK_INT >= 30) {
                stateDescription = if (ready) "可用" else "需先完成输入法设置"
            }
            hideDecorationFromAccessibility(this)
        }
    }

    /**
     * A setup card is one focusable, described node. Its number, label,
     * hint, status pill and chevron are decoration for the same action, so a
     * screen reader must not also land on each of them (and, being inside the
     * card, they cannot be separate actions anyway). Applied in code because
     * the status pill is added at runtime and a layout edit must not be able
     * to bring the duplicates back.
     */
    private fun hideDecorationFromAccessibility(card: View) {
        val group = card as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            hideDecorationFromAccessibility(child)
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
        val tokens = SetupUi.tokens(this)
        row.background = if (row.id == R.id.voice_permission) null else SetupUi.keyBackground(this)
        label.text = if (done) doneText else activeText
        label.setTextColor(getColor(if (done || active) R.color.setup_title else R.color.setup_body))
        // A completed step is easier to scan as a result than as an old
        // step number. Keep the number for the current step so the flow still
        // reads as 1 -> 2 while the completed state reads as a check.
        mark.text = if (done) "" else markText
        val markIcon = if (row.id == R.id.voice_permission) R.drawable.ic_mic else if (done) R.drawable.ic_check else 0
        val drawable = if (markIcon != 0) getDrawable(markIcon)?.apply {
            setBounds(0, 0, SetupUi.dp(this@MainActivity, 16), SetupUi.dp(this@MainActivity, 16))
        } else null
        mark.setCompoundDrawablesRelative(drawable, null, null, null)
        mark.compoundDrawableTintList = ColorStateList.valueOf(
            if (done || active) accent else tokens.keySecondaryText,
        )
        mark.background = SetupUi.rounded(tokens.functionKeyBackground, SetupUi.dp(this, ImeGeometryTokens.KEY_RADIUS_DP).toFloat())
        mark.setTextColor(if (active) accent else tokens.keySecondaryText)
        chevron?.imageTintList = ColorStateList.valueOf(
            getColor(R.color.setup_body),
        )
        chevron?.visibility = View.GONE
        if (row.id == R.id.open_ime_settings || row.id == R.id.choose_ime) {
            val layout = row as android.widget.LinearLayout
            (layout.getChildAt(1) as? android.widget.LinearLayout)?.getChildAt(1)?.visibility = if (done) View.GONE else View.VISIBLE
            val status = (layout.findViewWithTag<View>("setup-result") as? TextView) ?: TextView(this).apply {
                tag = "setup-result"; textSize = ImeTypographyTokens.SMALL_SP; setTextColor(getColor(R.color.setup_body))
                layout.addView(this, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val enableStep = row.id == R.id.open_ime_settings
            status.text = if (done) { if (enableStep) "已启用" else "已切换" } else { if (enableStep) "启用" else "切换" }
            status.visibility = View.VISIBLE
            status.gravity = android.view.Gravity.CENTER
            status.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            status.layoutParams = android.widget.LinearLayout.LayoutParams(
                if (done) android.widget.LinearLayout.LayoutParams.WRAP_CONTENT else SetupUi.dp(this, 66),
                if (done) android.widget.LinearLayout.LayoutParams.WRAP_CONTENT else SetupUi.dp(this, 40),
            )
            status.background = if (done) null else SetupUi.rounded(
                if (active) SetupUi.primaryButtonColor(this) else tokens.functionKeyBackground, SetupUi.dp(this, ImeGeometryTokens.PILL_RADIUS_DP).toFloat(),
            )
            status.setTextColor(if (done) getColor(R.color.setup_body) else if (active) android.graphics.Color.WHITE else getColor(R.color.setup_disabled_text))

        }
        if (row.id == R.id.test_step) (row as android.widget.LinearLayout).getChildAt(2)?.visibility = View.GONE
        hideDecorationFromAccessibility(row)
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
