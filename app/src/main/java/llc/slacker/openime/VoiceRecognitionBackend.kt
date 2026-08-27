package llc.slacker.openime

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.atomic.AtomicBoolean

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
    /** Microphone capture is live; model loading may still be in progress. */
    fun onReady()
    /** The buffered audio is now connected to the local recognizer. */
    fun onModelReady() {}
}

interface VoiceRecognitionBackend {
    val id: String

    fun isAvailable(): Boolean

    fun start(languageTag: String, events: VoiceRecognitionEvents)

    fun stop()

    /** Discard buffered audio and suppress the final transcript. */
    fun cancel() = stop()
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

/** One independently owned streaming decode session. */
interface StreamingVoiceModelSession : AutoCloseable {
    fun acceptWaveform(samples: FloatArray)

    /** Finish this stream and return the raw ASR text. */
    fun inputFinished(): String

    /** Run punctuation once at the end; null means punctuation failed. */
    fun punctuate(text: String): String?
}

/**
 * Audio-facing contract for the bundled streaming runtime. The recognizer is
 * shared and preloaded, but each long-press receives an independent stream
 * handle so stale session cleanup cannot release a newer session's stream.
 */
interface StreamingEmbeddedVoiceModelRuntime : EmbeddedVoiceModelRuntime {
    /** Map and initialize the local recognizer without starting a voice session. */
    fun preload()

    fun openSession(
        languageTag: String,
        events: VoiceRecognitionEvents,
    ): StreamingVoiceModelSession

    /** Release all native model resources after the IME cooldown expires. */
    fun release()
}

/** A small idempotent owner used so each voice session releases only its resource. */
internal class VoiceStreamLease<T : Any>(
    val value: T,
    private val releaseResource: (T) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) releaseResource(value)
    }
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
        return create(context, selection)
    }

    fun create(
        context: Context,
        selection: VoiceModelSelection,
    ): EmbeddedVoiceModelRuntime? {
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

    private val runtimeLock = Any()
    private var recognizer: OnlineRecognizer? = null
    private val activeSessions = mutableSetOf<SherpaSession>()
    private var legacySession: SherpaSession? = null

    override val isReady: Boolean
        get() = manifest.modelType == "zipformer" && manifest.files.containsAll(
            listOf(ENCODER, DECODER, JOINER, TOKENS),
        )

    override fun preload() {
        synchronized(runtimeLock) {
            ensureRecognizerLocked()
        }
    }

    override fun openSession(
        languageTag: String,
        events: VoiceRecognitionEvents,
    ): StreamingVoiceModelSession = synchronized(runtimeLock) {
        check(isReady) { "内置语音模型清单或文件不完整" }
        openSessionLocked(languageTag, events)
    }

    /** Legacy non-AudioRecord path retained for the generic runtime boundary. */
    override fun start(languageTag: String, events: VoiceRecognitionEvents) {
        synchronized(runtimeLock) {
            legacySession?.closeLocked()
            legacySession = openSessionLocked(languageTag, events)
        }
    }

    override fun stop() {
        synchronized(runtimeLock) {
            legacySession?.closeLocked()
            legacySession = null
        }
    }

    override fun release() {
        synchronized(runtimeLock) {
            activeSessions.toList().forEach { it.closeLocked() }
            legacySession = null
            recognizer?.release()
            recognizer = null
        }
    }

    private fun ensureRecognizerLocked(): OnlineRecognizer {
        recognizer?.let { return it }
        check(isReady) { "内置语音模型清单或文件不完整" }
        return OnlineRecognizer(
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
                    modelingUnit = "cjkchar",
                ),
                enableEndpoint = false,
                decodingMethod = "modified_beam_search",
                maxActivePaths = 4,
                hotwordsScore = 1.8f,
            ),
        ).also { recognizer = it }
    }

    private fun openSessionLocked(
        languageTag: String,
        events: VoiceRecognitionEvents,
    ): SherpaSession {
        val currentRecognizer = ensureRecognizerLocked()
        val hotwords = VoiceHotwordProvider.current()
        val stream = if (hotwords.isBlank()) {
            currentRecognizer.createStream()
        } else {
            currentRecognizer.createStream(hotwords)
        }
        return SherpaSession(
            streamLease = VoiceStreamLease(stream) { it.release() },
            languageTag = languageTag,
            events = events,
        ).also(activeSessions::add)
    }

    private inner class SherpaSession(
        private val streamLease: VoiceStreamLease<OnlineStream>,
        private val languageTag: String,
        private val events: VoiceRecognitionEvents,
    ) : StreamingVoiceModelSession {
        private var lastPartial = ""

        override fun acceptWaveform(samples: FloatArray) {
            if (samples.isEmpty()) return
            val partials = synchronized(runtimeLock) {
                checkOpenLocked()
                val currentRecognizer = checkNotNull(recognizer) { "语音识别器已释放" }
                val currentStream = streamLease.value
                currentStream.acceptWaveform(samples, SAMPLE_RATE)
                decodeReadyLocked(currentRecognizer, currentStream)
            }
            partials.forEach(events::onPartial)
        }

        override fun inputFinished(): String {
            val (result, partials) = synchronized(runtimeLock) {
                checkOpenLocked()
                val currentRecognizer = checkNotNull(recognizer) { "语音识别器已释放" }
                val currentStream = streamLease.value
                currentStream.inputFinished()
                val decoded = decodeReadyLocked(currentRecognizer, currentStream)
                val final = currentRecognizer.getResult(currentStream).text.trim()
                    .ifBlank { lastPartial }
                lastPartial = final
                final to decoded
            }
            partials.forEach(events::onPartial)
            return result
        }

        override fun punctuate(text: String): String = VoiceTextProcessor.process(text, languageTag)

        override fun close() {
            synchronized(runtimeLock) {
                closeLocked()
            }
        }

        fun closeLocked() {
            if (streamLease.isClosed) return
            streamLease.close()
            activeSessions.remove(this)
            if (legacySession === this) legacySession = null
            lastPartial = ""
        }

        private fun checkOpenLocked() {
            check(!streamLease.isClosed) { "语音流已经结束" }
        }

        private fun decodeReadyLocked(
            currentRecognizer: OnlineRecognizer,
            currentStream: OnlineStream,
        ): List<String> {
            val emitted = mutableListOf<String>()
            while (currentRecognizer.isReady(currentStream)) {
                currentRecognizer.decode(currentStream)
                val text = currentRecognizer.getResult(currentStream).text.trim()
                if (text.isNotEmpty() && text != lastPartial) {
                    lastPartial = text
                    emitted += text
                }
            }
            return emitted
        }
    }
}
