package llc.slacker.openime.data

import android.content.Context

/** Resolves bundled Microsoft Fluent Emoji 3D assets by their Unicode sequence. */
object FluentEmojiAssetRepository {
    private const val ROOT = "emoji/fluent"

    @Volatile
    private var availableFiles: Map<String, String>? = null

    fun pathFor(context: Context, emoji: String): String? {
        val fileName = fileNameFor(emoji)
        return files(context)[fileName]
    }

    internal fun fileNameFor(emoji: String): String =
        emoji.codePoints()
            .toArray()
            .joinToString("_") { it.toString(16) } + ".png"

    /**
     * AssetManager.open() used to run once per emoji cell on the IME thread
     * merely to probe whether a PNG existed. List the directory once per
     * process instead; actual bitmap decoding remains on the background pool.
     */
    private fun files(context: Context): Map<String, String> {
        availableFiles?.let { return it }
        return synchronized(this) {
            availableFiles ?: runCatching {
                val assets = context.applicationContext.assets
                val bundled = assets.list(ROOT).orEmpty().associateWith { "$ROOT/$it" }
                val reference = "emoji/reference"
                bundled + assets.list(reference).orEmpty().associateWith { "$reference/$it" }
            }.getOrDefault(emptyMap()).also { availableFiles = it }
        }
    }
}
