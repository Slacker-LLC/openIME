package llc.slacker.openime

import android.app.ActivityManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.EditorInfo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

enum class VoiceModelLifecycleState {
    COLD,
    VERIFYING,
    PRELOADING,
    HOT,
    RECORDING,
    COOLDOWN,
    ERROR,
}

/** Pure retry policy so transient native failures do not poison the service forever. */
internal class VoicePreloadRetryPolicy(
    private val baseBackoffMs: Long = 1_000L,
    private val maxBackoffMs: Long = 30_000L,
) {
    init {
        require(baseBackoffMs > 0L)
        require(maxBackoffMs >= baseBackoffMs)
    }

    private var transientFailures = 0
    private var retryNotBeforeMs = 0L
    private var permanentlyBlocked = false

    fun canAttempt(nowMs: Long): Boolean = !permanentlyBlocked && nowMs >= retryNotBeforeMs

    fun recordFailure(permanent: Boolean, nowMs: Long) {
        if (permanent) {
            permanentlyBlocked = true
            retryNotBeforeMs = Long.MAX_VALUE
            return
        }
        transientFailures += 1
        val multiplier = 1L shl (transientFailures - 1).coerceAtMost(10)
        val delay = (baseBackoffMs * multiplier).coerceAtMost(maxBackoffMs)
        retryNotBeforeMs = nowMs + delay
    }

    fun recordSuccess() {
        transientFailures = 0
        retryNotBeforeMs = 0L
        permanentlyBlocked = false
    }

    fun retryDelayMs(nowMs: Long): Long =
        if (permanentlyBlocked) Long.MAX_VALUE else (retryNotBeforeMs - nowMs).coerceAtLeast(0L)
}

internal data class VoiceMemorySnapshot(
    val lowRamDevice: Boolean,
    val memoryClassBytes: Long,
    val availableBytes: Long,
    val lowMemoryThresholdBytes: Long,
)

internal data class VoiceMemoryAdmission(
    val allowed: Boolean,
    val reason: String = "",
)

/**
 * Conservative admission for the large native ASR runtime. Android's Java heap
 * memoryClass is only a device-capability signal here, not a hard native-memory
 * limit; live system headroom is the primary gate.
 */
internal object VoiceMemoryAdmissionPolicy {
    private const val MIN_HEADROOM_BYTES = 64L * 1024L * 1024L

    fun evaluate(
        requiredMemory: Long,
        snapshot: VoiceMemorySnapshot,
        automaticPreload: Boolean,
    ): VoiceMemoryAdmission {
        if (requiredMemory <= 0L) {
            return VoiceMemoryAdmission(false, "模型内存需求无效")
        }
        if (automaticPreload && snapshot.lowRamDevice) {
            return VoiceMemoryAdmission(false, "低内存设备跳过自动语音预热")
        }

        val headroom = maxOf(MIN_HEADROOM_BYTES, requiredMemory / 5L)
        if (requiredMemory > Long.MAX_VALUE - headroom) {
            return VoiceMemoryAdmission(false, "模型内存需求溢出")
        }
        val requiredWithHeadroom = requiredMemory + headroom
        val usableAvailable =
            (snapshot.availableBytes - snapshot.lowMemoryThresholdBytes).coerceAtLeast(0L)
        if (usableAvailable < requiredWithHeadroom) {
            return VoiceMemoryAdmission(
                false,
                "可用内存不足 required=$requiredMemory usable=$usableAvailable",
            )
        }

        // memoryClass does not cap native ONNX allocations, but an extremely
        // small heap class is a strong signal that loading a 400+ MB runtime is
        // unsafe even when cached pages temporarily make availMem look large.
        if (
            snapshot.memoryClassBytes > 0L &&
            snapshot.memoryClassBytes < requiredMemory / 2L
        ) {
            return VoiceMemoryAdmission(
                false,
                "设备内存等级过低 memoryClass=${snapshot.memoryClassBytes}",
            )
        }
        return VoiceMemoryAdmission(true)
    }
}

