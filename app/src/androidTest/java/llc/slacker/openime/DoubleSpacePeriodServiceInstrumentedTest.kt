package llc.slacker.openime

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import llc.slacker.openime.candidate.CandidateQueryCoordinator
import llc.slacker.openime.core.ImeState
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.editor.InputConnectionGateway
import llc.slacker.openime.rime.RimeEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/**
 * 双击空格键输入句号在服务这一侧：200 毫秒内的第二个空格把刚写下的空格换成
 * 「。」，落在标点后面或太慢的第二个空格仍是空格，开关关着（参考自己的默认）
 * 从不换。参考的处理器是 `InputViewRoot$z.invoke`（classes.dex code_off
 * 0x4c1764），窗口 `const-wide/16 0xc8`。
 */
@RunWith(AndroidJUnit4::class)
class DoubleSpacePeriodServiceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun restoreTheSwitch() {
        ImeSettingsRepository.saveDoubleSpacePeriod(context, false)
    }

    private fun withService(
        enabled: Boolean,
        textBeforeCursor: String? = "好",
        test: (LocalVoiceImeService, MutableList<String>) -> Unit,
    ) {
        val events = mutableListOf<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val connection = Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getTextBeforeCursor" -> textBeforeCursor
                    "commitText" -> {
                        events += "commit:${args?.firstOrNull()?.toString().orEmpty()}"
                        true
                    }
                    "deleteSurroundingTextInCodePoints", "deleteSurroundingText" -> {
                        events += "delete"
                        true
                    }
                    "sendKeyEvent" -> {
                        events += "key"
                        true
                    }
                    else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                }
            } as InputConnection

            val service = LocalVoiceImeService()
            ContextWrapper::class.java
                .getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }
                .invoke(service, context)

            ImeSettingsRepository.saveDoubleSpacePeriod(context, enabled)
            setField(service, "gateway", InputConnectionGateway(context, { connection }))
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
                    editorInfo = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT },
                ),
            )
            test(service, events)
        }
    }

    private fun setField(service: LocalVoiceImeService, name: String, value: Any) {
        LocalVoiceImeService::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(service, value)
        }
    }

    private fun readField(service: LocalVoiceImeService, name: String): Any? =
        LocalVoiceImeService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun ageTheWindow(service: LocalVoiceImeService, millis: Long) {
        setField(service, "lastSpaceAtMs", SystemClock.uptimeMillis() - millis)
    }

    @Test
    fun aSecondSpaceInsideTheWindowWritesThePeriod() = withService(enabled = true) { service, events ->
        service.onSpace()
        assertEquals(listOf("commit: "), events)
        service.onSpace()
        assertEquals("删掉那个空格，再把句号写上去", listOf("commit: ", "delete", "commit:。"), events)
    }

    @Test
    fun offTheSecondSpaceIsJustASpace() = withService(enabled = false) { service, events ->
        service.onSpace()
        service.onSpace()
        assertEquals(listOf("commit: ", "commit: "), events)
    }

    @Test
    fun aSecondSpaceTooLateStaysASpace() = withService(enabled = true) { service, events ->
        service.onSpace()
        ageTheWindow(service, 500L)
        service.onSpace()
        assertEquals(listOf("commit: ", "commit: "), events)
    }

    @Test
    fun aSpaceAfterPunctuationStaysASpace() = withService(enabled = true) { service, events ->
        // The character before the first space is what the reference gates on.
        service.onSpace()
        setField(service, "lastCharBeforeSpace", '，')
        service.onSpace()
        assertEquals(listOf("commit: ", "commit: "), events)
    }

    @Test
    fun anEditorThatCannotAnswerStaysASpace() = withService(enabled = true, textBeforeCursor = null) { service, events ->
        service.onSpace()
        service.onSpace()
        assertEquals(listOf("commit: ", "commit: "), events)
    }

    @Test
    fun theFirstSpaceKeepsWhatWasBeforeIt() = withService(enabled = true, textBeforeCursor = "你") { service, _ ->
        service.onSpace()
        assertEquals('你', readField(service, "lastCharBeforeSpace"))
        assertEquals(0L < (readField(service, "lastSpaceAtMs") as Long), true)
    }
}
