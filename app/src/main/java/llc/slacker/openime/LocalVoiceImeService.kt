package llc.slacker.openime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import llc.slacker.openime.candidate.CandidateEngine
import llc.slacker.openime.candidate.CandidatePipeline
import llc.slacker.openime.candidate.CandidateQueryCoordinator
import llc.slacker.openime.candidate.CandidateResolver
import llc.slacker.openime.candidate.CandidateSnapshot
import llc.slacker.openime.candidate.CandidateSnapshotEntry
import llc.slacker.openime.candidate.EmojiAssociationIndex
import llc.slacker.openime.candidate.NineKeyReading
import llc.slacker.openime.candidate.PinyinLexicon
import llc.slacker.openime.candidate.FuzzyPinyin
import llc.slacker.openime.candidate.Stroke
import llc.slacker.openime.candidate.StrokeLexicon
import llc.slacker.openime.candidate.personalizedLearningAllowed
import llc.slacker.openime.core.CrashGuard
import llc.slacker.openime.core.ImeState
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.core.Panel
import llc.slacker.openime.core.ShiftState
import llc.slacker.openime.core.VoiceUiState
import llc.slacker.openime.core.dropLastCodePointSafe
import llc.slacker.openime.data.ClipboardHistoryRepository
import llc.slacker.openime.data.EmojiRecentRepository
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.data.PersonalizationRepository
import llc.slacker.openime.data.QuickPhraseRepository
import llc.slacker.openime.data.RimeUserDictionaryArchive
import llc.slacker.openime.data.UserPhraseRepository
import llc.slacker.openime.editor.EditorInfoAdapter
import llc.slacker.openime.editor.InputConnectionGateway
import llc.slacker.openime.editor.InputMethodSubtypePolicy
import llc.slacker.openime.editor.editorActionForEnter
import llc.slacker.openime.editor.shouldClearCompositionForSelectionUpdate
import llc.slacker.openime.floating.FloatingWindowController
import llc.slacker.openime.keyboard.EnglishShiftPolicy
import llc.slacker.openime.keyboard.HardwareContext
import llc.slacker.openime.keyboard.HardwareKey
import llc.slacker.openime.keyboard.HardwareKeyAction
import llc.slacker.openime.keyboard.HardwareKeyPolicy
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.keyboard.InlineAutofill
import llc.slacker.openime.rime.NativeCandidateReference
import llc.slacker.openime.rime.RimeEngine
import llc.slacker.openime.rime.RimeInputNormalizer
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeTheme
import llc.slacker.openime.voice.VoiceCorrectionRepository
import llc.slacker.openime.voice.VoiceCorrectionTracker
import llc.slacker.openime.voice.VoiceEditorContext
import llc.slacker.openime.voice.VoiceFinalPolicy
import llc.slacker.openime.voice.VoiceMediaMuteController
import llc.slacker.openime.voice.VoiceModelLifecycleManager
import llc.slacker.openime.voice.VoiceModelLifecycleState
import llc.slacker.openime.voice.VoicePerformanceTrace
import llc.slacker.openime.voice.VoiceRecognitionEvents
import llc.slacker.openime.voice.VoiceTextProcessor
import java.io.File

/**
 * Native system IME service. The view is a thin native renderer; all candidate
 * state, editor side effects and privacy rules live here.
 */
class LocalVoiceImeService : InputMethodService(), ImeKeyboardView.Listener, CandidateResolver {

    private data class CandidateDiagnostics(
        val learnedCount: Int = 0,
        val nativeCount: Int = 0,
        val fallbackCount: Int = 0,
        val nativeLatencyMs: Long = 0L,
        val resultLatencyMs: Long = 0L,
        val pathCount: Int = 0,
        val finalCandidateSource: String = "none",
    ) {
        fun asLogFields(): String =
            "learnedCount=$learnedCount nativeCount=$nativeCount " +
                "fallbackCount=$fallbackCount nativeLatencyMs=$nativeLatencyMs " +
                "resultLatencyMs=$resultLatencyMs pathCount=$pathCount " +
                "finalCandidateSource=$finalCandidateSource"
    }

    private var keyboardView: ImeKeyboardView? = null
    private lateinit var gateway: InputConnectionGateway
    /**
     * Starts as an empty pipeline and is replaced by the real one once the lexicon
     * and the nine-key decoder are built on a background thread (about 0.3 s on a
     * fast host, several times that on a mid-range phone). Building them in
     * onCreate froze the main thread at every cold start; until the swap, Rime
     * alone supplies candidates.
     */
    @Volatile
    private var candidatePipeline: CandidatePipeline = CandidatePipeline(CandidateEngine(linkedMapOf()))
    private lateinit var candidateQueries: CandidateQueryCoordinator
    private lateinit var rime: RimeEngine
    private lateinit var voiceLifecycle: VoiceModelLifecycleManager
    private var state = ImeState()
    private var lastComposition = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val floatingWindow by lazy {
        FloatingWindowController(
            resources = resources,
            mainHandler = mainHandler,
            windowProvider = { getWindow().window },
            keyboardHeightPx = { keyboardView?.measuredHeight },
            floatingWidthPercent = { ImeSettingsRepository.loadFloatingWidthPercent(this) },
            floatingOpacityPercent = { ImeSettingsRepository.loadFloatingOpacityPercent(this) },
            debugLog = { message ->
                if (verboseLogging) Log.d(TAG, message)
            },
        )
    }
    private var voiceComposing = false
    private var voiceAutoCommitOnFinal = true
    private val voiceCorrectionTracker by lazy {
        VoiceCorrectionTracker(
            snapshot = { gateway.absoluteCursorSnapshot() },
            learningAllowed = ::allowsPersonalizedLearning,
            record = VoiceCorrectionRepository::record,
        )
    }
    private val voiceMediaMute by lazy { VoiceMediaMuteController(this) }
    private var renderedCandidateSnapshot: CandidateSnapshot? = null
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

    /**
     * One bad key press must not take the keyboard down: the system would swap
     * in another keyboard and the user loses their place. A failure in a typing
     * handler is recorded (no typed text), the half-finished composition is
     * dropped, and the keyboard carries on.
     */
    @Volatile
    private var injectedFailureForTest = false

    private inline fun guarded(name: String, block: () -> Unit) {
        try {
            if (injectedFailureForTest) {
                injectedFailureForTest = false
                throw IllegalStateException("injected failure for test")
            }
            block()
        } catch (failure: Exception) {
            recoverFromHandledFailure(name, failure)
        } catch (failure: StackOverflowError) {
            recoverFromHandledFailure(name, failure)
        }
    }