internal object VoiceAutoPreloadPolicy {
    fun shouldPreload(
        editorKind: EditorInfoAdapter.EditorKind,
        imeOptions: Int,
    ): Boolean =
        !EditorInfoAdapter.isPassword(editorKind) &&
            (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
}

private class PermanentVoiceModelException(message: String) : IllegalStateException(message)

/**
 * Service-scoped owner of the offline ASR runtime.
 *
 * Model verification, native recognizer construction and release never run on
 * the IME main thread. Closing the keyboard keeps the mapped model hot for a
 * short cooldown so switching between editors does not repeatedly load 199 MB.
 */
class VoiceModelLifecycleManager(
    context: Context,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) : StreamingVoiceRuntimeProvider {

    companion object {
        private const val TAG = "OpenImeVoiceLifecycle"
        const val DEFAULT_COOLDOWN_MS = 10_000L
        private const val BYTES_PER_MIB = 1024L * 1024L
    }

    private val appContext = context.applicationContext
    private val inputMethodService = context as? InputMethodService
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val lock = Object()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "openime-voice-lifecycle").apply { isDaemon = true }
    }
    private val lifecycleGeneration = AtomicLong(0L)
    private val sessionGeneration = AtomicLong(0L)
    private val backend = LocalAudioVoiceBackend(appContext, this)
    private val retryPolicy = VoicePreloadRetryPolicy()

    @Volatile
    private var state: VoiceModelLifecycleState = VoiceModelLifecycleState.COLD
    private var runtime: StreamingEmbeddedVoiceModelRuntime? = null
    private var preloadInFlight = false
    private var inputViewActive = false
    private var recording = false
    private var destroyed = false

    private val unloadRunnable = Runnable { unloadAfterCooldown() }

    fun currentState(): VoiceModelLifecycleState = state

    fun onStartInputView() {
        mainHandler.removeCallbacks(unloadRunnable)
        synchronized(lock) {
            if (destroyed) return
            inputViewActive = true
            if (runtime != null && !recording) state = VoiceModelLifecycleState.HOT
        }
        if (shouldAutoPreloadForCurrentEditor()) {
            preload(automaticPreload = true)
        } else {
            Log.i(TAG, "preloadSkipped reason=privateEditor")
        }
    }

    fun onFinishInputView() {
        synchronized(lock) {
            if (destroyed) return
            inputViewActive = false
            if (runtime != null && !recording) state = VoiceModelLifecycleState.COOLDOWN
        }
        mainHandler.removeCallbacks(unloadRunnable)
        mainHandler.postDelayed(unloadRunnable, cooldownMs)
    }

    fun preload(automaticPreload: Boolean = false) {
        if (automaticPreload) {
            val snapshot = currentMemorySnapshot()
            if (snapshot == null) {
                Log.w(TAG, "preloadSkipped reason=memorySnapshotUnavailable")
                return
            }
            if (snapshot.lowRamDevice) {
                Log.i(TAG, "preloadSkipped reason=lowRamDevice")
                return
            }
        }

        val nowMs = SystemClock.elapsedRealtime()
        val token = synchronized(lock) {
            if (
                destroyed || runtime != null || preloadInFlight ||
                !retryPolicy.canAttempt(nowMs)
            ) {
                return
            }
            preloadInFlight = true
            state = VoiceModelLifecycleState.VERIFYING
            lifecycleGeneration.incrementAndGet()
        }
        val startedAt = nowMs
        executor.execute {
            var created: StreamingEmbeddedVoiceModelRuntime? = null
            try {
                val verifyStartedAt = SystemClock.elapsedRealtime()
                val selection = VoiceModelRepository(appContext).selectAvailable()
                val verifyMs = SystemClock.elapsedRealtime() - verifyStartedAt
                val manifest = selection.manifest
                    ?: throw PermanentVoiceModelException(
                        selection.reason.ifBlank { "没有可用的本地语音模型" },
                    )

                val admission = currentMemorySnapshot()?.let { snapshot ->
                    VoiceMemoryAdmissionPolicy.evaluate(
                        requiredMemory = manifest.requiredMemory,
                        snapshot = snapshot,
                        automaticPreload = automaticPreload,
                    )
                } ?: VoiceMemoryAdmission(false, "无法读取设备内存状态")
                if (!admission.allowed) {
                    val deniedAt = SystemClock.elapsedRealtime()
                    var retryDelay = 0L
                    synchronized(lock) {
                        if (destroyed || token != lifecycleGeneration.get()) return@execute
                        preloadInFlight = false
                        if (automaticPreload) {
                            state = VoiceModelLifecycleState.COLD
                        } else {
                            retryPolicy.recordFailure(permanent = false, nowMs = deniedAt)
                            retryDelay = retryPolicy.retryDelayMs(deniedAt)
                            state = VoiceModelLifecycleState.ERROR
                        }
                        lock.notifyAll()
                    }
                    Log.w(
                        TAG,
                        "preloadDenied automatic=$automaticPreload retryDelayMs=$retryDelay " +
                            "reason=${admission.reason}",
                    )
                    return@execute
                }

                synchronized(lock) {
                    if (destroyed || token != lifecycleGeneration.get()) return@execute
                    state = if (recording) {
                        VoiceModelLifecycleState.RECORDING
                    } else {
                        VoiceModelLifecycleState.PRELOADING
                    }
                }
                val modelCreateStartedAt = SystemClock.elapsedRealtime()
                created = EmbeddedVoiceRuntimeFactory.create(appContext, selection)
                    as? StreamingEmbeddedVoiceModelRuntime
                    ?: throw PermanentVoiceModelException("本地语音模型类型不受支持")
                created.preload()
                val modelCreateMs = SystemClock.elapsedRealtime() - modelCreateStartedAt
                val accepted = synchronized(lock) {
                    if (destroyed || token != lifecycleGeneration.get()) {
                        false
                    } else {
                        runtime = created
                        preloadInFlight = false
                        retryPolicy.recordSuccess()
                        state = when {
                            recording -> VoiceModelLifecycleState.RECORDING
                            inputViewActive -> VoiceModelLifecycleState.HOT
                            else -> VoiceModelLifecycleState.COOLDOWN
                        }
                        lock.notifyAll()
                        true
                    }
                }
                if (!accepted) {
                    created.release()
                } else {
                    Log.i(
                        TAG,
                        "preloadComplete verifyMs=$verifyMs modelCreateMs=$modelCreateMs " +
                            "totalMs=${SystemClock.elapsedRealtime() - startedAt}",
                    )
                }
            } catch (error: Throwable) {
                runCatching { created?.release() }
                val failedAt = SystemClock.elapsedRealtime()
                val permanent = error is PermanentVoiceModelException
                var retryDelay = Long.MAX_VALUE
                synchronized(lock) {
                    if (!destroyed && token == lifecycleGeneration.get()) {
                        preloadInFlight = false
                        retryPolicy.recordFailure(permanent, failedAt)
                        retryDelay = retryPolicy.retryDelayMs(failedAt)
                        state = VoiceModelLifecycleState.ERROR
                        lock.notifyAll()
                    }
                }
                Log.e(
                    TAG,
                    "preloadFailed elapsedMs=${failedAt - startedAt} permanent=$permanent " +
                        "retryDelayMs=$retryDelay",
                    error,
                )
            }
        }
    }

    fun start(languageTag: String, events: VoiceRecognitionEvents) {
        mainHandler.removeCallbacks(unloadRunnable)
        preload(automaticPreload = false)
        val token = sessionGeneration.incrementAndGet()
        val requestAt = SystemClock.elapsedRealtime()
        VoicePerformanceTrace.begin()
        synchronized(lock) {
            if (destroyed) {
                VoicePerformanceTrace.finish(droppedPcmSamples = 0L, failed = true)
                events.onError("本地语音服务已关闭")
                return
            }
            if (state == VoiceModelLifecycleState.ERROR) {
                VoicePerformanceTrace.finish(droppedPcmSamples = 0L, failed = true)
                events.onError("本地语音模型校验或加载失败，请稍后重试")
                return
            }
            recording = true
            state = VoiceModelLifecycleState.RECORDING
        }
        backend.start(languageTag, object : VoiceRecognitionEvents {
            override fun onPartial(text: String) {
                if (isCurrentSession(token)) events.onPartial(text)
            }

            override fun onFinal(text: String) {
                if (!isCurrentSession(token)) return
                markSessionFinished(token)
                events.onFinal(text)
            }

            override fun onRms(rms: Float) {
                if (isCurrentSession(token)) events.onRms(rms)
            }

            override fun onError(message: String) {
                if (!isCurrentSession(token)) return
                markSessionFinished(token)
                VoicePerformanceTrace.finish(droppedPcmSamples = 0L, failed = true)
                events.onError(message)
            }

            override fun onReady() {
                if (!isCurrentSession(token)) return
                Log.i(TAG, "recordStartMs=${SystemClock.elapsedRealtime() - requestAt}")
                events.onReady()
            }

            override fun onModelReady() {
                if (isCurrentSession(token)) {
                    VoicePerformanceTrace.markModelReady()
                    events.onModelReady()
                }
            }
        })
    }

    fun stop() {
        backend.stop()
    }

    fun cancel() {
        sessionGeneration.incrementAndGet()
        backend.cancel()
        synchronized(lock) {
            recording = false
            state = when {
                runtime == null && state == VoiceModelLifecycleState.ERROR -> VoiceModelLifecycleState.ERROR
                runtime == null -> VoiceModelLifecycleState.COLD
                inputViewActive -> VoiceModelLifecycleState.HOT
                else -> VoiceModelLifecycleState.COOLDOWN
            }
        }
        scheduleUnloadIfHidden()
    }

    fun destroy() {
        mainHandler.removeCallbacks(unloadRunnable)
        sessionGeneration.incrementAndGet()
        backend.cancel()
        val toRelease = synchronized(lock) {
            if (destroyed) return
            destroyed = true
            recording = false
            inputViewActive = false
            preloadInFlight = false
            lifecycleGeneration.incrementAndGet()
            val value = runtime
            runtime = null
            state = VoiceModelLifecycleState.COLD
            lock.notifyAll()
            value
        }
        if (toRelease != null) {
            executor.execute { runCatching { toRelease.release() } }
        }
        executor.shutdown()
    }

    override fun isExpectedAvailable(): Boolean = synchronized(lock) {
        !destroyed && state != VoiceModelLifecycleState.ERROR
    }

    override fun awaitRuntime(): StreamingEmbeddedVoiceModelRuntime? {
        preload(automaticPreload = false)
        synchronized(lock) {
            while (!destroyed && runtime == null && preloadInFlight) {
                try {
                    lock.wait(250L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return runtime
        }
    }

    private fun shouldAutoPreloadForCurrentEditor(): Boolean {
        val info = inputMethodService?.currentInputEditorInfo ?: return true
        return VoiceAutoPreloadPolicy.shouldPreload(
            editorKind = EditorInfoAdapter.kind(info),
            imeOptions = info.imeOptions,
        )
    }

    private fun currentMemorySnapshot(): VoiceMemorySnapshot? {
        val manager = activityManager ?: return null
        val memoryInfo = ActivityManager.MemoryInfo()
        return runCatching {
            manager.getMemoryInfo(memoryInfo)
            VoiceMemorySnapshot(
                lowRamDevice = manager.isLowRamDevice,
                memoryClassBytes = manager.memoryClass.toLong() * BYTES_PER_MIB,
                availableBytes = memoryInfo.availMem,
                lowMemoryThresholdBytes = memoryInfo.threshold,
            )
        }.getOrNull()
    }

    private fun isCurrentSession(token: Long): Boolean =
        token == sessionGeneration.get() && !destroyed

    private fun markSessionFinished(token: Long) {
        synchronized(lock) {
            if (token != sessionGeneration.get() || destroyed) return
            recording = false
            state = when {
                runtime == null && state == VoiceModelLifecycleState.ERROR -> VoiceModelLifecycleState.ERROR
                runtime == null -> VoiceModelLifecycleState.COLD
                inputViewActive -> VoiceModelLifecycleState.HOT
                else -> VoiceModelLifecycleState.COOLDOWN
            }
        }
        scheduleUnloadIfHidden()
    }

    private fun scheduleUnloadIfHidden() {
        val shouldSchedule = synchronized(lock) { !destroyed && !inputViewActive && !recording }
        if (!shouldSchedule) return
        mainHandler.removeCallbacks(unloadRunnable)
        mainHandler.postDelayed(unloadRunnable, cooldownMs)
    }

    private fun unloadAfterCooldown() {
        val toRelease = synchronized(lock) {
            if (destroyed || inputViewActive || recording || runtime == null) return
            lifecycleGeneration.incrementAndGet()
            val value = checkNotNull(runtime)
            runtime = null
            state = VoiceModelLifecycleState.COLD
            lock.notifyAll()
            value
        }
        executor.execute {
            val startedAt = SystemClock.elapsedRealtime()
            runCatching { toRelease.release() }
                .onFailure { Log.w(TAG, "unloadFailed", it) }
            Log.i(TAG, "unloadMs=${SystemClock.elapsedRealtime() - startedAt}")
        }
    }
}
