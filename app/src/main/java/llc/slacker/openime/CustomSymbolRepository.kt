package llc.slacker.openime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CustomSymbol(
    val id: Long,
    val group: String,
    val symbol: String,
    val pinned: Boolean = false,
)

/** Persistent user symbols with category, pin and order support. */
object CustomSymbolRepository {
    private const val PREFS = "ime_custom_symbols"
    private const val KEY_ITEMS = "items"

    fun load(context: Context): List<CustomSymbol> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val symbol = item.optString("symbol", "").trim()
                    if (symbol.isNotEmpty()) {
                        add(
                            CustomSymbol(
                                id = item.optLong("id", index.toLong() + 1L),
                                group = item.optString("group", "自定义").ifBlank { "自定义" },
                                symbol = symbol,
                                pinned = item.optBoolean("pinned", false),
                            ),
                        )
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun upsert(context: Context, id: Long, group: String, symbol: String): CustomSymbol? {
        val value = symbol.trim()
        if (value.isEmpty()) return null
        val items = load(context).toMutableList()
        val actualId = if (id > 0L) id else (items.maxOfOrNull { it.id } ?: 0L) + 1L
        val previous = items.firstOrNull { it.id == actualId }
        val updated = CustomSymbol(
            id = actualId,
            group = group.trim().ifBlank { "自定义" },
            symbol = value,
            pinned = previous?.pinned == true,
        )
        val index = items.indexOfFirst { it.id == actualId }
        if (index >= 0) items[index] = updated else items.add(updated)
        save(context, items)
        return updated
    }

    fun remove(context: Context, id: Long) = save(context, load(context).filterNot { it.id == id })

    fun togglePinned(context: Context, id: Long) {
        val items = load(context).map { if (it.id == id) it.copy(pinned = !it.pinned) else it }
        save(context, items.sortedWith(compareByDescending<CustomSymbol> { it.pinned }))
    }

    fun move(context: Context, id: Long, delta: Int) {
        val items = load(context).toMutableList()
        val index = items.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in items.indices) return
        val item = items.removeAt(index)
        items.add(target, item)
        save(context, items)
    }

    fun moveBefore(context: Context, movingId: Long, targetId: Long) {
        if (movingId == targetId) return
        val items = load(context).toMutableList()
        val movingIndex = items.indexOfFirst { it.id == movingId }
        val targetIndex = items.indexOfFirst { it.id == targetId }
        if (movingIndex < 0 || targetIndex < 0) return
        val moving = items.removeAt(movingIndex)
        val insertAt = items.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
        items.add(insertAt, moving)
        save(context, items)
    }

    private fun save(context: Context, items: List<CustomSymbol>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("group", item.group)
                    .put("symbol", item.symbol)
                    .put("pinned", item.pinned),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, array.toString()).apply()
    }
}
