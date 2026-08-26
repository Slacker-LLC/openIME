package llc.slacker.openime

import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ClipboardEntry(
    val text: String,
    val timestamp: Long,
    val pinned: Boolean = false,
)

/**
 * Tiny persistent clipboard history. Clipboard capture is best-effort and is
 * skipped by the caller for password fields; no clipboard content is logged.
 */
object ClipboardHistoryRepository {
    private const val PREFS = "ime_clipboard_history"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 24

    fun load(context: Context): List<ClipboardEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "[]") ?: "[]"
        return runCatching {
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
    }

    fun add(context: Context, text: String) {
        if (text.isBlank()) return
        val old = load(context).firstOrNull { it.text == text }
        val items = load(context).filterNot { it.text == text }.toMutableList()
        items.add(0, ClipboardEntry(text, System.currentTimeMillis(), old?.pinned ?: false))
        save(context, items.take(MAX_ITEMS))
    }

    /** Capture the current system clip when the user opens the clipboard UI. */
    fun capturePrimary(context: Context): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
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
        val items = load(context).toMutableList()
        val idx = items.indexOfFirst { it.text == text }
        if (idx >= 0) {
            val old = items[idx]
            items[idx] = old.copy(pinned = !old.pinned)
            save(context, items)
        }
    }

    fun remove(context: Context, text: String) {
        save(context, load(context).filterNot { it.text == text })
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
