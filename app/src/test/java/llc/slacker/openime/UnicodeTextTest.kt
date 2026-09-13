package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Test

class UnicodeTextTest {

    @Test
    fun bmpCharactersUseOneUtf16Unit() {
        assertEquals("ab", dropLastCodePointSafe("abc"))
        assertEquals(1, previousCodePointUtf16Length("abc"))
        assertEquals(1, nextCodePointUtf16Length("abc"))
    }

    @Test
    fun emojiSurrogatePairUsesTwoUtf16UnitsInBothDirections() {
        assertEquals("a", dropLastCodePointSafe("a😀"))
        assertEquals(2, previousCodePointUtf16Length("a😀"))
        assertEquals(2, nextCodePointUtf16Length("😀a"))
    }

    @Test
    fun supplementaryHanUsesTwoUtf16UnitsInBothDirections() {
        assertEquals("汉", dropLastCodePointSafe("汉𠀀"))
        assertEquals(2, previousCodePointUtf16Length("汉𠀀"))
        assertEquals(2, nextCodePointUtf16Length("𠀀汉"))
    }

    @Test
    fun isolatedSurrogatesDoNotConsumeNeighbor() {
        assertEquals("a", dropLastCodePointSafe("a\uDC00"))
        assertEquals(1, previousCodePointUtf16Length("a\uDC00"))
        assertEquals(1, nextCodePointUtf16Length("\uD83Da"))
    }

    @Test
    fun emptyTextHasNoAdjacentCodePoint() {
        assertEquals("", dropLastCodePointSafe(""))
        assertEquals(0, previousCodePointUtf16Length(""))
        assertEquals(0, previousCodePointUtf16Length(null))
        assertEquals(0, nextCodePointUtf16Length(""))
        assertEquals(0, nextCodePointUtf16Length(null))
    }
}
