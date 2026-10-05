package llc.slacker.openime.data

import android.content.Context
import org.json.JSONArray

/**
 * The symbols in the left rail of the nine-key and stroke keyboards, in order.
 * Until the user edits them (the rail's ＋ opens the editor) the rail shows
 * [DEFAULT], the ten punctuation marks typed most in Chinese.
 */
object RailSymbolRepository {
    private const val PREFS = "ime_rail_symbols"
    private const val KEY_SYMBOLS = "symbols"

    val DEFAULT: List<String> = listOf("，", "。", "？", "！", "、", "：", "；", "“", "”", "……")

    /** A rail holds at most this many; each is a short symbol or text. */
    const val MAX_SYMBOLS = 60
    const val MAX_SYMBOL_LENGTH = 8

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SYMBOLS, null)
            ?: return DEFAULT
        return runCatching {
            val array = JSONArray(raw)
            normalize((0 until array.length()).map { array.optString(it) })
        }.getOrDefault(DEFAULT)
    }

    /** Whether the user has changed the rail from [DEFAULT]. */
    fun isCustomized(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_SYMBOLS)

    fun save(context: Context, symbols: List<String>) {
        val value = normalize(symbols)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SYMBOLS, JSONArray(value).toString())
            .apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SYMBOLS).apply()
    }

    /** Trimmed, non-blank, short, unique, at most [MAX_SYMBOLS]; order kept. */
    fun normalize(symbols: List<String>): List<String> =
        symbols.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_SYMBOL_LENGTH }
            .distinct()
            .take(MAX_SYMBOLS)
            .toList()
}
