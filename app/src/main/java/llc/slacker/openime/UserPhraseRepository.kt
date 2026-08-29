package llc.slacker.openime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private const val MAX_ENTRIES = 2000
    private const val SAVE_DEBOUNCE_MS = 500L

    private data class Entry(
        val code: String,
        val text: String,
        var frequency: Int,
        var lastUsed: Long,
    )

    private val lock = Any()
    private var preferences: android.content.SharedPreferences? = null
    private val entries = LinkedHashMap<String, Entry>()

    /**
     * Persisting used to happen inline on the IME thread: every commit built a
     * JSONArray over up to 2 000 entries and serialized it to a string before
     * apply() handed the write off. Coalesce a burst of commits instead, and
     * do the serialization off the IME thread entirely.
     */
    private val saveExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "user-phrase-save").apply { isDaemon = true }
    }
    private val saveScheduled = AtomicBoolean(false)

    fun configure(context: Context) {
        synchronized(lock) {
            if (preferences != null) return
            preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            loadLocked()
        }
    }

    /**
     * This legacy fallback learner is consulted only while librime is not
     * ready. Requiring repeated choices prevents one accidental tap from
     * becoming a permanent first candidate. Existing entries stay untouched.
     */
    fun candidatesFor(
        code: String,
        minimumFrequency: Int = DEFAULT_MINIMUM_FREQUENCY,
    ): List<String> {
        val key = normalize(code)
        if (key.isEmpty()) return emptyList()
        val threshold = minimumFrequency.coerceAtLeast(1)
        synchronized(lock) {
            return entries.values
                .asSequence()
                .filter { it.code == key && it.frequency >= threshold }
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
            trimLocked()
            scheduleSaveLocked()
        }
    }

    /**
     * Write any pending learning immediately. Call when an input session ends
     * so a process kill cannot lose more than the debounce window.
     */
    fun flush() {
        synchronized(lock) {
            if (preferences == null) return
            writeLocked()
        }
    }

    /**
     * The map used to grow without bound: saveLocked() persisted only the last
     * 2 000 entries, so memory and disk silently diverged and a long-lived
     * process kept every phrase it had ever seen.
     */
    private fun trimLocked() {
        if (entries.size <= MAX_ENTRIES) return
        val keep = entries.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Entry>> { it.value.lastUsed }
                    .thenByDescending { it.value.frequency },
            )
            .take(MAX_ENTRIES)
            .mapTo(HashSet()) { it.key }
        entries.keys.retainAll(keep)
    }

    private fun scheduleSaveLocked() {
        if (preferences == null) return
        if (!saveScheduled.compareAndSet(false, true)) return
        saveExecutor.execute {
            runCatching { Thread.sleep(SAVE_DEBOUNCE_MS) }
            saveScheduled.set(false)
            synchronized(lock) { writeLocked() }
        }
    }

    /** Recent, repeatedly selected terms that can safely bias local ASR. */
    fun voiceHotwords(
        minimumFrequency: Int = 2,
        limit: Int = 64,
    ): List<String> {
        val threshold = minimumFrequency.coerceAtLeast(1)
        val boundedLimit = limit.coerceIn(0, 128)
        if (boundedLimit == 0) return emptyList()
        synchronized(lock) {
            return entries.values
                .asSequence()
                .filter { it.frequency >= threshold }
                .sortedWith(compareByDescending<Entry> { it.frequency }.thenByDescending { it.lastUsed })
                .map { it.text }
                .distinct()
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

    private fun writeLocked() {
        val target = preferences ?: return
        val array = JSONArray()
        entries.values.toList().takeLast(MAX_ENTRIES).forEach { entry ->
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

    private const val DEFAULT_MINIMUM_FREQUENCY = 3
}
