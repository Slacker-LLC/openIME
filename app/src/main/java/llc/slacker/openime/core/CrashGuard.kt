package llc.slacker.openime.core

import android.content.Context
import android.os.Build
import android.util.Log
import llc.slacker.openime.R
import java.io.File

/**
 * Crash resilience for a keyboard that has to keep working inside other apps.
 *
 * An input method that dies is replaced by the system with another keyboard, so
 * the goals are: remember what happened (locally; the app has no INTERNET
 * permission), never turn one bad key press into a crash, and stop a crash loop
 * from locking the user out of typing.
 *
 * What is stored never contains typed text: only the time, the app version, the
 * exception types and stack frames.
 */
internal object CrashGuard {
    private const val TAG = "OpenImeCrash"
    private const val PREFS = "openime_crash_guard"
    private const val KEY_TIMES = "crash_times"
    private const val KEY_LAST = "last_report"
    private const val KEY_HANDLED = "handled_count"
    private const val KEY_EXIT_SEEN = "exit_seen"
    private const val MAX_TIMES = 10
    private const val MAX_FRAMES = 14

    /** This many crashes within [LOOP_WINDOW_MS] switch the next start to safe mode. */
    internal const val LOOP_CRASHES = 3
    internal const val LOOP_WINDOW_MS = 10 * 60 * 1000L

    @Volatile
    private var installed = false

    /** Idempotent; chains to whatever handler was installed before. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(app, thread.name, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    internal fun recordCrash(context: Context, threadName: String, throwable: Throwable, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val times = parseTimes(prefs.getString(KEY_TIMES, null)) + now
        prefs.edit()
            .putString(KEY_TIMES, formatTimes(times.takeLast(MAX_TIMES)))
            .putString(KEY_LAST, report(context, "crash", threadName, throwable, now))
            .commit() // the process is about to die: no async apply()
    }

    /** An exception that was caught and survived; kept for diagnostics, not counted as a crash. */
    fun recordHandled(context: Context, where: String, throwable: Throwable, now: Long = System.currentTimeMillis()) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(KEY_HANDLED, prefs.getInt(KEY_HANDLED, 0) + 1)
                .putString(KEY_LAST, report(context, "handled in $where", Thread.currentThread().name, throwable, now))
                .apply()
        }
    }

    /** A native startup that never came back counts as a crash, even though no Java handler ran. */
    fun recordNativeStartupCrash(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val times = parseTimes(prefs.getString(KEY_TIMES, null)) + now
        prefs.edit()
            .putString(KEY_TIMES, formatTimes(times.takeLast(MAX_TIMES)))
            .putString(KEY_LAST, "${header(context, "native crash while starting librime", "local-rime-startup", now)}\n(no Java stack: the process died inside native code)")
            .commit()
    }

    /**
     * Android 11+ remembers why earlier processes died. Native crashes (no Java
     * handler ever runs) and ANRs (the keyboard froze) of this app are folded
     * into the crash history here, once each, so a freeze loop also ends in safe mode.
     */
    fun ingestProcessExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(KEY_EXIT_SEEN, 0L)
            val bad = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8).filter {
                it.timestamp > seen && it.reason in countedExitReasons
            }
            if (bad.isEmpty()) return
            val times = parseTimes(prefs.getString(KEY_TIMES, null)) + bad.map { it.timestamp }
            val newest = bad.maxBy { it.timestamp }
            prefs.edit()
                .putString(KEY_TIMES, formatTimes(times.sorted().takeLast(MAX_TIMES)))
                .putLong(KEY_EXIT_SEEN, newest.timestamp)
                .putString(KEY_LAST, "${header(context, "previous process ended: ${exitReasonName(newest.reason)}", "process", newest.timestamp)}\n(reported by Android; no stack)")
                .commit()
        }
    }

    /**
     * Whether the process before this one ended in a native crash. null when
     * Android cannot say (before Android 11). A marker left by a startup that was
     * merely killed (force stop, update, low memory) must not count as a crash.
     */
    fun previousExitWasNativeCrash(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val latest = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
                ?: return@runCatching null
            latest.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
        }.getOrNull()
    }

    /** Leave safe mode now, e.g. from the About screen. */
    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TIMES).commit()
    }

    private val countedExitReasons: Set<Int> by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setOf(
                android.app.ApplicationExitInfo.REASON_CRASH,
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
                android.app.ApplicationExitInfo.REASON_ANR,
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            )
        } else {
            emptySet()
        }
    }

    @android.annotation.SuppressLint("NewApi")
    private fun exitReasonName(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        android.app.ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialisation failure"
        else -> "reason $reason"
    }

    fun isSafeMode(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        inCrashLoop(parseTimes(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TIMES, null)), now)

    fun lastReport(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)

    internal fun inCrashLoop(times: List<Long>, now: Long): Boolean =
        times.count { now - it in 0..LOOP_WINDOW_MS } >= LOOP_CRASHES

    internal fun parseTimes(raw: String?): List<Long> =
        raw.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }

    internal fun formatTimes(times: List<Long>): String = times.joinToString(",")

    private fun header(context: Context, kind: String, thread: String, now: Long): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        return "openIME $version | $kind | thread=$thread | at=$now | android=${Build.VERSION.SDK_INT} | device=${Build.MANUFACTURER} ${Build.MODEL}"
    }

    /** Exception types and stack frames only. Messages are left out on purpose: they can quote user text. */
    internal fun report(context: Context, kind: String, thread: String, throwable: Throwable, now: Long): String =
        buildString {
            append(header(context, kind, thread, now)).append('\n')
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < 4) {
                append(if (depth == 0) "" else "caused by ").append(current.javaClass.name).append('\n')
                current.stackTrace.take(MAX_FRAMES).forEach { append("  at ").append(it).append('\n') }
                current = current.cause?.takeIf { it !== current }
                depth++
            }
        }

    internal fun log(message: String, throwable: Throwable? = null) {
        runCatching { Log.e(TAG, message, throwable) }
    }
}

