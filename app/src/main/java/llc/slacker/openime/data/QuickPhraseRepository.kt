package llc.slacker.openime.data

import android.content.Context
import llc.slacker.openime.core.ImeData
import org.json.JSONArray
import org.json.JSONObject

data class QuickPhrase(
    val id: Long,
    val category: String,
    val text: String,
    val inputCode: String = "",
)

/** Persistent user-editable quick phrases used by the clipboard panel. */
object QuickPhraseRepository {
    private const val PREFS = "ime_quick_phrases"
    private const val KEY_ITEMS = "items"
    private val cacheLock = Any()
    private var cachedRaw: String? = null
    private var cachedItems: List<QuickPhrase>? = null

    fun load(context: Context): List<QuickPhrase> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ITEMS, null)
        synchronized(cacheLock) {
            if (cachedRaw == raw && cachedItems != null) {
                return cachedItems.orEmpty()
            }
        }
        if (raw == null) {
            val defaults = defaults()
            synchronized(cacheLock) {
                cachedRaw = null
                cachedItems = defaults
            }
            return defaults
        }
        val parsed = runCatching {
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
                                inputCode = normalizeInputCode(item.optString("input_code", "")),
                            ),
                        )
                    }
                }
            }
        }.getOrElse { defaults() }
        synchronized(cacheLock) {
            cachedRaw = raw
            cachedItems = parsed
        }
        return parsed
    }

    fun upsert(
        context: Context,
        id: Long,
        category: String,
        text: String,
        inputCode: String? = null,
    ): QuickPhrase? {
        val value = text.trim()
        if (value.isEmpty()) return null
        val items = load(context).toMutableList()
        val safeCategory = category.trim().ifBlank { "常用" }
        val actualId = if (id > 0L) id else nextId(items)
        val existing = items.firstOrNull { it.id == actualId }
        val safeCode = if (inputCode == null) {
            existing?.inputCode.orEmpty()
        } else {
            normalizeInputCode(inputCode)
        }
        val updated = QuickPhrase(actualId, safeCategory, value, safeCode)
        val index = items.indexOfFirst { it.id == actualId }
        if (index >= 0) items[index] = updated else items.add(updated)
        save(context, items)
        return updated
    }

    fun candidatesForInputCode(
        context: Context,
        rawCode: String,
        exactOnly: Boolean = false,
        limit: Int = 8,
    ): List<String> {
        val code = normalizeInputCode(rawCode)
        if (code.length < 2 || limit <= 0) return emptyList()
        val phrases = load(context)
        val exact = phrases.asSequence()
            .filter { it.inputCode == code }
            .map { it.text }
        val prefix = if (exactOnly) {
            emptySequence()
        } else {
            phrases.asSequence()
                .filter { it.inputCode.length > code.length && it.inputCode.startsWith(code) }
                .map { it.text }
        }
        return (exact + prefix)
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
            .toList()
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
                        inputCode = "",
                    ),
                )
            }
        }
    }

    private fun normalizeInputCode(value: String): String =
        value.trim()
            .lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
            .take(24)

    private fun nextId(items: List<QuickPhrase>): Long =
        (items.maxOfOrNull { it.id } ?: 0L) + 1L

    private fun save(context: Context, items: List<QuickPhrase>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("category", item.category)
                    .put("text", item.text)
                    .put("input_code", item.inputCode),
            )
        }
        val raw = array.toString()
        synchronized(cacheLock) {
            cachedRaw = raw
            cachedItems = items.toList()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, raw)
            .apply()
    }
}
