package llc.slacker.openime.voice

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Extracts a local voice-model package into a staging directory.
 *
 * Supported forms: .zip, .tar, .tar.gz / .tgz.  Every entry is checked for
 * path traversal and the whole archive is bounded, so a malicious package
 * cannot escape the staging directory or fill the disk. tar.bz2 is not
 * supported (no bzip2 decoder on board): the import UI asks the user to
 * repack it.
 */
internal object VoiceModelArchive {
    const val MAX_UNPACKED_BYTES = 1_500L * 1_048_576

    enum class Format { ZIP, TAR, TAR_GZ }

    fun formatOf(fileName: String): Format? = when {
        fileName.endsWith(".zip", ignoreCase = true) -> Format.ZIP
        fileName.endsWith(".tar.gz", ignoreCase = true) || fileName.endsWith(".tgz", ignoreCase = true) ->
            Format.TAR_GZ
        fileName.endsWith(".tar", ignoreCase = true) -> Format.TAR
        else -> null
    }

    /** Sniff the format from the first bytes; SAF pickers do not always give a name. */
    fun sniff(header: ByteArray): Format? {
        if (header.size >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
            return Format.ZIP
        }
        if (header.size >= 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()) {
            return Format.TAR_GZ
        }
        if (header.size >= 263 &&
            header[257] == 'u'.code.toByte() && header[258] == 's'.code.toByte() &&
            header[259] == 't'.code.toByte() && header[260] == 'a'.code.toByte() &&
            header[261] == 'r'.code.toByte()
        ) {
            return Format.TAR
        }
        return null
    }

    fun extract(
        input: InputStream,
        format: Format,
        destination: File,
        maxBytes: Long = MAX_UNPACKED_BYTES,
    ): Long {
        val canonicalRoot = destination.canonicalFile
        return when (format) {
            Format.ZIP -> extractZip(input, canonicalRoot, maxBytes)
            Format.TAR -> extractTar(input, canonicalRoot, maxBytes)
            Format.TAR_GZ -> GZIPInputStream(input).use { extractTar(it, canonicalRoot, maxBytes) }
        }
    }

    private fun targetFor(root: File, name: String): File {
        val normalized = name.replace('\\', '/').removePrefix("./")
        require(normalized.isNotBlank()) { "压缩包含有空路径" }
        val target = File(root, normalized).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "压缩包含有非法路径：$name"
        }
        return target
    }

    private fun extractZip(raw: InputStream, root: File, maxBytes: Long): Long {
        var total = 0L
        ZipInputStream(BufferedInputStream(raw)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = targetFor(root, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    total += copyBounded(zip, target, maxBytes, total)
                }
                zip.closeEntry()
            }
        }
        return total
    }

    private fun extractTar(raw: InputStream, root: File, maxBytes: Long): Long {
        val input = BufferedInputStream(raw, 64 * 1024)
        var total = 0L
        var pendingName: String? = null
        val header = ByteArray(TAR_HEADER)
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it.toInt() == 0 }) break
            var name = readName(header)
            val size = readOctal(header, SIZE_OFFSET, SIZE_LENGTH)
            val type = header[TYPE_OFFSET].toInt().toChar()
            when (type) {
                'L' -> {
                    // GNU long name: the payload is the next entry's name.
                    val longName = ByteArray(size.toInt().coerceAtLeast(0))
                    readFully(input, longName)
                    skipPadding(input, size)
                    pendingName = longName.toString(Charsets.UTF_8).trimEnd('\u0000')
                    continue
                }
                'x', 'g' -> {
                    val data = ByteArray(size.toInt().coerceAtLeast(0))
                    readFully(input, data)
                    skipPadding(input, size)
                    parsePaxPath(data)?.let { pendingName = it }
                    continue
                }
                '0', '\u0000', '5' -> Unit
                else -> {
                    skipFully(input, size)
                    skipPadding(input, size)
                    pendingName = null
                    continue
                }
            }
            if (pendingName != null) {
                name = pendingName!!
                pendingName = null
            }
            val target = targetFor(root, name)
            if (type == '5') {
                target.mkdirs()
                skipFully(input, size)
            } else {
                target.parentFile?.mkdirs()
                total += copyBounded(input, target, maxBytes, total, size)
            }
            skipPadding(input, size)
        }
        return total
    }

    private fun copyBounded(input: InputStream, target: File, maxBytes: Long, already: Long, limit: Long = Long.MAX_VALUE): Long {
        var written = 0L
        target.outputStream().buffered().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (written < limit) {
                val want = minOf(buffer.size.toLong(), limit - written).toInt()
                val n = input.read(buffer, 0, want)
                if (n < 0) break
                written += n
                require(already + written <= maxBytes) { "模型包解压后超过 ${maxBytes / 1_048_576} MB 上限" }
                out.write(buffer, 0, n)
            }
        }
        return written
    }

    private fun parsePaxPath(data: ByteArray): String? {
        val text = data.toString(Charsets.UTF_8)
        val line = text.lineSequence().firstOrNull { it.startsWith("path=") } ?: return null
        return line.removePrefix("path=").trimEnd('\n')
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) return offset == 0
            offset += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = (TAR_BLOCK - size % TAR_BLOCK) % TAR_BLOCK
        if (padding > 0) skipFully(input, padding)
    }

    private fun readName(header: ByteArray): String {
        val name = readString(header, NAME_OFFSET, NAME_LENGTH)
        val prefix = readString(header, PREFIX_OFFSET, PREFIX_LENGTH)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun readString(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && bytes[end].toInt() != 0) end++
        return bytes.copyOfRange(offset, end).toString(Charsets.UTF_8)
    }

    private fun readOctal(bytes: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (index in offset until offset + length) {
            val byte = bytes[index].toInt() and 0xFF
            if (byte == 0 || byte == ' '.code) continue
            val digit = byte - '0'.code
            if (digit in 0..7) value = value * 8 + digit
        }
        return value
    }

    private const val TAR_BLOCK = 512
    private const val TAR_HEADER = 512
    private const val NAME_OFFSET = 0
    private const val NAME_LENGTH = 100
    private const val SIZE_OFFSET = 124
    private const val SIZE_LENGTH = 12
    private const val TYPE_OFFSET = 156
    private const val PREFIX_OFFSET = 345
    private const val PREFIX_LENGTH = 155
}
