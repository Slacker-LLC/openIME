package llc.slacker.openime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo

/**
 * Native system IME service. The view is a thin native renderer; all candidate
 * state, editor side effects and privacy rules live here.
 */
class LocalVoiceImeService : InputMethodService(), ImeKeyboardViewV2.Listener {

    private var keyboardView: ImeKeyboardView? = null
    private lateinit var gateway: InputConnectionGateway
    private lateinit var engine: CandidateEngine
    private lateinit var rime: RimeEngine
    private var state = ImeState()
    private var lastComposition = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private var floatingWindowEnabled = false
    private var floatingWindowX = 0
    private var floatingWindowY = 0
    private var baseImeGravity: Int? = null
    private var baseImeWidth: Int? = null
    private var baseImeHeight: Int? = null
    private var voiceComposing = false

    private val legacyAdapter = object : ImeKeyboardView.Listener {
        override fun onModeChanged(mode: KeyboardMode) = this@LocalVoiceImeService.onModeChanged(mode)
        override fun onPanelChanged(panel: Panel) = this@LocalVoiceImeService.onPanelChanged(panel)
        override fun onCharacter(char: String) = this@LocalVoiceImeService.onCharacter(char)
        override fun onBackspace() = this@LocalVoiceImeService.onBackspace()
        override fun onClearAll() = this@LocalVoiceImeService.onClearAll()
        override fun onSpace() = this@LocalVoiceImeService.onSpace()
        override fun onFloatingKeyboardChanged(floating: Boolean) =
            this@LocalVoiceImeService.onFloatingKeyboardChanged(floating)
        override fun onFloatingKeyboardDragged(deltaX: Float, deltaY: Float) =
            this@LocalVoiceImeService.onFloatingKeyboardDragged(deltaX, deltaY)
        override fun onVoiceToggle() = this@LocalVoiceImeService.onVoiceToggle()
        override fun onVoicePressChanged(pressed: Boolean) =
            this@LocalVoiceImeService.onVoicePressChanged(pressed)
        override fun onVoicePartial(text: String) = this@LocalVoiceImeService.onVoicePartial(text)
        override fun onVoiceFinal(text: String) = this@LocalVoiceImeService.onVoiceFinal(text)
        override fun onVoiceError(message: String) = this@LocalVoiceImeService.onVoiceError(message)
        override fun onEnter() = this@LocalVoiceImeService.onEnter()
        override fun onCompositionChanged(composition: String, candidates: List<String>) =
            this@LocalVoiceImeService.onCompositionChanged(composition, candidates)
        override fun onCandidateSelected(candidate: String) =
            this@LocalVoiceImeService.onCandidateSelected(candidate)
        override fun onCompositionBackspace() = this@LocalVoiceImeService.onCompositionBackspace()
        override fun onThemeChanged(theme: ImeTheme) = this@LocalVoiceImeService.onThemeChanged(theme)
        override fun onAppearanceChanged(appearance: ImeAppearance) = this@LocalVoiceImeService.onAppearanceChanged(appearance)
        override fun onShiftStateChanged(state: ShiftState) = this@LocalVoiceImeService.onShiftStateChanged(state)
        override fun onCandidateExpanded(open: Boolean) = this@LocalVoiceImeService.onCandidateExpanded(open)
        override fun onSymbolSelected(symbol: String) = this@LocalVoiceImeService.onSymbolSelected(symbol)
        override fun onEmojiSelected(emoji: String) = this@LocalVoiceImeService.onEmojiSelected(emoji)
        override fun onTextEdit(action: String) = this@LocalVoiceImeService.onTextEdit(action)
        override fun onSoundChanged(enabled: Boolean) = this@LocalVoiceImeService.onSoundChanged(enabled)
        override fun onHapticChanged(enabled: Boolean) = this@LocalVoiceImeService.onHapticChanged(enabled)
        override fun onPopupChanged(enabled: Boolean) = this@LocalVoiceImeService.onPopupChanged(enabled)
        override fun onFuzzyChanged(enabled: Boolean) = this@LocalVoiceImeService.onFuzzyChanged(enabled)
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        UserPhraseRepository.configure(this)
        engine = CandidateEngine(PinyinLexicon.load(this))
        rime = RimeEngine(this).also { it.start() }
        gateway = InputConnectionGateway(
            context = this,
            connection = { currentInputConnection },
            isPassword = { state.passwordField },
        )
        state = ImeState(
            theme = ImeSettingsRepository.loadTheme(this),
            appearance = ImeSettingsRepository.loadAppearance(this),
            soundEnabled = ImeSettingsRepository.loadSound(this),
            hapticEnabled = ImeSettingsRepository.loadHaptic(this),
            popupEnabled = ImeSettingsRepository.loadPopup(this),
            fuzzyPinyinEnabled = ImeSettingsRepository.loadFuzzy(this),
            skinOpacity = ImeSettingsRepository.loadSkinOpacity(this),
            skinRadius = ImeSettingsRepository.loadSkinRadius(this),
            skinFontSize = ImeSettingsRepository.loadSkinFont(this),
            skinPrimaryColor = ImeSettingsRepository.loadSkinColor(this),
        )
    }

