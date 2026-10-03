package llc.slacker.openime.hotword

import android.content.Context

/**
 * Entry point of the hotword module for the rest of the app.
 *
 * The voice pipeline calls [apply] on recognized text; the settings screen
 * calls [reload] after a switch or an import changes which packs are active.
 * Everything else in this package stays internal to it.
 */
internal object HotwordRuntime {
    @Volatile private var corrector: HomophoneCorrector? = null
    @Volatile private var candidates: PinyinCandidateIndex? = null
    @Volatile private var store: HotwordPackStore? = null
    @Volatile private var readings: PinyinReadings? = null
    private val lock = Any()

    /** Idempotent. Building the index reads assets, so it runs off the caller's thread. */
    fun configure(context: Context) {
        synchronized(lock) {
            if (store != null) return
            store = HotwordPackStore(context)
        }
        Thread({ reload(context) }, "openime-hotwords").start()
    }

    fun store(context: Context): HotwordPackStore =
        store ?: synchronized(lock) { store ?: HotwordPackStore(context).also { store = it } }

    /** Rebuilds the matcher from the packs that are currently switched on. */
    fun reload(context: Context) {
        val activeStore = store(context)
        val activeReadings = readings ?: loadReadings(context)
        val words = activeStore.enabledWords()
        corrector = HomophoneCorrector(words, activeReadings)
        candidates = PinyinCandidateIndex(words, activeReadings)
    }

    /** Returns [text] unchanged until the first [reload] has finished. */
    fun apply(text: String): String = corrector?.apply(text) ?: text

    /**
     * Typing: moves hotwords whose pinyin equals [composition] up behind the top
     * candidate. Returns [candidates] unchanged when nothing matches or the
     * index is not ready yet.
     */
    fun boost(composition: String, candidates: List<String>): List<String> =
        this.candidates?.boost(composition, candidates) ?: candidates

    private fun loadReadings(context: Context): PinyinReadings {
        val loaded = context.applicationContext.assets.open(READINGS_ASSET).bufferedReader(Charsets.UTF_8)
            .use { reader -> PinyinReadings.parseRimeDict(reader.lineSequence().toList().asSequence()) }
        readings = loaded
        return loaded
    }

    private const val READINGS_ASSET = "rime-data/openime_dicts/8105.dict.yaml"
}
