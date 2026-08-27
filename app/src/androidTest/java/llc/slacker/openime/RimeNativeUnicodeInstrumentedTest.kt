package llc.slacker.openime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RimeNativeUnicodeInstrumentedTest {
    @Test
    fun nativeBoundaryRoundTripsBmpAndNonBmpUnicode() {
        val value = "中文𠀀😀é"

        assertEquals(value, RimeNative.nativeUtf8RoundTripForTest(value))
    }
}
