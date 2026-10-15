package llc.slacker.openime.panel

import android.content.Context
import android.text.TextUtils
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import llc.slacker.openime.R
import llc.slacker.openime.SymbolManagerActivity
import llc.slacker.openime.core.EmojiCatalog
import llc.slacker.openime.core.ImeData
import llc.slacker.openime.core.SymbolCatalog
import llc.slacker.openime.core.KeyboardMode
import llc.slacker.openime.core.Panel
import llc.slacker.openime.data.CustomSymbolRepository
import llc.slacker.openime.data.EmojiRecentRepository
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.handwriting.HandwritingPadView
import llc.slacker.openime.handwriting.HandwritingProvider
import llc.slacker.openime.handwriting.HandwritingResult
import llc.slacker.openime.handwriting.HandwritingFeaturePolicy
import llc.slacker.openime.handwriting.StrokeMatchHandwritingProvider
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeSpacingTokens
import llc.slacker.openime.theme.ImeTheme
import llc.slacker.openime.theme.ImeTypographyTokens
import llc.slacker.openime.widget.ImeKeyView

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
    private val createBackspaceKey: () -> ImeKeyView,
    private val createTitle: (String, Boolean) -> TextView,
    private val createEmojiCell: (String) -> View,
    private val gridCellParams: (Int, Int, Int) -> LinearLayout.LayoutParams,
    private val currentMode: () -> KeyboardMode,
    private val onModeSelected: (KeyboardMode) -> Unit,
    private val onShowPanel: (Panel) -> Unit,
    private val onEnableFloatingKeyboard: () -> Unit,
    private val onOpenDataManagement: () -> Unit,
    private val onSymbolSelected: (String) -> Unit,
    private val onCharacter: (String) -> Unit,
    private val onSpace: () -> Unit,
    private val onFeedback: () -> Unit,
    private val applyTheme: () -> Unit,
    private val onHierarchyRebuilt: () -> Unit,
) {
    private var symbolCategory = "中文"
    private var emojiCategory = "笑脸"

    /** Parsed on first use; stays unavailable when the local table is absent. */
    private val handwritingProvider: HandwritingProvider by lazy {
        StrokeMatchHandwritingProvider.forContext(context)
    }

    private fun rimeAssetPresent(path: String): Boolean =
        runCatching { context.assets.open(path).close() }.isSuccess
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

    /** Same grid as 工具: four per row, an icon tile over a short label. */
    fun renderKeyboardSelect() {
        addHeader("切换键盘")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "keyboard-select-panel"
        }
        val modes = listOf(
            Triple(KeyboardMode.PINYIN_26, "拼音 26 键", R.drawable.ic_kb_pinyin26),
            Triple(KeyboardMode.PINYIN_9, "拼音 9 键", R.drawable.ic_kb_pinyin9),
            Triple(KeyboardMode.DOUBLE_PINYIN, "双拼", R.drawable.ic_kb_double),
            Triple(KeyboardMode.STROKE, "笔画", R.drawable.ic_kb_stroke),
            Triple(KeyboardMode.WUBI, "五笔", R.drawable.ic_kb_wubi26),
            Triple(KeyboardMode.ENGLISH_26, "英文 26 键", R.drawable.ic_kb_english),
            Triple(KeyboardMode.DIGITS, "数字键盘", R.drawable.ic_kb_digits),
        ).filterNot { (mode, _, _) ->
            // Tables that ship only with local builds keep their entry hidden.
            (mode == KeyboardMode.WUBI && !rimeAssetPresent(WUBI_TABLE_ASSET)) ||
                (mode == KeyboardMode.DOUBLE_PINYIN && !rimeAssetPresent(DOUBLE_PINYIN_SCHEMA_ASSET))
        }
        val tiles = modes.map { (mode, label, iconRes) -> keyboardChoice(mode, label, iconRes) }.toMutableList()
        // Handwriting is an input surface, not a keyboard mode. Give it the
        // same entry point as the keyboards so it is found in the same place —
        // while the pad is offered at all.
        if (HandwritingFeaturePolicy.entryShown(StrokeMatchHandwritingProvider.assetPresent(context))) {
            tiles += TileEntry(
                label = "手写输入",
                iconRes = R.drawable.ic_pref_handwriting,
                onTap = { onShowPanel(Panel.HANDWRITING) },
            )
        }
        body.addView(
            tileGrid(tiles),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val scroll = panelVerticalScroll(body, "keyboard-select-scroll")
        rememberPanelVerticalScroll(scroll, "keyboard-select")
        expandedPanel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
    }

    fun renderTools() {
        addHeader("工具")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "tools-panel"
        }
        val entries = listOf(
            ToolEntry("剪贴板", Panel.CLIPBOARD, R.drawable.ic_pref_clipboard),
            ToolEntry("表情", Panel.EMOJI, R.drawable.ic_pref_emoji),
            ToolEntry("符号", Panel.SYMBOLS, R.drawable.ic_pref_symbols),
            ToolEntry(
                "手写输入",
                Panel.HANDWRITING,
                R.drawable.ic_pref_handwriting,
                enabled = HandwritingFeaturePolicy.entryShown(
                    StrokeMatchHandwritingProvider.assetPresent(context),
                ),
            ),
            ToolEntry("文本编辑", Panel.TEXT_EDITOR, R.drawable.ic_pref_cursor),
            ToolEntry("浮动键盘", iconRes = R.drawable.ic_pref_floating, action = onEnableFloatingKeyboard),
            ToolEntry("设置", Panel.SETTINGS, R.drawable.ic_pref_settings),
            // Voice is a long-press on space and the keyboard switcher sits on the
            // toolbar, so neither repeats here.
            ToolEntry("数据管理", iconRes = R.drawable.ic_pref_data, action = onOpenDataManagement),
        ).filter { it.enabled }

        body.addView(
            tileGrid(
                entries.map { entry ->
                    TileEntry(
                        label = entry.label,
                        iconRes = entry.iconRes ?: R.drawable.ic_settings,
                        onTap = entry.action ?: { entry.target?.let(onShowPanel); Unit },
                    )
                },
            ),
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

    fun renderHandwriting() {
        addHeader("手写输入")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(toPx(10), toPx(10), toPx(10), toPx(10))
        }
        val candidateRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        candidateRow.addView(
            createTitle("在下方区域落笔手写...", true),
            wrapParams(),
        )
        body.addView(
            candidateRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ).apply { bottomMargin = toPx(8) },
        )

        var undoButton: ImeKeyView? = null
        var clearButton: ImeKeyView? = null
        fun refreshStrokeActions(hasStrokes: Boolean) {
            listOf(
                undoButton to "撤销",
                clearButton to "清空",
            ).forEach { (button, label) ->
                button ?: return@forEach
                button.isEnabled = hasStrokes
                button.alpha = if (hasStrokes) 1f else ImeSurfacePolicy.DISABLED_ALPHA
                button.contentDescription = if (hasStrokes) label else "$label（暂无笔画）"
                if (Build.VERSION.SDK_INT >= 30) {
                    button.stateDescription = if (hasStrokes) "可用" else "不可用"
                }
            }
        }

        var padRef: HandwritingPadView? = null
        fun showHandwritingHint(text: String) {
            candidateRow.addView(createTitle(text, true), wrapParams())
        }
        val pad = HandwritingPadView(context) { strokes ->
            refreshStrokeActions(strokes.isNotEmpty())
            candidateRow.removeAllViews()
            when (val result = handwritingProvider.recognize(strokes)) {
                is HandwritingResult.NotConfigured -> showHandwritingHint("当前未配置手写识别引擎")
                is HandwritingResult.Success -> when {
                    result.candidates.isNotEmpty() -> result.candidates.forEach { candidate ->
                        candidateRow.addView(
                            createKey(candidate, false, ImeTypographyTokens.TITLE_SP) {
                                onCharacter(candidate)
                                // 上屏后清空落笔区，直接写下一个字。
                                padRef?.clear()
                            },
                            wrapParams(),
                        )
                    }
                    strokes.isEmpty() -> showHandwritingHint("在下方区域落笔手写...")
                    else -> showHandwritingHint("没有认出这个字，再写一次试试")
                }
                is HandwritingResult.Error -> showHandwritingHint("识别出错了：${result.message}")
            }
        }.apply {
            tag = "handwriting-canvas"
        }
        padRef = pad
        body.addView(
            pad,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(140),
            ).apply { bottomMargin = toPx(8) },
        )

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        undoButton = createKey("撤销", true, ImeTypographyTokens.BODY_SP) { pad.undo() }
        clearButton = createKey("清空", true, ImeTypographyTokens.BODY_SP) { pad.clear() }
        actions.addView(
            undoButton,
            LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f).apply { marginEnd = toPx(6) },
        )
        actions.addView(
            clearButton,
            LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f).apply { marginEnd = toPx(6) },
        )
        actions.addView(
            createKey("空格", true, ImeTypographyTokens.BODY_SP, onSpace),
            LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f),
        )
        refreshStrokeActions(false)
        body.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ),
        )

        val scroll = panelVerticalScroll(body, "handwriting-scroll")
        rememberPanelVerticalScroll(scroll, "handwriting")
        expandedPanel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                panelBodyHeightPx(),
            ),
        )
    }

    fun renderEmoji() {
        addHeader("表情")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, toPx(8), 0, 0)
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
        heightDp: Int = ImeGeometryTokens.TOUCH_TARGET_DP,
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
            val chip = filterChip(label, label == selected) { onSelected(label) }.apply {
                minimumHeight = toPx(heightDp)
                if (heightDp == 32) {
                    minWidth = toPx(if (label == selected) 56 else 52)
                    if (label == selected) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
            }
            if (label == selected) selectedView = chip
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    toPx(heightDp),
                ).apply { marginEnd = toPx(6) },
            )
        }
        addView(
            row,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, toPx(heightDp)),
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
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(toPx(8), toPx(5), toPx(4), toPx(4))

        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        if (symbolCategory == "自定义") {
            content.addView(
                createPanelButton("管理自定义符号", ImeTypographyTokens.BODY_SP, true).apply {
                    contentDescription = "管理自定义符号"
                    setOnClickListener {
                        context.startActivity(
                            Intent(context, SymbolManagerActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
                ).apply { bottomMargin = toPx(8) },
            )
        }

        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val items = symbolItems(symbolCategory)
        if (items.isEmpty()) {
            grid.addView(
                TextView(context).apply {
                    text = "还没有自定义符号；点击上方按钮添加第一个。"
                    textSize = ImeTypographyTokens.PANEL_NOTE_SP
                    gravity = Gravity.CENTER
                    tag = "panel-note"
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(56),
                ),
            )
        }
        // Kaomoji and other long items get three wide cells a row and a smaller
        // size; five narrow cells cut (づ｡◕‿‿◕｡)づ down to "(づ｡". The reference
        // halves its symbol cells to ten a row (`input_kbd_symbol.xml:6-15`),
        // which our touch-target floor does not allow.
        val wide = items.any { it.codePointCount(0, it.length) > WIDE_SYMBOL_CODE_POINTS }
        val columns = if (wide) 3 else 5
        val textSize = if (wide) ImeTypographyTokens.BODY_SP else ImeTypographyTokens.KEY_LETTER_SP
        items.chunked(columns).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { symbol ->
                row.addView(
                    createKey(
                        symbol,
                        false,
                        textSize,
                    ) { onSymbolSelected(symbol) },
                    gridCellParams(54, columns, 0),
                )
            }
            repeat(columns - chunk.size) {
                row.addView(View(context), gridCellParams(54, columns, 0))
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(54),
                ),
            )
        }

        val scroll = panelVerticalScroll(grid, "symbols-scroll")
        rememberPanelVerticalScroll(scroll, "symbols:$symbolCategory")
        content.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        // The reference keeps this page's category strip and its two function
        // keys in its bottom rows: 123 and 退格 close the symbol row
        // (`input_kbd_symbol.xml:31-41`, weight 15 against the cells' 70) and the
        // strip shares the next one with the return key
        // (`input_kbd_symbol.xml:44-47`, weight 85 against 13.1,
        // `cls_symbol_tab_container`, `style.xml:707-710`). Our cells are five to
        // a row, so both keys take the ends of the strip's own row.
        val stripRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "symbol-strip-row"
        }
        stripRow.addView(
            createKey("123", true, ImeTypographyTokens.BODY_SP) {
                onModeSelected(KeyboardMode.DIGITS)
            }.apply { contentDescription = "数字键盘" },
            LinearLayout.LayoutParams(
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ),
        )
        stripRow.addView(
            panelChipScroll(SymbolCatalog.CATEGORIES, symbolCategory, { category ->
                if (category != symbolCategory) {
                    symbolCategory = category
                    renderSymbolContent(body, notifyRebuilt = true)
                }
            }, heightDp = 32),
            LinearLayout.LayoutParams(0, toPx(32), 1f),
        )
        stripRow.addView(
            createBackspaceKey(),
            LinearLayout.LayoutParams(
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ),
        )
        body.addView(
            stripRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
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
        val categories = listOf("全部") + EmojiCatalog.byCategory(context).keys.toList()
        fun displayCategory(category: String): String = when (category) {
            "人物/手势" -> "手势"
            "动物/自然" -> "动物"
            "食物/饮品" -> "食物"
            else -> category
        }
        val tabs = panelChipScroll(categories.map(::displayCategory), displayCategory(emojiCategory), { label ->
            val category = categories.first { displayCategory(it) == label }
            if (category != emojiCategory) {
                emojiCategory = category
                renderEmojiContent(body, notifyRebuilt = true)
            }
        }, heightDp = 32)
        body.addView(
            tabs,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(32),
            ).apply { leftMargin = toPx(12); rightMargin = toPx(12); bottomMargin = toPx(4) },
        )

        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(toPx(4), 0, toPx(4), 0)
        }
        val items = if (emojiCategory == "全部") {
            (EmojiRecentRepository.load(context) + EmojiCatalog.byCategory(context).values.flatten()).distinct()
        } else {
            val catalog = EmojiCatalog.byCategory(context)[emojiCategory].orEmpty()
            if (emojiCategory == "笑脸") (ImeData.referenceSmileys + catalog).distinct() else catalog
        }
        if (items.isEmpty() && emojiCategory == "最近") {
            grid.addView(
                TextView(context).apply {
                    text = "最近使用的表情会显示在这里"
                    textSize = ImeTypographyTokens.PANEL_NOTE_SP
                    gravity = Gravity.CENTER
                    tag = "panel-note"
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
                ),
            )
        }
        items.chunked(8).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { emoji ->
                row.addView(
                    createEmojiCell(emoji),
                    gridCellParams(42, 8, 0),
                )
            }
            repeat(8 - chunk.size) { row.addView(View(context), gridCellParams(42, 8, 0)) }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(42),
                ),
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
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ),
        )
    }

    private fun filterChip(
        label: String,
        active: Boolean,
        onTap: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        textSize = ImeTypographyTokens.BODY_SP
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
        minimumHeight = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
        setPadding(toPx(10), 0, toPx(10), 0)
        tag = if (active) "tab-active" else "panel-tab"
        typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
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

    /** One tile of a launcher grid: its glyph, its name, and what it opens. */
    private class TileEntry(
        val label: String,
        val iconRes: Int,
        val selected: Boolean = false,
        /** Overrides the `tool:$label` tag the tiles of the tools page carry. */
        val tag: String? = null,
        val contentDescription: String? = null,
        val onTap: () -> Unit,
    )

    /**
     * The reference's own launcher grid (`input_settings.xml:13-45`,
     * `input_switch_keyboard.xml:13-29`): a 4-column, 2-row container 88.9% of
     * the page wide with its own paddings, each tile filling its cell with the
     * radius-14 face. See [TileGridPolicy] for every quoted value.
     */
    private fun tileGrid(entries: List<TileEntry>): View {
        val pageWidth = expandedPanel.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val containerWidth = (pageWidth * TileGridPolicy.WIDTH_FRACTION).toInt()
        val padH = (pageWidth * TileGridPolicy.PAD_H_FRACTION).toInt()
        val padTop = (pageWidth * TileGridPolicy.PAD_TOP_FRACTION).toInt()
        val padBottom = (pageWidth * TileGridPolicy.PAD_BOTTOM_FRACTION).toInt()
        val cellWidth = ((containerWidth - 2 * padH) / TileGridPolicy.COLUMNS).coerceAtLeast(1)
        val hPad = (cellWidth * TileGridPolicy.CHILD_PAD_FRACTION).toInt()
        // The grid never grows past the reference's own cap, and never past the
        // room the panel has left under its header.
        val available = (panelBodyHeightPx() - padTop - padBottom).coerceAtLeast(1)
        val gridHeight = minOf(toPx(TileGridPolicy.MAX_HEIGHT_UNITS), available)
        val rows = TileGridPolicy.rows(entries.size)
        val fittedRow = gridHeight / rows
        val vPad = (fittedRow * TileGridPolicy.CHILD_V_PAD_FRACTION).toInt()
        // A tile never shrinks under our 48dp touch target; a row that would is
        // grown back and the panel's own scroll takes the difference.
        val tileHeight = (fittedRow - 2 * vPad).coerceAtLeast(toPx(ImeGeometryTokens.TOUCH_TARGET_DP))
        val rowHeight = (tileHeight + 2 * vPad).coerceAtLeast(fittedRow)

        val container = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
                if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && availableWidth > 0) {
                    val gridWidth = (availableWidth * TileGridPolicy.WIDTH_FRACTION).toInt()
                    val innerPadding = (availableWidth * TileGridPolicy.PAD_H_FRACTION).toInt()
                    val inset = (availableWidth - gridWidth) / 2 + innerPadding
                    setPadding(inset, padTop, inset, padBottom)
                    val width = ((availableWidth - 2 * inset) / TileGridPolicy.COLUMNS).coerceAtLeast(1)
                    val margin = (width * TileGridPolicy.CHILD_PAD_FRACTION).toInt()
                    for (rowIndex in 0 until childCount) {
                        val row = getChildAt(rowIndex) as LinearLayout
                        for (column in 0 until row.childCount) {
                            val cell = row.getChildAt(column) as LinearLayout
                            (cell.layoutParams as LinearLayout.LayoutParams).width = width
                            val tile = cell.getChildAt(0)
                            (tile.layoutParams as LinearLayout.LayoutParams).apply {
                                this.width = (width - 2 * margin).coerceAtLeast(1)
                                leftMargin = margin
                                rightMargin = margin
                            }
                        }
                    }
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            tag = "tile-grid"
            setPadding(padH, padTop, padH, padBottom)
        }
        entries.chunked(TileGridPolicy.COLUMNS).forEach { chunk ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            chunk.forEach { entry ->
                val cell = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                cell.addView(
                    toolTile(entry, cellWidth - 2 * hPad, tileHeight),
                    LinearLayout.LayoutParams(
                        cellWidth - 2 * hPad,
                        tileHeight,
                    ).apply {
                        leftMargin = hPad
                        rightMargin = hPad
                        topMargin = vPad
                        bottomMargin = vPad
                    },
                )
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(cellWidth, rowHeight).apply { gravity = Gravity.CENTER },
                )
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeight),
            )
        }
        return container
    }

    /**
     * One tile: the glyph over its name, the whole cell the touch target and
     * the press (`cls_settings_button`; the label sits under the icon as the
     * skin's `align="top"` draws it).
     */
    private fun toolTile(entry: TileEntry, widthPx: Int, heightPx: Int): LinearLayout {
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumWidth = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
            minimumHeight = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
            tag = entry.tag ?: "tool:${entry.label}"
            contentDescription = entry.contentDescription ?: entry.label
            isSelected = entry.selected
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onFeedback()
                entry.onTap()
            }
        }
        val iconSide = (heightPx * TileGridPolicy.ICON_FRACTION).toInt().coerceAtLeast(1)
        tile.addView(
            ImageView(context).apply {
                setImageResource(entry.iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = null
                isDuplicateParentStateEnabled = true
                tag = if (entry.selected) "tool-icon-selected" else "tool-icon"
            },
            LinearLayout.LayoutParams(iconSide, iconSide).apply {
                bottomMargin = toPx(ImeSpacingTokens.XS_DP)
            },
        )
        tile.addView(
            TextView(context).apply {
                text = entry.label
                tag = if (entry.selected) "tool-label-selected" else "tool-label"
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                textSize = ImeTypographyTokens.SMALL_SP
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                includeFontPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return tile
    }

    /** A launcher tile for a keyboard; the current keyboard's tile is drawn selected. */
    private fun keyboardChoice(mode: KeyboardMode, label: String, iconRes: Int): TileEntry {
        val selected = currentMode() == mode
        return TileEntry(
            label = label,
            iconRes = iconRes,
            selected = selected,
            tag = if (selected) "keyboard-choice-selected" else "keyboard-choice",
            contentDescription = "$label，${if (selected) "已选中" else "未选中"}",
            onTap = { onModeSelected(mode) },
        )
    }

    private fun symbolItems(category: String): List<String> = when (category) {
        "中文" -> (
            listOf("，", "。", "、", "；", "：", "？", "！", "…", "—", "～", "·", "「", "」", "『", "』", "（", "）", "《", "》", "【", "】", "“", "”", "‘", "’") +
                SymbolCatalog.CHINESE_EXTRA
            ).distinct()
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
            ImeData.symbols["特殊图形"].orEmpty(),
            SymbolCatalog.SPECIAL_EXTRA,
            ImeData.symbols["几何图形"].orEmpty(),
            ImeData.symbols["箭头线条"].orEmpty(),
            ImeData.symbols["括号边框"].orEmpty(),
        ).flatten().distinct()
        "网络颜文字" -> (ImeData.symbols["网络颜文字"].orEmpty() + SymbolCatalog.KAOMOJI_EXTRA).distinct()
        "拼音" -> SymbolCatalog.PINYIN_TONES
        "日文" -> SymbolCatalog.JAPANESE
        "注音" -> SymbolCatalog.ZHUYIN
        "制表" -> SymbolCatalog.BOX_DRAWING
        "自定义" -> CustomSymbolRepository.load(context).map { it.symbol }
        else -> listOf(
            ImeData.symbols["常用"].orEmpty(),
            ImeData.symbols["中文标点"].orEmpty().take(12),
            ImeData.symbols["数学运算"].orEmpty().take(12),
        ).flatten().distinct()
    }

    private fun wrapParams() =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    private data class ToolEntry(
        val label: String,
        val target: Panel? = null,
        val iconRes: Int? = null,
        val enabled: Boolean = true,
        val action: (() -> Unit)? = null,
    )

    private companion object {
        /** Longer than this, a symbol is text (kaomoji, 【】 pairs...) and needs a wide cell. */
        const val WIDE_SYMBOL_CODE_POINTS = 3
        /** Compiled wubi table; absent from builds without the local sources. */
        const val WUBI_TABLE_ASSET = "rime-data/build/wubi86.table.bin"
        /** Compiled double-pinyin schema marker. */
        const val DOUBLE_PINYIN_SCHEMA_ASSET = "rime-data/build/double_pinyin_flypy.schema.yaml"
    }
}
