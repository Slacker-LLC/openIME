package llc.slacker.openime.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import java.io.File
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
    /** Capture has ended; decoding may still be draining buffered audio. */
    fun onCaptureStopped() {}
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

/** Local-only backend backed by whichever verified model source is selected. */
class EmbeddedLocalVoiceBackend(
    private val context: Context,
    private val runtime: EmbeddedVoiceModelRuntime? = null,
) : VoiceRecognitionBackend {
    override val id: String = "embedded-local"

    override fun isAvailable(): Boolean = runtime?.isReady == true

    override fun start(languageTag: String, events: VoiceRecognitionEvents) {
        if (!isAvailable()) {
            events.onError("本地语音模型不可用")
            return
        }
        runtime?.start(languageTag, events)
    }

    override fun stop() {
        runtime?.stop()
    }
}

object VoiceRecognitionBackendFactory {
    /** Keep local/offline selection behind one production seam. */
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
        if (manifest.modelType != "paraformer") return null
        val downloadedRoot = File(context.filesDir, VOICE_MODEL_DOWNLOADED_DIR)
        if (resolveSherpaRuntimeModelFiles(selection, downloadedRoot) == null) return null
        return SherpaOnnxStreamingRuntime(context, selection)
    }
}

/**
 * Streaming Paraformer runtime for either signed APK assets or a verified
 * app-private downloaded package. Model loading is lazy and the recognizer is
 * reused until the selected source changes or the lifecycle releases it.
 */
