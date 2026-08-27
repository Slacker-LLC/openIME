package llc.slacker.openime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Bounded, in-memory PCM queue for one voice session. It deliberately has no
 * file output and is cleared when the session ends, is cancelled, or fails.
 */
class PcmRingBuffer(private val capacitySamples: Int) {
    init {
        require(capacitySamples > 0) { "capacitySamples must be positive" }
    }

    private val buffer = ShortArray(capacitySamples)
    private var readIndex = 0
    private var writeIndex = 0
    private var count = 0

    @get:Synchronized
    var droppedSamples: Long = 0
        private set

    @get:Synchronized
    val size: Int
        get() = count

    @Synchronized
    fun offer(samples: ShortArray, offset: Int = 0, length: Int = samples.size): Int {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) {
            "invalid PCM range"
        }
        if (length == 0) return 0

        var sourceOffset = offset
        var accepted = length
        if (accepted >= capacitySamples) {
            droppedSamples += (accepted - capacitySamples).toLong()
            sourceOffset += accepted - capacitySamples
            accepted = capacitySamples
            readIndex = 0
            writeIndex = 0
            count = 0
        }

        val overflow = (count + accepted - capacitySamples).coerceAtLeast(0)
        if (overflow > 0) {
            readIndex = (readIndex + overflow) % capacitySamples
            count -= overflow
            droppedSamples += overflow.toLong()
        }

