package llc.slacker.openime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import llc.slacker.openime.core.CrashGuard
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.setup.SetupUi
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeDrawableFactory
import llc.slacker.openime.theme.ImeGeometryTokens

/** Match the selected IME by the exact package component, never by substring. */
internal fun matchesSelectedInputMethod(defaultInputMethodId: String, packageName: String): Boolean {
    val separator = defaultInputMethodId.indexOf('/')
    return separator > 0 && defaultInputMethodId.substring(0, separator) == packageName
}

/**
 * Home page. Until openIME is enabled and selected it is a three-step setup
 * whose bottom button performs the current step; afterwards the steps fold
 * into one "ready" card and the page becomes a place to try the keyboard.
 */
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
            (button.layoutParams as LinearLayout.LayoutParams).let { params ->
                val buttonWidth = (width - SetupUi.dp(this, 32)).coerceAtLeast(0)
                if (params.width != buttonWidth) {
                    params.width = buttonWidth
                    params.gravity = android.view.Gravity.CENTER_HORIZONTAL
                    button.layoutParams = params
                }
            }
        }
        styleStaticViews()
        setupClick(findViewById(R.id.open_ime_settings)) { openInputMethodSettings() }
        setupClick(findViewById(R.id.choose_ime)) { showInputMethodPicker() }
        setupClick(findViewById(R.id.open_app_settings)) {
            if (imeStatus().enabled) showInputMethodPicker() else openInputMethodSettings()
        }
        setupClick(findViewById(R.id.open_settings_icon)) { openPreferences() }
        setupClick(findViewById(R.id.shortcut_preferences)) { openPreferences() }
        setupClick(findViewById(R.id.shortcut_fuzzy)) { openPreferences(fuzzy = true) }
        setupClick(findViewById(R.id.voice_permission)) { requestMicrophone() }
        setupClick(findViewById(R.id.voice_permission_authorize)) { requestMicrophone() }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSetupState()
    }

    private fun openInputMethodSettings() {
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    private fun showInputMethodPicker() {
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    private fun openPreferences(fuzzy: Boolean = false) {
        if (!isImeReady()) {
            Toast.makeText(this, R.string.setup_need_switch, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, ImeSettingsActivity::class.java)
                .putExtra(ImeSettingsActivity.EXTRA_OPEN_FUZZY, fuzzy),
        )
    }

    private fun requestMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getPreferences(MODE_PRIVATE)
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

    /** Surfaces and tints that do not change with the setup state. */
    private fun styleStaticViews() {
        val page = getColor(R.color.setup_page_bg)
        window.statusBarColor = page
        window.navigationBarColor = page
        listOf(R.id.steps_card, R.id.voice_permission, R.id.shortcuts_card, R.id.ready_card).forEach {
            findViewById<View>(it).clipToOutline = true
        }
        listOf(R.id.open_ime_settings, R.id.choose_ime, R.id.shortcut_preferences, R.id.shortcut_fuzzy).forEach {
            findViewById<View>(it).background = SetupUi.rowBackground(this)
        }
        listOf(R.id.voice_permission_icon, R.id.shortcut_preferences_icon, R.id.shortcut_fuzzy_icon).forEach {
            findViewById<ImageView>(it).apply {
                background = SetupUi.rounded(getColor(R.color.setup_icon_tile), SetupUi.dp(this@MainActivity, 9).toFloat())
                imageTintList = ColorStateList.valueOf(getColor(R.color.setup_icon))
            }
        }
        findViewById<View>(R.id.ready_mark).background = circle(getColor(R.color.setup_ready))
        findViewById<View>(R.id.open_settings_icon).background = ImeDrawableFactory.statefulRounded(
            getColor(R.color.setup_surface),
            getColor(R.color.setup_muted),
            SetupUi.dp(this, ImeGeometryTokens.PILL_RADIUS_DP),
            SetupUi.accent(this),
            SetupUi.dp(this, 1),
        )
        val tips = findViewById<ViewGroup>(R.id.typing_tips)
        for (index in 0 until tips.childCount) {
            tips.getChildAt(index).background =
                SetupUi.rounded(getColor(R.color.setup_surface), SetupUi.dp(this, ImeGeometryTokens.PILL_RADIUS_DP).toFloat())
        }
        findViewById<EditText>(R.id.test_input).apply {
            background = ImeDrawableFactory.focusedRounded(
                fillColor = getColor(R.color.setup_surface),
                radiusPx = SetupUi.dp(this@MainActivity, 14).toFloat(),
                focusedStrokeColor = SetupUi.accent(this@MainActivity),
                defaultStrokeColor = getColor(R.color.setup_hairline),
                strokeWidthPx = SetupUi.dp(this@MainActivity, 1).coerceAtLeast(1) + SetupUi.dp(this@MainActivity, 1) / 2,
            )
            SetupUi.styleCursor(this@MainActivity, this)
        }
        findViewById<TextView>(R.id.voice_permission_authorize).apply {
            background = SetupUi.secondaryBackground(this@MainActivity)
            setTextColor(SetupUi.secondaryTextColor(this@MainActivity))
            // The row is the one accessible action; the button repeats it.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        findViewById<View>(R.id.voice_permission_status).background =
            SetupUi.rounded(getColor(R.color.setup_icon_tile), SetupUi.dp(this, ImeGeometryTokens.PILL_RADIUS_DP).toFloat())
        findViewById<View>(R.id.open_app_settings).background =
            SetupUi.buttonBackground(this, SetupUi.primaryButtonColor(this))
    }

    private fun refreshSetupState() {
        val status = imeStatus()
        val enabled = status.enabled
        val selected = status.selected
        val ready = enabled && selected

        findViewById<View>(R.id.onboarding_group).visibility = if (ready) View.GONE else View.VISIBLE
        findViewById<View>(R.id.ready_group).visibility = if (ready) View.VISIBLE else View.GONE
        findViewById<View>(R.id.open_settings_icon).visibility = if (ready) View.VISIBLE else View.GONE
        moveVoiceBlock(if (ready) R.id.ready_voice_slot else R.id.onboarding_voice_slot)
        findViewById<TextView>(R.id.voice_section_title).setText(if (ready) R.string.section_voice else R.string.section_optional)

        val currentStep = if (!enabled) 1 else 2
        findViewById<TextView>(R.id.setup_progress_label).text = getString(R.string.setup_progress, currentStep)
        listOf(R.id.setup_progress_1, R.id.setup_progress_2, R.id.setup_progress_3).forEachIndexed { index, id ->
            findViewById<View>(id).background = SetupUi.rounded(
                getColor(if (index < currentStep) R.color.setup_primary else R.color.setup_progress_todo),
                SetupUi.dp(this, 2).toFloat(),
            )
        }

        styleStep(R.id.open_ime_settings, R.id.open_ime_settings_mark, R.id.open_ime_settings_label, "1", done = enabled, active = !enabled)
        styleStep(R.id.choose_ime, R.id.choose_ime_mark, R.id.choose_ime_label, "2", done = selected, active = enabled && !selected)
        styleStep(R.id.test_step, R.id.test_step_mark, R.id.test_step_label, "3", done = false, active = false)
        findViewById<View>(R.id.choose_ime).isEnabled = enabled
        findViewById<View>(R.id.test_step).isClickable = false

        findViewById<TextView>(R.id.open_app_settings).apply {
            visibility = if (ready) View.GONE else View.VISIBLE
            setText(if (enabled) R.string.choose_ime_action else R.string.open_ime_settings_action)
            contentDescription = text
            if (Build.VERSION.SDK_INT >= 30) {
                stateDescription = getString(R.string.setup_progress, currentStep)
            }
        }

        refreshVoice(ready)
        findViewById<View>(R.id.ready_card).contentDescription =
            "${getString(R.string.setup_ready)}，${getString(R.string.setup_ready_detail)}"
    }

    private fun refreshVoice(ready: Boolean) {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val requested = getPreferences(MODE_PRIVATE).getBoolean("microphone_requested", false)
        val deniedPermanently = requested && !granted &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        val label = findViewById<TextView>(R.id.voice_permission_label)
        val description = findViewById<TextView>(R.id.voice_permission_description)
        label.setText(if (ready && granted) R.string.voice_title_ready else R.string.voice_title)
        description.setText(
            when {
                granted -> R.string.voice_permission_ready_description
                deniedPermanently -> R.string.voice_permission_denied
                else -> R.string.voice_permission_description
            },
        )
        val authorize = findViewById<TextView>(R.id.voice_permission_authorize)
        authorize.visibility = if (granted) View.GONE else View.VISIBLE
        authorize.setText(if (deniedPermanently) R.string.voice_permission_settings else R.string.voice_permission_enable)
        findViewById<View>(R.id.voice_permission_status).visibility = if (granted) View.VISIBLE else View.GONE
        findViewById<View>(R.id.voice_permission).apply {
            // Granted is a status, not an action: the row stops looking pressable.
            background = if (granted) {
                SetupUi.cardBackground(this@MainActivity)
            } else {
                android.graphics.drawable.LayerDrawable(
                    arrayOf(SetupUi.cardBackground(this@MainActivity), SetupUi.rowBackground(this@MainActivity)),
                )
            }
            contentDescription = "${label.text}，${description.text}"
            if (Build.VERSION.SDK_INT >= 30) {
                stateDescription = getString(if (granted) R.string.voice_permission_ready else R.string.voice_permission_enable)
            }
            hideDecorationFromAccessibility(this)
        }
    }

    private fun moveVoiceBlock(slotId: Int) {
        val block = findViewById<View>(R.id.voice_block)
        val slot = findViewById<ViewGroup>(slotId)
        if (block.parent === slot) return
        (block.parent as? ViewGroup)?.removeView(block)
        slot.addView(block)
    }

    /**
     * Step marker: the current step is the accent with its number, a finished
     * step is a green check, a later step is an outlined grey number.
     */
    private fun styleStep(rowId: Int, markId: Int, labelId: Int, number: String, done: Boolean, active: Boolean) {
        val row = findViewById<View>(rowId)
        val mark = findViewById<TextView>(markId)
        val label = findViewById<TextView>(labelId)
        val stroke = SetupUi.dp(this, 1) + SetupUi.dp(this, 1) / 2
        when {
            done -> {
                mark.text = ""
                mark.background = circle(getColor(R.color.setup_ready))
                val check = getDrawable(R.drawable.ic_pref_check)?.mutate()?.apply {
                    val size = SetupUi.dp(this@MainActivity, 16)
                    setBounds(0, 0, size, size)
                    setTint(android.graphics.Color.WHITE)
                }
                mark.setCompoundDrawablesRelative(check, null, null, null)
                mark.setPaddingRelative(SetupUi.dp(this, 6), 0, 0, 0)
            }
            active -> {
                mark.text = number
                mark.setCompoundDrawablesRelative(null, null, null, null)
                mark.setPadding(0, 0, 0, 0)
                mark.background = circle(getColor(R.color.setup_primary))
                mark.setTextColor(getColor(R.color.setup_on_primary))
            }
            else -> {
                mark.text = number
                mark.setCompoundDrawablesRelative(null, null, null, null)
                mark.setPadding(0, 0, 0, 0)
                mark.background = circle(android.graphics.Color.TRANSPARENT, getColor(R.color.setup_step_todo), stroke)
                mark.setTextColor(getColor(R.color.setup_body))
            }
        }
        label.typeface = android.graphics.Typeface.create(
            "sans-serif-medium",
            if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
        )
        hideDecorationFromAccessibility(row)
        row.contentDescription = if (active || done) label.text else "${label.text}，${getString(R.string.setup_step_later)}"
        if (Build.VERSION.SDK_INT >= 30) {
            row.stateDescription = getString(
                when {
                    done -> R.string.setup_step_done
                    active -> R.string.setup_step_current
                    else -> R.string.setup_step_later
                },
            )
        }
    }

    private fun circle(fill: Int, strokeColor: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        strokeColor?.let { setStroke(strokeWidth, it) }
    }

    /**
     * A setup card is one focusable, described node. Its marker, label, hint
     * and button are decoration for the same action, so a screen reader must
     * not also land on each of them.
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
}