    private fun recoverFromHandledFailure(name: String, failure: Throwable) {
        CrashGuard.log("handled failure in $name", failure)
        CrashGuard.recordHandled(this, name, failure)
        runCatching {
            clearImeCompositionState(render = true)
            gateway.cancelComposing()
        }
    }

    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        CrashGuard.ingestProcessExitReasons(this)
        VoiceMediaMuteController.recoverAfterCrash(this)
        activeInstance = this
        VoiceEditorContext.allowNaturalPunctuation = {
            EditorInfoAdapter.allowNaturalLanguageVoicePunctuation(currentInputEditorInfo)
        }
        VoiceEditorContext.stripFillers = { ImeSettingsRepository.loadVoiceStripFillers(this) }
        VoiceEditorContext.punctuationAsSpace =
            { ImeSettingsRepository.loadVoicePunctuationAsSpace(this) }
        UserPhraseRepository.configure(this)
        VoiceCorrectionRepository.configure(this)
        FuzzyPinyin.rules = ImeSettingsRepository.loadFuzzyRules(this)
        voiceLifecycle = VoiceModelLifecycleManager(this)
        Thread({
            runCatching { CandidatePipeline(CandidateEngine(PinyinLexicon.load(this))) }
                .onSuccess { candidatePipeline = it }
                .onFailure { Log.e(TAG, "lexicon/decoder initialisation failed; running on Rime only", it) }
            // Only someone who uses the 笔画 keyboard pays for its table; the
            // keyboard loads it on first use otherwise.
            if (ImeSettingsRepository.loadPreferredChineseMode(this) == KeyboardMode.STROKE) {
                runCatching { StrokeLexicon.load(this) }
                    .onFailure { Log.e(TAG, "stroke table failed to load", it) }
            }
        }, "openime-lexicon").apply { isDaemon = true; start() }
        rime = RimeEngine(this).also { it.start() }
        candidateQueries = CandidateQueryCoordinator(
            rime = rime,
            mainHandler = mainHandler,
            fallbackCandidatesFor = { candidatePipeline.nineKeyFallbackCandidatesFor(it) },
            maxInputLength = MAX_RIME_INPUT_LENGTH,
            maxNineKeyPaths = MAX_RIME_NINE_KEY_PATHS,
            maxCandidates = MAX_CANDIDATES,
        )
        gateway = InputConnectionGateway(
            context = this,
            connection = { currentInputConnection },
            isPassword = { state.passwordField },
            isRawKeys = { EditorInfoAdapter.kind(state.editorInfo) == EditorInfoAdapter.EditorKind.RAW_KEYS },
        )
        state = ImeState(
            theme = ImeSettingsRepository.loadTheme(this),
            appearance = ImeSettingsRepository.loadAppearance(this),
            soundEnabled = ImeSettingsRepository.loadSound(this),
            hapticEnabled = ImeSettingsRepository.loadHaptic(this),
            popupEnabled = ImeSettingsRepository.loadPopup(this),
            fuzzyPinyinEnabled = ImeSettingsRepository.loadFuzzy(this),
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
        lockPreferred: Boolean,
    ): CandidatePipeline.NineKeyResolution = candidatePipeline.resolveNineKey(
        digits = digits,
        segmentPrefix = segmentPrefix,
        preferredSuffix = preferredSuffix,
        fuzzy = fuzzy,
        lockPreferred = lockPreferred,
    )

    override fun nineKeyReadingsFor(digits: String, preferred: String?): List<NineKeyReading> =
        candidatePipeline.nineKeyReadingsFor(digits, preferred)

    override fun nineKeyReadingFor(digits: String, candidate: String): List<String>? =
        candidatePipeline.nineKeyReadingFor(digits, candidate)

    override fun nineKeyPathsFor(code: String?): List<String> =
        candidatePipeline.nineKeyPathsFor(code)

    override fun selectedNineKeyPathFor(code: String?): String? =
        candidatePipeline.selectedNineKeyPathFor(code)

    override fun selectNineKeyPath(code: String, path: String) {
        candidatePipeline.selectNineKeyPath(code, path)
    }

