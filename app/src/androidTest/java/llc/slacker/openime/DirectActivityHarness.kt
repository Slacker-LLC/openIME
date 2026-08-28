package llc.slacker.openime

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference

/**
 * OEM-safe activity launcher for instrumentation.
 *
 * ActivityScenario/startActivitySync wait for global main-thread idleness. Some
 * MIUI builds keep Choreographer/window callbacks active while an IME-like view
 * is visible, which can make that global-idle condition unreachable. This
 * harness waits only for the lifecycle/UI condition the test actually needs.
 */
internal class DirectActivityHarness<T : Activity>(
    private val activityClass: Class<T>,
) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val resumed = AtomicReference<T?>(null)

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activityClass.isInstance(activity)) {
                @Suppress("UNCHECKED_CAST")
                resumed.set(activity as T)
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (resumed.get() === activity) resumed.set(null)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    init {
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    fun launch(timeoutMs: Long = 30_000L): T {
        resumed.set(null)
        instrumentation.targetContext.startActivity(
            Intent(instrumentation.targetContext, activityClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )
        return await(timeoutMs) { resumed.get() }
            ?: error("${activityClass.simpleName} did not resume within ${timeoutMs}ms")
    }

    fun <R : Any> awaitMain(
        timeoutMs: Long = 30_000L,
        probe: (T) -> R?,
    ): R {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val activity = resumed.get()
            if (activity != null) {
                val result = AtomicReference<R?>(null)
                instrumentation.runOnMainSync {
                    result.set(probe(activity))
                }
                result.get()?.let { return it }
            }
            SystemClock.sleep(POLL_MS)
        }
        error("UI condition for ${activityClass.simpleName} was not met within ${timeoutMs}ms")
    }

    private fun <R : Any> await(timeoutMs: Long, probe: () -> R?): R? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            probe()?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        return null
    }

    override fun close() {
        val activity = resumed.getAndSet(null)
        if (activity != null && !activity.isFinishing) {
            instrumentation.runOnMainSync { activity.finish() }
        }
        application.unregisterActivityLifecycleCallbacks(callbacks)
    }

    private companion object {
        const val POLL_MS = 25L
    }
}
