package llc.slacker.openime.data

import android.content.Context
import org.json.JSONArray

/**
 * Process-persistent MRU list of the symbols the user has typed, the history the
 * reference's symbol board lists in its toolbar (`input_toolbar_quickinput.xml:7-10`,
 * 历史符号列表 `style.xml:1054-1070`).
 */
internal object SymbolRecentRepository {
    private const val PREFS = "openime_symbol_recent"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 40

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index)
                    if (value.isNotBlank() && value !in this) add(value)
                    if (size >= MAX_ITEMS) break
                }
            }
        }.getOrDefault(emptyList())
    }

    fun record(context: Context, symbol: String) {
        if (symbol.isBlank()) return
        val next = buildList {
            add(symbol)
            load(context).forEach { if (it != symbol && size < MAX_ITEMS) add(it) }
        }
        val array = JSONArray()
        next.forEach(array::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ITEMS)
            .apply()
    }
}
