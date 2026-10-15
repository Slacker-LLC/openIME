package llc.slacker.openime

import android.content.Context
import android.content.ContextWrapper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import llc.slacker.openime.core.ImeState
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.core.PinyinLocation
import llc.slacker.openime.editor.InputConnectionGateway
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/**
 * 「输入拼音显示」在服务这一侧：选输入框时拼音进应用自己的输入框，切档时在场的
 * 那段预编辑跟着搬（切回候选栏要把它收回），收不下的输入框一律不写。
 */
@RunWith(AndroidJUnit4::class)
class PinyinLocationServiceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withService(
        pinyinLocation: PinyinLocation,
        inputType: Int,
        passwordField: Boolean = false,
        events: MutableList<String>,
        test: (LocalVoiceImeService) -> Unit,
    ) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val connection = Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "setComposingText" -> { events += "compose:${args!![0]}"; true }
                    "finishComposingText" -> { events += "finish"; true }
                    "commitText" -> { events += "commit:${args!![0]}"; true }
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

            setField(service, "gateway", InputConnectionGateway(null, { connection }))
            setField(service, "lastComposition", "nihao")
            setField(
                service,
                "state",
                ImeState(
                    keyboardMode = KeyboardMode.PINYIN_26,
                    composition = "nihao",
                    passwordField = passwordField,
                    pinyinLocation = pinyinLocation,
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

    /** 打一个字母走的那条路（`updateComposition` 读设置与输入框类型决定写不写）。 */
    private fun typePinyin(service: LocalVoiceImeService, next: String) {
        LocalVoiceImeService::class.java
            .getDeclaredMethod("updateComposition", String::class.java, List::class.java)
            .apply { isAccessible = true }
            .invoke(service, next, emptyList<String>())
    }

    @Test
    fun typingPinyinEntersTheEditorOnlyInTheInputBoxMode() {
        val events = mutableListOf<String>()
        withService(PinyinLocation.CANDIDATE_BAR, InputType.TYPE_CLASS_TEXT, events = events) { service ->
            typePinyin(service, "nihao")
            assertEquals("候选栏一档：拼音不上输入框", emptyList<String>(), events)
        }

        val editorEvents = mutableListOf<String>()
        withService(PinyinLocation.INPUT_BOX, InputType.TYPE_CLASS_TEXT, events = editorEvents) { service ->
            typePinyin(service, "nihao")
            assertEquals(listOf("compose:nihao"), editorEvents)
        }
    }

    @Test
    fun fieldsThatCannotHoldPinyinKeepItOnTheKeyboard() {
        listOf(
            "数字框" to InputType.TYPE_CLASS_NUMBER,
            "小数框" to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
            "电话框" to InputType.TYPE_CLASS_PHONE,
            "日期框" to (InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE),
            "只吃按键的框" to InputType.TYPE_NULL,
        ).forEach { (name, type) ->
            val events = mutableListOf<String>()
            withService(PinyinLocation.INPUT_BOX, type, events = events) { service ->
                typePinyin(service, "nihao")
                assertEquals(name, emptyList<String>(), events)
            }
        }
    }

    @Test
    fun aPasswordFieldNeverTakesTheComposition() {
        val typed = mutableListOf<String>()
        withService(
            PinyinLocation.INPUT_BOX,
            InputType.TYPE_CLASS_TEXT,
            passwordField = true,
            events = typed,
        ) { service -> typePinyin(service, "nihao") }
        assertEquals("密码框不收拼音", emptyList<String>(), typed)

        // 密码框有时报纯文本类型，只能靠这个标志挡住。收尾事件（finishComposingText）
        // 不带字，允许；带字的那条通道不能走。
        val switched = mutableListOf<String>()
        withService(
            PinyinLocation.CANDIDATE_BAR,
            InputType.TYPE_CLASS_TEXT,
            passwordField = true,
            events = switched,
        ) { service -> service.onPinyinLocationChanged(PinyinLocation.INPUT_BOX) }
        assertEquals(
            "密码框里切档也不写拼音",
            emptyList<String>(),
            switched.filter { it.contains("nihao") },
        )
    }

    @Test
    fun switchingTheSettingMovesTheCompositionInAndBackOut() {
        val events = mutableListOf<String>()
        withService(PinyinLocation.CANDIDATE_BAR, InputType.TYPE_CLASS_TEXT, events = events) { service ->
            service.onPinyinLocationChanged(PinyinLocation.INPUT_BOX)
            assertEquals("在场的拼音搬进输入框", listOf("compose:nihao"), events)

            events.clear()
            service.onPinyinLocationChanged(PinyinLocation.CANDIDATE_BAR)
            assertEquals("切回候选栏把输入框里那段收回", listOf("compose:", "finish"), events)
        }
    }

    @Test
    fun switchingWithNothingComposingTouchesNothing() {
        val events = mutableListOf<String>()
        withService(PinyinLocation.CANDIDATE_BAR, InputType.TYPE_CLASS_TEXT, events = events) { service ->
            setField(service, "lastComposition", "")
            service.onPinyinLocationChanged(PinyinLocation.INPUT_BOX)
            assertEquals(emptyList<String>(), events)
        }
    }
}
