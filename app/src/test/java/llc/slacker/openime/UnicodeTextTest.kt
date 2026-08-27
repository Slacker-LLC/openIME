package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Test

class UnicodeTextTest {

    @Test
    fun dropsBmpCharacterAsOneUtf16Unit() {
        assertEquals("ab", dropLastCodePointSafe("abc"))
        assertEquals(1, previousCodePointUtf16Length("abc"))
    }

    @Test
    fun dropsEmojiSurrogatePairAsOneCodePoint() {
        assertEquals("a", dropLastCodePointSafe("a😀"))
        assertEquals(2, previousCodePointUtf16Length("a😀"))
    }

    @Test
    fun dropsSupplementaryHanAsOneCodePoint() {
        assertEquals("汉", dropLastCodePointSafe("汉𠀀"))
        assertEquals(2, previousCodePointUtf16Length("汉𠀀"))
    }

    @Test
    fun isolatedSurrogateDoesNotConsumeNeighbor() {
        assertEquals("a", dropLastCodePointSafe("a\uDC00"))
        assertEquals(1, previousCodePointUtf16Length("a\uDC00"))
    }

    @Test
    fun emptyTextHasNoPreviousCodePoint() {
        assertEquals("", dropLastCodePointSafe(""))
        assertEquals(0, previousCodePointUtf16Length(""))
        assertEquals(0, previousCodePointUtf16Length(null))
    }
}
