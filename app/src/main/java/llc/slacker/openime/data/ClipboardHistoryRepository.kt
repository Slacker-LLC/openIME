package llc.slacker.openime.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo
import llc.slacker.openime.editor.EditorInfoAdapter
import org.json.JSONArray
import org.json.JSONObject

data class ClipboardEntry(
    val text: String,
    val timestamp: Long,
    val pinned: Boolean = false,
)

internal object ClipboardSensitivityPolicy {
    // ClipDescription.EXTRA_IS_SENSITIVE was added as a public constant in
    // API 33, but this literal is the compatibility key Android documents for
    // older releases as well. Keeping the literal avoids any runtime API-level
    // dependency while preserving the same contract on API 26+.
    const val SENSITIVE_KEY = "android.content.extra.IS_SENSITIVE"

    fun isSensitive(readBoolean: (String) -> Boolean): Boolean =
        runCatching { readBoolean(SENSITIVE_KEY) }.getOrDefault(false)
}

/**
 * Identifies one copy action so that "清空" can suppress exactly the clip that was on
 * the system clipboard at that moment. The framework stamps every clip with its own
 * set-time (ClipDescription.getTimestamp), which is unique per copy. Only when that
 * stamp is missing do we fall back to a SHA-256 of the text; hashCode+length is not
 * used because different strings can collide on it.
 */
internal object ClipboardSuppression {
    fun fingerprint(clipTimestamp: Long, text: String): String =
        if (clipTimestamp > 0L) {
            "t:$clipTimestamp"
        } else {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
            "h:" + digest.joinToString("") { "%02x".format(it) }
        }

    fun isSuppressed(suppressed: String?, clipTimestamp: Long, text: String): Boolean =
        suppressed != null && suppressed == fingerprint(clipTimestamp, text)
}

internal object ClipboardPrivacyPolicy {
    fun canUsePersistentHistory(
        editorKind: EditorInfoAdapter.EditorKind,
        imeOptions: Int,
    ): Boolean =
        // Password fields use the history like any other field (their owner's choice).
        // Editors that ask for no personalized learning, and clips the source app
        // marks sensitive (see ClipboardSensitivityPolicy), stay out of it.
        (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
}

internal object ClipboardRetentionPolicy {
    const val UNPINNED_TTL_MS = 24L * 60L * 60L * 1_000L

    fun retain(entry: ClipboardEntry, nowMs: Long): Boolean {
        if (entry.pinned) return true
        if (entry.timestamp <= 0L) return false
        val age = nowMs - entry.timestamp
        // Keep future timestamps when the wall clock moved backwards; they
        // become eligible for expiry naturally after real time catches up.
        return age < 0L || age <= UNPINNED_TTL_MS
    }

    fun prune(entries: List<ClipboardEntry>, nowMs: Long): List<ClipboardEntry> =
        entries.filter { retain(it, nowMs) }

    fun trimToCount(entries: List<ClipboardEntry>, maxItems: Int): List<ClipboardEntry> {
        // Pins consume the normal budget but are never evicted, even above it.
        var remaining = (maxItems - entries.count { it.pinned }).coerceAtLeast(0)
        return entries.filter { entry ->
            when {
                entry.pinned -> true
                remaining > 0 -> {
                    remaining--
                    true
                }
                else -> false
            }
        }
    }
}

/**
 * Tiny persistent clipboard history. Clipboard capture is best-effort; no
 * clipboard content is logged. Password and no-personalized-learning editors
 * do not expose persistent history through the IME. Unpinned
 * items expire after 24 hours; pinned items remain until the user removes them.
 */
object ClipboardHistoryRepository {
    private const val PREFS = "ime_clipboard_history"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 24
    private const val KEY_SUPPRESSED = "suppressed_fingerprint"

    /**
     * Every read-modify-write of the stored list (and of the suppression marker) runs
     * under this lock. The clipboard listener captures on its own thread, so without it
     * a capture could read the list before a clear, then write the cleared-away clip back.
     * All callers live in one process, so one JVM monitor is enough.
     */
    private val lock = Any()

    fun load(context: Context): List<ClipboardEntry> {
        if (!canUsePersistentHistory(context)) return emptyList()
        synchronized(lock) { return loadStored(context) }
    }

    fun add(context: Context, text: String) {
        if (text.isBlank() || !canUsePersistentHistory(context)) return
        synchronized(lock) { addLocked(context, text) }
    }

