package llc.slacker.openime

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Compatibility facade for the voice panel. Backend selection is kept behind
 * [VoiceRecognitionBackendFactory] so an embedded model can replace the
 * Android speech service in this independent APK later.
 */
class SpeechRecognitionProvider(
    private val context: Context,
) {

    interface SpeechEvents : VoiceRecognitionEvents

    private val backend: VoiceRecognitionBackend = VoiceRecognitionBackendFactory.create(context)

    val backendId: String get() = backend.id

    fun isAvailable(): Boolean = backend.isAvailable()

    fun start(languageTag: String, events: SpeechEvents) = backend.start(languageTag, events)

    fun stop() = backend.stop()

    fun cancel() = backend.cancel()
}

/** Real device speech provider used until the embedded runtime is supplied. */
class AndroidSpeechRecognitionBackend(
    private val context: Context,
) : VoiceRecognitionBackend {
    override val id: String = "android-speech"

    private var recognizer: SpeechRecognizer? = null

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(languageTag: String, events: VoiceRecognitionEvents) {
        stop()
        if (!isAvailable()) {
            events.onError("当前设备没有可用语音识别服务")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            events.onError("当前未授予麦克风权限，请在系统设置中授权后重试")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) = events.onReady()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = events.onRms(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                events.onError("语音识别错误($error)")
            }
            override fun onResults(results: android.os.Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                events.onFinal(text)
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                events.onPartial(text)
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        r.startListening(intent)
    }

    override fun stop() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    override fun cancel() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }
}
