package llc.slacker.openime

import llc.slacker.openime.voice.VoiceModelArchive
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VoiceModelArchiveTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun writeOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
        val text = value.toString(8).padStart(length - 1, '0') + "\u0000"
        text.toByteArray(Charsets.US_ASCII).copyInto(target, offset)
    }

    private fun tarOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        entries.forEach { (name, bytes) ->
            val header = ByteArray(512)
            name.toByteArray(Charsets.UTF_8).copyInto(header, 0, 0, minOf(100, name.length))
            writeOctal(header, 100, 8, 420L)
            writeOctal(header, 108, 8, 0L)
            writeOctal(header, 116, 8, 0L)
            writeOctal(header, 124, 12, bytes.size.toLong())
            writeOctal(header, 136, 12, 0L)
            header[156] = '0'.code.toByte()
            "ustar\u000000".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
            out.write(header)
            out.write(bytes)
            val padding = (512 - bytes.size % 512) % 512
            out.write(ByteArray(padding))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun staging(): File = Files.createTempDirectory("voice-archive-test").toFile()

    @Test
    fun extractsZipWithNestedDirectories() {
        val dir = staging()
        try {
            val bytes = zipOf(
                "model/tokens.txt" to "a\n".toByteArray(),
                "model/encoder.onnx" to ByteArray(700) { 1 },
            )
            val total = VoiceModelArchive.extract(bytes.inputStream(), VoiceModelArchive.Format.ZIP, dir)
            assertEquals(702, total.toInt())
            assertArrayEquals(ByteArray(700) { 1 }, File(dir, "model/encoder.onnx").readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsZipPathTraversal() {
        val dir = staging()
        try {
            val bytes = zipOf("../evil.txt" to "x".toByteArray())
            try {
                VoiceModelArchive.extract(bytes.inputStream(), VoiceModelArchive.Format.ZIP, dir)
                fail("expected a traversal rejection")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("非法路径"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun extractsPlainGzippedAndBzip2Tar() {
        val payload = "tokens\n".toByteArray()
        val tar = tarOf("pkg/tokens.txt" to payload, "pkg/joiner.onnx" to ByteArray(3) { 2 })
        for ((format, bytes) in listOf(
            VoiceModelArchive.Format.TAR to tar,
            VoiceModelArchive.Format.TAR_GZ to gzip(tar),
            VoiceModelArchive.Format.TAR_BZ2 to bzip2(tar),
        )) {
            val dir = staging()
            try {
                VoiceModelArchive.extract(bytes.inputStream(), format, dir)
                assertArrayEquals(payload, File(dir, "pkg/tokens.txt").readBytes())
                assertArrayEquals(ByteArray(3) { 2 }, File(dir, "pkg/joiner.onnx").readBytes())
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun enforcesTheSizeCap() {
        val dir = staging()
        try {
            val bytes = zipOf("big.onnx" to ByteArray(4096))
            try {
                VoiceModelArchive.extract(bytes.inputStream(), VoiceModelArchive.Format.ZIP, dir, maxBytes = 1024)
                fail("expected the size cap to trip")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("上限"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sniffsFormatsFromMagicBytes() {
        assertEquals(VoiceModelArchive.Format.ZIP, VoiceModelArchive.sniff(zipOf("a" to ByteArray(1))))
        assertEquals(VoiceModelArchive.Format.TAR_GZ, VoiceModelArchive.sniff(gzip(ByteArray(512))))
        assertEquals(VoiceModelArchive.Format.TAR, VoiceModelArchive.sniff(tarOf("a" to ByteArray(1))))
        // `BZh9` is how the official model archives open; a name-less SAF pick
        // must still recognise them.
        assertEquals(
            VoiceModelArchive.Format.TAR_BZ2,
            VoiceModelArchive.sniff("BZh9".toByteArray(Charsets.US_ASCII) + ByteArray(508)),
        )
        assertEquals(null, VoiceModelArchive.sniff("BZh0".toByteArray(Charsets.US_ASCII) + ByteArray(508)))
        assertEquals(null, VoiceModelArchive.sniff("not an archive".toByteArray()))
    }

    @Test
    fun formatsComeFromFileNames() {
        assertEquals(VoiceModelArchive.Format.ZIP, VoiceModelArchive.formatOf("m.zip"))
        assertEquals(VoiceModelArchive.Format.TAR_GZ, VoiceModelArchive.formatOf("m.tgz"))
        assertEquals(VoiceModelArchive.Format.TAR_GZ, VoiceModelArchive.formatOf("m.tar.gz"))
        assertEquals(VoiceModelArchive.Format.TAR, VoiceModelArchive.formatOf("m.tar"))
        // The official sherpa-onnx voice-model releases are published as tar.bz2.
        for (name in listOf("m.tar.bz2", "m.tbz2", "m.tbz", "m.TAR.BZ2")) {
            assertEquals(name, VoiceModelArchive.Format.TAR_BZ2, VoiceModelArchive.formatOf(name))
        }
    }

    /** The decompressor for the format the official model releases ship. */
    @Test
    fun bzip2ImportKeepsTheArchivesSecurityChecks() {
        val dir = staging()
        try {
            val escape = tarOf("../escaped.txt" to "x".toByteArray())
            try {
                VoiceModelArchive.extract(
                    bzip2(escape).inputStream(),
                    VoiceModelArchive.Format.TAR_BZ2,
                    dir,
                )
                fail("expected the traversal check to trip")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("非法路径"))
            }
            try {
                VoiceModelArchive.extract(
                    bzip2(tarOf("big.onnx" to ByteArray(4096))).inputStream(),
                    VoiceModelArchive.Format.TAR_BZ2,
                    dir,
                    maxBytes = 1024,
                )
                fail("expected the size cap to trip")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("上限"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun bzip2(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        BZip2CompressorOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
