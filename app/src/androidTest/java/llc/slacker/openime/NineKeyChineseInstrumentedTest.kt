package llc.slacker.openime

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NineKeyChineseInstrumentedTest {

    private lateinit var harness: DirectActivityHarness<DebugKeyboardActivity>

    @Before
    fun launch() {
        harness = DirectActivityHarness(DebugKeyboardActivity::class.java)
        harness.launch()
        enterChineseNineKey()
    }

    @After
    fun close() {
        harness.close()
    }

    @Test
    fun niHaoSequenceKeepsStablePreedit() {
        tapDigits("64")
        assertComposition("ni")

        tapDigits("426")
        assertComposition("nihao")
    }

    @Test
    fun haoDoesNotResolveAsGong() {
        tapDigits("426")
        assertComposition("hao")
        assertStatusDoesNotContain("gong")
    }

    @Test
    fun correctedCommonMappingsReachTheRenderer() {
        tapDigits("943")
        assertComposition("zhe")
    }

    @Test
    fun ambiguousRailShowsInputtablePinyinAndSelectionKeepsCompositionActive() {
        tapDigits("64")

        val paths = harness.awaitMain { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@awaitMain emptyList()
            val content = findView(root) { it.tag == "nine-pinyin-panel" } as? ViewGroup
                ?: return@awaitMain emptyList()
            (0 until content.childCount).mapNotNull { index ->
                (content.getChildAt(index) as? TextView)?.text?.toString()
            }
        }
        // While composing, the rail is the reading list ("nine-pinyin-panel"),
        // not the punctuation stack.
        assertTrue("64 must keep ni as an available reading: $paths", "ni" in paths)
        assertTrue("64 must keep mi as an available reading: $paths", "mi" in paths)
        assertFalse("64 must not show the non-Pinyin spelling oh: $paths", "oh" in paths)

        val selected = harness.awaitMain { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@awaitMain false
            val content = findView(root) { it.tag == "nine-pinyin-panel" } as? ViewGroup
                ?: return@awaitMain false
            val choice = (0 until content.childCount)
                .mapNotNull { content.getChildAt(it) as? TextView }
                .firstOrNull { it.text.toString() == "mi" }
                ?: return@awaitMain false
            choice.performClick()
        }
        assertTrue("The mi path must be independently selectable", selected)
        assertComposition("mi")
    }

    private fun enterChineseNineKey() {
        harness.awaitMain<View> { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@awaitMain null
            findView(root) { it.tag == "keyboard-selector" && it.isShown }?.also {
                it.performClick()
            }
        }
        harness.awaitMain<View> { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@awaitMain null
            findView(root) {
                it.isShown && it.contentDescription?.toString()?.startsWith("拼音 9 键") == true
            }?.also {
                it.performClick()
            }
        }
        harness.awaitMain { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@awaitMain null
            root.findViewWithTag<View>("key-9:2")?.takeIf { it.isShown }
        }
    }

    private fun findView(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findView(root.getChildAt(index), predicate)?.let { return it }
            }
        }
        return null
    }

    private fun tapDigits(digits: String) {
        digits.forEach { digit ->
            harness.awaitMain<View> { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@awaitMain null
                val key = root.findViewWithTag<View>("key-9:$digit") ?: return@awaitMain null
                if (!key.isShown) return@awaitMain null
                key.performClick()
                key
            }
        }
    }

    private fun assertComposition(expected: String) {
        val status = harness.awaitMain { activity ->
            currentStatus(activity).takeIf { it.startsWith("composition=$expected ") }
        }
        assertTrue("expected composition=$expected, got $status", status.startsWith("composition=$expected "))
    }

    private fun assertStatusDoesNotContain(unexpected: String) {
        val status = harness.awaitMain { activity -> currentStatus(activity).takeIf { it.startsWith("composition=") } }
        assertTrue("unexpected $unexpected in $status", !status.contains(unexpected))
    }

    private fun currentStatus(activity: DebugKeyboardActivity): String {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return ""
        var status = ""
        fun walk(view: View) {
            if (view is TextView) {
                val text = view.text.toString()
                if (text.startsWith("composition=")) status = text
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
        return status
    }
}
