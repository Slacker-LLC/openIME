package llc.slacker.openime

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback

class ImeSettingsActivity : Activity(), ImeKeyboardView.Listener {
    private lateinit var keyboardView: ImeKeyboardView
    private lateinit var host: FrameLayout
    private var backCallback: OnBackInvokedCallback? = null

    private fun refreshLiveIme() {
        LocalVoiceImeService.activeInstance?.refreshPersistedSettingsFromActivity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.setup_page_bg))
            setOnApplyWindowInsetsListener { view, insets ->
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
        }
        keyboardView = ImeKeyboardView(this, this, standalonePanel = true).apply {
            applyPersistedSettings(
                newTheme = ImeSettingsRepository.loadTheme(this@ImeSettingsActivity),
                newAppearance = ImeSettingsRepository.loadAppearance(this@ImeSettingsActivity),
                sound = ImeSettingsRepository.loadSound(this@ImeSettingsActivity),
                haptic = ImeSettingsRepository.loadHaptic(this@ImeSettingsActivity),
                popup = ImeSettingsRepository.loadPopup(this@ImeSettingsActivity),
                fuzzy = ImeSettingsRepository.loadFuzzy(this@ImeSettingsActivity),
                opacity = ImeSettingsRepository.loadSkinOpacity(this@ImeSettingsActivity),
                radius = ImeSettingsRepository.loadSkinRadius(this@ImeSettingsActivity),
                fontSize = ImeSettingsRepository.loadSkinFont(this@ImeSettingsActivity),
                primaryColor = ImeSettingsRepository.loadSkinColor(this@ImeSettingsActivity),
            )
            showPanel(Panel.SETTINGS)
        }
        host.addView(
            keyboardView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(host)
        refreshWindowChrome()
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback!!,
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            backCallback = null
        }
        super.onDestroy()
    }

    private fun handleBack() {
        if (keyboardView.currentPanel() == Panel.SETTINGS) {
            finish()
            return
        }
        if (keyboardView.closePanelToKeyboard()) {
            if (keyboardView.currentPanel() == Panel.NONE) finish()
            return
        }
        if (keyboardView.currentPanel() == Panel.NONE) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT < 33) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun refreshWindowChrome() {
        val appearance = ImeSettingsRepository.loadAppearance(this)
        val theme = ImeSettingsRepository.loadTheme(this)
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val tokens = theme.tokens(
            appearance = appearance,
            systemDark = systemDark,
            accentOverride = AccentPalette.parse(ImeSettingsRepository.loadSkinColor(this)),
        )
        val chrome = tokens.expandedBackground
        host.setBackgroundColor(chrome)
        window.statusBarColor = chrome
        window.navigationBarColor = chrome
        val lightChrome = ImeContrastPolicy.relativeLuminance(chrome) > 0.55
        var flags = window.decorView.systemUiVisibility
        flags = if (lightChrome) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        if (Build.VERSION.SDK_INT >= 26) {
            flags = if (lightChrome) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
        window.decorView.systemUiVisibility = flags
    }

    override fun onModeChanged(mode: KeyboardMode) = Unit
    override fun onPanelChanged(panel: Panel) {
        if (panel == Panel.NONE) finish()
    }
    override fun onCharacter(char: String) = Unit
    override fun onBackspace() = Unit
    override fun onClearAll() = Unit
    override fun onSpace() = Unit
    override fun onFloatingKeyboardChanged(floating: Boolean) = Unit
    override fun onFloatingKeyboardDragged(deltaX: Float, deltaY: Float) = Unit
    override fun onVoiceToggle() = Unit
    override fun onEnter() = Unit
    override fun onCompositionChanged(composition: String, candidates: List<String>) = Unit
    override fun onCandidateSelected(candidate: String) = Unit
    override fun onCompositionBackspace() = Unit
    override fun onThemeChanged(theme: ImeTheme) {
        ImeSettingsRepository.saveTheme(this, theme)
        refreshWindowChrome()
        refreshLiveIme()
    }
    override fun onAppearanceChanged(appearance: ImeAppearance) {
        ImeSettingsRepository.saveAppearance(this, appearance)
        refreshWindowChrome()
        refreshLiveIme()
    }
    override fun onShiftStateChanged(state: ShiftState) = Unit
    override fun onCandidateExpanded(open: Boolean) = Unit
    override fun onSymbolSelected(symbol: String) = Unit
    override fun onEmojiSelected(emoji: String) = Unit
    override fun onTextEdit(action: String) = Unit
    override fun onSoundChanged(enabled: Boolean) {
        ImeSettingsRepository.saveSound(this, enabled)
        refreshLiveIme()
    }
    override fun onHapticChanged(enabled: Boolean) {
        ImeSettingsRepository.saveHaptic(this, enabled)
        refreshLiveIme()
    }
    override fun onPopupChanged(enabled: Boolean) {
        ImeSettingsRepository.savePopup(this, enabled)
        refreshLiveIme()
    }
    override fun onFuzzyChanged(enabled: Boolean) {
        ImeSettingsRepository.saveFuzzy(this, enabled)
        refreshLiveIme()
    }
    override fun onSkinChanged(opacity: Int, radius: Int, fontSize: Int, primaryColor: String) {
        ImeSettingsRepository.saveSkin(this, opacity, radius, fontSize, primaryColor)
        refreshWindowChrome()
        refreshLiveIme()
    }
}
