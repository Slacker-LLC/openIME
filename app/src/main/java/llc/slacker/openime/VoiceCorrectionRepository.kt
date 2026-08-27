package llc.slacker.openime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Private on-device ASR correction pairs. Nothing is uploaded or logged. */
object VoiceCorrectionRepository {
    private const val PREFS = "voice_corrections"
    private const val KEY = "pairs"
    private const val MAX_ENTRIES = 500

    private data class Entry(
        val original: String,
        val corrected: String,
        var count: Int,
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

    fun record(original: String, corrected: String) {
        val source = normalize(original)
        val target = normalize(corrected)
        if (source.isEmpty() || target.isEmpty() || source == target) return
        if (source.length > 128 || target.length > 128) return
        synchronized(lock) {
            val key = "$source\u0000$target"
            val current = entries[key]
            if (current == null) {
                entries[key] = Entry(source, target, 1, System.currentTimeMillis())
            } else {
                current.count = (current.count + 1).coerceAtMost(1_000_000)
                current.lastUsed = System.currentTimeMillis()
            }
            trimLocked()
            saveLocked()
        }
    }

    fun apply(text: String): String {
        val source = normalize(text)
        if (source.isEmpty()) return text
        synchronized(lock) {
            return entries.values
                .asSequence()
                .filter { it.original == source }
                .sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastUsed })
                .firstOrNull()
                ?.corrected
                ?: text
        }
    }

    fun hotwords(limit: Int = 32): List<String> = synchronized(lock) {
        entries.values
            .asSequence()
            .sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastUsed })
            .map { it.corrected }
            .distinct()
            .take(limit.coerceIn(0, 64))
            .toList()
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            preferences?.edit()?.remove(KEY)?.apply()
        }
    }

    private fun normalize(value: String): String = value
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun trimLocked() {
        if (entries.size <= MAX_ENTRIES) return
        entries.values
            .sortedWith(compareBy<Entry> { it.count }.thenBy { it.lastUsed })
            .take(entries.size - MAX_ENTRIES)
            .forEach { entries.remove("${it.original}\u0000${it.corrected}") }
    }

    private fun loadLocked() {
        entries.clear()
        val raw = preferences?.getString(KEY, null).orEmpty()
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val original = normalize(item.optString("original"))
                val corrected = normalize(item.optString("corrected"))
                if (original.isEmpty() || corrected.isEmpty() || original == corrected) continue
                val entry = Entry(
                    original = original,
                    corrected = corrected,
                    count = item.optInt("count", 1).coerceAtLeast(1),
                    lastUsed = item.optLong("lastUsed", 0L),
                )
                entries["$original\u0000$corrected"] = entry
            }
            trimLocked()
        }
    }

    private fun saveLocked() {
        val target = preferences ?: return
        val array = JSONArray()
        entries.values.forEach { entry ->
            array.put(
                JSONObject()
                    .put("original", entry.original)
                    .put("corrected", entry.corrected)
                    .put("count", entry.count)
                    .put("lastUsed", entry.lastUsed),
            )
        }
        target.edit().putString(KEY, array.toString()).apply()
    }
}
