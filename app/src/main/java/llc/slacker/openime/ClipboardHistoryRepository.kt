package llc.slacker.openime

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo
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

internal object ClipboardPrivacyPolicy {
    fun canUsePersistentHistory(
        editorKind: EditorInfoAdapter.EditorKind,
        imeOptions: Int,
    ): Boolean =
        !EditorInfoAdapter.isPassword(editorKind) &&
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
}

/**
 * Tiny persistent clipboard history. Clipboard capture is best-effort; no
 * clipboard content is logged. Password and no-personalized-learning editors
 * cannot read from or write to the persistent history at all. Unpinned items
 * expire after 24 hours; pinned items remain until the user removes them.
 */
object ClipboardHistoryRepository {
    private const val PREFS = "ime_clipboard_history"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 24

    fun load(context: Context): List<ClipboardEntry> {
        if (!canUsePersistentHistory(context)) return emptyList()
        return loadStored(context)
    }

    fun add(context: Context, text: String) {
        if (text.isBlank() || !canUsePersistentHistory(context)) return
        val stored = loadStored(context)
        val old = stored.firstOrNull { it.text == text }
        val items = stored.filterNot { it.text == text }.toMutableList()
        items.add(0, ClipboardEntry(text, System.currentTimeMillis(), old?.pinned ?: false))
        save(context, items.take(MAX_ITEMS))
    }

    /** Capture the current system clip when the user opens the clipboard UI. */
    fun capturePrimary(context: Context): Boolean {
        if (!canUsePersistentHistory(context)) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val description = runCatching { clipboard.primaryClipDescription }.getOrNull()
        val sensitive = description?.extras?.let { extras ->
            ClipboardSensitivityPolicy.isSensitive { key -> extras.getBoolean(key, false) }
        } ?: false
        if (sensitive) return false

        val text = runCatching {
            clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")
        if (text.isBlank()) return false
        add(context, text)
        return true
    }

    fun togglePin(context: Context, text: String) {
        if (!canUsePersistentHistory(context)) return
        val items = loadStored(context).toMutableList()
        val idx = items.indexOfFirst { it.text == text }
        if (idx >= 0) {
            val old = items[idx]
            items[idx] = old.copy(pinned = !old.pinned)
            save(context, items)
        }
    }

    fun remove(context: Context, text: String) {
        if (!canUsePersistentHistory(context)) return
        save(context, loadStored(context).filterNot { it.text == text })
    }

    fun clearUnpinned(context: Context) {
        if (!canUsePersistentHistory(context)) return
        save(context, loadStored(context).filter { it.pinned })
    }

    fun clearAll(context: Context) {
        if (!canUsePersistentHistory(context)) return
        save(context, emptyList())
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