    private fun addLocked(context: Context, text: String) {
        val stored = loadStored(context)
        val old = stored.firstOrNull { it.text == text }
        val items = stored.filterNot { it.text == text }.toMutableList()
        items.add(0, ClipboardEntry(text, System.currentTimeMillis(), old?.pinned ?: false))
        save(context, ClipboardRetentionPolicy.trimToCount(items, MAX_ITEMS))
    }

    /** Capture the current system clip when the user opens the clipboard UI. */
    fun capturePrimary(context: Context): Boolean {
        if (!canUsePersistentHistory(context)) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return false
        return captureClip(context, clip)
    }

    /** Use the same immutable clipboard snapshot for its sensitivity flag and text. */
    fun captureClip(context: Context, clip: ClipData): Boolean {
        if (!canUsePersistentHistory(context)) return false
        val sensitive = clip.description.extras?.let { extras ->
            ClipboardSensitivityPolicy.isSensitive { key -> extras.getBoolean(key, false) }
        } ?: false
        if (sensitive) return false

        val text = runCatching {
            clip.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")
        if (text.isBlank()) return false
        synchronized(lock) {
            // 用户刚清空过：系统剪贴板里还躺着那一条（同一次复制的时间戳），不能写回来。
            val clipTimestamp = clip.description?.timestamp ?: 0L
            if (ClipboardSuppression.isSuppressed(suppressed(context), clipTimestamp, text)) return false
            addLocked(context, text)
        }
        return true
    }

    /**
     * 监听器收到的是一次复制动作。抑制标记按"那一次复制"的时间戳区分，新复制自然不匹配，
     * 所以这里不需要也不能解除抑制：解除会让旧事件线程把已清空的旧剪贴板写回来。
     */
    fun captureCopied(context: Context, clip: ClipData): Boolean = captureClip(context, clip)

    fun togglePin(context: Context, text: String) {
        if (!canUsePersistentHistory(context)) return
        synchronized(lock) {
            val items = loadStored(context).toMutableList()
            val idx = items.indexOfFirst { it.text == text }
            if (idx >= 0) {
                val old = items[idx]
                items[idx] = old.copy(pinned = !old.pinned)
                save(context, items)
            }
        }
    }

    fun remove(context: Context, text: String) {
        if (!canUsePersistentHistory(context)) return
        synchronized(lock) { save(context, loadStored(context).filterNot { it.text == text }) }
    }

    fun clearUnpinned(context: Context) {
        if (!canUsePersistentHistory(context)) return
        synchronized(lock) {
            suppressCurrentSystemClip(context)
            save(context, loadStored(context).filter { it.pinned })
        }
    }

    fun clearAll(context: Context) {
        if (!canUsePersistentHistory(context)) return
        synchronized(lock) {
            suppressCurrentSystemClip(context)
            save(context, emptyList())
        }
    }

    private fun suppressed(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SUPPRESSED, null)

    /** Records the clip that is on the clipboard now; caller holds [lock]. */
    private fun suppressCurrentSystemClip(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return
        val text = runCatching {
            clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull().orEmpty()
        if (text.isBlank()) return
        val fp = ClipboardSuppression.fingerprint(clip.description?.timestamp ?: 0L, text)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUPPRESSED, fp).apply()
    }

    private fun canUsePersistentHistory(context: Context): Boolean {
        val service = context as? InputMethodService ?: return true
        val editorInfo = service.currentInputEditorInfo ?: return true
        return ClipboardPrivacyPolicy.canUsePersistentHistory(
            editorKind = EditorInfoAdapter.kind(editorInfo),
            imeOptions = editorInfo.imeOptions,
        )
    }

    private fun loadStored(context: Context): List<ClipboardEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "[]") ?: "[]"
        val parsed = runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        ClipboardEntry(
                            text = obj.optString("text", ""),
                            timestamp = obj.optLong("timestamp", 0L),
                            pinned = obj.optBoolean("pinned", false),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
        val retained = ClipboardRetentionPolicy.prune(parsed, System.currentTimeMillis())
        if (retained.size != parsed.size) save(context, retained)
        return retained
    }

    private fun save(context: Context, items: List<ClipboardEntry>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("text", it.text)
                    .put("timestamp", it.timestamp)
                    .put("pinned", it.pinned),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, arr.toString()).apply()
    }
}
