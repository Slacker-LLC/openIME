package llc.slacker.openime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
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
    const val SESSION_BUFFER_SECONDS = 8
}

/** Convert signed PCM16 samples to the float range expected by sherpa/ONNX. */
fun pcm16ToFloat(samples: ShortArray): FloatArray = FloatArray(samples.size) { index ->
    samples[index] / 32768f
}

/**
 * Real microphone transport for the eventual bundled streaming runtime.
 * Capture and inference deliberately run on separate threads; the IME main
 * thread only receives VoiceRecognitionEvents.
 */
class LocalAudioVoiceBackend(
    private val context: Context,
    private val runtime: StreamingEmbeddedVoiceModelRuntime?,
) : VoiceRecognitionBackend {

    override val id: String = "embedded-local-audiorecord"

    private val lock = Any()
    private var session: Session? = null

    override fun isAvailable(): Boolean = runtime?.isReady == true

    @SuppressLint("MissingPermission")
    override fun start(languageTag: String, events: VoiceRecognitionEvents) {
        stop()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            events.onError("当前未授予麦克风权限，请在系统设置中授权后重试")
            return
        }
        val model = runtime
        if (model == null || !model.isReady) {
            events.onError("本地语音模型尚未内置或仍在加载")
            return
        }

        val minBufferBytes = AudioRecord.getMinBufferSize(
            LocalVoiceAudioSpec.SAMPLE_RATE,
            LocalVoiceAudioSpec.CHANNEL_MASK,
            LocalVoiceAudioSpec.ENCODING,
        )
        if (minBufferBytes <= 0) {
            events.onError("设备不支持 16kHz 麦克风采集")
            return
        }

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
            events.onError("无法初始化本地麦克风：${it.message.orEmpty()}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            events.onError("无法初始化本地麦克风")
            return
        }

        val session = Session(
            record = record,
            ring = PcmRingBuffer(
                LocalVoiceAudioSpec.SAMPLE_RATE * LocalVoiceAudioSpec.SESSION_BUFFER_SECONDS,
            ),
            events = events,
            runtime = model,
        )
        synchronized(lock) { this.session = session }

        val modelEvents = object : VoiceRecognitionEvents {
            override fun onPartial(text: String) {
                session.lastPartial = text
                events.onPartial(text)
            }

            override fun onFinal(text: String) = events.onFinal(text)
            override fun onRms(rms: Float) = events.onRms(rms)
            override fun onError(message: String) = events.onError(message)
            override fun onReady() = events.onReady()
        }

        try {
            model.start(languageTag, modelEvents)
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord 未进入录音状态")
            }
            session.running.set(true)
            events.onReady()
            startThreads(session)
        } catch (error: Throwable) {
            fail(session, "本地语音启动失败：${error.message.orEmpty()}")
        }
    }

    override fun stop() {
        val current = synchronized(lock) {
            val value = session
            session = null
            value
        } ?: return
        current.running.set(false)
        runCatching { current.record.stop() }
        current.ring.wake()
        current.captureThread?.interrupt()
        // The inference thread drains the bounded in-memory queue and commits
        // one final result. It releases the AudioRecord and clears the queue
        // in its finally block; no audio survives this session.
    }

    private fun startThreads(session: Session) {
        session.captureThread = Thread({ captureLoop(session) }, "local-voice-capture")
        session.inferenceThread = Thread({ inferenceLoop(session) }, "local-voice-inference")
        session.captureThread?.start()
        session.inferenceThread?.start()
    }

    private fun captureLoop(session: Session) {
        val pcm = ShortArray(LocalVoiceAudioSpec.CHUNK_SAMPLES)
        try {
            while (session.running.get()) {
                val read = session.record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                when {
                    read > 0 -> {
                        session.ring.offer(pcm, 0, read)
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
                session.runtime.acceptWaveform(pcm16ToFloat(chunk))
            }
            if (pendingCount > 0) {
                session.runtime.acceptWaveform(pcm16ToFloat(pending.copyOf(pendingCount)))
            }
            if (!session.failed.get() && session.finished.compareAndSet(false, true)) {
                val raw = session.runtime.inputFinished().ifBlank { session.lastPartial }
                val final = if (raw.isBlank()) raw else session.runtime.punctuate(raw) ?: raw
                if (final.isNotBlank()) session.events.onFinal(final)
            }
        } catch (error: Throwable) {
            fail(session, "本地语音推理失败：${error.message.orEmpty()}")
        } finally {
            runCatching { session.runtime.stop() }
            session.ring.clear()
            synchronized(lock) {
                if (this.session === session) this.session = null
            }
        }
    }

    private fun fail(session: Session, message: String) {
        if (session.failed.compareAndSet(false, true)) {
            session.running.set(false)
            runCatching { session.record.stop() }
            session.ring.wake()
            session.events.onError(message)
        }
    }

    private class Session(
        val record: AudioRecord,
        val ring: PcmRingBuffer,
        val events: VoiceRecognitionEvents,
        val runtime: StreamingEmbeddedVoiceModelRuntime,
    ) {
        val running = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        var captureThread: Thread? = null
        var inferenceThread: Thread? = null
        var lastPartial: String = ""
    }
}
