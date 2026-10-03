package llc.slacker.openime

import llc.slacker.openime.widget.SwipeUpDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeUpDetectorTest {
    private fun detector() = SwipeUpDetector(thresholdPx = 50f).also { it.down(100f, 300f) }

    @Test
    fun firesOnceWhenTheFingerTravelsFarEnoughUp() {
        val d = detector()
        assertFalse(d.move(100f, 280f))
        assertTrue(d.move(102f, 240f))
        assertFalse("must not fire twice in one gesture", d.move(102f, 200f))
    }

    @Test
    fun ignoresSidewaysAndDownwardMovement() {
        assertFalse(detector().move(220f, 290f))
        assertFalse(detector().move(100f, 400f))
        // Far up, but even further sideways: a drag, not a swipe up.
        assertFalse(detector().move(300f, 240f))
    }

    @Test
    fun aNewDownStartsANewGesture() {
        val d = detector()
        assertTrue(d.move(100f, 240f))
        d.down(100f, 300f)
        assertTrue(d.move(100f, 240f))
    }
}
