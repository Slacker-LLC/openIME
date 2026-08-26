package llc.slacker.openime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small private user dictionary for selected Pinyin candidates. It is kept
 * inside this independent APK and is never uploaded. The dictionary is
 * deliberately keyed by the exact editable composition, so a user can teach
 * both `nihao` and an explicitly segmented `xi an` without changing the
 * built-in dictionary.
 */
object UserPhraseRepository {
    private const val PREFS = "user_phrases"
    private const val KEY = "entries"

    private data class Entry(
        val code: String,
        val text: String,
        var frequency: Int,
        var lastUsed: Long,
    )

    private val lock = Any()
    private var preferences: android.content.SharedPreferences? = null
    private val entries = LinkedHashMap<String, Entry>()

    fun configure(context: Context) {
        synchronized(lock) {
            if (preferences != null) return
            preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            loadLocked()
        }
    }

    fun candidatesFor(code: String): List<String> {
        val key = normalize(code)
        if (key.isEmpty()) return emptyList()
        synchronized(lock) {
            return entries.values
                .asSequence()
                .filter { it.code == key }
                .sortedWith(compareByDescending<Entry> { it.frequency }.thenByDescending { it.lastUsed })
                .map { it.text }
                .distinct()
                .take(24)
                .toList()
        }
    }

    fun record(code: String, text: String) {
        val key = normalize(code)
        val value = text.trim()
        if (key.isEmpty() || value.isEmpty() || value == key) return
        synchronized(lock) {
            // The IME can be constructed in a debug activity before the
            // service has configured the repository; keep the in-memory part
            // useful in that case and persist once configure() is called.
            val entryKey = "$key\u0000$value"
            val current = entries[entryKey]
            if (current == null) {
                entries[entryKey] = Entry(key, value, frequency = 1, lastUsed = System.currentTimeMillis())
            } else {
                current.frequency = (current.frequency + 1).coerceAtMost(1_000_000)
                current.lastUsed = System.currentTimeMillis()
            }
            saveLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            preferences?.edit()?.remove(KEY)?.apply()
        }
    }

    private fun normalize(code: String): String = code
        .lowercase()
        .replace('|', ' ')
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    private fun loadLocked() {
        entries.clear()
        val raw = preferences?.getString(KEY, null).orEmpty()
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val code = normalize(item.optString("code"))
                val text = item.optString("text").trim()
                if (code.isNotEmpty() && text.isNotEmpty()) {
                    val entry = Entry(
                        code = code,
                        text = text,
                        frequency = item.optInt("frequency", 1).coerceAtLeast(1),
                        lastUsed = item.optLong("lastUsed", 0L),
                    )
                    entries["$code\u0000$text"] = entry
                }
            }
        }
    }

    private fun saveLocked() {
        val target = preferences ?: return
        val array = JSONArray()
        entries.values.toList().takeLast(2_000).forEach { entry ->
            array.put(
                JSONObject()
                    .put("code", entry.code)
                    .put("text", entry.text)
                    .put("frequency", entry.frequency)
                    .put("lastUsed", entry.lastUsed),
            )
        }
        target.edit().putString(KEY, array.toString()).apply()
    }
}
