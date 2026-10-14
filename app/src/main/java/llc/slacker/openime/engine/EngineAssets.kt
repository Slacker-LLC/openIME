package llc.slacker.openime.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * File access for the compiled engine tables (lex.bin, bigram.bin under the
 * imengine asset directory).
 *
 * The tables are built locally by scripts/build_imengine_data.py and are not
 * part of the repository. Assets cannot be mapped from inside the APK, so the
 * first use copies them into the app's files directory (same pattern as the
 * Rime data) and later starts map them read-only.
 */
internal object EngineAssets {
    const val ASSET_DIR = "imengine"
    private const val LEX = "lex.bin"
    private const val BIGRAM = "bigram.bin"

    fun lexFile(context: Context): File? = materialize(context, LEX)

    fun bigramFile(context: Context): File? = materialize(context, BIGRAM)

    fun present(context: Context): Boolean =
        runCatching { context.assets.open("$ASSET_DIR/$LEX").close() }.isSuccess

    private fun materialize(context: Context, name: String): File? = runCatching {
        val dir = File(context.filesDir, ASSET_DIR)
        val target = File(dir, name)
        val stamp = File(dir, "$name.rev")
        // The assets are compressed in the APK, so openFd is not usable; the
        // small revision asset records what the tables should look like.
        val revision = context.assets.open("$ASSET_DIR/revision.txt")
            .bufferedReader().use { it.readText() }.trim()
        if (target.isFile && stamp.isFile && stamp.readText().trim() == revision) {
            return@runCatching target
        }
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        context.assets.open("$ASSET_DIR/$name").use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output, 1 shl 16) }
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        stamp.writeText(revision)
        target
    }.getOrNull()

    fun map(file: File): ByteBuffer {
        RandomAccessFile(file, "r").use { raf ->
            return raf.channel
                .map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                .order(ByteOrder.LITTLE_ENDIAN)
        }
    }
}
