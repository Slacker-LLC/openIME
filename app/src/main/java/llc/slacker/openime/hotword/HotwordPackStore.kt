package llc.slacker.openime.hotword

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Where hotword packs live: bundled in `assets/hotwords/`, imported into the
 * app-private `files/hotwords/`, and the per-pack on/off switches.
 *
 * Nothing here touches the network. Imports are copied into private storage in
 * the canonical format, so the original file can be moved or deleted.
 */
internal class HotwordPackStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val importDir get() = File(appContext.filesDir, IMPORT_DIR)

    sealed interface ImportResult {
        data class Imported(val pack: HotwordPack, val truncated: Boolean) : ImportResult
        data class Failed(val reason: String) : ImportResult
    }

    /** Bundled packs first (on-by-default ones ahead of opt-in ones), then imports in the order added. */
    fun packs(): List<HotwordPack> = bundledPacks() + importedPacks()

    fun isEnabled(pack: HotwordPack): Boolean =
        prefs.getBoolean(enabledKey(pack.id), pack.defaultEnabled)

    fun setEnabled(pack: HotwordPack, enabled: Boolean) {
        prefs.edit().putBoolean(enabledKey(pack.id), enabled).apply()
    }

    fun enabledWords(): List<String> =
        packs().filter(::isEnabled).flatMap { it.words }.distinct()

    fun importFrom(uri: Uri): ImportResult {
        val text = try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytes(HotwordParser.MAX_BYTES + 1)
                if (bytes.size > HotwordParser.MAX_BYTES) {
                    return ImportResult.Failed("文件超过 ${HotwordParser.MAX_BYTES / 1024} KB，请拆分后再导入。")
                }
                String(bytes, Charsets.UTF_8)
            } ?: return ImportResult.Failed("无法打开这个文件。")
        } catch (error: Exception) {
            return ImportResult.Failed("读取文件失败：${error.message.orEmpty()}")
        }

        val parsed = HotwordParser.parse(text, fallbackTitle = displayName(uri))
        if (parsed.words.isEmpty()) {
            return ImportResult.Failed(
                "没有找到可用的词。词表每行一个词，只支持 2 到 ${HotwordParser.MAX_WORD_LENGTH} 个汉字的词。",
            )
        }

        val id = IMPORT_PREFIX + java.lang.Long.toString(System.currentTimeMillis(), 36)
        return try {
            importDir.mkdirs()
            val target = File(importDir, "$id.txt")
            val temp = File(importDir, "$id.tmp")
            temp.writeText(HotwordParser.render(parsed), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                temp.delete()
                return ImportResult.Failed("保存词表失败。")
            }
            ImportResult.Imported(toPack(id, parsed, HotwordPack.Origin.IMPORTED), parsed.truncated)
        } catch (error: Exception) {
            ImportResult.Failed("保存词表失败：${error.message.orEmpty()}")
        }
    }

    /** Only imported packs can be removed; bundled packs can only be switched off. */
    fun delete(pack: HotwordPack): Boolean {
        if (pack.origin != HotwordPack.Origin.IMPORTED) return false
        prefs.edit().remove(enabledKey(pack.id)).apply()
        return File(importDir, "${pack.id}.txt").delete()
    }

    private fun bundledPacks(): List<HotwordPack> {
        val names = appContext.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".txt") }.sorted()
        val packs = names.mapNotNull { name ->
            runCatching {
                val text = appContext.assets.open("$ASSET_DIR/$name").use { String(it.readBytes(), Charsets.UTF_8) }
                val id = BUNDLED_PREFIX + name.removeSuffix(".txt")
                toPack(id, HotwordParser.parse(text, name.removeSuffix(".txt")), HotwordPack.Origin.BUNDLED)
            }.getOrNull()
        }
        return packs.sortedWith(compareByDescending<HotwordPack> { it.defaultEnabled }.thenBy { it.id })
    }

    private fun importedPacks(): List<HotwordPack> {
        val files = importDir.listFiles { file -> file.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }
        return files.mapNotNull { file ->
            runCatching {
                val id = file.name.removeSuffix(".txt")
                toPack(id, HotwordParser.parse(file.readText(Charsets.UTF_8), id), HotwordPack.Origin.IMPORTED)
            }.getOrNull()
        }
    }

    private fun toPack(id: String, parsed: HotwordParser.Parsed, origin: HotwordPack.Origin) = HotwordPack(
        id = id,
        title = parsed.title,
        description = parsed.description,
        origin = origin,
        // An import is something the user just asked for: start it switched on.
        defaultEnabled = if (origin == HotwordPack.Origin.IMPORTED) true else parsed.defaultEnabled,
        words = parsed.words,
        rejectedLines = parsed.rejectedLines,
    )

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        val raw = fromProvider ?: uri.lastPathSegment ?: "导入词表"
        return raw.substringBeforeLast('.').ifBlank { "导入词表" }
    }

    private fun enabledKey(id: String) = "enabled:$id"

    private companion object {
        const val PREFS = "hotword_packs"
        const val ASSET_DIR = "hotwords"
        const val IMPORT_DIR = "hotwords"
        const val BUNDLED_PREFIX = "bundled-"
        const val IMPORT_PREFIX = "user-"
    }
}