    override fun onCreateInputView(): View {
        keyboardView = ImeKeyboardView(this, legacyAdapter)
        keyboardView?.setMode(state.keyboardMode)
        keyboardView?.setTheme(state.theme)
        keyboardView?.setAppearance(state.appearance)
        keyboardView?.setSettings(
            state.soundEnabled,
            state.hapticEnabled,
            state.popupEnabled,
            state.fuzzyPinyinEnabled,
        )
        keyboardView?.renderState(state)
        return keyboardView!!
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val kind = EditorInfoAdapter.kind(attribute)
        state = state.copy(
            editorInfo = attribute,
            editorAction = attribute?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                ?: EditorInfo.IME_ACTION_NONE,
            passwordField = EditorInfoAdapter.isPassword(kind),
            keyboardMode = EditorInfoAdapter.defaultKeyboardMode(kind),
            panel = Panel.NONE,
            composition = "",
            candidates = emptyList(),
        )
        lastComposition = ""
        rime.clear()
        keyboardView?.clearAssociationCandidates()
        keyboardView?.setMode(state.keyboardMode)
        keyboardView?.renderState(state)
    }

    override fun onStartInputView(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        if (keyboardView == null) onCreateInputView()
        if (state.panel != Panel.GAMING) restoreImeWindow()
    }

    override fun onFinishInput() {
        keyboardView?.shutdown()
        gateway.finishComposing()
        rime.clear()
        voiceComposing = false
        lastComposition = ""
        state = state.copy(composition = "", candidates = emptyList())
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyboardView?.shutdown()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (::rime.isInitialized) rime.shutdown()
        activeInstance = null
        super.onDestroy()
    }

    internal fun handleTestCommand(command: String): Boolean = when {
        command.startsWith("tap:") ->
            keyboardView?.tapTestTarget(command.substringAfter("tap:")) == true
        command.startsWith("longtap:") ->
            keyboardView?.findTestTarget(command.substringAfter("longtap:"))?.performLongClick() == true
        command.startsWith("type:") -> {
            command.substringAfter("type:").forEach { ch -> onCharacter(ch.toString()) }
            true
        }
        command.startsWith("type64:") -> runCatching {
            val text = String(
                java.util.Base64.getDecoder().decode(command.substringAfter("type64:")),
            )
            text.forEach { ch -> onCharacter(ch.toString()) }
            true
        }.getOrDefault(false)
        command == "bounds" -> {
            Log.i(TAG, "BOUNDS\n${keyboardView?.normalizedBoundsReport().orEmpty()}")
            true
        }
        command == "state" -> {
            Log.i(
                TAG,
                "STATE mode=${state.keyboardMode} panel=${state.panel} " +
                    "compositionLength=${lastComposition.length} voice=${isVoiceActive()}",
            )
            true
        }
        else -> false
    }

    internal fun currentMode(): KeyboardMode = state.keyboardMode

    internal fun isVoiceActive(): Boolean = keyboardView?.isVoiceActive() == true

    override fun onModeChanged(mode: KeyboardMode) {
        Log.i(TAG, "mode=$mode")
        commitPendingComposition()
        rime.clear()
        state = state.withMode(mode)
        lastComposition = ""
        keyboardView?.renderState(state)
    }

    override fun onPanelChanged(panel: Panel) {
        state = state.copy(panel = panel)
        if (panel != Panel.GAMING && floatingWindowEnabled) restoreImeWindow()
    }

    override fun onCharacter(char: String) {
        commitPendingComposition()
        keyboardView?.clearAssociationCandidates()
        gateway.commitText(char)
    }

