package llc.slacker.openime.engine

import java.nio.ByteBuffer

/**
 * Read-only view of lex.bin: every word sorted by its syllable id sequence.
 *
 * Layout (little-endian, see scripts/build_imengine_data.py):
 * header u32[10], then the syllable table (u8 length + ASCII bytes per entry),
 * u32 syllable offsets per word (first element index inside the id array),
 * u16 syllable ids, u32 byte text offsets, the UTF-8 text blob and u16 scores,
 * each section aligned.
 */
internal class LexiconIndex(private val buffer: ByteBuffer) {
    private val wordCount: Int
    private val syllableCount: Int
    private val offSyllables: Int
    private val offSylOffsets: Int
    private val offSylIds: Int
    private val offTextOffsets: Int
    private val offTextBlob: Int
    private val offScores: Int

    val syllables: List<String>

    init {
        require(buffer.getInt(0) == MAGIC) { "not a lex.bin table" }
        wordCount = buffer.getInt(4)
        syllableCount = buffer.getInt(8)
        offSyllables = buffer.getInt(12)
        offSylOffsets = buffer.getInt(16)
        offSylIds = buffer.getInt(20)
        offTextOffsets = buffer.getInt(24)
        offTextBlob = buffer.getInt(28)
        offScores = buffer.getInt(32)
        syllables = readSyllables()
    }

    val size: Int get() = wordCount

    /** The syllable ids of [word] are written into [out]; returns their count. */
    fun sequenceOf(word: Int, out: IntArray): Int {
        val start = buffer.getInt(offSylOffsets + word * 4)
        val end = buffer.getInt(offSylOffsets + (word + 1) * 4)
        val count = minOf(end - start, out.size)
        for (index in 0 until count) {
            out[index] = buffer.getShort(offSylIds + (start + index) * 2).toInt() and 0xFFFF
        }
        return count
    }

    fun syllableLengthOf(word: Int): Int =
        buffer.getInt(offSylOffsets + (word + 1) * 4) - buffer.getInt(offSylOffsets + word * 4)

    fun syllableAt(word: Int, index: Int): Int =
        buffer.getShort(offSylIds + (buffer.getInt(offSylOffsets + word * 4) + index) * 2).toInt() and 0xFFFF

    fun textOf(word: Int): String {
        val start = buffer.getInt(offTextOffsets + word * 4)
        val end = buffer.getInt(offTextOffsets + (word + 1) * 4)
        if (end <= start) return ""
        val bytes = ByteArray(end - start)
        val view = buffer.duplicate()
        view.position(offTextBlob + start)
        view.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun scoreOf(word: Int): Int = buffer.getShort(offScores + word * 2).toInt() and 0xFFFF

    /**
     * First word whose sequence is >= [ids]; words that continue the sequence
     * sort after it, so this is also the lower bound of a prefix scan.
     */
    fun lowerBound(ids: IntArray, length: Int): Int {
        var low = 0
        var high = wordCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (compare(middle, ids, length) < 0) low = middle + 1 else high = middle
        }
        return low
    }

    /** First word after every word that equals [ids] exactly. */
    fun exactEnd(ids: IntArray, length: Int): Int {
        var low = 0
        var high = wordCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (compare(middle, ids, length) <= 0) low = middle + 1 else high = middle
        }
        return low
    }

    /** First word after every word whose sequence starts with [ids]. */
    fun prefixEnd(ids: IntArray, length: Int): Int {
        var low = 0
        var high = wordCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (startsWith(middle, ids, length)) low = middle + 1 else high = middle
        }
        return low
    }

    internal fun compare(word: Int, ids: IntArray, length: Int): Int {
        val wordLength = syllableLengthOf(word)
        val common = minOf(wordLength, length)
        for (index in 0 until common) {
            val a = syllableAt(word, index)
            val b = ids[index]
            if (a != b) return a - b
        }
        return wordLength - length
    }

    internal fun startsWith(word: Int, ids: IntArray, length: Int): Boolean {
        if (length > syllableLengthOf(word)) return false
        for (index in 0 until length) {
            if (syllableAt(word, index) != ids[index]) return false
        }
        return true
    }

    private fun readSyllables(): List<String> {
        val out = ArrayList<String>(syllableCount)
        var cursor = offSyllables
        repeat(syllableCount) {
            val length = buffer.get(cursor).toInt() and 0xFF
            cursor++
            val bytes = ByteArray(length)
            val view = buffer.duplicate()
            view.position(cursor)
            view.get(bytes)
            out += String(bytes, Charsets.US_ASCII)
            cursor += length
        }
        return out
    }

    companion object {
        const val MAGIC = 0x31454D49 // "IME1" little-endian
    }
}
