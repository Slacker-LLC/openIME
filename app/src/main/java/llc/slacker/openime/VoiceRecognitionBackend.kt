package llc.slacker.openime

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Stable voice boundary used by the keyboard UI. The production contract is
 * local-only; a bundled model runtime can be supplied without changing
 * InputMethodService, key handling, or the transcript UI.
 */
interface VoiceRecognitionEvents {
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onRms(rms: Float)
    fun onError(message: String)
    fun onReady()
}

interface VoiceRecognitionBackend {
    val id: String

    fun isAvailable(): Boolean

    fun start(languageTag: String, events: VoiceRecognitionEvents)

    fun stop()
}

/**
 * Runtime contract for the model that will eventually be packaged in the APK.
 * The model runtime owns audio decoding/inference; the IME only receives text
 * and level events through this small interface.
 */
interface EmbeddedVoiceModelRuntime {
    val isReady: Boolean

    fun start(languageTag: String, events: VoiceRecognitionEvents)

    fun stop()
}

/**
 * Audio-facing contract for the bundled streaming runtime. The runtime keeps
 * its OnlineStream state between calls; the IME never sends the whole
 * recording back through the model on every partial result.
 */
interface StreamingEmbeddedVoiceModelRuntime : EmbeddedVoiceModelRuntime {
    fun acceptWaveform(samples: FloatArray)

    /** Finish the current stream and return the raw ASR text. */
    fun inputFinished(): String

    /** Run punctuation once at the end; null means punctuation failed. */
    fun punctuate(text: String): String?
}

/**
 * Detects the future model bundle without pretending that an absent model is
 * usable. The expected files are deliberately explicit so a partial APK does
 * not silently claim to support local recognition.
 */
class EmbeddedLocalVoiceBackend(
    private val context: Context,
    private val runtime: EmbeddedVoiceModelRuntime? = null,
) : VoiceRecognitionBackend {
    override val id: String = "embedded-local"

    private val modelAssetsPresent: Boolean by lazy {
        assetExists("models/voice/manifest.json")
    }

    override fun isAvailable(): Boolean = modelAssetsPresent && runtime?.isReady == true

    override fun start(languageTag: String, events: VoiceRecognitionEvents) {
        if (!isAvailable()) {
            events.onError("APK 尚未内置可用的本地语音模型")
            return
        }
        runtime?.start(languageTag, events)
    }

    override fun stop() {
        runtime?.stop()
    }

    private fun assetExists(path: String): Boolean = runCatching {
        context.assets.open(path).use { true }
    }.getOrDefault(false)
}

object VoiceRecognitionBackendFactory {
    /**
     * Keep the selection in one place. Once the model runtime is linked into
     * this independent APK, this factory is the only production seam that
     * needs to change.
     */
    fun create(context: Context): VoiceRecognitionBackend {
        val runtime = EmbeddedVoiceRuntimeFactory.create(context)
        val embedded = EmbeddedLocalVoiceBackend(context, runtime)
        if (embedded.isAvailable() && runtime is StreamingEmbeddedVoiceModelRuntime) {
            return LocalAudioVoiceBackend(context, runtime)
        }
        // Do not silently fall back to Android Speech: it may upload audio or
        // text to a system/provider service and would violate the offline-only
        // contract. The UI reports that the local model is not ready instead.
        return UnavailableLocalVoiceBackend()
    }
}

private class UnavailableLocalVoiceBackend : VoiceRecognitionBackend {
    override val id: String = "embedded-local-unavailable"

    override fun isAvailable(): Boolean = false

    override fun start(languageTag: String, events: VoiceRecognitionEvents) {
        events.onError("本地语音模型未就绪；未启用联网语音识别")
    }

    override fun stop() = Unit
}

object EmbeddedVoiceRuntimeFactory {
    fun create(context: Context): EmbeddedVoiceModelRuntime? {
        val selection = VoiceModelRepository(context).selectAvailable()
        val manifest = selection.manifest ?: return null
        if (manifest.modelType != "zipformer") return null
        return SherpaOnnxStreamingRuntime(context, manifest)
    }
}

/**
 * The bundled Chinese streaming Zipformer runtime. Model loading is lazy: the
 * 182 MB encoder is not mapped while the keyboard is merely being displayed,
 * and the loaded recognizer is reused across voice sessions.
 */
private class SherpaOnnxStreamingRuntime(
    private val context: Context,
    private val manifest: VoiceModelManifest,
) : StreamingEmbeddedVoiceModelRuntime {
    companion object {
        private const val SAMPLE_RATE = LocalVoiceAudioSpec.SAMPLE_RATE
        private const val MODEL_ROOT = "models/voice/bilingual-zipformer"
        private const val ENCODER = "$MODEL_ROOT/encoder-epoch-99-avg-1.int8.onnx"
        private const val DECODER = "$MODEL_ROOT/decoder-epoch-99-avg-1.onnx"
        private const val JOINER = "$MODEL_ROOT/joiner-epoch-99-avg-1.int8.onnx"
        private const val TOKENS = "$MODEL_ROOT/tokens.txt"
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var events: VoiceRecognitionEvents? = null
    private var lastPartial = ""

    override val isReady: Boolean
        get() = manifest.modelType == "zipformer" && manifest.files.containsAll(
            listOf(ENCODER, DECODER, JOINER, TOKENS),
        )

    override fun start(languageTag: String, events: VoiceRecognitionEvents) {
        check(isReady) { "内置语音模型清单或文件不完整" }
        if (recognizer == null) {
            recognizer = OnlineRecognizer(
                context.assets,
                OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = ENCODER,
                            decoder = DECODER,
                            joiner = JOINER,
                        ),
                        tokens = TOKENS,
                        numThreads = 2,
                        provider = "cpu",
                        modelType = "zipformer",
                    ),
                    enableEndpoint = false,
                    decodingMethod = "greedy_search",
                ),
            )
        }
        stream?.release()
        stream = recognizer?.createStream()
        this.events = events
        lastPartial = ""
    }

    override fun acceptWaveform(samples: FloatArray) {
        val currentRecognizer = checkNotNull(recognizer) { "语音识别器尚未启动" }
        val currentStream = checkNotNull(stream) { "语音流尚未启动" }
        if (samples.isEmpty()) return
        currentStream.acceptWaveform(samples, SAMPLE_RATE)
        decodeReady(currentRecognizer, currentStream)
    }

    override fun inputFinished(): String {
        val currentRecognizer = recognizer ?: return lastPartial
        val currentStream = stream ?: return lastPartial
        currentStream.inputFinished()
        decodeReady(currentRecognizer, currentStream)
        return currentRecognizer.getResult(currentStream).text.trim().also { lastPartial = it }
    }

    override fun punctuate(text: String): String = text

    override fun stop() {
        stream?.release()
        stream = null
        events = null
        lastPartial = ""
        // Keep the recognizer mapped. The next long-press can start promptly
        // without remapping the large encoder into the IME process.
    }

    private fun decodeReady(currentRecognizer: OnlineRecognizer, currentStream: OnlineStream) {
        while (currentRecognizer.isReady(currentStream)) {
            currentRecognizer.decode(currentStream)
            val text = currentRecognizer.getResult(currentStream).text.trim()
            if (text.isNotEmpty() && text != lastPartial) {
                lastPartial = text
                events?.onPartial(text)
            }
        }
    }
}
