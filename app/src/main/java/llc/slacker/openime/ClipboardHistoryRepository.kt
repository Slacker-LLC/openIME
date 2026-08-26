package llc.slacker.openime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ClipboardEntry(
    val text: String,
    val timestamp: Long,
    val pinned: Boolean = false,
)

/**
 * Tiny persistent clipboard history. It deliberately stores only text the user
 * explicitly copies in the text-editor panel; password fields are never added.
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
        val items = load(context).filterNot { it.text == text }.toMutableList()
        items.add(0, ClipboardEntry(text, System.currentTimeMillis(), false))
        save(context, items.take(MAX_ITEMS))
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