    private fun createKeyboardView(): ImeKeyboardView {
        return ImeKeyboardView(this, this).also { view ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inlineSuggestions.isNotEmpty()) {
                view.post { view.showInlineSuggestions(inlineSuggestions) }
            }
            view.setMode(state.keyboardMode, notifyListener = false)
            view.setTheme(state.theme)
            view.setAppearance(state.appearance)
            view.setSettings(
                state.soundEnabled,
                state.hapticEnabled,
                state.popupEnabled,
                state.fuzzyPinyinEnabled,
            )
            view.renderState(state)
        }
    }

    /** Autofill chips in the toolbar row (Android 11+); see [InlineAutofill]. */
    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? =
        InlineAutofill.request(this)

    /**
     * The latest response, kept until the field goes away. The response can
     * arrive while the keyboard view is still being (re)created for the new
     * field; it is then shown as soon as the view exists instead of being
     * refused, which would send the system back to its dropdown.
     */
    private var inlineSuggestions: List<android.view.inputmethod.InlineSuggestion> = emptyList()

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        inlineSuggestions = response.inlineSuggestions
        val view = keyboardView ?: return inlineSuggestions.isNotEmpty()
        return view.showInlineSuggestions(inlineSuggestions)
    }

    override fun onCreateInputView(): View {
        keyboardView = createKeyboardView()
        return keyboardView!!
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Orientation changes affect keyboard geometry, not the user's chosen
        // window mode. A manually floating keyboard stays floating; a docked
        // keyboard stays docked.
        floatingWindow.onConfigurationChanged()
    }

    override fun onEvaluateInputViewShown(): Boolean {
        // The emulator and some Chromebooks expose a hardware keyboard, so
        // InputMethodService's default policy suppresses the on-screen view
        // even after the user explicitly taps a text field. openIME is a
        // touch-first keyboard like Gboard: the explicit focus gesture should
        // always be able to open the visual keyboard, while the Back action
        // still lets the user dismiss it.
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        // The framework default turns the whole screen into the IME in landscape:
        // the app's editor is hidden behind an unthemed copy of the field (the
        // extract view) and the conversation, search results or form the user is
        // typing into disappear. Every mainstream keyboard stays a bottom panel
        // in every orientation and lets the app resize or pan, so do the same.
        return false
    }

    private fun ensureInputViewAfterFinish() {
        if (keyboardView != null) return
        // InputMethodService keeps the old view instance after
        // onFinishInputView(). Replacing the framework-owned view here is
        // required when the user switches away from openIME and back; merely
        // assigning a new field would leave the old, shut-down renderer on
        // screen and the IME window would report no drawable surface.
        setInputView(createKeyboardView().also { keyboardView = it })
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (::gateway.isInitialized) gateway.onEditorStarted()
        reloadPersistedSettings()
        voiceCorrectionTracker.clear()
        val previousRimeInputs = candidateQueries.activeInputs
        invalidateCandidateQueries()
        val kind = EditorInfoAdapter.kind(attribute)
        // Android restarts the same field after a rotation, a window resize or
        // a multi-window transition. Dropping the pre-edit there throws away
        // what the user was mid-way through typing; only a genuinely new
        // editor resets the composition.
        val sameEditorKind = kind == EditorInfoAdapter.kind(state.editorInfo)
        val preserve = restarting && sameEditorKind && !EditorInfoAdapter.isPassword(kind) && state.composition.isNotEmpty()
        val policyMode = if (restarting && sameEditorKind) {
            state.keyboardMode
        } else {
            InputMethodSubtypePolicy.defaultKeyboardMode(kind, currentSystemSubtypeLocale())
        }
        // defaultKeyboardMode returns PINYIN_26 only for the Chinese/unknown
        // branch (number fields give DIGITS, Latin/credential fields give
        // ENGLISH_26), so it is safe to swap in the persisted 26/9 preference.
        val nextMode = when {
            restarting && sameEditorKind -> policyMode
            policyMode == KeyboardMode.PINYIN_26 ->
                ImeSettingsRepository.loadPreferredChineseMode(this)
            else -> policyMode
        }
        val initialShiftState = if (nextMode == KeyboardMode.ENGLISH_26) {
            desiredEnglishShiftState(attribute)
        } else {
            ShiftState.LOWERCASE
        }
        state = state.copy(
            editorInfo = attribute,
            passwordField = EditorInfoAdapter.isPassword(kind),
            keyboardMode = nextMode,
            panel = Panel.NONE,
            composition = if (preserve) state.composition else "",
            candidates = if (preserve) state.candidates else emptyList(),
            // Everything below is per-editor contract state. None of it was
            // reset here before, so a Caps Lock or a nine-key filter picked in
            // one app leaked into the next editor.
            shiftState = initialShiftState,
            pinyin9Filters = emptyList(),
            selectedPinyin9Filter = "",
            expandedCandidates = emptyList(),
            voiceState = VoiceUiState(),
        )
        if (!preserve) lastComposition = ""
        attribute?.let { gateway.updateSelection(it.initialSelStart, it.initialSelEnd) }
        rime.clear()
        keyboardView?.clearAssociationCandidates()
        keyboardView?.setShiftState(initialShiftState)
        keyboardView?.setMode(state.keyboardMode, notifyListener = false)
        keyboardView?.renderState(state)
        if (preserve) {
            // Restored candidates must be selectable before the new native query finishes.
            val generation = requestNativeCandidates(
                state.composition, state.keyboardMode, state.candidates,
                previousRimeInputs.ifEmpty { listOf(state.composition) },
            )
            renderedCandidateSnapshot = CandidateSnapshot.rendered(
                generation, state.composition, state.keyboardMode, state.candidates,
            )
        }
    }

    /** Keep the live IME instance in sync with settings changed from the app page. */
    private fun reloadPersistedSettings() {
        state = state.copy(
            theme = ImeSettingsRepository.loadTheme(this),
            appearance = ImeSettingsRepository.loadAppearance(this),
            soundEnabled = ImeSettingsRepository.loadSound(this),
            hapticEnabled = ImeSettingsRepository.loadHaptic(this),
            popupEnabled = ImeSettingsRepository.loadPopup(this),
            fuzzyPinyinEnabled = ImeSettingsRepository.loadFuzzy(this),
        )
        syncFuzzyRules()
        keyboardView?.applyPersistedSettings(
            newTheme = state.theme,
            newAppearance = state.appearance,
            sound = state.soundEnabled,
            haptic = state.hapticEnabled,
            popup = state.popupEnabled,
            fuzzy = state.fuzzyPinyinEnabled,
        )
    }

    /** Apply settings changed by standalone Activities on the IME main thread. */
    internal fun refreshPersistedSettingsFromActivity() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            reloadPersistedSettings()
        } else {
            mainHandler.post(::reloadPersistedSettings)
        }
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        if (!::gateway.isInitialized || !::rime.isInitialized) return

        @Suppress("DEPRECATION")
        val policyMode = InputMethodSubtypePolicy.defaultKeyboardMode(
            EditorInfoAdapter.kind(state.editorInfo),
            newSubtype.locale,
        )
        // Honor the persisted 26/9-key preference in the Chinese/unknown branch.
        val nextMode = if (policyMode == KeyboardMode.PINYIN_26) {
            ImeSettingsRepository.loadPreferredChineseMode(this)
        } else {
            policyMode
        }

        // A subtype switch changes the input language contract. Discard only
        // text actually owned by this IME; setComposingText("") without an
        // active composing span can otherwise delete a user's normal selection.
        val hadComposingText = lastComposition.isNotEmpty() || voiceComposing
        clearImeCompositionState(render = false)
        if (hadComposingText) gateway.cancelComposing()
        voiceComposing = false
        voiceCorrectionTracker.clear()
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
            state = state.copy(
                keyboardMode = nextMode,
                composition = "",
                candidates = emptyList(),
            )
        }
        keyboardView?.renderState(state)
    }

    override fun onStartInputView(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        ensureInputViewAfterFinish()
        if (floatingWindow.enabled) {
            keyboardView?.setFloatingWindowMode(true)
            floatingWindow.reapply()
        } else {
            floatingWindow.restore()
        }
        keyboardView?.refreshAuxiliaryContent()
        // Safe mode (repeated crashes or freezes): do not preload the voice model.
        if (!CrashGuard.isSafeMode(this)) voiceLifecycle.onStartInputView()
    }

    /** Re-render panels whose data may have been edited in a full-screen Activity. */
    internal fun refreshAuxiliaryContentFromActivity() {
        keyboardView?.refreshAuxiliaryContent()
    }

    override fun onFinishInput() {
        inlineSuggestions = emptyList()
        invalidateCandidateQueries()
        voiceCorrectionTracker.finalizeIfNeeded()
        voiceMediaMute.restore()
        // shutdown() cancels an active voice session and its callback clears
        // voiceComposing. Check ownership afterwards so we never cancel twice.
        keyboardView?.shutdown()
        if (lastComposition.isNotEmpty() || voiceComposing) gateway.cancelComposing()
        rime.clear()
        voiceComposing = false
        lastComposition = ""
        state = state.copy(composition = "", candidates = emptyList())
        voiceCorrectionTracker.clear()
        UserPhraseRepository.flush()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        voiceMediaMute.restore()
        keyboardView?.shutdown()
        keyboardView = null
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
        gateway.updateSelection(newSelStart, newSelEnd, reportedByEditor = true)
        guarded("onUpdateSelection") { refreshTextEditControls() }
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        // A cursor move caused by our own commit never drops the pre-edit.
        if (gateway.consumeOwnEdit()) return
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
        if (::candidateQueries.isInitialized) candidateQueries.shutdown()
        renderedCandidateSnapshot = null
        voiceMediaMute.restore()
        // The keyboard view owns a Handler with pending key-repeat callbacks
        // and holds this service as its listener. Releasing it here keeps the
        // view tree (and its Context reference) from outliving the service.
        keyboardView?.shutdown()
        keyboardView = null
        UserPhraseRepository.flush()
        if (::rime.isInitialized) rime.shutdown()
        if (::voiceLifecycle.isInitialized) voiceLifecycle.destroy()
        activeInstance = null
        VoiceEditorContext.allowNaturalPunctuation = { true }
        VoiceEditorContext.stripFillers = { true }
        VoiceEditorContext.punctuationAsSpace = { false }
        super.onDestroy()
    }

    internal fun handleTestCommand(command: String): Boolean = when {
        command.startsWith("mode:") -> {
            val targetName = command.substringAfter("mode:").trim()
            val targetMode = KeyboardMode.entries.find { it.name.equals(targetName, ignoreCase = true) }
            if (targetMode != null) {
                keyboardView?.setMode(targetMode)
                true
            } else false
        }
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
            if (text.isBlank()) {
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
            if (text.isBlank()) {
                false
            } else {
                VoicePerformanceTrace.abandon()
                onVoiceSessionStarted(autoCommitOnFinal = true)
                onVoiceFinal(VoiceCorrectionRepository.apply(text))
                true
            }
        }.getOrDefault(false)
        // The recogniser's final text goes through the same post-processing as
        // a real session (spoken punctuation, fillers, punctuation as spaces,
        // word lists) before it reaches the editor.
        command.startsWith("voice-processed64:") -> runCatching {
            val raw = String(
                java.util.Base64.getDecoder().decode(command.substringAfter("voice-processed64:")),
                Charsets.UTF_8,
            )
            val processed = VoiceTextProcessor.process(raw, "zh-CN")
            if (processed.isBlank()) {
                false
            } else {
                VoicePerformanceTrace.abandon()
                onVoiceSessionStarted(autoCommitOnFinal = true)
                onVoiceFinal(VoiceCorrectionRepository.apply(processed))
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
        command == "fail-next" -> {
            injectedFailureForTest = true
            true
        }
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
        voiceCorrectionTracker.finalizeIfNeeded()
        invalidateCandidateQueries()
        rime.clear()
        state = state.copy(
            keyboardMode = mode,
            composition = "",
            candidates = emptyList(),
        )
        lastComposition = ""
        keyboardView?.renderState(state)
        if (mode == KeyboardMode.ENGLISH_26) {
            mainHandler.post { resetEnglishShiftForEditor() }
        }
    }

    override fun onPanelChanged(panel: Panel) {
        state = state.copy(panel = panel)
        // Panels are content inside the current IME window. Opening Tools,
        // Clipboard or Settings must not silently cancel a user-selected
        // floating window mode.
        if (panel == Panel.TEXT_EDITOR) mainHandler.post(::refreshTextEditControls)
    }

    private fun refreshTextEditControls() {
        if (keyboardView?.currentPanel() != Panel.TEXT_EDITOR) return
        keyboardView?.refreshTextEditAvailability(
            selectionAvailable = gateway.hasSelection(),
            clipboardAvailable = gateway.hasClipboardText(),
        )
    }

    override fun onCharacter(char: String) = guarded("onCharacter") {
        prepareForManualInput()
        voiceCorrectionTracker.noteReplacementInput()
        commitPendingComposition()
        keyboardView?.clearAssociationCandidates()
        gateway.commitText(char)
    }

    override fun onBackspace() = guarded("onBackspace") {
        prepareForManualInput()
        voiceCorrectionTracker.noteBackspace()
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

    override fun onClearAll() = guarded("onClearAll") {
        prepareForManualInput()
        // Invalidate every pending candidate/Rime path before touching the
        // editor. Otherwise a late native result can restore the just-cleared
        // pre-edit on the very next key press.
        clearImeCompositionState(render = false)
        val cleared = gateway.clearAllText()
        if (!cleared) {
            android.widget.Toast.makeText(this, "当前应用未能清空全部文本", android.widget.Toast.LENGTH_SHORT).show()
        }
        voiceCorrectionTracker.clear()
        voiceComposing = false
        state = state.copy(voiceState = VoiceUiState())
        keyboardView?.renderState(state)
    }

    override fun onFloatingKeyboardChanged(floating: Boolean) {
        keyboardView?.setFloatingWindowMode(floating)
        if (floating) floatingWindow.enable() else floatingWindow.restore()
    }

    override fun onFloatingKeyboardDragged(deltaX: Float, deltaY: Float) {
        floatingWindow.drag(deltaX, deltaY)
    }

    override fun onFloatingStyleChanged(widthPercent: Int, opacityPercent: Int) {
        if (floatingWindow.enabled) floatingWindow.reapply()
    }

    override fun onSpace() = guarded("onSpace") {
        prepareForManualInput()
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
        voiceCorrectionTracker.finalizeIfNeeded()
        keyboardView?.clearAssociationCandidates()
        gateway.commitText(" ")
    }

    override fun onVoiceToggle() {
        voiceMediaMute.mute()
        keyboardView?.startVoiceFromSpace()
    }

    override fun onVoicePressChanged(pressed: Boolean) {
        if (pressed) {
            // Mute before model startup is posted so media cannot leak through
            // during the preparation window shown to the user.
            voiceMediaMute.mute()
            commitPendingComposition()
            voiceCorrectionTracker.finalizeIfNeeded()
            voiceAutoCommitOnFinal = true
            keyboardView?.startVoiceFromSpace()
        } else {
            keyboardView?.stopVoiceFromSpace()
        }
    }

    override fun onVoiceSessionStarted(autoCommitOnFinal: Boolean) {
        voiceMediaMute.mute()
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
        // Stop capture immediately. Decoding still drains recorded audio;
        // restore media when AudioRecord has actually stopped.
        if (::voiceLifecycle.isInitialized) voiceLifecycle.stop()
    }

    override fun onVoiceCaptureStopped() {
        voiceMediaMute.restore()
    }

    override fun cancelVoiceRecognition() {
        voiceMediaMute.restore()
        if (::voiceLifecycle.isInitialized) voiceLifecycle.cancel()
    }

    override fun onVoicePartial(text: String) {
        // Password fields get only the final text, as one direct commit: nothing
        // is shown in the editor or on the keyboard while the user is speaking.
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
        // The backend only emits a final after capture has stopped. This is a
        // safety net for release paths that race with the final callback.
        voiceMediaMute.restore()
        val plan = VoiceFinalPolicy.resolve(
            passwordField = state.passwordField,
            hadPartialComposition = voiceComposing,
            autoCommit = voiceAutoCommitOnFinal,
            finalText = text,
        )
        if (plan.commitDirect) gateway.commitText(text)
        if (plan.setFinalText) {
            gateway.setComposingText(text)
            VoicePerformanceTrace.markFirstDisplay()
        }
        if (plan.finishComposing) gateway.finishComposing()
        voiceComposing = plan.composingAfter
        if (plan.finishComposing && text.isNotBlank()) voiceCorrectionTracker.begin(text)
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
        voiceMediaMute.restore()
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
        voiceMediaMute.restore()
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
        voiceMediaMute.restore()
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

    override fun onEnter() = guarded("onEnter") {
        prepareForManualInput()
        if (lastComposition.isNotEmpty()) {
            // Space picks the first word; Enter ("确定") keeps what was typed,
            // as Rime, fcitx, Sogou and Gboard Pinyin do. Collapsing both into
            // "commit the first candidate" left no way to enter pinyin as text.
            commitRawComposition()
            return
        }
        voiceCorrectionTracker.finalizeIfNeeded()
        val action = state.editorInfo?.imeOptions?.let(::editorActionForEnter)
        if (action != null) {
            gateway.performEditorAction(action)
        } else {
            gateway.sendKeyDownUp(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onCompositionChanged(composition: String, candidates: List<String>) = guarded("onCompositionChanged") {
        prepareForManualInput()
        if (composition.isNotEmpty()) voiceCorrectionTracker.noteReplacementInput()
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
    ) = guarded("onNineKeyCompositionChanged") {
        prepareForManualInput()
        if (composition.isNotEmpty()) voiceCorrectionTracker.noteReplacementInput()
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
        val directCommit = state.passwordField || (
            // Terminals and games need each letter as it is typed; composing English
            // there shows nothing until the word ends. Pinyin still composes.
            EditorInfoAdapter.kind(state.editorInfo) == EditorInfoAdapter.EditorKind.RAW_KEYS &&
                !state.keyboardMode.isChineseLayout
            )
        if (directCommit) {
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

    override fun onCandidateSelected(candidate: String) = guarded("onCandidateSelected") {
        prepareForManualInput()
        if (state.passwordField) return
        selectCandidate(candidate)
    }

    override fun onCandidateLongPressed(candidate: String) {
        val composition = lastComposition
        if (
            state.passwordField ||
            composition.isBlank() ||
            candidate.isBlank() ||
            !allowsPersonalizedLearning()
        ) {
            return
        }

        if (
            QuickPhraseRepository.candidatesForInputCode(
                context = this,
                rawCode = composition,
                exactOnly = false,
                limit = 16,
            ).contains(candidate)
        ) {
            return
        }

        val modeAtRequest = state.keyboardMode

        fun refreshAfterDelete() {
            if (lastComposition != composition || state.keyboardMode != modeAtRequest) return
            val fallback = fallbackCandidatesFor(composition, modeAtRequest)
            handleCompositionChanged(
                composition = composition,
                candidates = fallback,
                rimeInputs = listOf(composition),
            )
        }

        fun confirmDelete(onConfirm: () -> Unit) {
            if (lastComposition != composition || state.keyboardMode != modeAtRequest) return
            keyboardView?.confirmCandidateDeletion(candidate, onConfirm)
        }

        if (!rime.isReady) {
            if (!UserPhraseRepository.contains(composition, candidate)) return
            confirmDelete {
                if (UserPhraseRepository.forget(composition, candidate)) {
                    PersonalizationRepository.forget(candidate)
                    refreshAfterDelete()
                    showTextEditFeedback("已删除个人学习词")
                }
            }
            return
        }

        val queued = rime.isUserLearnedCandidate(composition, candidate) { learned ->
            mainHandler.post {
                if (
                    !learned ||
                    lastComposition != composition ||
                    state.keyboardMode != modeAtRequest
                ) {
                    return@post
                }
                confirmDelete {
                    val deleteQueued = rime.deleteCandidate(composition, candidate) { deleted ->
                        mainHandler.post {
                            if (deleted) {
                                PersonalizationRepository.forget(candidate)
                                refreshAfterDelete()
                                showTextEditFeedback("已删除个人学习词")
                            }
                        }
                    }
                    if (!deleteQueued) {
                        showTextEditFeedback("暂时无法修改个人词")
                    }
                }
            }
        }
        if (!queued) {
            return
        }
    }

    /**
     * Association ("联想") chips are produced only after a commit, so there is
     * no composition for [selectCandidate] to match against. Commit the word
     * directly and chain to the next association set so a user can keep
     * tapping: 你好 -> 呀 -> ！
     */
    override fun onAssociationSelected(text: String) = guarded("onAssociationSelected") {
        prepareForManualInput()
        if (state.passwordField || text.isEmpty()) return
        commitPendingComposition()
        gateway.commitText(text)
        gateway.finishComposing()
        keyboardView?.clearAssociationCandidates()
        keyboardView?.setAssociationCandidates(associationsAfterCommit(text))
    }

    private val emojiAssociations: EmojiAssociationIndex by lazy {
        EmojiAssociationIndex.load(this)
    }

    /** Next-word suggestions, with the emoji that go with the committed text first. */
    private fun associationsAfterCommit(committed: String): List<String> {
        val words = candidatePipeline.associationsFor(committed)
        if (state.passwordField || !ImeSettingsRepository.loadEmojiAssociation(this)) return words
        return emojiAssociations.emojiFor(committed) + words
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
        syncFuzzyRules()
    }

    override fun onFuzzyRulesChanged() = syncFuzzyRules()

    /**
     * Hand the 模糊音 switches to both candidate sources: the Kotlin fallback
     * reads [FuzzyPinyin.rules]; librime re-deploys when the rules file changes.
     */
    private fun syncFuzzyRules() {
        FuzzyPinyin.rules = ImeSettingsRepository.loadFuzzyRules(this)
        if (::rime.isInitialized) {
            // RimeEngine mirrors the master switch on the candidate hot path.
            rime.invalidateSettingsCache()
            rime.applyFuzzyRules()
        }
    }

    override fun onShiftStateChanged(state: ShiftState) {
        this.state = this.state.copy(shiftState = state)
    }

    override fun onCandidateExpanded(open: Boolean) {
        state = state.copy(panel = if (open) Panel.CANDIDATE_EXPANDED else Panel.NONE)
    }

    override fun onSymbolSelected(symbol: String) = guarded("onSymbolSelected") {
        prepareForManualInput()
        commitPendingComposition()
        keyboardView?.clearAssociationCandidates()
        gateway.commitText(symbol)
    }

    override fun onEmojiSelected(emoji: String) = guarded("onEmojiSelected") {
        prepareForManualInput()
        commitPendingComposition()
        keyboardView?.clearAssociationCandidates()
        EmojiRecentRepository.record(this, emoji)
        gateway.commitText(emoji)
    }

    override fun onTextEdit(action: String) = guarded("onTextEdit") {
        if (action in setOf("select-all", "cut", "paste", "left", "right")) prepareForManualInput()
        when (action) {
            "select-all" -> if (!gateway.selectAll()) {
                showTextEditFeedback("当前应用不支持全选")
            }
            "copy" -> {
                val selected = gateway.copySelection()
                if (selected.isNotEmpty()) {
                    ClipboardHistoryRepository.add(this, selected)
                    gateway.copyToClipboard(selected)
                } else {
                    showTextEditFeedback("请先选择要复制的文本")
                }
            }
            "cut" -> {
                val selected = gateway.copySelection()
                if (selected.isNotEmpty()) {
                    ClipboardHistoryRepository.add(this, selected)
                    gateway.copyToClipboard(selected)
                    gateway.deleteSelection()
                } else {
                    showTextEditFeedback("请先选择要剪切的文本")
                }
            }
            "paste" -> {
                commitPendingComposition()
                keyboardView?.clearAssociationCandidates()
                val pasted = gateway.pasteClipboard { clip -> ClipboardHistoryRepository.captureClip(this, clip) }
                if (pasted.isEmpty()) {
                    showTextEditFeedback("剪贴板没有可粘贴文本")
                }
            }
            "left" -> {
                gateway.moveCursorHorizontally(-1)
            }
            "right" -> {
                gateway.moveCursorHorizontally(1)
            }
            "up" -> gateway.moveCursorVertically(-1)
            "down" -> gateway.moveCursorVertically(1)
            "undo" -> if (!gateway.undo()) {
                showTextEditFeedback("当前编辑器不支持撤销")
            }
        }
        refreshTextEditControls()
    }

    private fun showTextEditFeedback(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && keyboardView?.closePanelToKeyboard() == true) {
            return true
        }
        if (event != null && handleHardwareKey(event)) {
            hardwareConsumed += keyCode
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // The matching up of a key we consumed must not reach the app on its own.
        if (hardwareConsumed.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private val hardwareConsumed = HashSet<Int>()

    /**
     * Pinyin typing from a physical keyboard (tablets, foldables with a keyboard
     * cover, Chromebooks, desktop mode, emulators). Returns true when the key was
     * ours; anything else, and every shortcut, goes on to the app unchanged.
     */
    private fun handleHardwareKey(event: KeyEvent): Boolean {
        val view = keyboardView ?: return false
        if (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0) return false
        val kind = EditorInfoAdapter.kind(state.editorInfo)
        val pinyinMode = isInputViewShown && view.hardwareAccepts() && !state.passwordField &&
            EditorInfoAdapter.allowCandidates(kind) && kind != EditorInfoAdapter.EditorKind.RAW_KEYS
        if (!pinyinMode) return false
        val composing = view.hardwareIsComposing()
        val unicode = event.unicodeChar
        val needsCharBefore = !composing && unicode in HARDWARE_ASCII_AFTER_DIGIT
        val action = HardwareKeyPolicy.decide(
            HardwareKey(
                keyCode = event.keyCode,
                unicode = unicode,
                shift = event.isShiftPressed,
                ctrl = event.isCtrlPressed,
                alt = event.isAltPressed,
                meta = event.isMetaPressed,
                capsLock = event.isCapsLockOn,
                repeat = event.repeatCount > 0,
            ),
            HardwareContext(
                pinyinMode = true,
                composing = composing,
                candidateCount = view.hardwareCandidateCount(),
                charBeforeCursor = if (needsCharBefore) gateway.charBeforeCursor() else null,
            ),
        )
        var consumed = true
        guarded("hardwareKey") {
            when (action) {
                HardwareKeyAction.PassThrough -> consumed = false
                HardwareKeyAction.Consume -> Unit
                HardwareKeyAction.FinishCompositionThenPassThrough -> {
                    commitPendingComposition()
                    consumed = false
                }
                is HardwareKeyAction.Letter -> view.hardwareLetter(action.char)
                HardwareKeyAction.Backspace -> view.hardwareBackspace()
                HardwareKeyAction.Space -> view.hardwareSpace()
                HardwareKeyAction.Enter -> onEnter()
                HardwareKeyAction.Cancel -> view.hardwareCancelComposition()
                HardwareKeyAction.Apostrophe -> view.hardwareApostrophe()
                is HardwareKeyAction.SelectCandidate -> consumed = view.hardwareSelectCandidate(action.index)
                is HardwareKeyAction.Punctuation -> {
                    if (action.commitFirstCandidate) commitFirstCandidate()
                    onCharacter(action.text)
                }
            }
        }
        return consumed
    }

    private fun updateComposition(next: String, candidates: List<String>) {
        lastComposition = next
        state = state.copy(composition = next, candidates = candidates)
        if (next.isEmpty()) {
            // Finishing a non-empty composing span preserves it as committed
            // editor text in many apps. Deleting the final Pinyin character
            // must remove that span instead of leaving a raw letter behind.
            gateway.cancelComposing()
        } else if (!state.keyboardMode.isChineseLayout) {
            // Chinese pre-edit (pinyin, nine-key digits, strokes) stays on the
            // keyboard, as on Sogou and iFlytek: written into the app's field it
            // showed "669" or "ni'hao" there and set off the app's own
            // suggestions. An English word is composed in place, as on Gboard.
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

    private fun fallbackCandidatesFor(
        composition: String,
        mode: KeyboardMode,
    ): List<String> {
        val normal = candidatePipeline.candidatesFor(
            mode,
            composition,
            state.fuzzyPinyinEnabled,
        )
        if (mode == KeyboardMode.STROKE) {
            // Characters the user picked for these strokes before come first.
            val learned = strokeLearningKey(composition)
                ?.takeIf { allowsPersonalizedLearning() }
                ?.let(UserPhraseRepository::candidatesFor)
                .orEmpty()
            return (learned + normal).distinct().take(MAX_CANDIDATES)
        }
        if (mode != KeyboardMode.PINYIN_26 && mode != KeyboardMode.ENGLISH_26) {
            return normal
        }
        val quickPhrases = QuickPhraseRepository.candidatesForInputCode(
            context = this,
            rawCode = composition,
            exactOnly = false,
            limit = 8,
        )
        return (quickPhrases + normal).distinct().take(MAX_CANDIDATES)
    }

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
        val ticket = candidateQueries.request(
            composition = composition,
            mode = mode,
            rimeInputs = rimeInputs,
            onResult = result@{ request, queryInputs, query ->
                fun applyWhenCandidateSurfaceIdle() {
                    if (
                        state.keyboardMode != mode ||
                        lastComposition != composition ||
                        candidateQueries.currentGeneration() != request
                    ) {
                        return
                    }
                    if (keyboardView?.isCandidateInteractionActive() == true) {
                        mainHandler.postDelayed(
                            { applyWhenCandidateSurfaceIdle() },
                            CANDIDATE_REFRESH_IDLE_POLL_MS,
                        )
                        return
                    }

                    val native = query.choices
                // Once Rime returns candidates, its mature dictionary and
                // userdb ordering replace the transient Kotlin preview. The
                // fallback is retained only when native has no answer.
                val learned = if (!rime.isReady && native.isEmpty()) {
                    UserPhraseRepository.candidatesFor(composition)
                } else {
                    emptyList()
                }
                val rankedCandidates = if (native.isNotEmpty()) {
                    val nativeText = native.map { it.text }.let { texts ->
                        if (mode == KeyboardMode.PINYIN_9) {
                            candidatePipeline.preferExactNineKeyMatches(queryInputs.firstOrNull(), texts)
                        } else {
                            texts
                        }
                    }
                    val exactQuickPhrases =
                        if (mode == KeyboardMode.PINYIN_26 || mode == KeyboardMode.ENGLISH_26) {
                            QuickPhraseRepository.candidatesForInputCode(
                                context = this,
                                rawCode = composition,
                                exactOnly = true,
                                limit = 8,
                            )
                        } else {
                            emptyList()
                        }
                    if (exactQuickPhrases.isNotEmpty()) {
                        (exactQuickPhrases + nativeText + fallback)
                            .distinct()
                            .take(MAX_CANDIDATES)
                    } else if (
                        mode == KeyboardMode.PINYIN_26 &&
                        nativeText.size < TYPO_CORRECTION_NATIVE_THRESHOLD
                    ) {
                        (nativeText + fallback).distinct().take(MAX_CANDIDATES)
                    } else {
                        nativeText
                    }
                } else {
                    (learned + fallback).distinct().take(MAX_CANDIDATES)
                }
                val finalCandidates = rankedCandidates
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
                    resultLatencyMs = query.resultLatencyMs,
                    pathCount = queryInputs.size,
                    finalCandidateSource = when {
                        native.isNotEmpty() -> "native"
                        learned.isNotEmpty() -> "learned_fallback"
                        finalCandidates.isNotEmpty() -> "fallback"
                        else -> "none"
                    },
                )
                if (verboseLogging) {
                    Log.d(TAG, "candidate-stats ${candidateDiagnostics.asLogFields()}")
                }
                state = state.copy(candidates = finalCandidates)
                renderedCandidateSnapshot = CandidateSnapshot.rendered(
                    generation = request,
                    composition = composition,
                    mode = mode,
                    candidates = finalCandidates,
                    nativeReferences = nativeReferences,
                )
                keyboardView?.renderState(state)
                if (mode == KeyboardMode.PINYIN_9 && native.isNotEmpty()) {
                    finalCandidates.firstOrNull()?.let { top ->
                        keyboardView?.alignNineKeyPreview(composition, top)
                    }
                }
            }
                applyWhenCandidateSurfaceIdle()
            },
        )

        if (!ticket.scheduled) {
            candidateDiagnostics = CandidateDiagnostics(
                fallbackCount = fallback.distinct().size,
                finalCandidateSource = if (fallback.isEmpty()) "none" else "fallback",
            )
        }
        return ticket.generation
    }

    /** Commit only the first candidate that belongs to the currently rendered snapshot. */
    private fun commitFirstCandidate() {
        val composition = lastComposition
        if (composition.isEmpty()) return
        val mode = state.keyboardMode
        val entry = renderedCandidateSnapshot?.firstForCommit(
            currentGeneration = candidateQueries.currentGeneration(),
            currentComposition = composition,
            currentMode = mode,
        ) ?: return
        invalidateCandidateQueries()
        commitSnapshotEntry(composition, mode, entry)
    }

    /** Commit the pre-edit pinyin as typed (separators dropped); no word is chosen or learned. */
    private fun commitRawComposition() {
        val composition = lastComposition
        if (composition.isEmpty()) return
        val raw = composition
            .filterNot { it == ' ' || it == '\'' || it == '|' }
            .ifEmpty { composition }
        invalidateCandidateQueries()
        gateway.commitText(raw)
        gateway.finishComposing()
        voiceCorrectionTracker.finalizeIfNeeded()
        rime.clear()
        lastComposition = ""
        state = state.copy(composition = "", candidates = emptyList())
        keyboardView?.clearAssociationCandidates()
        keyboardView?.renderState(state)
    }

    private fun selectCandidate(candidate: String) {
        val composition = lastComposition
        if (composition.isEmpty()) return
        val mode = state.keyboardMode
        val entry = renderedCandidateSnapshot?.candidateForCommit(
            candidate = candidate,
            currentGeneration = candidateQueries.currentGeneration(),
            currentComposition = composition,
            currentMode = mode,
        ) ?: return
        invalidateCandidateQueries()
        commitSnapshotEntry(composition, mode, entry)
    }

    /** Commit one rendered candidate; a partial match keeps the rest of the input composing. */
    private fun commitSnapshotEntry(composition: String, mode: KeyboardMode, entry: CandidateSnapshotEntry) {
        val reference = entry.nativeReference
        val remaining = partialRemainder(reference, mode)
        if (remaining != null) {
            finishPartialCandidateCommit(entry.text, remaining)
            return
        }
        val nativeCommit = if (
            reference != null &&
            rime.isReady &&
            (mode == KeyboardMode.PINYIN_26 || mode == KeyboardMode.PINYIN_9)
        ) {
            rime.selectCandidate(reference.input, reference.nativeIndex, allowsPersonalizedLearning())
        } else {
            ""
        }
        finishCandidateCommit(composition, nativeCommit.ifBlank { entry.text })
    }

    /**
     * Input left over when [reference] spells only the start of its input, or
     * null when it spells all of it (or the extent is unknown). Previously the
     * leftover was discarded, and the native whole-composition commit also
     * taught the user dictionary a phrase the user never chose.
     */
    private fun partialRemainder(reference: NativeCandidateReference?, mode: KeyboardMode): String? {
        val ref = reference ?: return null
        if (ref.consumed <= 0 || ref.nativeIndex < 0) return null
        if (mode != KeyboardMode.PINYIN_26 && mode != KeyboardMode.PINYIN_9) return null
        val normalized = RimeInputNormalizer.normalize(ref.input)
        if (ref.consumed >= normalized.length) return null
        return normalized.substring(ref.consumed).trim('\'').ifEmpty { null }
    }

    private fun finishPartialCandidateCommit(committed: String, remaining: String) {
        if (committed.isEmpty()) return
        // One atomic edit: commit the word and start the next composing span
        // together, so the editor never reports a half-way selection state.
        gateway.batchEdit {
            gateway.commitText(committed)
            gateway.finishComposing()
            voiceCorrectionTracker.finalizeIfNeeded()
            rime.clear()
            lastComposition = ""
            state = state.copy(composition = "", candidates = emptyList())
            keyboardView?.clearAssociationCandidates()
            // The view republishes the leftover; this calls back into
            // handleCompositionChanged, which starts a fresh composing span.
            keyboardView?.continueCompositionAfterPartial(remaining)
        }
    }

    private fun finishCandidateCommit(composition: String, committed: String) {
        if (committed.isEmpty()) return
        // librime owns normal learning through its userdb. Keep the old local
        // repository only as an offline fallback; never run two unconditional
        // ranking systems over the same successful native selection.
        if (state.keyboardMode == KeyboardMode.STROKE) {
            // Stroke input has no librime session, so it always learns locally.
            strokeLearningKey(composition)?.takeIf { allowsPersonalizedLearning() }
                ?.let { UserPhraseRepository.record(it, committed) }
        } else if (!rime.isReady && allowsPersonalizedLearning()) {
            UserPhraseRepository.record(composition, committed)
        }
        // An English word is followed by a space, as space and a suggestion
        // tap do on Gboard and Sogou: otherwise "hello world" comes out as
        // "helloworld".
        gateway.commitText(if (state.keyboardMode == KeyboardMode.ENGLISH_26) "$committed " else committed)
        gateway.finishComposing()
        voiceCorrectionTracker.finalizeIfNeeded()
        rime.clear()
        lastComposition = ""
        state = state.copy(composition = "", candidates = emptyList())
        keyboardView?.renderState(state)
        keyboardView?.setAssociationCandidates(associationsAfterCommit(committed))
    }

    /**
     * The learning key for a stroke composition: its stroke code, prefixed so it
     * never meets a Pinyin code. Null with 通配, whose matches are a guess.
     */
    private fun strokeLearningKey(composition: String): String? =
        Stroke.codeOf(composition)?.takeIf { Stroke.WILDCARD_CODE !in it }?.let { "stroke:$it" }

    private fun allowsPersonalizedLearning(): Boolean =
        personalizedLearningAllowed(state.passwordField, state.editorInfo?.imeOptions)

    /** Remove only voice-owned composing text before a manual edit takes ownership. */
    private fun prepareForManualInput() {
        keyboardView?.cancelVoiceForManualInput()
        // Also handles service/test sessions without a view-owned recording.
        if (voiceComposing) onVoiceCancel()
    }

    private fun desiredEnglishShiftState(info: EditorInfo?): ShiftState {
        if (info == null) return ShiftState.LOWERCASE
        return EnglishShiftPolicy.initial(
            inputType = info.inputType,
            password = EditorInfoAdapter.isPassword(EditorInfoAdapter.kind(info)),
        )
    }

    private fun resetEnglishShiftForEditor() {
        if (state.keyboardMode != KeyboardMode.ENGLISH_26 || state.passwordField || lastComposition.isNotEmpty()) return
        val next = desiredEnglishShiftState(state.editorInfo)
        if (next == state.shiftState) return
        state = state.copy(shiftState = next)
        keyboardView?.setShiftState(next)
    }

    private fun dropLastCodePoint(text: String): String = dropLastCodePointSafe(text)

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
        candidateQueries.invalidate()
        renderedCandidateSnapshot = null
    }

    internal fun exportRimeUserData(
        targetDir: File,
        onComplete: (List<RimeUserDictionaryArchive>?) -> Unit,
    ): Boolean =
        ::rime.isInitialized &&
            rime.exportUserDictionaries(targetDir, onComplete)

    internal fun importRimeUserData(
        sourceDir: File,
        dictionaries: List<RimeUserDictionaryArchive>,
        onComplete: (Int?) -> Unit,
    ): Boolean =
        ::rime.isInitialized &&
            rime.importUserDictionaries(sourceDir, dictionaries, onComplete)

    override fun onOpenAbout() {
        startActivity(Intent(this, AboutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onOpenDataManagement() {
        startActivity(Intent(this, DataManagementActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    internal companion object {
        const val TAG = "OpenIme"
        const val CANDIDATE_REFRESH_IDLE_POLL_MS = 16L
        const val MAX_RIME_INPUT_LENGTH = 256
        const val MAX_RIME_NINE_KEY_PATHS = 6
        const val MAX_CANDIDATES = 96
        const val TYPO_CORRECTION_NATIVE_THRESHOLD = 8
        @Volatile
        var activeInstance: LocalVoiceImeService? = null
    }
}

/** Punctuation that stays ASCII right after a digit (3.14, 12:30, 1,000). */
private val HARDWARE_ASCII_AFTER_DIGIT = setOf(','.code, '.'.code, ':'.code)
