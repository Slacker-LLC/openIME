package llc.slacker.openime.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
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

/**
 * 解码方法选择。sherpa-onnx 只有 transducer 支持热词：静态热词表与开流时的
 * 动态热词都要求 modified_beam_search，所以 transducer 一律用它；
 * paraformer / CTC 即使用户有热词也保持 greedy_search（hasHotwords 被忽略）。
 */
internal fun decodingFor(kind: StreamingModelKind, hasHotwords: Boolean): String =
    if (kind == StreamingModelKind.TRANSDUCER) {
        "modified_beam_search"
    } else {
        "greedy_search"
    }

/**
 * 热词分词方式准入。原生 EncodeHotwords 只认 cjkchar / bpe / cjkchar+bpe，
 * 遇到其他取值会直接结束进程；modelingUnit 未声明时按 cjkchar 处理（本应用
 * 的热词均含 CJK 字符），bpe 类缺少词表时返回 null（禁用热词）。
 */
internal fun hotwordModelingUnit(modelingUnit: String, hasBpeVocab: Boolean): String? = when (modelingUnit) {
    "", "cjkchar" -> "cjkchar"
    "bpe", "cjkchar+bpe" -> modelingUnit.takeIf { hasBpeVocab }
    else -> null
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
        if (VoiceModelLayoutResolver.resolve(manifest) == null) return null
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
    private var vad: Vad? = null
    private val activeSessions = mutableSetOf<SherpaSession>()
    private var legacySession: SherpaSession? = null

    override val isReady: Boolean
        get() = synchronized(runtimeLock) {
            val manifest = selection.manifest
            manifest != null && modelFiles != null
        }

    override fun preload() {
        synchronized(runtimeLock) {
            refreshSelectionLocked()
            ensureRecognizerLocked()
            ensureVadLocked()
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

    /** 标点模型跟着所选模型走：内置读 assets，导入的读私有目录；没有就返回 null（保留原文）。 */
    private fun createPunctuationLocked(): OfflinePunctuation? {
        val manifest = selection.manifest ?: return null
        if (!manifest.supportsPunctuation) return null
        val rel = VoiceModelLayoutResolver.punctuationPath(manifest) ?: return null
        return when (selection.source) {
            VoiceModelSelection.Source.BUILT_IN -> OfflinePunctuation(
                context.assets,
                OfflinePunctuationConfig(model = OfflinePunctuationModelConfig(ctTransformer = rel, numThreads = 2)),
            )
            VoiceModelSelection.Source.DOWNLOADED -> {
                val root = File(downloadedRoot, manifest.modelId)
                val file = resolveContainedVoiceModelFile(root, rel)
                OfflinePunctuation(
                    null,
                    OfflinePunctuationConfig(model = OfflinePunctuationModelConfig(ctTransformer = file.absolutePath, numThreads = 2)),
                )
            }
            VoiceModelSelection.Source.NONE -> null
        }
    }

    private fun releaseRecognizerLocked() {
        activeSessions.toList().forEach { it.closeLocked() }
        legacySession = null
        recognizer?.release()
        recognizer = null
        punctuation?.release()
        punctuation = null
        vad?.release()
        vad = null
    }

    private fun ensureRecognizerLocked(): OnlineRecognizer {
        recognizer?.let { return it }
        val files = checkNotNull(modelFiles) { "本地语音模型清单或文件不完整" }
        // 热词分词方式先过准入：原生编码器遇到不支持的 modelingUnit 会直接
        // 结束进程，bpe 类缺少词表也会失败；两种情况都不给引擎传热词。
        val hotwordUnit = hotwordModelingUnit(files.modelingUnit, files.bpeVocab != null)
        if (files.kind == StreamingModelKind.TRANSDUCER && hotwordUnit == null) {
            Log.i("OpenImeVoicePerf", "modelingUnit=${files.modelingUnit} 不支持热词，热词表与动态热词已禁用")
        }
        val staticHotwords = files.hotwords?.takeIf {
            files.kind == StreamingModelKind.TRANSDUCER && hotwordUnit != null
        }
        val modelConfig = when (files.kind) {
            StreamingModelKind.PARAFORMER -> OnlineModelConfig(
                paraformer = OnlineParaformerModelConfig(
                    encoder = checkNotNull(files.encoder), decoder = checkNotNull(files.decoder),
                ),
                tokens = files.tokens, numThreads = 2, provider = "cpu", modelType = "paraformer",
            )
            StreamingModelKind.TRANSDUCER -> OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = checkNotNull(files.encoder), decoder = checkNotNull(files.decoder),
                    joiner = checkNotNull(files.joiner),
                ),
                tokens = files.tokens, numThreads = 2, provider = "cpu",
                // 家族留空时由 ONNX 元数据判定；bpe 词表/分词单元按包里的清单透传。
                modelType = files.family,
                modelingUnit = hotwordUnit.orEmpty(),
                bpeVocab = files.bpeVocab.orEmpty(),
            )
            StreamingModelKind.ZIPFORMER2_CTC -> OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = checkNotNull(files.model)),
                tokens = files.tokens, numThreads = 2, provider = "cpu",
            )
            StreamingModelKind.NEMO_CTC -> OnlineModelConfig(
                neMoCtc = OnlineNeMoCtcModelConfig(model = checkNotNull(files.model)),
                tokens = files.tokens, numThreads = 2, provider = "cpu",
            )
            StreamingModelKind.T_ONE_CTC -> OnlineModelConfig(
                toneCtc = OnlineToneCtcModelConfig(model = checkNotNull(files.model)),
                tokens = files.tokens, numThreads = 2, provider = "cpu",
            )
        }
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            // paraformer 的流式端点由本应用自己判断；其余类型用引擎自带的端点检测。
            enableEndpoint = files.kind != StreamingModelKind.PARAFORMER,
            // 解码方法见 decodingFor()；按解码方法要求固定 beam 宽度。
            decodingMethod = decodingFor(files.kind, staticHotwords != null),
            maxActivePaths = 4,
            hotwordsFile = staticHotwords.orEmpty(),
        )
        val assetManager = if (files.storage == SherpaRuntimeStorage.ASSETS) {
            context.assets
        } else {
            null
        }
        return OnlineRecognizer(assetManager, config).also { recognizer = it }
    }

    /**
     * Silero VAD 可选：包里有 silero_vad.onnx 时用它做端点判断；创建失败只是
     * 记录日志并退回原有判断，绝不阻塞识别器本身。
     */
    private fun ensureVadLocked(): Vad? {
        vad?.let { return it }
        val files = modelFiles ?: return null
        val model = files.vad ?: return null
        return runCatching {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = model,
                    threshold = 0.5F,
                    minSilenceDuration = 0.5F,
                    minSpeechDuration = 0.25F,
                    windowSize = 512,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            )
            val assetManager = if (files.storage == SherpaRuntimeStorage.ASSETS) context.assets else null
            Vad(assetManager, config).also { vad = it }
        }.onFailure {
            Log.w("OpenImeVoicePerf", "VAD 模型不可用，端点判断退回原有逻辑", it)
        }.getOrNull()
    }

    private fun openSessionLocked(
        languageTag: String,
        events: VoiceRecognitionEvents,
    ): SherpaSession {
        val currentRecognizer = ensureRecognizerLocked()
        val currentVad = ensureVadLocked()
        val files = checkNotNull(modelFiles) { "本地语音模型清单或文件不完整" }
        val hotwordUnit = hotwordModelingUnit(files.modelingUnit, files.bpeVocab != null)
        // 动态热词只对 transducer 生效；每次开流都重新取，用户可能刚改过。
        // Streaming Paraformer uses a plain stream; openIME still applies its
        // local post-ASR correction repository.
        val hotwords = if (files.kind == StreamingModelKind.TRANSDUCER && hotwordUnit != null) {
            VoiceHotwordProvider.current()
        } else {
            ""
        }
        val stream = if (hotwords.isEmpty()) {
            currentRecognizer.createStream()
        } else {
            // sherpa-onnx 按 "/" 换行解析热词；图构建失败时退回无热词流，
            // 不能让用户为一条坏热词丢掉整次录音。
            runCatching { currentRecognizer.createStream(hotwords) }.getOrElse {
                Log.w("OpenImeVoicePerf", "动态热词开流失败，本次退回无热词流", it)
                currentRecognizer.createStream()
            }
        }
        return SherpaSession(
            streamLease = VoiceStreamLease(stream) { it.release() },
            languageTag = languageTag,
            events = events,
            vad = currentVad,
        ).also(activeSessions::add)
    }

    private inner class SherpaSession(
        private val streamLease: VoiceStreamLease<OnlineStream>,
        private val languageTag: String,
        private val events: VoiceRecognitionEvents,
        private val vad: Vad?,
    ) : StreamingVoiceModelSession {
        private var lastPartial = ""
        private val endpointTracker = VadSpeechTracker()
        private var activeVad: Vad? = vad

        override fun acceptWaveform(samples: FloatArray) {
            if (samples.isEmpty()) return
            val partials = synchronized(runtimeLock) {
                checkOpenLocked()
                val currentRecognizer = checkNotNull(recognizer) { "语音识别器已释放" }
                val currentStream = streamLease.value
                currentStream.acceptWaveform(samples, SAMPLE_RATE)
                observeVadLocked(samples)
                decodeReadyLocked(currentRecognizer, currentStream)
            }
            partials.forEach(events::onPartial)
        }

        /** VAD 跟随同一块 PCM 更新；不可用或中途失败时立刻退回原有端点判断。 */
        private fun observeVadLocked(samples: FloatArray) {
            val detector = activeVad ?: return
            runCatching {
                detector.acceptWaveform(samples)
                endpointTracker.observe(detector.isSpeechDetected())
            }.onFailure {
                activeVad = null
                endpointTracker.observe(null)
                Log.w("OpenImeVoicePerf", "VAD 推理失败，本次会话退回原有端点判断", it)
            }
        }

        override fun inputFinished(): String {
            val (result, partials) = synchronized(runtimeLock) {
                checkOpenLocked()
                val currentRecognizer = checkNotNull(recognizer) { "语音识别器已释放" }
                val currentStream = streamLease.value
                // sherpa-onnx's streaming Paraformer examples append 300 ms
                // silence before InputFinished so the last chunk can flush.
                // VAD 可用时用 isSpeechDetected 的状态收窄补零：整段没听到语音
                // 就不补；其余情况（包括没有 VAD）与原来完全一致。
                if (selection.manifest?.modelType == "paraformer" && endpointTracker.tailPaddingNeeded()) {
                    currentStream.acceptWaveform(FloatArray(PARAFORMER_TAIL_PADDING_SAMPLES), SAMPLE_RATE)
                }
                if (vad != null) {
                    Log.i(
                        "OpenImeVoicePerf",
                        "endpoint vadActive=${endpointTracker.isVadActive} " +
                            "hasSpeech=${endpointTracker.hasSpeech} " +
                            "speechEnded=${endpointTracker.speechEnded()} " +
                            "tailPad=${endpointTracker.tailPaddingNeeded()}",
                    )
                }
                currentStream.inputFinished()
                // InputFinished ends feature extraction, but Paraformer's
                // IsReady accepts the remaining short chunk only with this
                // option. Otherwise already-recorded tail frames stay undecoded.
                if (selection.manifest?.modelType == "paraformer") currentStream.setOption("is_final", "1")
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
                        val model = punctuation ?: createPunctuationLocked()
                            ?: error("当前模型不带标点模型")
                        punctuation = model
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
