package llc.slacker.openime

import android.content.res.Configuration
import llc.slacker.openime.floating.FloatingPositionMemory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingPositionMemoryTest {

    @Test
    fun eachOrientationKeepsItsOwnPosition() {
        val memory = FloatingPositionMemory()
        memory.save(Configuration.ORIENTATION_PORTRAIT, 20, 900)
        memory.save(Configuration.ORIENTATION_LANDSCAPE, 300, 120)

        assertArrayEquals(intArrayOf(20, 900), memory.restore(Configuration.ORIENTATION_PORTRAIT))
        assertArrayEquals(intArrayOf(300, 120), memory.restore(Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun unknownOrientationRestoresNothing() {
        val memory = FloatingPositionMemory()
        assertNull(memory.restore(Configuration.ORIENTATION_PORTRAIT))
        assertNull(memory.restore(Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun savedPositionsAreCopies() {
        val memory = FloatingPositionMemory()
        memory.save(Configuration.ORIENTATION_PORTRAIT, 10, 20)
        val restored = memory.restore(Configuration.ORIENTATION_PORTRAIT)!!
        restored[0] = 999
        assertArrayEquals(intArrayOf(10, 20), memory.restore(Configuration.ORIENTATION_PORTRAIT))
    }
}
