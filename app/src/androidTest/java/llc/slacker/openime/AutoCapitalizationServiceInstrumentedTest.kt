package llc.slacker.openime

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import llc.slacker.openime.candidate.CandidateQueryCoordinator
import llc.slacker.openime.core.ImeState
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.core.ShiftState
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.editor.InputConnectionGateway
import llc.slacker.openime.rime.RimeEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/**
 * 首字母自动大写在服务这一侧：开着的时候，句末标记之后的英文要 Shift 抬起；关着的
 * 时候（参考自己的默认）什么都不做。密码框、要求全大写的输入框、用户自己锁的 Caps
 * Lock 都不动，读不到输入框文字时也不猜。
 */
@RunWith(AndroidJUnit4::class)
class AutoCapitalizationServiceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun restoreTheSwitch() {
        ImeSettingsRepository.saveFirstLetterCapitalization(context, false)
    }

    private fun withService(
        enabled: Boolean,
        textBefore: () -> String?,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        passwordField: Boolean = false,
        shiftState: ShiftState = ShiftState.LOWERCASE,
        test: (LocalVoiceImeService) -> Unit,
    ) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val connection = Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getTextBeforeCursor" -> textBefore()
                    "commitText", "deleteSurroundingText", "sendKeyEvent", "performEditorAction",
                    "finishComposingText", "setComposingText", "setComposingRegion",
                    "requestCursorUpdates", "closeConnection",
                    -> true
                    else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                }
            } as InputConnection

            val service = LocalVoiceImeService()
            // The service is constructed directly instead of being started by the
            // platform, so it has no base context yet.
            ContextWrapper::class.java
                .getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }
                .invoke(service, context)

            ImeSettingsRepository.saveFirstLetterCapitalization(context, enabled)
            setField(service, "gateway", InputConnectionGateway(context, { connection }, { passwordField }))
            // The backspace path clears the pre-edit, which goes through the
            // engine and the candidate queries; neither is started in a bare
            // service, so the test hands both a not-ready instance.
            val rime = RimeEngine(context)
            setField(service, "rime", rime)
            setField(
                service,
                "candidateQueries",
                CandidateQueryCoordinator(
                    rime = rime,
                    mainHandler = Handler(Looper.getMainLooper()),
                    fallbackCandidatesFor = { emptyList() },
                    maxInputLength = 32,
                    maxNineKeyPaths = 8,
                    maxCandidates = 20,
                ),
            )
            setField(
                service,
                "state",
                ImeState(
                    keyboardMode = KeyboardMode.ENGLISH_26,
                    passwordField = passwordField,
                    shiftState = shiftState,
                    editorInfo = EditorInfo().apply { this.inputType = inputType },
                ),
            )
            test(service)
        }
    }

    private fun setField(service: LocalVoiceImeService, name: String, value: Any) {
        LocalVoiceImeService::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(service, value)
        }
    }

    private fun shiftState(service: LocalVoiceImeService): ShiftState {
        val field = LocalVoiceImeService::class.java.getDeclaredField("state").apply { isAccessible = true }
        return (field.get(service) as ImeState).shiftState
    }

    @Test
    fun offItLeavesTheShiftKeyWhereTheEditorPutIt() {
        withService(enabled = false, textBefore = { "Hello. " }) { service ->
            service.onCharacter("a")
            assertEquals(ShiftState.LOWERCASE, shiftState(service))
        }
    }

    @Test
    fun onAMarkAfterAWordTheNextLetterIsACapital() {
        withService(enabled = true, textBefore = { "Hello. " }) { service ->
            service.onCharacter("a")
            assertEquals(ShiftState.SHIFT_ONCE, shiftState(service))
        }
        withService(enabled = true, textBefore = { "Hello wor" }) { service ->
            service.onCharacter("a")
            assertEquals(ShiftState.LOWERCASE, shiftState(service))
        }
    }

    @Test
    fun theSwitchItselfTakesEffectOnTheFieldThatIsUp() {
        var before = "Hello. "
        withService(enabled = false, textBefore = { before }) { service ->
            service.onCharacter("a")
            assertEquals("关着的时候不动", ShiftState.LOWERCASE, shiftState(service))

            // The panel writes the setting and calls this; the field is re-read.
            ImeSettingsRepository.saveFirstLetterCapitalization(context, true)
            service.onAutoCapitalizationChanged()
            assertEquals(ShiftState.SHIFT_ONCE, shiftState(service))

            // Turning it back off leaves the last decision alone: Shift is the
            // user's from here on.
            ImeSettingsRepository.saveFirstLetterCapitalization(context, false)
            before = "Hello"
            service.onAutoCapitalizationChanged()
            assertEquals(ShiftState.SHIFT_ONCE, shiftState(service))
        }
    }

    @Test
    fun deletingTheMarkLowersTheShiftAgain() {
        var before = "Hi. "
        withService(enabled = true, textBefore = { before }) { service ->
            service.onCharacter("a")
            assertEquals(ShiftState.SHIFT_ONCE, shiftState(service))

            before = "Hi"
            service.onBackspace()
            assertEquals(ShiftState.LOWERCASE, shiftState(service))
        }
    }

    @Test
    fun typingTheMarkItselfRaisesTheShift() {
        var before = "Hello"
        withService(enabled = true, textBefore = { before }) { service ->
            before = "Hello."
            service.onSymbolSelected(".")
            assertEquals(ShiftState.SHIFT_ONCE, shiftState(service))
        }
    }

    @Test
    fun aSentenceMarkInAFieldThatOnlyWantsLowercaseIsStillRespected() {
        withService(
            enabled = true,
            textBefore = { "Hello. " },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
        ) { service ->
            service.onAutoCapitalizationChanged()
            assertEquals(
                "全大写输入框由它自己决定，这里不改",
                ShiftState.LOWERCASE,
                shiftState(service),
            )
        }
    }

    @Test
    fun theUserSLockedCapsIsNeverReleased() {
        withService(
            enabled = true,
            textBefore = { "Hello wor" },
            shiftState = ShiftState.CAPS_LOCK,
        ) { service ->
            service.onCharacter("a")
            assertEquals(ShiftState.CAPS_LOCK, shiftState(service))
        }
    }

    @Test
    fun aPasswordFieldIsNeverRead() {
        withService(
            enabled = true,
            textBefore = { "Hello. " },
            passwordField = true,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        ) { service ->
            service.onAutoCapitalizationChanged()
            assertEquals(ShiftState.LOWERCASE, shiftState(service))
        }
    }

    @Test
    fun anEditorThatCannotAnswerLeavesTheShiftAlone() {
        withService(enabled = true, textBefore = { null }) { service ->
            service.onAutoCapitalizationChanged()
            assertEquals(ShiftState.LOWERCASE, shiftState(service))
        }
    }
}
