package llc.slacker.openime.engine

import java.nio.ByteBuffer

/**
 * Read-only view of bigram.bin: word pair scores on the lexicon's word ids.
 *
 * Layout: u32 magic, u32 pair count, then (8-byte aligned) a sorted u64 key
 * array (`idA << 32 | idB`) and a parallel u16 score array.
 */
internal class BigramIndex(private val buffer: ByteBuffer) {
    private val pairCount: Int
    private val offKeys: Int
    private val offScores: Int

    init {
        require(buffer.getInt(0) == MAGIC) { "not a bigram.bin table" }
        pairCount = buffer.getInt(4)
        offKeys = align8(8)
        offScores = align8(offKeys + pairCount * 8)
    }

    val size: Int get() = pairCount

    /** 0 when the pair is unknown. */
    fun score(idA: Int, idB: Int): Int {
        val key = (idA.toLong() shl 32) or (idB.toLong() and 0xFFFFFFFFL)
        var low = 0
        var high = pairCount
        while (low < high) {
            val middle = (low + high) ushr 1
            val value = buffer.getLong(offKeys + middle * 8)
            val order = java.lang.Long.compareUnsigned(value, key)
            when {
                order < 0 -> low = middle + 1
                order > 0 -> high = middle
                else -> return buffer.getShort(offScores + middle * 2).toInt() and 0xFFFF
            }
        }
        return 0
    }

    private fun align8(value: Int): Int = (value + 7) and 7.inv()

    companion object {
        const val MAGIC = 0x31424D49 // "IMB1" little-endian
    }
}
