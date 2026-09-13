package llc.slacker.openime

import android.content.Context

/** Resolves bundled Microsoft Fluent Emoji 3D assets by their Unicode sequence. */
object FluentEmojiAssetRepository {
    private const val ROOT = "emoji/fluent"

    @Volatile
    private var availableFiles: Set<String>? = null

    fun pathFor(context: Context, emoji: String): String? {
        val fileName = fileNameFor(emoji)
        return if (fileName in files(context)) "$ROOT/$fileName" else null
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
    private fun files(context: Context): Set<String> {
        availableFiles?.let { return it }
        return synchronized(this) {
            availableFiles ?: runCatching {
                context.applicationContext.assets.list(ROOT)?.toSet().orEmpty()
            }.getOrDefault(emptySet()).also { availableFiles = it }
        }
    }
}
