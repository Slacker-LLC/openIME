package llc.slacker.openime

import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Owns the Chinese 9-key side symbol rail and ambiguity filter presentation.
 *
 * Candidate decoding remains in CandidatePipeline/Rime. This controller only
 * reflects the cached ambiguity paths and the user's explicit path choice.
 */
internal object NineKeySymbolRailController {
    private const val NINE_RAIL_TAG = "nine-punct-stack"
    private const val NINE_CONTENT_TAG = "nine-symbol-scroll-content"
    private const val FILTER_TAG = "nine-pinyin-path-filter"
    private const val WATCHER_TAG = 0x1F000081

    fun decorate(
        root: View,
        onCommit: (String) -> Unit,
        onFeedback: () -> Unit,
    ) {
        val existingHeader = root
            .findViewWithTag<ScrollView>(NINE_RAIL_TAG)
            ?.getChildAt(0)
            ?.let { it as? LinearLayout }
            ?.findViewWithTag<TextView>(FILTER_TAG)

        SymbolRailRenderer.decorate(
            root = root,
            sourceTag = NINE_RAIL_TAG,
            railTag = NINE_RAIL_TAG,
            contentTag = NINE_CONTENT_TAG,
            contentDescription = "九键常用符号，上下滑动查看更多",
            symbols = commonSymbols(root),
            tagPrefix = "punct:",
            preservedHeader = existingHeader,
            onCommit = onCommit,
            onFeedback = onFeedback,
        )

        installFilterWatcher(root, onFeedback)
        refreshPinyinFilters(root, onFeedback)
    }

    private fun installFilterWatcher(root: View, onFeedback: () -> Unit) {
        val editor = root.findViewWithTag<EditText>("pinyin-composition-editor") ?: return
        if (editor.getTag(WATCHER_TAG) != null) return

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(s: Editable?) {
                root.post { refreshPinyinFilters(root, onFeedback) }
            }
        }
        editor.addTextChangedListener(watcher)
        editor.setTag(WATCHER_TAG, watcher)
    }

    private fun refreshPinyinFilters(root: View, onFeedback: () -> Unit) {
        val scroll = root.findViewWithTag<ScrollView>(NINE_RAIL_TAG) ?: return
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        if (root.findViewWithTag<View>("pinyin9-layout") == null) {
            content.findViewWithTag<View>(FILTER_TAG)?.let(content::removeView)
            return
        }

        val editor = root.findViewWithTag<EditText>("pinyin-composition-editor") ?: return
        val text = editor.text?.toString().orEmpty()
        if (text.isBlank()) {
            setPinyinFilters(root, emptyList(), null, onFeedback) {}
            return
        }

        val lastSpace = text.lastIndexOf(' ')
        val prefix = if (lastSpace >= 0) text.substring(0, lastSpace + 1) else ""
        val suffix = if (lastSpace >= 0) text.substring(lastSpace + 1) else text
        val digits = CandidatePipeline.nineKeyDigitsFor(suffix)
        val code = digits?.let { NineKeyLocalDecoder.nativeCode(prefix, it) }
        if (code.isNullOrEmpty()) {
            setPinyinFilters(root, emptyList(), null, onFeedback) {}
            return
        }

        val resolver = root.context as? CandidateResolver ?: return
        val choices = resolver.nineKeyPathsFor(code)
        val selected = resolver.selectedNineKeyPathFor(code) ?: text
        setPinyinFilters(
            root = root,
            filters = choices,
            selected = selected,
            onFeedback = onFeedback,
        ) { chosen ->
            resolver.selectNineKeyPath(code, chosen)
            if (chosen == editor.text?.toString()) return@setPinyinFilters
            editor.setText(chosen)
            editor.setSelection(chosen.length)
        }
    }

    fun setPinyinFilters(
        root: View,
        filters: List<String>,
        selected: String?,
        onFeedback: () -> Unit = {},
        onSelect: (String) -> Unit,
    ) {
        val scroll = root.findViewWithTag<ScrollView>(NINE_RAIL_TAG) ?: return
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        val choices = filters
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(8)
            .toList()

        val existing = content.findViewWithTag<TextView>(FILTER_TAG)
        if (choices.size <= 1) {
            if (existing != null) content.removeView(existing)
            return
        }

        var active = selected?.takeIf { it in choices } ?: choices.first()
        val view = existing ?: TextView(content.context).apply {
            tag = FILTER_TAG
            textSize = 12f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minimumHeight = SymbolRailRenderer.cellHeightPx(content.context)
            val inherited = (0 until content.childCount)
                .asSequence()
                .mapNotNull { content.getChildAt(it) as? TextView }
                .firstOrNull { it.tag != FILTER_TAG }
                ?.currentTextColor
            inherited?.let(::setTextColor)
            content.addView(
                this,
                0,
                SymbolRailRenderer.cellParams(content.context, withGap = true),
            )
        }

        fun updatePresentation() {
            view.text = active.replace(" ", "·") + " ›"
            view.contentDescription =
                "九键拼音筛选，当前${active.replace(" ", "、")}，点击切换"
            if (Build.VERSION.SDK_INT >= 30) {
                view.stateDescription = "当前${active.replace(" ", "、")}"
            }
        }

        updatePresentation()
        view.setOnClickListener {
            onFeedback()
            val current = choices.indexOf(active).coerceAtLeast(0)
            active = choices[(current + 1) % choices.size]
            updatePresentation()
            onSelect(active)
        }

        if (scroll.scrollY != 0) scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun commonSymbols(root: View): List<String> =
        CustomSymbolRepository.load(root.context)
            .map { it.symbol }
            .filter { it.isNotBlank() } +
            ImeData.symbols["常用"].orEmpty()
                .asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
                .ifEmpty { listOf("，", "。", "？", "！") }
}
