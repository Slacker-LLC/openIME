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
    private var backCallback: OnBackInvokedCallback? = null

    private fun refreshLiveIme() {
        LocalVoiceImeService.activeInstance?.refreshPersistedSettingsFromActivity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = FrameLayout(this).apply {
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
            setTheme(ImeSettingsRepository.loadTheme(this@ImeSettingsActivity))
            setAppearance(ImeSettingsRepository.loadAppearance(this@ImeSettingsActivity))
            setSettings(
                ImeSettingsRepository.loadSound(this@ImeSettingsActivity),
                ImeSettingsRepository.loadHaptic(this@ImeSettingsActivity),
                ImeSettingsRepository.loadPopup(this@ImeSettingsActivity),
                ImeSettingsRepository.loadFuzzy(this@ImeSettingsActivity),
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
        refreshLiveIme()
    }
    override fun onAppearanceChanged(appearance: ImeAppearance) {
        ImeSettingsRepository.saveAppearance(this, appearance)
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
        refreshLiveIme()
    }
}
