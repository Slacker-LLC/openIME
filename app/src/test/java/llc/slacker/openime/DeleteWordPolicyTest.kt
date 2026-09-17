package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteWordPolicyTest {
    @Test
    fun deletesLatinWordAndTrailingSpaces() {
        assertEquals(6, previousWordDeleteUtf16Length("hello "))
        assertEquals(5, previousWordDeleteUtf16Length("hello"))
        assertEquals(11, previousWordDeleteUtf16Length("hello_world"))
    }

    @Test
    fun cjkDeletesOneCodePointWithoutEatingSentence() {
        assertEquals(1, previousWordDeleteUtf16Length("你好"))
        assertEquals(2, previousWordDeleteUtf16Length("你好 "))
    }

    @Test
    fun punctuationDeletesOneSymbol() {
        assertEquals(1, previousWordDeleteUtf16Length("hello!"))
    }

    @Test
    fun supplementaryCodePointDeletesWholePair() {
        assertEquals(2, previousWordDeleteUtf16Length("🙂"))
    }
}