    override fun onBackspace() {
        keyboardView?.clearAssociationCandidates()
        if (lastComposition.isNotEmpty()) {
            val next = dropLastCodePoint(lastComposition)
            updateComposition(next, candidatesFor(next))
            // The view normally updates itself before this callback. If the
            // visible pre-edit field lost focus, however, the service owns the
            // deletion and must also remove stale candidate chips.
            keyboardView?.renderState(state)
        } else {
            gateway.deleteBackwards()
            clearImeCompositionState(render = true)
        }
    }

    override fun onClearAll() {
        gateway.clearAllText()
        clearImeCompositionState(render = true)
    }

    override fun onFloatingKeyboardChanged(floating: Boolean) {
        floatingWindowEnabled = floating
        if (floating) {
            scheduleFloatingWindowLayout(resetPosition = floatingWindowX == 0 && floatingWindowY == 0)
        } else {
            restoreImeWindow()
        }
    }

    override fun onFloatingKeyboardDragged(deltaX: Float, deltaY: Float) {
        if (!floatingWindowEnabled) return
        floatingWindowX += deltaX.toInt()
        floatingWindowY += deltaY.toInt()
        applyFloatingWindowLayout()
    }

    override fun onSpace() {
        if (state.passwordField) {
            keyboardView?.clearAssociationCandidates()
            gateway.commitText(" ")
            return
        }
        if (lastComposition.isNotEmpty()) {
            selectCandidate(state.candidates.firstOrNull() ?: lastComposition)
            return
        }
        keyboardView?.clearAssociationCandidates()
        gateway.commitText(" ")
    }

    override fun onVoiceToggle() {
        keyboardView?.toggleVoiceFromSpace()
    }

    override fun onVoicePressChanged(pressed: Boolean) {
        if (pressed) {
            commitPendingComposition()
            keyboardView?.startVoiceFromSpace()
        } else {
            keyboardView?.stopVoiceFromSpace()
        }
    }

    override fun onVoicePartial(text: String) {
        if (state.passwordField || text.isBlank()) return
        voiceComposing = true
        gateway.setComposingText(text)
        state = state.copy(
            voiceState = state.voiceState.copy(
                listening = true,
                partialText = text,
                message = "",
            ),
        )
    }

    override fun onVoiceFinal(text: String) {
        if (!state.passwordField && text.isNotBlank()) {
            gateway.setComposingText(text)
            gateway.finishComposing()
        }
        voiceComposing = false
        state = state.copy(
            voiceState = state.voiceState.copy(
                listening = false,
                partialText = "",
                finalText = text,
                message = "",
            ),
        )
    }

    override fun onVoiceError(message: String) {
        if (voiceComposing) gateway.finishComposing()
        voiceComposing = false
        state = state.copy(
            voiceState = state.voiceState.copy(
                listening = false,
                message = message,
            ),
        )
    }

