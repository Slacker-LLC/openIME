package llc.slacker.openime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class QuickPhrase(
    val id: Long,
    val category: String,
    val text: String,
)

/** Persistent user-editable quick phrases used by the clipboard panel. */
object QuickPhraseRepository {
    private const val PREFS = "ime_quick_phrases"
    private const val KEY_ITEMS = "items"

    fun load(context: Context): List<QuickPhrase> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ITEMS, null) ?: return defaults()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val text = item.optString("text", "").trim()
                    if (text.isNotEmpty()) {
                        add(
                            QuickPhrase(
                                id = item.optLong("id", index.toLong() + 1L),
                                category = item.optString("category", "常用").ifBlank { "常用" },
                                text = text,
                            ),
                        )
                    }
                }
            }
        }.getOrElse { defaults() }
    }

    fun upsert(context: Context, id: Long, category: String, text: String): QuickPhrase? {
        val value = text.trim()
        if (value.isEmpty()) return null
        val items = load(context).toMutableList()
        val safeCategory = category.trim().ifBlank { "常用" }
        val actualId = if (id > 0L) id else nextId(items)
        val updated = QuickPhrase(actualId, safeCategory, value)
        val index = items.indexOfFirst { it.id == actualId }
        if (index >= 0) items[index] = updated else items.add(updated)
        save(context, items)
        return updated
    }

    fun remove(context: Context, id: Long) {
        save(context, load(context).filterNot { it.id == id })
    }

    private fun defaults(): List<QuickPhrase> = buildList {
        ImeData.quickPhrases.entries.forEachIndexed { categoryIndex, (category, phrases) ->
            phrases.forEachIndexed { phraseIndex, text ->
                add(
                    QuickPhrase(
                        id = (categoryIndex + 1L) * 10_000L + phraseIndex + 1L,
                        category = category,
                        text = text,
                    ),
                )
            }
        }
    }

    private fun nextId(items: List<QuickPhrase>): Long =
        (items.maxOfOrNull { it.id } ?: 0L) + 1L

    private fun save(context: Context, items: List<QuickPhrase>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("category", item.category)
                    .put("text", item.text),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }
}