private class SherpaOnnxStreamingRuntime(
    private val context: Context,
    initialSelection: VoiceModelSelection,
) : StreamingEmbeddedVoiceModelRuntime {
    companion object {
        private const val SAMPLE_RATE = LocalVoiceAudioSpec.SAMPLE_RATE
        private const val PARAFORMER_TAIL_PADDING_SAMPLES = SAMPLE_RATE * 3 / 10
    }

    private val runtimeLock = Any()
    private val downloadedRoot = File(context.filesDir, VOICE_MODEL_DOWNLOADED_DIR)
    private var selection: VoiceModelSelection = initialSelection
    private var modelFiles: SherpaRuntimeModelFiles? =
        resolveSherpaRuntimeModelFiles(initialSelection, downloadedRoot)
    private var recognizer: OnlineRecognizer? = null
    private var punctuation: OfflinePunctuation? = null
    private val activeSessions = mutableSetOf<SherpaSession>()
    private var legacySession: SherpaSession? = null

    override val isReady: Boolean
        get() = synchronized(runtimeLock) {
            val manifest = selection.manifest
            manifest?.modelType == "paraformer" && modelFiles != null
        }

    override fun preload() {
        synchronized(runtimeLock) {
            refreshSelectionLocked()
            ensureRecognizerLocked()
        }
    }

    override fun openSession(
        languageTag: String,
        events: VoiceRecognitionEvents,
    ): StreamingVoiceModelSession = synchronized(runtimeLock) {
        refreshSelectionLocked()
        check(isReady) { "本地语音模型清单或文件不完整" }
        openSessionLocked(languageTag, events)
    }

    /** Legacy non-AudioRecord path retained for the generic runtime boundary. */
    override fun start(languageTag: String, events: VoiceRecognitionEvents) {
        synchronized(runtimeLock) {
            refreshSelectionLocked()
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
            releaseRecognizerLocked()
        }
    }

    /**
     * Repository selection is checked only on worker-thread preload/session
     * boundaries. If source changes, every old stream is closed before the old
     * native recognizer is released, then the next recognizer uses the new root.
     */
    private fun refreshSelectionLocked() {
        val latest = VoiceModelRepository(context).selectAvailable()
        if (latest.runtimeIdentity() == selection.runtimeIdentity()) return

        releaseRecognizerLocked()
        selection = latest
        modelFiles = resolveSherpaRuntimeModelFiles(latest, downloadedRoot)
    }

    private fun releaseRecognizerLocked() {
        activeSessions.toList().forEach { it.closeLocked() }
        legacySession = null
        recognizer?.release()
        recognizer = null
        punctuation?.release()
        punctuation = null
    }

    private fun ensureRecognizerLocked(): OnlineRecognizer {
        recognizer?.let { return it }
        val files = checkNotNull(modelFiles) { "本地语音模型清单或文件不完整" }
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                paraformer = OnlineParaformerModelConfig(
                    encoder = files.encoder,
                    decoder = files.decoder,
                ),
                tokens = files.tokens,
                numThreads = 2,
                provider = "cpu",
                modelType = "paraformer",
            ),
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        )
        val assetManager = if (files.storage == SherpaRuntimeStorage.ASSETS) {
            context.assets
        } else {
            null
        }
        return OnlineRecognizer(assetManager, config).also { recognizer = it }
    }

    private fun openSessionLocked(
        languageTag: String,
        events: VoiceRecognitionEvents,
    ): SherpaSession {
        val currentRecognizer = ensureRecognizerLocked()
        // sherpa-onnx dynamic hotword graphs are implemented by the
        // transducer recognizer. Streaming Paraformer uses a plain stream;
        // openIME still applies its local post-ASR correction repository.
        val stream = currentRecognizer.createStream()
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
                // sherpa-onnx's streaming Paraformer examples append 300 ms
                // silence before InputFinished so the last chunk can flush.
                currentStream.acceptWaveform(FloatArray(PARAFORMER_TAIL_PADDING_SAMPLES), SAMPLE_RATE)
                currentStream.inputFinished()
                // InputFinished ends feature extraction, but Paraformer's
                // IsReady accepts the remaining short chunk only with this
                // option. Otherwise already-recorded tail frames stay undecoded.
                currentStream.setOption("is_final", "1")
                val decoded = decodeReadyLocked(currentRecognizer, currentStream)
                val recognizerFinal = currentRecognizer.getResult(currentStream).text.trim()
                val usedFallback = recognizerFinal.isBlank() && lastPartial.isNotBlank()
                if (usedFallback) {
                    Log.i(
                        "OpenImeVoicePerf",
                        "sherpa inputFinished fallback=true finalLength=${recognizerFinal.length} " +
                            "lastPartialLength=${lastPartial.length}",
                    )
                }
                val final = recognizerFinal.ifBlank { lastPartial }
                lastPartial = final
                final to decoded
            }
            partials.forEach(events::onPartial)
            return result
        }

        override fun punctuate(text: String): String {
            val policy = VoiceTextProcessingPolicy(
                autoTerminalPunctuation = VoiceEditorContext.allowNaturalPunctuation(),
                stripFillers = VoiceEditorContext.stripFillers(),
                punctuationAsSpace = VoiceEditorContext.punctuationAsSpace(),
            )
            val prepared = VoiceTextProcessor.process(text, languageTag, policy.copy(
                autoTerminalPunctuation = false, punctuationAsSpace = false,
            ))
            // Preserve explicitly dictated marks and structured editor values.
            val restored = if (policy.autoTerminalPunctuation && prepared.none { it in "，。！？；：,.!?;:" }) {
                synchronized(runtimeLock) {
                    checkOpenLocked()
                    runCatching {
                        val model = punctuation ?: OfflinePunctuation(context.assets,
                            OfflinePunctuationConfig(model = OfflinePunctuationModelConfig(
                                ctTransformer = "models/voice/punctuation/model.int8.onnx",
                                numThreads = 2,
                            )),
                        ).also { punctuation = it }
                        model.addPunctuation(prepared)
                    }.onFailure { Log.w("OpenImeVoicePerf", "punctuation failed; keeping transcript", it) }
                        .getOrNull()
                }
            } else null
            val normalized = if (prepared.none { it in '\u4e00'..'\u9fff' }) {
                (restored ?: prepared).replace('，', ',').replace('。', '.')
                    .replace('？', '?').replace('！', '!').replace('：', ':').replace('；', ';')
            } else restored ?: prepared
            return VoiceTextProcessor.process(normalized, languageTag, policy.copy(
                autoTerminalPunctuation = restored == null && policy.autoTerminalPunctuation,
                stripFillers = false,
            ))
        }

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
