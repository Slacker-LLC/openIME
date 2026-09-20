package llc.slacker.openime

import android.content.Context
import org.json.JSONArray

/** Process-persistent MRU list for the emoji panel. */
internal object EmojiRecentRepository {
    private const val PREFS = "openime_emoji_recent"
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
                    val value = array.optString(index).trim()
                    if (value.isNotEmpty() && value !in this) add(value)
                    if (size >= MAX_ITEMS) break
                }
            }
        }.getOrDefault(emptyList())
    }

    fun record(context: Context, emoji: String) {
        if (emoji.isBlank()) return
        val next = buildList {
            add(emoji)
            load(context).forEach { if (it != emoji && size < MAX_ITEMS) add(it) }
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
