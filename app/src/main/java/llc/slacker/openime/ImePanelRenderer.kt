package llc.slacker.openime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Owns the low-state panel surfaces and their local presentation state.
 *
 * Voice, clipboard, handwriting, text editing, and settings stay in the host
 * until their asynchronous/editor state can be separated independently.
 */
internal class ImePanelRenderer(
    private val context: Context,
    private val expandedPanel: LinearLayout,
    private val toPx: (Int) -> Int,
    private val panelBodyHeightPx: () -> Int,
    private val imeHeightPx: () -> Int,
    private val createHeader: (String) -> LinearLayout,
    private val createKey: (
        text: String,
        function: Boolean,
        textSize: Float?,
        onTap: () -> Unit,
    ) -> ImeKeyView,
    private val createPanelButton: (String, Float, Boolean) -> TextView,
    private val createEmojiCell: (String) -> View,
    private val gridCellParams: (Int, Int, Int) -> LinearLayout.LayoutParams,
    private val currentMode: () -> KeyboardMode,
    private val isPasswordField: () -> Boolean,
    private val onModeSelected: (KeyboardMode) -> Unit,
    private val onShowPanel: (Panel) -> Unit,
    private val onEnableFloatingKeyboard: () -> Unit,
    private val onSymbolSelected: (String) -> Unit,
    private val onFeedback: () -> Unit,
    private val applyTheme: () -> Unit,
    private val onHierarchyRebuilt: () -> Unit,
) {
    private var symbolCategory = "中文"
    private var emojiCategory = "笑脸"
    private var symbolsBody: LinearLayout? = null
    private val chipScrollPositions = mutableMapOf<String, Int>()
    private val verticalScrollPositions = mutableMapOf<String, Int>()

    fun syncSymbolCategoryForMode(mode: KeyboardMode) {
        symbolCategory = when (mode) {
            KeyboardMode.ENGLISH_26 -> "英文"
            KeyboardMode.DIGITS -> "数学"
            else -> "中文"
        }
    }

    fun renderKeyboardSelect() {
        addHeader("切换键盘")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(toPx(12), toPx(10), toPx(12), toPx(10))
            tag = "keyboard-select-panel"
        }
        body.addView(
            TextView(context).apply {
                text = "选择输入布局"
                textSize = 13f
                setPadding(toPx(4), 0, 0, toPx(8))
                tag = "panel-section-title"
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(28),
            ),
        )

        val modes = listOf(
            KeyboardMode.PINYIN_26 to "拼音 26 键",
            KeyboardMode.PINYIN_9 to "拼音 9 键",
            KeyboardMode.ENGLISH_26 to "英文 26 键",
            KeyboardMode.DIGITS to "数字键盘",
        )
        modes.chunked(2).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { (modeValue, label) ->
                row.addView(
                    createKey(label, true, 13f) { onModeSelected(modeValue) }.apply {
                        val selected = currentMode() == modeValue
                        tag = if (selected) "tab-active" else "keyboard-choice"
                        contentDescription = "$label，${if (selected) "已选中" else "未选中"}"
                        if (Build.VERSION.SDK_INT >= 30) {
                            stateDescription = if (selected) "已选中" else "未选中"
                        }
                    },
                    LinearLayout.LayoutParams(0, toPx(50), 1f).apply {
                        marginEnd = toPx(7)
                    },
                )
            }
            if (chunk.size == 1) {
                row.addView(View(context), LinearLayout.LayoutParams(0, toPx(50), 1f))
            }
            body.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(50),
                ).apply { bottomMargin = toPx(7) },
            )
        }
        expandedPanel.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                imeHeightPx() - toPx(48),
            ),
        )
    }

    fun renderTools() {
        addHeader("工具")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(toPx(10), toPx(10), toPx(10), toPx(10))
            tag = "tools-panel"
        }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val handwritingAvailable =
            HandwritingFeaturePolicy.entryEnabled(UnavailableHandwritingProvider)
        val cards = listOf(
            ToolEntry("表情", Panel.EMOJI, R.drawable.ic_emoji),
            ToolEntry("剪贴板", Panel.CLIPBOARD, R.drawable.ic_clipboard, enabled = !isPasswordField()),
            ToolEntry("手写输入", Panel.HANDWRITING, R.drawable.ic_handwriting, enabled = handwritingAvailable),
            ToolEntry("符号", Panel.SYMBOLS, R.drawable.ic_symbols),
            ToolEntry("切换键盘", Panel.KEYBOARD_SELECT, R.drawable.ic_grid),
            ToolEntry("文本编辑", Panel.TEXT_EDITOR, R.drawable.ic_keyboard),
            ToolEntry("浮动键盘", iconRes = R.drawable.ic_game, action = onEnableFloatingKeyboard),
            ToolEntry("设置", Panel.SETTINGS, R.drawable.ic_settings),
        ).filter { it.enabled }

        cards.chunked(4).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { entry ->
                val action = entry.action ?: {
                    entry.target?.let(onShowPanel)
                    Unit
                }
                row.addView(
                    toolCard(entry.iconRes ?: R.drawable.ic_settings, entry.label, action),
                    LinearLayout.LayoutParams(
                        0,
                        toPx(ImeGeometryTokens.TOOL_CARD_HEIGHT_DP),
                        1f,
                    ).apply { marginEnd = toPx(8) },
                )
            }
            repeat(4 - chunk.size) {
                row.addView(
                    View(context),
                    LinearLayout.LayoutParams(
                        0,
                        toPx(ImeGeometryTokens.TOOL_CARD_HEIGHT_DP),
                        1f,
                    ).apply { marginEnd = toPx(8) },
                )
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(ImeGeometryTokens.TOOL_CARD_HEIGHT_DP),
                ).apply { bottomMargin = toPx(8) },
            )
        }
        body.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val scroll = panelVerticalScroll(body, "tools-scroll")
        rememberPanelVerticalScroll(scroll, "tools")
        expandedPanel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
    }

    fun renderSymbols() {
        addHeader("符号")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(toPx(10), toPx(10), toPx(10), toPx(10))
            tag = "symbols-panel"
        }
        symbolsBody = body
        expandedPanel.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                panelBodyHeightPx(),
            ),
        )
        renderSymbolContent(body, notifyRebuilt = false)
    }

    fun refreshCustomSymbols() {
        val body = symbolsBody ?: return
        if (symbolCategory == "自定义") {
            renderSymbolContent(body, notifyRebuilt = true)
        }
    }

    fun renderEmoji() {
        addHeader("表情")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(toPx(12), toPx(12), toPx(12), toPx(12))
            tag = "emoji-panel"
        }
        expandedPanel.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                panelBodyHeightPx(),
            ),
        )
        renderEmojiContent(body, notifyRebuilt = false)
    }

    fun panelChipScroll(
        labels: List<String>,
        selected: String,
        onSelected: (String) -> Unit,
    ): HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        val scrollKey = labels.joinToString("\u001f")
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var selectedView: View? = null
        labels.forEach { label ->
            val chip = filterChip(label, label == selected) { onSelected(label) }
            if (label == selected) selectedView = chip
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    toPx(48),
                ).apply { marginEnd = toPx(6) },
            )
        }
        addView(
            row,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, toPx(48)),
        )
        setOnScrollChangeListener { _, scrollX, _, _, _ ->
            chipScrollPositions[scrollKey] = scrollX
        }
        post {
            val remembered = chipScrollPositions[scrollKey]
            if (remembered != null) {
                scrollTo(remembered, 0)
            } else {
                selectedView?.let { active ->
                    val target = (active.left - (width - active.width) / 2).coerceAtLeast(0)
                    scrollTo(target, 0)
                    chipScrollPositions[scrollKey] = scrollX
                }
            }
        }
    }

    fun panelVerticalScroll(content: View, tagValue: String): ScrollView =
        ScrollView(context).apply {
            tag = tagValue
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    fun rememberPanelVerticalScroll(scroll: ScrollView, scrollKey: String) {
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            verticalScrollPositions[scrollKey] = scrollY
        }
        scroll.post {
            verticalScrollPositions[scrollKey]?.let { remembered ->
                scroll.scrollTo(0, remembered)
            }
        }
    }

    private fun renderSymbolContent(
        body: LinearLayout,
        notifyRebuilt: Boolean,
    ) {
        body.removeAllViews()
        val categories = listOf("常用", "中文", "英文", "数学", "序号", "单位", "特殊", "编程", "自定义")
        val tabs = panelChipScroll(categories, symbolCategory) { category ->
            if (category != symbolCategory) {
                symbolCategory = category
                renderSymbolContent(body, notifyRebuilt = true)
            }
        }
        body.addView(
            tabs,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(48),
            ).apply { bottomMargin = toPx(8) },
        )

        if (symbolCategory == "自定义") {
            body.addView(
                createPanelButton("管理自定义符号", 12f, true).apply {
                    contentDescription = "管理自定义符号"
                    setOnClickListener {
                        onFeedback()
                        context.startActivity(
                            Intent(context, SymbolManagerActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(48),
                ).apply { bottomMargin = toPx(8) },
            )
        }

        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val items = symbolItems(symbolCategory)
        if (items.isEmpty()) {
            grid.addView(
                TextView(context).apply {
                    text = "还没有自定义符号；点击上方按钮添加第一个。"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    tag = "panel-note"
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(56),
                ),
            )
        }
        items.chunked(6).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { symbol ->
                row.addView(
                    createKey(
                        symbol,
                        false,
                        if (symbol.length > 2) 12f else 17f,
                    ) { onSymbolSelected(symbol) },
                    gridCellParams(48, 6, 6),
                )
            }
            repeat(6 - chunk.size) {
                row.addView(View(context), gridCellParams(48, 6, 6))
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(48),
                ).apply { bottomMargin = toPx(6) },
            )
        }

        val scroll = panelVerticalScroll(grid, "symbols-scroll")
        rememberPanelVerticalScroll(scroll, "symbols:$symbolCategory")
        body.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        applyTheme()
        if (notifyRebuilt) onHierarchyRebuilt()
    }

    private fun renderEmojiContent(
        body: LinearLayout,
        notifyRebuilt: Boolean,
    ) {
        body.removeAllViews()
        val categories = listOf("最近") + ImeData.fluentSmileysByCategory.keys.toList()
        val tabs = panelChipScroll(categories, emojiCategory) { category ->
            if (category != emojiCategory) {
                emojiCategory = category
                renderEmojiContent(body, notifyRebuilt = true)
            }
        }
        body.addView(
            tabs,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(48),
            ).apply { bottomMargin = toPx(10) },
        )

        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val items = if (emojiCategory == "最近") {
            EmojiRecentRepository.load(context)
        } else {
            ImeData.fluentSmileysByCategory[emojiCategory].orEmpty()
        }
        if (items.isEmpty() && emojiCategory == "最近") {
            grid.addView(
                TextView(context).apply {
                    text = "最近使用的表情会显示在这里"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    tag = "panel-note"
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(48),
                ),
            )
        }
        items.chunked(8).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { emoji ->
                row.addView(
                    createEmojiCell(emoji),
                    gridCellParams(48, 8, 4),
                )
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(48),
                ).apply { bottomMargin = toPx(4) },
            )
        }

        val scroll = panelVerticalScroll(grid, "emoji-scroll")
        rememberPanelVerticalScroll(scroll, "emoji:$emojiCategory")
        body.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        applyTheme()
        if (notifyRebuilt) onHierarchyRebuilt()
    }

    private fun addHeader(title: String) {
        expandedPanel.addView(
            createHeader(title),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(48),
            ),
        )
    }

    private fun filterChip(
        label: String,
        active: Boolean,
        onTap: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        textSize = 11f
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = toPx(48)
        minimumHeight = toPx(48)
        setPadding(toPx(10), 0, toPx(10), 0)
        tag = if (active) "tab-active" else "panel-tab"
        contentDescription = "$label，${if (active) "已选中" else "未选中"}"
        if (Build.VERSION.SDK_INT >= 30) {
            stateDescription = if (active) "已选中" else "未选中"
        }
        isClickable = true
        isFocusable = true
        setOnClickListener {
            onFeedback()
            onTap()
        }
    }

    private fun toolCard(
        iconRes: Int,
        label: String,
        onTap: () -> Unit,
    ): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, toPx(8), 0, toPx(6))
            minimumWidth = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
            minimumHeight = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
            tag = "tool:$label"
            contentDescription = label
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onFeedback()
                onTap()
            }
        }
        card.addView(
            ImageView(context).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = null
            },
            LinearLayout.LayoutParams(toPx(20), toPx(20)).apply {
                bottomMargin = toPx(6)
            },
        )
        card.addView(
            TextView(context).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                includeFontPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return card
    }

    private fun symbolItems(category: String): List<String> = when (category) {
        "中文" -> ImeData.symbols["中文标点"].orEmpty()
        "英文" -> ImeData.symbols["英文标点"].orEmpty()
        "数学" -> listOf(
            ImeData.symbols["数学运算"].orEmpty(),
            ImeData.symbols["更多数学"].orEmpty(),
            ImeData.symbols["希腊字母"].orEmpty(),
            ImeData.symbols["上下标"].orEmpty(),
        ).flatten()
        "序号" -> listOf(
            ImeData.symbols["数字序号"].orEmpty(),
            ImeData.symbols["数字扩展"].orEmpty(),
        ).flatten()
        "单位" -> listOf(
            ImeData.symbols["货币单位"].orEmpty(),
            ImeData.symbols["单位符号"].orEmpty(),
        ).flatten()
        "编程" -> ImeData.symbols["技术编程"].orEmpty()
        "特殊" -> listOf(
            ImeData.symbols["数字序号"].orEmpty(),
            ImeData.symbols["特殊图形"].orEmpty(),
            ImeData.symbols["几何图形"].orEmpty(),
            ImeData.symbols["箭头线条"].orEmpty(),
            ImeData.symbols["括号边框"].orEmpty(),
            ImeData.symbols["网络颜文字"].orEmpty(),
        ).flatten()
        "自定义" -> CustomSymbolRepository.load(context).map { it.symbol }
        else -> listOf(
            ImeData.symbols["常用"].orEmpty(),
            ImeData.symbols["中文标点"].orEmpty().take(12),
            ImeData.symbols["数学运算"].orEmpty().take(12),
        ).flatten().distinct()
    }

    private data class ToolEntry(
        val label: String,
        val target: Panel? = null,
        val iconRes: Int? = null,
        val enabled: Boolean = true,
        val action: (() -> Unit)? = null,
    )
}
