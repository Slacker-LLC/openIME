package llc.slacker.openime

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Owns the Chinese 9-key side symbol rail and ambiguity filter presentation.
 *
 * The renderer receives the built rail directly. Composition updates also use
 * explicit editor/rail references rather than searching the whole View tree.
 */
internal class NineKeySymbolRailController(
    private val context: Context,
    private val composition: EditText,
    private val onCommit: (String) -> Unit,
    private val onFeedback: () -> Unit,
) {
    private var rail: ScrollView? = null

    init {
        composition.addTextChangedListener(object : TextWatcher {
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
                composition.post(::refreshPinyinFilters)
            }
        })
    }

    fun buildRail(): ScrollView =
        SymbolRailRenderer.build(
            context = context,
            railTag = NINE_RAIL_TAG,
            contentTag = NINE_CONTENT_TAG,
            contentDescription = "九键常用符号，上下滑动查看更多",
            symbols = commonSymbols(),
            tagPrefix = "punct:",
            onCommit = onCommit,
            onFeedback = onFeedback,
        ).also {
            rail = it
            refreshPinyinFilters()
        }

    fun refreshSymbols() {
        val current = rail ?: return
        val content = current.getChildAt(0) as? LinearLayout ?: return
        val header = content.findViewWithTag<TextView>(FILTER_TAG)
        SymbolRailRenderer.populate(
            scroll = current,
            symbols = commonSymbols(),
            tagPrefix = "punct:",
            preservedHeader = header,
            onCommit = onCommit,
            onFeedback = onFeedback,
        )
        refreshPinyinFilters()
    }

    private fun refreshPinyinFilters() {
        val text = composition.text?.toString().orEmpty()
        if (text.isBlank()) {
            setPinyinFilters(emptyList(), null) {}
            return
        }

        val lastSpace = text.lastIndexOf(' ')
        val prefix = if (lastSpace >= 0) text.substring(0, lastSpace + 1) else ""
        val suffix = if (lastSpace >= 0) text.substring(lastSpace + 1) else text
        val digits = CandidatePipeline.nineKeyDigitsFor(suffix)
        val code = digits?.let { NineKeyLocalDecoder.nativeCode(prefix, it) }
        if (code.isNullOrEmpty()) {
            setPinyinFilters(emptyList(), null) {}
            return
        }

        val resolver = context as? CandidateResolver ?: return
        val choices = resolver.nineKeyPathsFor(code)
        val selected = resolver.selectedNineKeyPathFor(code) ?: text
        setPinyinFilters(
            filters = choices,
            selected = selected,
        ) { chosen ->
            resolver.selectNineKeyPath(code, chosen)
            if (chosen == composition.text?.toString()) return@setPinyinFilters
            composition.setText(chosen)
            composition.setSelection(chosen.length)
        }
    }

    private fun setPinyinFilters(
        filters: List<String>,
        selected: String?,
        onSelect: (String) -> Unit,
    ) {
        val scroll = rail ?: return
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

    private fun commonSymbols(): List<String> =
        CustomSymbolRepository.load(context)
            .map { it.symbol }
            .filter { it.isNotBlank() } +
            ImeData.symbols["常用"].orEmpty()
                .asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
                .ifEmpty { listOf("，", "。", "？", "！") }

    private companion object {
        const val NINE_RAIL_TAG = "nine-punct-stack"
        const val NINE_CONTENT_TAG = "nine-symbol-scroll-content"
        const val FILTER_TAG = "nine-pinyin-path-filter"
    }
}