        repeat(accepted) { index ->
            buffer[writeIndex] = samples[sourceOffset + index]
            writeIndex = (writeIndex + 1) % capacitySamples
        }
        count += accepted
        (this as java.lang.Object).notifyAll()
        return accepted
    }

    /** Wait briefly for capture data, then remove at most [maxSamples]. */
    @Synchronized
    fun awaitAndDrain(maxSamples: Int, timeoutMs: Long): ShortArray {
        require(maxSamples > 0) { "maxSamples must be positive" }
        if (count == 0 && timeoutMs > 0) {
            try {
                (this as java.lang.Object).wait(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return drainLocked(maxSamples)
    }

    @Synchronized
    fun drain(maxSamples: Int): ShortArray {
        require(maxSamples > 0) { "maxSamples must be positive" }
        return drainLocked(maxSamples)
    }

    @Synchronized
    fun clear() {
        readIndex = 0
        writeIndex = 0
        count = 0
        droppedSamples = 0
    }

    @Synchronized
    fun wake() {
        (this as java.lang.Object).notifyAll()
    }

    private fun drainLocked(maxSamples: Int): ShortArray {
        val take = minOf(maxSamples, count)
        if (take == 0) return ShortArray(0)
        val result = ShortArray(take)
        repeat(take) { index ->
            result[index] = buffer[readIndex]
            readIndex = (readIndex + 1) % capacitySamples
        }
        count -= take
        return result
    }
}

/** Android/ASR audio constants kept in one place for model integration. */
object LocalVoiceAudioSpec {
    const val SAMPLE_RATE = 16_000
    const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const val CHUNK_MILLIS = 20
    const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MILLIS / 1_000
    const val CHUNK_BYTES = CHUNK_SAMPLES * 2
    // First-use model mapping can take a few seconds on slower phones. Keep a
    // bounded 30-second pre-roll so speech is never lost while it warms up.
    const val SESSION_BUFFER_SECONDS = 30
}

/** Convert signed PCM16 samples to the float range expected by sherpa/ONNX. */
fun pcm16ToFloat(samples: ShortArray): FloatArray = FloatArray(samples.size) { index ->
    samples[index] / 32768f
}

/**
 * Supplies the model on a worker thread. Implementations may wait for an
 * asynchronous preload while [LocalAudioVoiceBackend] already records PCM.
 */
interface StreamingVoiceRuntimeProvider {
    fun isExpectedAvailable(): Boolean

    fun awaitRuntime(): StreamingEmbeddedVoiceModelRuntime?

    /** Trace token created by the service before this voice gesture starts. */
    fun performanceTraceToken(): VoicePerformanceTrace.Token? =
        VoicePerformanceTrace.currentTokenForBackend()
}

private class FixedStreamingVoiceRuntimeProvider(
    private val runtime: StreamingEmbeddedVoiceModelRuntime?,
) : StreamingVoiceRuntimeProvider {
    override fun isExpectedAvailable(): Boolean = runtime?.isReady == true

    override fun awaitRuntime(): StreamingEmbeddedVoiceModelRuntime? = runtime
}

/**
 * Real microphone transport for the eventual bundled streaming runtime.
 * Capture and inference deliberately run on separate threads; the IME main
 * thread only receives VoiceRecognitionEvents.
 */
class LocalAudioVoiceBackend(
    private val context: Context,
    private val runtimeProvider: StreamingVoiceRuntimeProvider,
) : VoiceRecognitionBackend {

    constructor(
        context: Context,
        runtime: StreamingEmbeddedVoiceModelRuntime?,
    ) : this(context, FixedStreamingVoiceRuntimeProvider(runtime))

    override val id: String = "embedded-local-audiorecord"

    private val lock = Any()
    private var session: Session? = null
    private val startExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "local-voice-start").apply { isDaemon = true }
    }
    private val startGeneration = AtomicLong(0L)
    private val stopRequestedGeneration = AtomicLong(-1L)
    private val routeManager = VoiceAudioRouteManager(context)

    override fun isAvailable(): Boolean = runtimeProvider.isExpectedAvailable()

    @SuppressLint("MissingPermission")
    override fun start(languageTag: String, events: VoiceRecognitionEvents) {
        // A new gesture replaces an unfinished old one. Each gesture receives
        // its own model stream handle, so stale cleanup cannot stop a new one.
        cancel()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            events.onError("当前未授予麦克风权限，请在系统设置中授权后重试")
            return
        }
        if (!runtimeProvider.isExpectedAvailable()) {
            events.onError("本地语音模型尚未内置或仍在加载")
            return
        }

        val traceToken = runtimeProvider.performanceTraceToken() ?: VoicePerformanceTrace.begin()
        val generation = startGeneration.incrementAndGet()
        stopRequestedGeneration.set(-1L)
        startExecutor.execute {
            initializeSession(generation, languageTag, events, traceToken)
        }
    }

    @SuppressLint("MissingPermission")
    private fun initializeSession(
        generation: Long,
        languageTag: String,
        events: VoiceRecognitionEvents,
        traceToken: VoicePerformanceTrace.Token,
    ) {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            LocalVoiceAudioSpec.SAMPLE_RATE,
            LocalVoiceAudioSpec.CHANNEL_MASK,
            LocalVoiceAudioSpec.ENCODING,
        )
        if (minBufferBytes <= 0) {
            VoicePerformanceTrace.finish(traceToken, 0L, failed = true)
            events.onError("设备不支持 16kHz 麦克风采集")
            return
        }

        val routeSession = routeManager.beginSession()
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(LocalVoiceAudioSpec.SAMPLE_RATE)
                        .setChannelMask(LocalVoiceAudioSpec.CHANNEL_MASK)
                        .setEncoding(LocalVoiceAudioSpec.ENCODING)
                        .build(),
                )
                // The model block is always 640 bytes. This larger system
                // buffer absorbs short inference stalls without changing the
                // model block size.
                .setBufferSizeInBytes(
                    maxOf(
                        minBufferBytes,
                        LocalVoiceAudioSpec.CHUNK_BYTES * 8,
                    ),
                )
                .build()
        }.getOrElse {
            routeSession.close()
            VoicePerformanceTrace.finish(traceToken, 0L, failed = true)
            events.onError("无法初始化本地麦克风：${it.message.orEmpty()}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            routeSession.close()
            VoicePerformanceTrace.finish(traceToken, 0L, failed = true)
            events.onError("无法初始化本地麦克风")
            return
        }

        val session = Session(
            record = record,
            ring = PcmRingBuffer(
                LocalVoiceAudioSpec.SAMPLE_RATE * LocalVoiceAudioSpec.SESSION_BUFFER_SECONDS,
            ),
            events = events,
            routeSession = routeSession,
            traceToken = traceToken,
        )
        val accepted = synchronized(lock) {
            if (startGeneration.get() != generation) {
                false
            } else {
                this.session = session
                true
            }
        }
        if (!accepted) {
            record.release()
            routeSession.close()
            VoicePerformanceTrace.abandon(traceToken)
            return
        }
        session.stopRequested.set(stopRequestedGeneration.get() == generation)
        if (session.stopRequested.get()) VoicePerformanceTrace.markVoiceRelease(traceToken)

        val modelEvents = object : VoiceRecognitionEvents {
            override fun onPartial(text: String) {
                session.lastPartial = text
                VoicePerformanceTrace.markFirstPartial(session.traceToken)
                events.onPartial(text)
            }

            override fun onFinal(text: String) = events.onFinal(text)
            override fun onRms(rms: Float) = events.onRms(rms)
            override fun onError(message: String) = events.onError(message)
            override fun onReady() = Unit
            override fun onModelReady() = Unit
        }

        try {
            // Capture first. The expensive first recognizer mapping happens
            // below while PCM accumulates in the ring, so no spoken prefix is
            // lost and the waveform can follow the finger immediately.
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord 未进入录音状态")
            }
            VoicePerformanceTrace.markAudioRecordStart(session.traceToken)
            val canRun = synchronized(lock) {
                if (startGeneration.get() != generation || this.session !== session) {
                    false
                } else {
                    session.running.set(true)
                    true
                }
            }
            if (!canRun) {
                cleanupStartingSession(session)
                return
            }
            startCaptureThread(session)
            events.onReady()
            if (session.stopRequested.get()) requestCaptureStop(session)

            // Waiting for verification/model mapping is safe here: capture is
            // already live and the bounded ring keeps the spoken prefix.
            val model = runtimeProvider.awaitRuntime()
                ?: throw IllegalStateException("本地语音模型尚未内置或加载失败")
            session.voiceSession = model.openSession(languageTag, modelEvents)
            if (!isCurrent(generation, session) || session.cancelled.get() || session.failed.get()) {
                cleanupStartingSession(session)
                return
            }
            session.modelReady.set(true)
            events.onModelReady()
            startInferenceThread(session)
        } catch (error: Throwable) {
            if (isCurrent(generation, session)) {
                fail(session, "本地语音启动失败：${error.message.orEmpty()}")
                if (!session.inferenceStarted.get()) cleanupStartingSession(session)
            } else {
                cleanupStartingSession(session)
            }
        }
    }

    override fun stop() {
        val current = synchronized(lock) { session }
        if (current != null) {
            VoicePerformanceTrace.markVoiceRelease(current.traceToken)
        } else {
            runtimeProvider.performanceTraceToken()?.let(VoicePerformanceTrace::markVoiceRelease)
        }
        val generation = startGeneration.get()
        stopRequestedGeneration.set(generation)
        if (current == null) return
        current.stopRequested.set(true)
        requestCaptureStop(current)
        if (current.modelReady.get()) startInferenceThread(current)
        // If the first model load is still running, initializeSession keeps
        // this session alive, then drains the buffered PCM and emits one final.
    }

    override fun cancel() {
        startGeneration.incrementAndGet()
        stopRequestedGeneration.set(-1L)
        val current = synchronized(lock) {
            val value = session
            session = null
            value
        } ?: return
        current.cancelled.set(true)
        VoicePerformanceTrace.abandon(current.traceToken)
        requestCaptureStop(current)
        current.ring.clear()
        current.ring.wake()
        current.captureThread?.interrupt()
        current.inferenceThread?.interrupt()
    }

    private fun isCurrent(generation: Long, value: Session): Boolean = synchronized(lock) {
        startGeneration.get() == generation && session === value
    }

    private fun cleanupStartingSession(value: Session) {
        value.running.set(false)
        runCatching { value.record.stop() }
        value.ring.wake()
        runCatching { value.voiceSession?.close() }
        value.voiceSession = null
        runCatching { value.record.release() }
        value.routeSession.close()
        value.ring.clear()
        VoicePerformanceTrace.abandon(value.traceToken)
        synchronized(lock) {
            if (session === value) session = null
        }
    }

    private fun requestCaptureStop(session: Session) {
        session.running.set(false)
        runCatching { session.record.stop() }
        session.ring.wake()
        session.captureThread?.interrupt()
    }

    private fun startCaptureThread(session: Session) {
        session.captureThread = Thread({ captureLoop(session) }, "local-voice-capture")
        session.captureThread?.start()
    }

    private fun startInferenceThread(session: Session) {
        if (!session.inferenceStarted.compareAndSet(false, true)) return
        session.inferenceThread = Thread({ inferenceLoop(session) }, "local-voice-inference")
        session.inferenceThread?.start()
    }

    private fun captureLoop(session: Session) {
        val pcm = ShortArray(LocalVoiceAudioSpec.CHUNK_SAMPLES)
        try {
            while (session.running.get()) {
                val read = session.record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                when {
                    read > 0 -> {
                        VoicePerformanceTrace.markFirstPcm(session.traceToken)
                        val droppedBefore = session.ring.droppedSamples
                        session.ring.offer(pcm, 0, read)
                        if (
                            session.ring.droppedSamples > droppedBefore &&
                            session.degraded.compareAndSet(false, true)
                        ) {
                            Log.w(
                                "OpenImeVoicePerf",
                                "PCM pipeline degraded droppedPcmSamples=${session.ring.droppedSamples}",
                            )
                        }
                        var sum = 0.0
                        repeat(read) { index ->
                            val value = pcm[index] / 32768.0
                            sum += value * value
                        }
                        session.events.onRms(sqrt(sum / read).toFloat())
                    }
                    read < 0 -> fail(session, "麦克风读取失败($read)")
                }
            }
        } catch (error: Throwable) {
            if (session.running.get()) fail(session, "麦克风读取异常：${error.message.orEmpty()}")
        } finally {
            session.running.set(false)
            session.ring.wake()
            runCatching { session.record.release() }
        }
    }

    private fun inferenceLoop(session: Session) {
        val voiceSession = checkNotNull(session.voiceSession) { "本地语音模型尚未连接" }
        val pending = ShortArray(LocalVoiceAudioSpec.CHUNK_SAMPLES)
        var pendingCount = 0
        try {
            while (session.running.get() || session.ring.size > 0) {
                val part = session.ring.awaitAndDrain(
                    LocalVoiceAudioSpec.CHUNK_SAMPLES - pendingCount,
                    50,
                )
                if (part.isNotEmpty()) {
                    part.copyInto(pending, pendingCount)
                    pendingCount += part.size
                }
                if (pendingCount == 0) continue
                if (pendingCount < pending.size && session.running.get()) continue
                val chunk = pending.copyOf(pendingCount)
                pendingCount = 0
                VoicePerformanceTrace.markFirstDecode(session.traceToken)
                voiceSession.acceptWaveform(pcm16ToFloat(chunk))
            }
            if (pendingCount > 0) {
                VoicePerformanceTrace.markFirstDecode(session.traceToken)
                voiceSession.acceptWaveform(pcm16ToFloat(pending.copyOf(pendingCount)))
            }
            if (
                !session.failed.get() &&
                !session.cancelled.get() &&
                session.finished.compareAndSet(false, true)
            ) {
                val raw = voiceSession.inputFinished().ifBlank { session.lastPartial }
                VoicePerformanceTrace.markFinalAsr(session.traceToken)
                val punctuated = if (raw.isBlank()) raw else voiceSession.punctuate(raw) ?: raw
                val final = VoiceCorrectionRepository.apply(punctuated)
                VoicePerformanceTrace.markPunctuationDone(session.traceToken)
                session.events.onFinal(final)
                VoicePerformanceTrace.finish(session.traceToken, session.ring.droppedSamples)
            }
        } catch (error: Throwable) {
            fail(session, "本地语音推理失败：${error.message.orEmpty()}")
        } finally {
            runCatching { voiceSession.close() }
            session.voiceSession = null
            session.ring.clear()
            session.routeSession.close()
            synchronized(lock) {
                if (this.session === session) this.session = null
            }
        }
    }

    private fun fail(session: Session, message: String) {
        if (session.failed.compareAndSet(false, true)) {
            requestCaptureStop(session)
            VoicePerformanceTrace.finish(
                session.traceToken,
                session.ring.droppedSamples,
                failed = true,
            )
            session.events.onError(message)
            if (!session.modelReady.get() && session.captureThread == null) {
                cleanupStartingSession(session)
            }
        }
    }

    private class Session(
        val record: AudioRecord,
        val ring: PcmRingBuffer,
        val events: VoiceRecognitionEvents,
        val routeSession: VoiceAudioRouteManager.Session,
        val traceToken: VoicePerformanceTrace.Token,
    ) {
        @Volatile
        var voiceSession: StreamingVoiceModelSession? = null
        val running = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val modelReady = AtomicBoolean(false)
        val inferenceStarted = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val degraded = AtomicBoolean(false)
        var captureThread: Thread? = null
        var inferenceThread: Thread? = null
        var lastPartial: String = ""
    }
}
