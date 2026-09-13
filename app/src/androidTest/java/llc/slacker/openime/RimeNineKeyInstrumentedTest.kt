package llc.slacker.openime

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RimeNineKeyInstrumentedTest {

    @Test
    fun nativeRimeDecodesNineKeyDigitsAndSegmentBoundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rime = RimeEngine(context)
        try {
            rime.start()
            val deadline = SystemClock.elapsedRealtime() + 120_000L
            while (!rime.isReady && rime.errorMessage.isBlank() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100L)
            }
            assertTrue("librime failed to start: ${rime.errorMessage}", rime.isReady)

            val continuous = rime.candidates("64426")
            assertTrue("64426 should resolve 你好, got ${continuous.take(12)}", "你好" in continuous)

            val segmented = rime.candidates("64'426")
            assertTrue("64'426 should resolve 你好, got ${segmented.take(12)}", "你好" in segmented)
        } finally {
            rime.shutdown()
        }
    }
}
