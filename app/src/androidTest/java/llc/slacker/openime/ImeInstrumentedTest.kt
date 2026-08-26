package llc.slacker.openime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeInstrumentedTest {
    @Test
    fun engineProducesNihaoOnDevice() {
        val engine = CandidateEngine()
        assertTrue(engine.getCandidates("nihao").contains("你好"))
    }
}
