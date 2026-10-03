package llc.slacker.openime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.data.QuickPhraseRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickPhraseRepositoryInstrumentedTest {
    private val context =
        ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun addEditCategoryLongTextPersistAndDeleteRoundTrip() {
        val marker = "quick-phrase-roundtrip-${System.nanoTime()}"
        val longText = "$marker " + "长文本".repeat(120)
        var id: Long? = null
        try {
            val added = QuickPhraseRepository.upsert(
                context = context,
                id = 0L,
                category = "  测试分类  ",
                text = "  $marker  ",
            )
            assertNotNull(added)
            id = added!!.id
            assertEquals("测试分类", added.category)
            assertEquals(marker, added.text)

            val persisted = QuickPhraseRepository.load(context)
                .single { it.id == added.id }
            assertEquals(added, persisted)

            val edited = QuickPhraseRepository.upsert(
                context = context,
                id = added.id,
                category = "长文本分类",
                text = longText,
            )
            assertEquals(added.id, edited!!.id)
            assertEquals("长文本分类", edited.category)
            assertEquals(longText, edited.text)
            assertEquals(
                longText,
                QuickPhraseRepository.load(context).single { it.id == added.id }.text,
            )
        } finally {
            id?.let { QuickPhraseRepository.remove(context, it) }
        }
        assertTrue(
            "Deleted quick phrase must not survive a subsequent repository load",
            QuickPhraseRepository.load(context).none { it.id == id },
        )
    }

    @Test
    fun blankTextIsRejectedAndBlankCategoryFallsBackToCommon() {
        val marker = "quick-phrase-category-${System.nanoTime()}"
        assertNull(QuickPhraseRepository.upsert(context, 0L, "测试", "   "))

        val added = QuickPhraseRepository.upsert(context, 0L, "   ", marker)
        assertNotNull(added)
        try {
            assertEquals("常用", added!!.category)
            assertEquals(
                "常用",
                QuickPhraseRepository.load(context).single { it.id == added.id }.category,
            )
        } finally {
            QuickPhraseRepository.remove(context, added!!.id)
        }
    }

    @Test
    fun duplicateTextKeepsIndependentIdsAndDeleteTargetsOnlyOneEntry() {
        val text = "duplicate-${System.nanoTime()}"
        val first = requireNotNull(
            QuickPhraseRepository.upsert(context, 0L, "分类一", text),
        )
        val second = requireNotNull(
            QuickPhraseRepository.upsert(context, 0L, "分类二", text),
        )
        try {
            assertNotEquals(first.id, second.id)
            assertEquals(
                setOf(first.id, second.id),
                QuickPhraseRepository.load(context)
                    .filter { it.text == text }
                    .map { it.id }
                    .toSet(),
            )

            QuickPhraseRepository.remove(context, first.id)

            val remaining = QuickPhraseRepository.load(context)
                .filter { it.text == text }
            assertEquals(1, remaining.size)
            assertEquals(second.id, remaining.single().id)
            assertEquals("分类二", remaining.single().category)
        } finally {
            QuickPhraseRepository.remove(context, first.id)
            QuickPhraseRepository.remove(context, second.id)
        }
    }
}