    override fun onEnter() {
        if (lastComposition.isNotEmpty()) {
            selectCandidate(state.candidates.firstOrNull() ?: lastComposition)
            return
        }
        val action = state.editorAction
        when (action) {
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            -> gateway.performEditorAction(action)
            else -> gateway.sendKeyDownUp(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onCompositionChanged(composition: String, candidates: List<String>) {
        if (state.passwordField) {
            val ch = composition.lastOrNull() ?: return
            gateway.commitText(ch.toString())
            lastComposition = ""
            state = state.copy(composition = "", candidates = emptyList())
            keyboardView?.renderState(state)
            return
        }
        val resolvedCandidates = candidatesFor(composition)
        updateComposition(
            composition,
            resolvedCandidates.ifEmpty { candidates },
        )
        // The view renders a fast local result first, then receives the
        // authoritative librime ordering in the same callback.
        keyboardView?.renderState(state)
    }

    override fun onCandidateSelected(candidate: String) {
        if (state.passwordField) return
        selectCandidate(candidate)
    }

    override fun onCompositionBackspace() {
        onBackspace()
    }

    override fun onThemeChanged(theme: ImeTheme) {
        state = state.copy(theme = ImeTheme.IOS)
        ImeSettingsRepository.saveTheme(this, ImeTheme.IOS)
    }

    override fun onAppearanceChanged(appearance: ImeAppearance) {
        state = state.copy(appearance = appearance)
        ImeSettingsRepository.saveAppearance(this, appearance)
    }

    override fun onSoundChanged(enabled: Boolean) {
        state = state.copy(soundEnabled = enabled)
        ImeSettingsRepository.saveSound(this, enabled)
    }

    override fun onHapticChanged(enabled: Boolean) {
        state = state.copy(hapticEnabled = enabled)
        ImeSettingsRepository.saveHaptic(this, enabled)
    }

    override fun onPopupChanged(enabled: Boolean) {
        state = state.copy(popupEnabled = enabled)
        ImeSettingsRepository.savePopup(this, enabled)
    }

    override fun onFuzzyChanged(enabled: Boolean) {
        state = state.copy(fuzzyPinyinEnabled = enabled)
        ImeSettingsRepository.saveFuzzy(this, enabled)
    }

    override fun onSkinChanged(opacity: Int, radius: Int, fontSize: Int, primaryColor: String) {
        state = state.copy(
            skinOpacity = opacity,
            skinRadius = radius,
            skinFontSize = fontSize,
            skinPrimaryColor = primaryColor,
        )
        ImeSettingsRepository.saveSkin(this, opacity, radius, fontSize, primaryColor)
    }

    override fun onShiftStateChanged(state: ShiftState) {
        this.state = this.state.copy(shiftState = state)
    }

    override fun onCandidateExpanded(open: Boolean) {
        state = state.copy(panel = if (open) Panel.CANDIDATE_EXPANDED else Panel.NONE)
    }

    override fun onSymbolSelected(symbol: String) {
        commitPendingComposition()
        keyboardView?.clearAssociationCandidates()
        gateway.commitText(symbol)
    }

    override fun onEmojiSelected(emoji: String) {
        commitPendingComposition()
        keyboardView?.clearAssociationCandidates()
        gateway.commitText(emoji)
    }

    override fun onTextEdit(action: String) {
        when (action) {
            "select-all" -> gateway.selectAll()
            "copy" -> {
                val selected = gateway.copySelection()
                if (selected.isNotEmpty()) {
                    ClipboardHistoryRepository.add(this, selected)
                    gateway.copyToClipboard(selected)
                }
            }
            "cut" -> {
                val selected = gateway.copySelection()
                if (selected.isNotEmpty()) {
                    ClipboardHistoryRepository.add(this, selected)
                    gateway.copyToClipboard(selected)
                    gateway.deleteBackwards()
                }
            }
            "paste" -> {
                val text = gateway.pasteClipboard()
                if (text.isNotEmpty()) ClipboardHistoryRepository.add(this, text)
            }
            "left" -> {
                val start = gateway.currentSelectionStart()
                gateway.selectStartEnd(start.coerceAtLeast(1) - 1, start.coerceAtLeast(1) - 1)
            }
            "right" -> {
                val end = gateway.currentSelectionEnd()
                gateway.selectStartEnd(end + 1, end + 1)
            }
            "up", "down", "undo" -> {
                // These depend on the target editor; no fake implementation here.
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && keyboardView?.closePanelToKeyboard() == true) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun updateComposition(next: String, candidates: List<String>) {
        lastComposition = next
        state = state.copy(composition = next, candidates = candidates)
        if (next.isEmpty()) {
            gateway.finishComposing()
        } else {
            gateway.setComposingText(next)
        }
    }

    /**
     * Finish a pending composing string before inserting a non-composing item.
     * This is what makes punctuation, emoji, symbols and mode changes behave
     * like a normal IME instead of concatenating the next key into raw pinyin.
     */
    private fun commitPendingComposition() {
        if (lastComposition.isEmpty()) return
        selectCandidate(state.candidates.firstOrNull() ?: lastComposition)
    }

    private fun candidatesFor(composition: String): List<String> = when (state.keyboardMode) {
        KeyboardMode.ENGLISH_26 -> engine.getEnglishCompletions(composition)
        KeyboardMode.PINYIN_26,
        KeyboardMode.PINYIN_9,
        -> {
            val fallback = engine.getCandidates(composition, state.fuzzyPinyinEnabled)
            val native = rime.candidates(composition)
            if (native.isEmpty()) fallback else (native + fallback).distinct().take(96)
        }
        else -> emptyList()
    }

    private fun selectCandidate(candidate: String) {
        val composition = lastComposition
        val nativeCommit = if (
            state.keyboardMode == KeyboardMode.PINYIN_26 ||
            state.keyboardMode == KeyboardMode.PINYIN_9
        ) {
            rime.selectCandidate(composition, candidate)
        } else {
            ""
        }
        val committed = nativeCommit.ifBlank { candidate }
        UserPhraseRepository.record(composition, committed)
        gateway.commitText(committed)
        gateway.finishComposing()
        rime.clear()
        lastComposition = ""
        state = state.copy(composition = "", candidates = emptyList())
        keyboardView?.renderState(state)
        keyboardView?.setAssociationCandidates(engine.getAssociations(committed))
    }

    private fun dropLastCodePoint(text: String): String {
        if (text.isEmpty()) return text
        val last = text.last()
        if (Character.isHighSurrogate(last) && text.length >= 2 && Character.isLowSurrogate(text[text.length - 2])) {
            return text.substring(0, text.length - 2)
        }
        return text.substring(0, text.length - 1)
    }

    /**
     * The floating keyboard is an actual IME window, not a card translated
     * inside the 296dp keyboard view.  That keeps it draggable over the whole
     * display while retaining the system IME token and editor connection.
     */
    private fun scheduleFloatingWindowLayout(resetPosition: Boolean) {
        mainHandler.post {
            val imeWindow = getWindow().window ?: return@post
            val attrs = imeWindow.attributes
            if (baseImeGravity == null) {
                baseImeGravity = attrs.gravity
                baseImeWidth = attrs.width
                baseImeHeight = attrs.height
            }
            val (screenWidth, screenHeight) = displaySize()
            val desiredWidth = minOf(dp(600), (screenWidth - dp(10)).coerceAtLeast(dp(1)))
            val viewLocation = IntArray(2)
            keyboardView?.getLocationOnScreen(viewLocation)
            val currentHeight = (keyboardView?.measuredHeight ?: dp(296)).coerceAtLeast(dp(1))
            if (resetPosition) {
                floatingWindowX = ((screenWidth - desiredWidth) / 2).coerceAtLeast(0)
                val existingTop = viewLocation[1]
                floatingWindowY = (if (existingTop > 0) existingTop else screenHeight - currentHeight)
                    .coerceIn(0, (screenHeight - currentHeight).coerceAtLeast(0))
            }
            attrs.gravity = Gravity.TOP or Gravity.START
            attrs.width = desiredWidth
            attrs.height = WindowManager.LayoutParams.WRAP_CONTENT
            attrs.x = floatingWindowX.coerceIn(0, (screenWidth - desiredWidth).coerceAtLeast(0))
            attrs.y = floatingWindowY.coerceIn(0, (screenHeight - currentHeight).coerceAtLeast(0))
            imeWindow.attributes = attrs
            Log.d(TAG, "floating-window x=${attrs.x} y=${attrs.y} w=${attrs.width} h=${attrs.height}")
        }
    }

    private fun applyFloatingWindowLayout() {
        if (!floatingWindowEnabled) return
        val imeWindow = getWindow().window ?: return
        val (screenWidth, screenHeight) = displaySize()
        val attrs = imeWindow.attributes
        val width = if (attrs.width > 0) attrs.width else minOf(dp(600), screenWidth - dp(10))
        val height = (imeWindow.decorView.height.takeIf { it > 0 } ?: dp(296))
        floatingWindowX = floatingWindowX.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
        floatingWindowY = floatingWindowY.coerceIn(0, (screenHeight - height).coerceAtLeast(0))
        attrs.gravity = Gravity.TOP or Gravity.START
        attrs.x = floatingWindowX
        attrs.y = floatingWindowY
        imeWindow.attributes = attrs
        Log.d(TAG, "floating-window-drag x=$floatingWindowX y=$floatingWindowY")
    }

    private fun restoreImeWindow() {
        mainHandler.post {
            val imeWindow = getWindow().window ?: return@post
            val attrs = imeWindow.attributes
            attrs.gravity = baseImeGravity ?: Gravity.BOTTOM
            attrs.width = baseImeWidth ?: WindowManager.LayoutParams.MATCH_PARENT
            attrs.height = baseImeHeight ?: WindowManager.LayoutParams.WRAP_CONTENT
            attrs.x = 0
            attrs.y = 0
            imeWindow.attributes = attrs
            floatingWindowEnabled = false
            floatingWindowX = 0
            floatingWindowY = 0
            Log.d(TAG, "floating-window-restored")
        }
    }

    private fun displaySize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun clearImeCompositionState(render: Boolean) {
        lastComposition = ""
        state = state.copy(composition = "", candidates = emptyList())
        keyboardView?.clearAssociationCandidates()
        if (render) keyboardView?.renderState(state)
    }

    internal companion object {
        const val TAG = "MinisIme"
        @Volatile
        var activeInstance: LocalVoiceImeService? = null
    }
}
