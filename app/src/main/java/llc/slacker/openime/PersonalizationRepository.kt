package llc.slacker.openime

import android.content.Context
import android.view.inputmethod.EditorInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Candidate-selection events used only to bias local ASR hotwords.
 *
 * This store is deliberately separate from candidate ranking. Rime userdb
 * remains the authoritative learner for normal native candidate ordering, and
 * UserPhraseRepository remains only the Rime-unavailable fallback learner.
 */
object PersonalizationRepository {
    private const val PREFS = "personalization_events"
    private const val KEY = "selected_terms"
    private const val MAX_ENTRIES = 1_000

    private data class Entry(
        val text: String,
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

    fun record(text: String) {
        val value = normalize(text)
        if (value.isEmpty()) return
        synchronized(lock) {
            val current = entries[value]
            if (current == null) {
                entries[value] = Entry(value, count = 1, lastUsed = System.currentTimeMillis())
            } else {
                current.count = (current.count + 1).coerceAtMost(1_000_000)
                current.lastUsed = System.currentTimeMillis()
            }
            trimLocked()
            saveLocked()
        }
    }

    /** Repeated explicit selections are eligible to bias local speech only. */
    fun hotwords(
        minimumFrequency: Int = 2,
        limit: Int = 64,
    ): List<String> {
        val threshold = minimumFrequency.coerceAtLeast(1)
        val boundedLimit = limit.coerceIn(0, 128)
        if (boundedLimit == 0) return emptyList()
        synchronized(lock) {
            return entries.values
                .asSequence()
                .filter { it.count >= threshold }
                .sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastUsed })
                .map { it.text }
                .take(boundedLimit)
                .toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            preferences?.edit()?.remove(KEY)?.apply()
        }
    }

    private fun normalize(value: String): String {
        val cleaned = value.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty() || cleaned.length > 128) return ""
        return cleaned
    }

    private fun trimLocked() {
        if (entries.size <= MAX_ENTRIES) return
        entries.values
            .sortedWith(compareBy<Entry> { it.count }.thenBy { it.lastUsed })
            .take(entries.size - MAX_ENTRIES)
            .forEach { entries.remove(it.text) }
    }

    private fun loadLocked() {
        entries.clear()
        val raw = preferences?.getString(KEY, null).orEmpty()
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = normalize(item.optString("text"))
                if (text.isEmpty()) continue
                entries[text] = Entry(
                    text = text,
                    count = item.optInt("count", 1).coerceAtLeast(1),
                    lastUsed = item.optLong("lastUsed", 0L),
                )
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
                    .put("text", entry.text)
                    .put("count", entry.count)
                    .put("lastUsed", entry.lastUsed),
            )
        }
        target.edit().putString(KEY, array.toString()).apply()
    }
}

/** Privacy gate captured at the editor event that produced a learning signal. */
object PersonalizationPolicy {
    fun allow(info: EditorInfo?): Boolean {
        if (info == null) return false
        if (EditorInfoAdapter.isPassword(EditorInfoAdapter.kind(info))) return false
        return (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
    }
}