/**
 * Librime lives in native code: a segmentation fault there cannot be caught and
 * kills the whole keyboard process, and with a damaged compiled dictionary or
 * user database it would do so on every start. A marker file is written before
 * native startup and removed once the engine proves itself, so a marker that is
 * still there at the next start means the last start died natively. Each such
 * death escalates: clear the compiled data, then set the user database aside,
 * then run without the native engine.
 */
internal class RimeStartupRecovery(private val stateDir: File) {
    enum class Action { NORMAL, CLEAN_BUILD, RESET_USER_DATA, SKIP_NATIVE }

    private val marker = File(stateDir, ".rime-startup-pending")
    private val failuresFile = File(stateDir, ".rime-startup-failures")

    /** Call before native startup. Returns what to do and arms the marker unless native is skipped. */
    fun begin(): Action {
        stateDir.mkdirs()
        if (marker.exists()) writeFailures(failures() + 1)
        val action = when (failures()) {
            0 -> Action.NORMAL
            1 -> Action.CLEAN_BUILD
            2 -> Action.RESET_USER_DATA
            else -> Action.SKIP_NATIVE
        }
        if (action == Action.SKIP_NATIVE) marker.delete() else marker.writeText("pending\n")
        return action
    }

    /** The engine started and passed its health probe. */
    fun succeeded() {
        marker.delete()
        failuresFile.delete()
    }

    /** Startup ended with a Java exception: not a crash, so it is not counted. */
    fun failedWithoutCrash() {
        marker.delete()
    }

    /** True when the previous native startup never finished. */
    fun lastStartupDiedNatively(): Boolean = marker.exists()

    fun failures(): Int = runCatching { failuresFile.readText().trim().toInt() }.getOrDefault(0)

    private fun writeFailures(value: Int) {
        failuresFile.writeText("$value\n")
    }
}
