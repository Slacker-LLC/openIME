package llc.slacker.openime

import android.content.Context

/** Resolves bundled Microsoft Fluent Emoji 3D assets by their Unicode sequence. */
object FluentEmojiAssetRepository {
    private const val ROOT = "emoji/fluent"

    fun pathFor(context: Context, emoji: String): String? {
        val key = emoji.codePoints()
            .toArray()
            .joinToString("_") { it.toString(16) }
        val path = "$ROOT/$key.png"
        return runCatching {
            context.assets.open(path).use { path }
        }.getOrNull()
    }
}
