package llc.slacker.openime

import android.content.Context
import android.content.ContextWrapper
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualVoiceOwnershipInstrumentedTest {
    @Test fun manualTextRemovesVoiceCompositionBeforeCommitting() {
        verifyManualEdits(voiceComposing = true)
    }

    @Test fun inactiveVoiceDoesNotClearOrdinaryEditorComposition() {
        verifyManualEdits(voiceComposing = false)
    }

    private fun verifyManualEdits(voiceComposing: Boolean) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val edits: List<(LocalVoiceImeService) -> Unit> = listOf(
                { it.onCharacter("x") }, { it.onSymbolSelected("x") }, { it.onEmojiSelected("x") },
            )
            for (edit in edits) {
                val events = mutableListOf<String>()
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
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
                    isAccessible = true
                    invoke(service, InstrumentationRegistry.getInstrumentation().targetContext)
                }
                fun setField(name: String, value: Any) {
                    LocalVoiceImeService::class.java.getDeclaredField(name).apply {
                        isAccessible = true
                        set(service, value)
                    }
                }
                setField("gateway", InputConnectionGateway(null, { connection }))
                setField("voiceComposing", voiceComposing)
                edit(service)
                assertEquals(
                    if (voiceComposing) listOf("compose:", "finish", "commit:x") else listOf("commit:x"),
                    events,
                )
                assertEquals(false, service.voiceComposingForTest())
            }
        }
    }
}
