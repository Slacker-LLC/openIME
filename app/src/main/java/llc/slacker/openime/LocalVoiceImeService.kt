package llc.slacker.openime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Native system IME service. The view is a thin native renderer; all candidate
 * state, editor side effects and privacy rules live here.
 */
class LocalVoiceImeService : InputMethodService(), ImeKeyboardViewV2.Listener, CandidateResolver {

    private data class CandidateDiagnostics(
        val learnedCount: Int = 0,
        val nativeCount: Int = 0,
        val fallbackCount: Int = 0,
        val nativeLatencyMs: Long = 0L,
        val pathCount: Int = 0,
        val finalCandidateSource: String = "none",
    ) {
        fun asLogFields(): String =
            "learnedCount=$learnedCount nativeCount=$nativeCount " +
                "fallbackCount=$fallbackCount nativeLatencyMs=$nativeLatencyMs " +
                "pathCount=$pathCount finalCandidateSource=$finalCandidateSource"
    }

    private data class NativeQueryResult(
        val choices: List<NativeCandidateChoice>,
        val latencyMs: Long,
    )

    private data class PendingVoiceCorrection(
        val range: VoiceCorrectionRange,
        var edited: Boolean = false,
    )

    private var keyboardView: ImeKeyboardView? = null
    private lateinit var gateway: InputConnectionGateway
    private lateinit var candidatePipeline: CandidatePipeline
    private lateinit var rime: RimeEngine
    private lateinit var voiceLifecycle: VoiceModelLifecycleManager
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
    private var voiceAutoCommitOnFinal = true
    private var pendingVoiceCorrection: PendingVoiceCorrection? = null
    private val candidateExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ime-candidates").apply { isDaemon = true }
    }
    private val candidateGeneration = AtomicLong(0L)
    private var renderedCandidateSnapshot: CandidateSnapshot? = null
    private var activeRimeInputs: List<String> = emptyList()
    @Volatile
    private var candidateDiagnostics = CandidateDiagnostics()

    /**
     * candidate-stats fires from every async librime callback, i.e. once per
     * key. Logcat is a synchronous binder round-trip; keeping it on in release
     * costs real input latency and it was never gated.
     */
    private val verboseLogging: Boolean by lazy {
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

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
        override fun onVoiceSessionStarted(autoCommitOnFinal: Boolean) =
            this@LocalVoiceImeService.onVoiceSessionStarted(autoCommitOnFinal)
        override fun onVoicePartial(text: String) = this@LocalVoiceImeService.onVoicePartial(text)
        override fun onVoiceFinal(text: String) = this@LocalVoiceImeService.onVoiceFinal(text)
        override fun onVoiceError(message: String) = this@LocalVoiceImeService.onVoiceError(message)
        override fun onVoiceCommit() = this@LocalVoiceImeService.onVoiceCommit()
        override fun onVoiceCancel() = this@LocalVoiceImeService.onVoiceCancel()
        override fun voiceModelState() = this@LocalVoiceImeService.voiceModelState()
        override fun startVoiceRecognition(languageTag: String, events: VoiceRecognitionEvents) =
            this@LocalVoiceImeService.startVoiceRecognition(languageTag, events)
        override fun stopVoiceRecognition() = this@LocalVoiceImeService.stopVoiceRecognition()
        override fun cancelVoiceRecognition() = this@LocalVoiceImeService.cancelVoiceRecognition()
        override fun onEnter() = this@LocalVoiceImeService.onEnter()
        override fun onCompositionChanged(composition: String, candidates: List<String>) =
            this@LocalVoiceImeService.onCompositionChanged(composition, candidates)
        override fun onNineKeyCompositionChanged(
            composition: String,
            digitBuffer: String,
            pinyinPaths: List<String>,
            candidates: List<String>,
        ) = this@LocalVoiceImeService.onNineKeyCompositionChanged(
            composition,
            digitBuffer,
            pinyinPaths,
            candidates,
        )
        override fun onCandidateSelected(candidate: String) =
            this@LocalVoiceImeService.onCandidateSelected(candidate)
        override fun onAssociationSelected(text: String) =
            this@LocalVoiceImeService.onAssociationSelected(text)
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
        VoiceCorrectionRepository.configure(this)
        voiceLifecycle = VoiceModelLifecycleManager(this)
        candidatePipeline = CandidatePipeline(CandidateEngine(PinyinLexicon.load(this)))
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

    override fun candidatesFor(
        mode: KeyboardMode,
        composition: String,
        fuzzy: Boolean,
    ): List<String> = candidatePipeline.candidatesFor(mode, composition, fuzzy)

    override fun resolveNineKey(
        digits: String,
        segmentPrefix: String,
        preferredSuffix: String?,
        fuzzy: Boolean,
    ): CandidatePipeline.NineKeyResolution = candidatePipeline.resolveNineKey(
        digits = digits,
        segmentPrefix = segmentPrefix,
        preferredSuffix = preferredSuffix,
        fuzzy = fuzzy,
    )

    override fun onCreateInputView(): View {
        keyboardView = ImeKeyboardViewV2(this, this)
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
        invalidateCandidateQueries()
        val kind = EditorInfoAdapter.kind(attribute)
        // Android restarts the same field after a rotation, a window resize or
        // a multi-window transition. Dropping the pre-edit there throws away
        // what the user was mid-way through typing; only a genuinely new
        // editor resets the composition.
        val preserve = restarting && state.composition.isNotEmpty()
        val nextMode = if (restarting) {
            state.keyboardMode
        } else {
            InputMethodSubtypePolicy.defaultKeyboardMode(kind, currentSystemSubtypeLocale())
        }
        state = state.copy(
            editorInfo = attribute,
            editorAction = attribute?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                ?: EditorInfo.IME_ACTION_NONE,
            passwordField = EditorInfoAdapter.isPassword(kind),
            keyboardMode = nextMode,
            panel = Panel.NONE,
            composition = if (preserve) state.composition else "",
            candidates = if (preserve) state.candidates else emptyList(),
            // Everything below is per-editor contract state. None of it was
            // reset here before, so a Caps Lock or a nine-key filter picked in
            // one app leaked into the next editor.
            shiftState = ShiftState.LOWERCASE,
            pinyin9Filters = emptyList(),
            selectedPinyin9Filter = "",
            expandedCandidates = emptyList(),
            voiceState = VoiceUiState(),
        )
        if (!preserve) lastComposition = ""
        rime.clear()
        keyboardView?.clearAssociationCandidates()
        keyboardView?.setShiftState(ShiftState.LOWERCASE)
        keyboardView?.setMode(state.keyboardMode)
        keyboardView?.renderState(state)
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        if (!::gateway.isInitialized || !::rime.isInitialized) return

        @Suppress("DEPRECATION")
        val nextMode = InputMethodSubtypePolicy.defaultKeyboardMode(
            EditorInfoAdapter.kind(state.editorInfo),
            newSubtype.locale,
        )

        // A subtype switch changes the input language contract. Discard the old
        // pre-edit rather than committing it under the newly selected language.
        clearImeCompositionState(render = false)
        gateway.cancelComposing()
        voiceComposing = false
        pendingVoiceCorrection = null
        state = state.copy(
            panel = Panel.NONE,
            composition = "",
            candidates = emptyList(),
            expandedCandidates = emptyList(),
            pinyin9Filters = emptyList(),
            selectedPinyin9Filter = "",
        )
        if (keyboardView != null) {
            // setMode clears the View-side 26/9-key buffers and synchronously
            // feeds the effective mode back through onModeChanged().
            keyboardView?.setMode(nextMode)
        } else {
            state = state.withMode(nextMode)
        }
        keyboardView?.renderState(state)
    }

    override fun onStartInputView(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        if (keyboardView == null) onCreateInputView()
        if (state.panel != Panel.GAMING) restoreImeWindow()
        voiceLifecycle.onStartInputView()
    }

    override fun onFinishInput() {
        invalidateCandidateQueries()
        finalizeVoiceCorrectionIfNeeded()
        keyboardView?.shutdown()
        gateway.cancelComposing()
        rime.clear()
        voiceComposing = false
        lastComposition = ""
        state = state.copy(composition = "", candidates = emptyList())
        pendingVoiceCorrection = null
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyboardView?.shutdown()
        if (::voiceLifecycle.isInitialized) voiceLifecycle.onFinishInputView()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        if (!shouldClearCompositionForSelectionUpdate(
                hasComposition = lastComposition.isNotEmpty(),
                oldSelStart = oldSelStart,
                oldSelEnd = oldSelEnd,
                newSelStart = newSelStart,
                newSelEnd = newSelEnd,
                candidatesStart = candidatesStart,
                candidatesEnd = candidatesEnd,
            )
        ) return

        // Drop IME-side state before touching the editor so any callback caused
        // by cancelComposing() observes an already-empty composition and cannot
        // recursively invalidate a new one.
        clearImeCompositionState(render = false)
        gateway.cancelComposing()
        keyboardView?.renderState(state)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        candidateExecutor.shutdownNow()
        invalidateCandidateQueries()
        // The keyboard view owns a Handler with pending key-repeat callbacks
        // and holds this service as its listener. Releasing it here keeps the
        // view tree (and its Context reference) from outliving the service.
        keyboardView?.shutdown()
        keyboardView = null
        if (::rime.isInitialized) rime.shutdown()
        if (::voiceLifecycle.isInitialized) voiceLifecycle.destroy()
        activeInstance = null
        super.onDestroy()
    }

    internal fun handleTestCommand(command: String): Boolean = when {
        command.startsWith("tap:") ->
            keyboardView?.tapTestTarget(command.substringAfter("tap:")) == true
        command.startsWith("longtap:") ->
            keyboardView?.findTestTarget(command.substringAfter("longtap:"))?.performLongClick() == true
        command.startsWith("type:") -> {
            typeForTest(command.substringAfter("type:"))
            true
        }
        command.startsWith("type64:") -> runCatching {
            // UTF-8 explicitly: the platform default is not guaranteed to be
            // UTF-8, while the other base64 commands in this switch already
            // decode as UTF-8.
            typeForTest(decodeBase64Utf8(command.substringAfter("type64:")))
            true
        }.getOrDefault(false)
        command.startsWith("nine-sequence:") -> {
            val digits = command.substringAfter("nine-sequence:")
                .filter { it in '2'..'9' }
                .take(CandidateEngine.MAX_NINE_KEY_DIGITS)
            digits.isNotEmpty() && digits.all { digit ->
                keyboardView?.tapTestTarget(digit.toString()) == true
            }
        }
        command == "clear-swipe" -> keyboardView?.swipeClearForTest() == true
        command == "voice-press" -> {
            onVoicePressChanged(true)
            true
        }
        command == "voice-release" -> {
            onVoicePressChanged(false)
            true
        }
        command.startsWith("voice-simulate64:") -> runCatching {
            val text = String(
                java.util.Base64.getDecoder().decode(command.substringAfter("voice-simulate64:")),
                Charsets.UTF_8,
            )
            if (text.isBlank() || state.passwordField) {
                false
            } else {
                VoicePerformanceTrace.abandon()
                // Emulator audio is nondeterministic. Feed a deterministic
                // result through the exact same composing/final callbacks as
                // the local recognizer while the real long-press UI is tested
                // separately by voice-press/voice-release.
                onVoiceSessionStarted(autoCommitOnFinal = true)
                onVoicePartial(text)
                onVoiceFinal(text)
                true
            }
        }.getOrDefault(false)
        command.startsWith("voice-final-only64:") -> runCatching {
            val text = String(
                java.util.Base64.getDecoder().decode(command.substringAfter("voice-final-only64:")),
                Charsets.UTF_8,
            )
            if (text.isBlank() || state.passwordField) {
                false
            } else {
                VoicePerformanceTrace.abandon()
                onVoiceSessionStarted(autoCommitOnFinal = true)
                onVoiceFinal(VoiceCorrectionRepository.apply(text))
                true
            }
        }.getOrDefault(false)
        command.startsWith("quick-phrase-edit64:") -> runCatching {
            val text = String(
                java.util.Base64.getDecoder().decode(command.substringAfter("quick-phrase-edit64:")),
                Charsets.UTF_8,
            )
            text.isNotBlank() && keyboardView?.editQuickPhraseForTest(text) == true
        }.getOrDefault(false)
        command.startsWith("quick-phrase-use64:") -> runCatching {
            val text = String(
                java.util.Base64.getDecoder().decode(command.substringAfter("quick-phrase-use64:")),
                Charsets.UTF_8,
            )
            text.isNotBlank() && keyboardView?.useQuickPhraseForTest(text) == true
        }.getOrDefault(false)
        command.startsWith("quick-phrase-delete64:") -> runCatching {
            val text = String(
                java.util.Base64.getDecoder().decode(command.substringAfter("quick-phrase-delete64:")),
                Charsets.UTF_8,
            )
            text.isNotBlank() && keyboardView?.deleteQuickPhraseForTest(text) == true
        }.getOrDefault(false)
        command.startsWith("quick-phrase-exists64:") -> runCatching {
            val text = String(
                java.util.Base64.getDecoder().decode(command.substringAfter("quick-phrase-exists64:")),
                Charsets.UTF_8,
            )
            text.isNotBlank() && QuickPhraseRepository.load(this).any { it.text == text }
        }.getOrDefault(false)
        command == "bounds" -> {
            Log.i(TAG, "BOUNDS\n${keyboardView?.normalizedBoundsReport().orEmpty()}")
            true
        }
        command == "state" -> {
            Log.i(
                TAG,
                "STATE mode=${state.keyboardMode} panel=${state.panel} " +
                    "editorLength=${gateway.currentTextLength()} " +
                    "compositionLength=${lastComposition.length} voice=${isVoiceActive()} " +
                    "voiceComposing=$voiceComposing " +
                    candidateDiagnostics.asLogFields(),
            )
            true
        }
        else -> false
    }

    private fun decodeBase64Utf8(payload: String): String =
        String(java.util.Base64.getDecoder().decode(payload), Charsets.UTF_8)

    /**
     * Feed text one *code point* per call. Iterating Char-by-Char split every
     * surrogate pair, so a test could never exercise emoji input through the
     * same path a user takes.
     */
    private fun typeForTest(text: String) {
        var index = 0
        while (index < text.length) {
            val end = Character.offsetByCodePoints(text, index, 1)
            onCharacter(text.substring(index, end))
            index = end
        }
    }

    internal fun currentMode(): KeyboardMode = state.keyboardMode

    internal fun isVoiceActive(): Boolean = keyboardView?.isVoiceActive() == true

    internal fun isRimeReadyForTest(): Boolean = ::rime.isInitialized && rime.isReady

    internal fun compositionLengthForTest(): Int = lastComposition.length

    internal fun voiceComposingForTest(): Boolean = voiceComposing
    internal fun voiceModelStateForTest(): VoiceModelLifecycleState = voiceModelState()

    internal fun editorTextLengthForTest(): Int = gateway.currentTextLength()

    internal fun candidateDiagnosticsForTest(): String = candidateDiagnostics.asLogFields()

    override fun onModeChanged(mode: KeyboardMode) {
        if (verboseLogging) Log.i(TAG, "mode=$mode")
        commitPendingComposition()
        finalizeVoiceCorrectionIfNeeded()
        invalidateCandidateQueries()
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
        noteVoiceReplacementInput()
        commitPendingComposition()
        keyboardView?.clearAssociationCandidates()
        gateway.commitText(char)
    }

    override fun onBackspace() {
        noteVoiceBackspace()
        if (keyboardView?.deleteInlineEditorChar() == true) return
        keyboardView?.clearAssociationCandidates()
        if (lastComposition.isNotEmpty()) {
            val next = dropLastCodePoint(lastComposition)
            val mode = state.keyboardMode
            val fallback = fallbackCandidatesFor(next, mode)
            val immediate = immediateCandidates(next, fallback)
            updateComposition(next, immediate)
            val generation = requestNativeCandidates(next, mode, fallback)
            renderedCandidateSnapshot = CandidateSnapshot.rendered(
                generation = generation,
                composition = next,
                mode = mode,
                candidates = immediate,
            )
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
        // Invalidate every pending candidate/Rime path before touching the
        // editor. Otherwise a late native result can restore the just-cleared
        // pre-edit on the very next key press.
        clearImeCompositionState(render = false)
        gateway.clearAllText()
        pendingVoiceCorrection = null
        voiceComposing = false
        state = state.copy(voiceState = VoiceUiState())
        keyboardView?.renderState(state)
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
        if (keyboardView?.insertIntoInlineEditor(" ") == true) return
        if (state.passwordField) {
            keyboardView?.clearAssociationCandidates()
            gateway.commitText(" ")
            return
        }
        if (lastComposition.isNotEmpty()) {
            commitFirstCandidate()
            return
        }
        finalizeVoiceCorrectionIfNeeded()
        keyboardView?.clearAssociationCandidates()
        gateway.commitText(" ")
    }

    override fun onVoiceToggle() {
        keyboardView?.toggleVoiceFromSpace()
    }

    override fun onVoicePressChanged(pressed: Boolean) {
        if (pressed) {
            commitPendingComposition()
            finalizeVoiceCorrectionIfNeeded()
            voiceAutoCommitOnFinal = true
            keyboardView?.startVoiceFromSpace()
        } else {
            keyboardView?.stopVoiceFromSpace()
        }
    }

    override fun onVoiceSessionStarted(autoCommitOnFinal: Boolean) {
        voiceAutoCommitOnFinal = autoCommitOnFinal
    }

    override fun voiceModelState(): VoiceModelLifecycleState =
        if (::voiceLifecycle.isInitialized) {
            voiceLifecycle.currentState()
        } else {
            VoiceModelLifecycleState.COLD
        }

    override fun startVoiceRecognition(languageTag: String, events: VoiceRecognitionEvents) {
        if (::voiceLifecycle.isInitialized) {
            voiceLifecycle.start(languageTag, events)
        } else {
            events.onError("本地语音服务尚未初始化")
        }
    }

    override fun stopVoiceRecognition() {
        if (::voiceLifecycle.isInitialized) voiceLifecycle.stop()
    }

    override fun cancelVoiceRecognition() {
        if (::voiceLifecycle.isInitialized) voiceLifecycle.cancel()
    }

    override fun onVoicePartial(text: String) {
        if (state.passwordField || text.isBlank()) return
        voiceComposing = true
        gateway.setComposingText(text)
        VoicePerformanceTrace.markFirstDisplay()
        state = state.copy(
            voiceState = state.voiceState.copy(
                listening = true,
                partialText = text,
                message = "",
            ),
        )
    }

    override fun onVoiceFinal(text: String) {
        val plan = VoiceFinalPolicy.resolve(
            passwordField = state.passwordField,
            hadPartialComposition = voiceComposing,
            autoCommit = voiceAutoCommitOnFinal,
            finalText = text,
        )
        if (plan.setFinalText) {
            gateway.setComposingText(text)
            VoicePerformanceTrace.markFirstDisplay()
        }
        if (plan.finishComposing) gateway.finishComposing()
        voiceComposing = plan.composingAfter
        if (plan.finishComposing && text.isNotBlank()) beginVoiceCorrection(text)
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
        if (voiceComposing) gateway.cancelComposing()
        voiceComposing = false
        state = state.copy(
            voiceState = state.voiceState.copy(
                listening = false,
                message = message,
            ),
        )
    }

    override fun onVoiceCommit() {
        if (!state.passwordField && voiceComposing) gateway.finishComposing()
        voiceComposing = false
        state = state.copy(
            voiceState = state.voiceState.copy(
                listening = false,
                partialText = "",
                message = "",
            ),
        )
    }

    override fun onVoiceCancel() {
        if (!state.passwordField && voiceComposing) gateway.cancelComposing()
        voiceComposing = false
        state = state.copy(
            voiceState = state.voiceState.copy(
                listening = false,
                partialText = "",
                finalText = "",
                message = "语音输入已取消",
            ),
        )
    }

    override fun onEnter() {
        if (lastComposition.isNotEmpty()) {
            commitFirstCandidate()
            return
        }
        finalizeVoiceCorrectionIfNeeded()
        val action = state.editorInfo?.imeOptions?.let(::editorActionForEnter)
        if (action != null) {
            gateway.performEditorAction(action)
        } else {
            gateway.sendKeyDownUp(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onCompositionChanged(composition: String, candidates: List<String>) {
        if (composition.isNotEmpty()) noteVoiceReplacementInput()
        handleCompositionChanged(
            composition = composition,
            candidates = candidates,
            rimeInputs = listOf(composition),
        )
    }

    override fun onNineKeyCompositionChanged(
        composition: String,
        digitBuffer: String,
        pinyinPaths: List<String>,
        candidates: List<String>,
    ) {
        if (state.keyboardMode != KeyboardMode.PINYIN_9 || digitBuffer.isEmpty()) {
            onCompositionChanged(composition, candidates)
            return
        }
        handleCompositionChanged(
            composition = composition,
            candidates = candidates,
            rimeInputs = pinyinPaths,
        )
    }

    private fun handleCompositionChanged(
        composition: String,
        candidates: List<String>,
        rimeInputs: List<String>,
    ) {
        if (state.passwordField) {
            // Password fields never receive composing text, so the view's
            // buffer is the only holder of pending input and renderState()
            // empties it on every report. The buffer therefore contains
            // exactly what is new since the last report — which can be more
            // than one character when the user pastes or edits the pre-edit
            // field. Taking only the last Char dropped everything before it,
            // and splitting a surrogate pair produced invalid UTF-16 in the
            // editor. Commit the whole delta.
            if (composition.isEmpty()) return
            gateway.commitText(composition)
            lastComposition = ""
            state = state.copy(composition = "", candidates = emptyList())
            renderedCandidateSnapshot = null
            keyboardView?.renderState(state)
            return
        }
        val modeAtRequest = state.keyboardMode
        // The service-owned pipeline has already produced the bounded local
        // result for this key event. Reuse that immutable list while the native
        // Rime query runs instead of performing dictionary work twice.
        val fallback = candidates.ifEmpty {
            fallbackCandidatesFor(composition, modeAtRequest)
        }
        val immediate = immediateCandidates(composition, fallback)
        updateComposition(
            composition,
            immediate,
        )
        val generation = requestNativeCandidates(composition, modeAtRequest, fallback, rimeInputs)
        renderedCandidateSnapshot = CandidateSnapshot.rendered(
            generation = generation,
            composition = composition,
            mode = modeAtRequest,
            candidates = immediate,
        )
        // The view renders this exact snapshot first. A later native callback
        // replaces both the rendered list and its immutable commit identity.
        keyboardView?.renderState(state)
    }

    override fun onCandidateSelected(candidate: String) {
        if (state.passwordField) return
        selectCandidate(candidate)
    }

    /**
     * Association ("联想") chips are produced only after a commit, so there is
     * no composition for [selectCandidate] to match against. Commit the word
     * directly and chain to the next association set so a user can keep
     * tapping: 你好 -> 呀 -> ！
     */
    override fun onAssociationSelected(text: String) {
        if (state.passwordField || text.isEmpty()) return
        commitPendingComposition()
        gateway.commitText(text)
        gateway.finishComposing()
        keyboardView?.clearAssociationCandidates()
        keyboardView?.setAssociationCandidates(candidatePipeline.associationsFor(text))
    }

    override fun onCompositionBackspace() {
        onBackspace()
    }

    override fun onThemeChanged(theme: ImeTheme) {
        // The requested theme used to be discarded here and IOS written back,
        // so every shipped skin except IOS was dead code.
        state = state.copy(theme = theme)
        ImeSettingsRepository.saveTheme(this, theme)
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
        // RimeEngine mirrors this value on the candidate hot path.
        if (::rime.isInitialized) rime.invalidateSettingsCache()
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
                    gateway.deleteSelection()
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
            // Finishing a non-empty composing span preserves it as committed
            // editor text in many apps. Deleting the final Pinyin character
            // must remove that span instead of leaving a raw letter behind.
            gateway.cancelComposing()
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
        commitFirstCandidate()
    }

    private fun fallbackCandidatesFor(composition: String, mode: KeyboardMode): List<String> =
        candidatePipeline.candidatesFor(mode, composition, state.fuzzyPinyinEnabled)

    /** The extra learner is only a repeated-choice fallback while Rime is unavailable. */
    private fun immediateCandidates(composition: String, fallback: List<String>): List<String> {
        val learned = if (!rime.isReady) {
            UserPhraseRepository.candidatesFor(composition)
        } else {
            emptyList()
        }
        candidateDiagnostics = CandidateDiagnostics(
            learnedCount = learned.size,
            fallbackCount = fallback.distinct().size,
            finalCandidateSource = when {
                learned.isNotEmpty() -> "learned_fallback_waiting"
                fallback.isNotEmpty() -> "fallback_waiting"
                else -> "none"
            },
        )
        return (learned + fallback).distinct().take(MAX_CANDIDATES)
    }

    /** Query librime away from the IME input thread; stale answers are ignored. */
    private fun requestNativeCandidates(
        composition: String,
        mode: KeyboardMode,
        fallback: List<String>,
        rimeInputs: List<String> = listOf(composition),
    ): Long {
        val request = candidateGeneration.incrementAndGet()
        val queryInputs = rimeInputs
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_RIME_INPUT_LENGTH }
            .distinct()
            .take(if (mode == KeyboardMode.PINYIN_9) MAX_RIME_NINE_KEY_PATHS else 1)
            .toList()
        activeRimeInputs = queryInputs
        if (
            composition.isBlank() ||
            composition.length > MAX_RIME_INPUT_LENGTH ||
            (mode != KeyboardMode.PINYIN_26 && mode != KeyboardMode.PINYIN_9) ||
            queryInputs.isEmpty()
        ) {
            candidateDiagnostics = CandidateDiagnostics(
                fallbackCount = fallback.distinct().size,
                finalCandidateSource = if (fallback.isEmpty()) "none" else "fallback",
            )
            return request
        }
        candidateExecutor.execute {
            // Coalesce a burst of key events before entering librime. Older
            // requests are already obsolete and must not build a native queue.
            if (candidateGeneration.get() != request) return@execute
            val query = queryNativeChoices(queryInputs) {
                candidateGeneration.get() != request
            } ?: return@execute
            val native = query.choices
            mainHandler.post {
                if (
                    candidateGeneration.get() != request ||
                    state.keyboardMode != mode ||
                    lastComposition != composition ||
                    activeRimeInputs != queryInputs
                ) return@post
                // Once Rime returns candidates, its mature dictionary and
                // userdb ordering replace the transient Kotlin preview. The
                // fallback is retained only when native has no answer.
                val learned = if (!rime.isReady && native.isEmpty()) {
                    UserPhraseRepository.candidatesFor(composition)
                } else {
                    emptyList()
                }
                val finalCandidates = if (native.isNotEmpty()) {
                    native.map { it.text }
                } else {
                    (learned + fallback).distinct().take(MAX_CANDIDATES)
                }
                val nativeReferences = if (native.isNotEmpty()) {
                    native.associate { it.text to it.reference }
                } else {
                    emptyMap()
                }
                candidateDiagnostics = CandidateDiagnostics(
                    learnedCount = learned.size,
                    nativeCount = native.size,
                    fallbackCount = fallback.distinct().size,
                    nativeLatencyMs = query.latencyMs,
                    pathCount = queryInputs.size,
                    finalCandidateSource = when {
                        native.isNotEmpty() -> "native"
                        learned.isNotEmpty() -> "learned_fallback"
                        finalCandidates.isNotEmpty() -> "fallback"
                        else -> "none"
                    },
                )
                if (verboseLogging) Log.d(TAG, "candidate-stats ${candidateDiagnostics.asLogFields()}")
                state = state.copy(candidates = finalCandidates)
                renderedCandidateSnapshot = CandidateSnapshot.rendered(
                    generation = request,
                    composition = composition,
                    mode = mode,
                    candidates = finalCandidates,
                    nativeReferences = nativeReferences,
                )
                keyboardView?.renderState(state)
            }
        }
        return request
    }

    private fun queryNativeChoices(
        inputs: List<String>,
        isCancelled: () -> Boolean = { false },
    ): NativeQueryResult? {
        val startedAt = SystemClock.elapsedRealtime()
        val batches = ArrayList<Pair<String, List<RimeCandidateEntry>>>(inputs.size)
        for (input in inputs) {
            if (isCancelled()) return null
            batches += input to rime.candidateEntries(input)
        }
        if (isCancelled()) return null
        return NativeQueryResult(
            choices = NativeCandidatePipeline.mergeRoundRobin(batches, MAX_CANDIDATES),
            latencyMs = SystemClock.elapsedRealtime() - startedAt,
        )
    }

    /** Commit only the first candidate that belongs to the currently rendered snapshot. */
    private fun commitFirstCandidate() {
        val composition = lastComposition
        if (composition.isEmpty()) return
        val mode = state.keyboardMode
        val entry = renderedCandidateSnapshot?.firstForCommit(
            currentGeneration = candidateGeneration.get(),
            currentComposition = composition,
            currentMode = mode,
        ) ?: return
        invalidateCandidateQueries()
        val reference = entry.nativeReference
        val nativeCommit = if (
            reference != null &&
            rime.isReady &&
            (mode == KeyboardMode.PINYIN_26 || mode == KeyboardMode.PINYIN_9)
        ) {
            rime.selectCandidate(reference.input, reference.nativeIndex)
        } else {
            ""
        }
        finishCandidateCommit(composition, nativeCommit.ifBlank { entry.text })
    }

    private fun selectCandidate(candidate: String) {
        val composition = lastComposition
        if (composition.isEmpty()) return
        val mode = state.keyboardMode
        val entry = renderedCandidateSnapshot?.candidateForCommit(
            candidate = candidate,
            currentGeneration = candidateGeneration.get(),
            currentComposition = composition,
            currentMode = mode,
        ) ?: return
        invalidateCandidateQueries()
        val reference = entry.nativeReference
        val nativeCommit = if (
            reference != null &&
            rime.isReady &&
            (mode == KeyboardMode.PINYIN_26 || mode == KeyboardMode.PINYIN_9)
        ) {
            rime.selectCandidate(reference.input, reference.nativeIndex)
        } else {
            ""
        }
        finishCandidateCommit(composition, nativeCommit.ifBlank { entry.text })
    }

    private fun finishCandidateCommit(composition: String, committed: String) {
        if (committed.isEmpty()) return
        // librime owns normal learning through its userdb. Keep the old local
        // repository only as an offline fallback; never run two unconditional
        // ranking systems over the same successful native selection.
        if (!rime.isReady) UserPhraseRepository.record(composition, committed)
        gateway.commitText(committed)
        gateway.finishComposing()
        if (pendingVoiceCorrection != null) {
            finalizeVoiceCorrectionIfNeeded()
        }
        rime.clear()
        lastComposition = ""
        state = state.copy(composition = "", candidates = emptyList())
        keyboardView?.renderState(state)
        keyboardView?.setAssociationCandidates(candidatePipeline.associationsFor(committed))
    }

    private fun beginVoiceCorrection(original: String) {
        if (state.passwordField || original.isBlank()) {
            pendingVoiceCorrection = null
            return
        }
        val snapshot = gateway.absoluteCursorSnapshot() ?: run {
            pendingVoiceCorrection = null
            return
        }
        val range = voiceCorrectionRange(original, snapshot)
        pendingVoiceCorrection = range?.let(::PendingVoiceCorrection)
    }

    private fun noteVoiceBackspace() {
        val pending = pendingVoiceCorrection ?: return
        val cursorAbsolute = gateway.absoluteCursorSnapshot()?.cursorAbsolute ?: run {
            pendingVoiceCorrection = null
            return
        }
        if (cursorAbsolute in (pending.range.startAbsolute + 1)..pending.range.endAbsolute) {
            pending.edited = true
        } else if (!pending.edited) {
            pendingVoiceCorrection = null
        }
    }

    private fun noteVoiceReplacementInput() {
        val pending = pendingVoiceCorrection ?: return
        // Typing at the end without first deleting any part of the ASR result
        // is ordinary continuation, not a correction pair.
        if (!pending.edited) pendingVoiceCorrection = null
    }

    private fun finalizeVoiceCorrectionIfNeeded() {
        val pending = pendingVoiceCorrection ?: return
        pendingVoiceCorrection = null
        if (!pending.edited || state.passwordField) return
        val snapshot = gateway.absoluteCursorSnapshot() ?: return
        val corrected = correctedVoiceText(pending.range, snapshot) ?: return
        VoiceCorrectionRepository.record(pending.range.original, corrected)
    }

    private fun dropLastCodePoint(text: String): String = dropLastCodePointSafe(text)

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

    @Suppress("DEPRECATION")
    private fun currentSystemSubtypeLocale(): String? =
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.currentInputMethodSubtype
            ?.locale

    private fun clearImeCompositionState(render: Boolean) {
        invalidateCandidateQueries()
        rime.clear()
        lastComposition = ""
        state = state.copy(composition = "", candidates = emptyList())
        keyboardView?.clearAssociationCandidates()
        if (render) keyboardView?.renderState(state)
    }

    private fun invalidateCandidateQueries() {
        candidateGeneration.incrementAndGet()
        renderedCandidateSnapshot = null
        activeRimeInputs = emptyList()
    }

    internal companion object {
        const val TAG = "OpenIme"
        const val MAX_RIME_INPUT_LENGTH = 256
        const val MAX_RIME_NINE_KEY_PATHS = 6
        const val MAX_CANDIDATES = 96
        @Volatile
        var activeInstance: LocalVoiceImeService? = null
    }
}
