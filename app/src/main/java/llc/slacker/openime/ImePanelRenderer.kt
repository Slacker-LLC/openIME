package llc.slacker.openime

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.widget.FrameLayout
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
    private val createTitle: (String, Boolean) -> TextView,
    private val createEmojiCell: (String) -> View,
    private val gridCellParams: (Int, Int, Int) -> LinearLayout.LayoutParams,
    private val currentMode: () -> KeyboardMode,
    private val isPasswordField: () -> Boolean,
    private val onModeSelected: (KeyboardMode) -> Unit,
    private val onShowPanel: (Panel) -> Unit,
    private val onEnableFloatingKeyboard: () -> Unit,
    private val onSymbolSelected: (String) -> Unit,
    private val onCharacter: (String) -> Unit,
    private val onSpace: () -> Unit,
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
            setPadding(toPx(8), toPx(12), toPx(8), toPx(10))
            tag = "keyboard-select-panel"
        }
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
                    keyboardChoice(modeValue, label),
                    LinearLayout.LayoutParams(0, toPx(96), 1f).apply {
                        marginEnd = toPx(4); marginStart = toPx(4)
                    },
                )
            }
            if (chunk.size == 1) {
                row.addView(View(context), LinearLayout.LayoutParams(0, toPx(96), 1f))
            }
            body.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(96),
                ).apply { bottomMargin = toPx(8) },
            )
        }
        expandedPanel.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                imeHeightPx() - toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ),
        )
    }

    fun renderTools() {
        addHeader("工具")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(toPx(12), toPx(12), toPx(4), 0)
            tag = "tools-panel"
        }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val cards = listOf(
            ToolEntry("剪贴板", Panel.CLIPBOARD, R.drawable.ic_clipboard, enabled = !isPasswordField()),
            ToolEntry("表情", Panel.EMOJI, R.drawable.ic_emoji),
            ToolEntry("符号", Panel.SYMBOLS, R.drawable.ic_symbols),
            ToolEntry("语音输入", Panel.VOICE, R.drawable.ic_mic),
            ToolEntry("切换键盘", Panel.KEYBOARD_SELECT, R.drawable.ic_keyboard),
            ToolEntry("文本编辑", Panel.TEXT_EDITOR, R.drawable.ic_text_cursor),
            ToolEntry("浮动键盘", iconRes = R.drawable.ic_floating, action = onEnableFloatingKeyboard),
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

        val pad = HandwritingPadView(context) { strokes ->
            refreshStrokeActions(strokes.isNotEmpty())
            candidateRow.removeAllViews()
            when (val result = UnavailableHandwritingProvider.recognize(strokes)) {
                is HandwritingResult.NotConfigured -> {
                    candidateRow.addView(
                        createTitle("当前未配置手写识别引擎", true),
                        wrapParams(),
                    )
                }
                is HandwritingResult.Success -> {
                    result.candidates.forEach { candidate ->
                        candidateRow.addView(
                            createKey(candidate, false, ImeTypographyTokens.TITLE_SP) { onCharacter(candidate) },
                            wrapParams(),
                        )
                    }
                }
                is HandwritingResult.Error -> Unit
            }
        }.apply {
            tag = "handwriting-canvas"
        }
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
        val categories = listOf(
            "常用", "中文", "英文", "数学", "序号", "特殊", "网络颜文字", "单位", "编程", "自定义",
        )
        body.orientation = LinearLayout.HORIZONTAL
        body.setPadding(toPx(8), toPx(5), toPx(4), 0)
        val categoryColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        categories.forEach { category ->
            categoryColumn.addView(filterChip(category, category == symbolCategory) {
                if (category != symbolCategory) {
                    symbolCategory = category
                    renderSymbolContent(body, notifyRebuilt = true)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(44)))
        }
        body.addView(panelVerticalScroll(categoryColumn, "symbol-categories"),
            LinearLayout.LayoutParams(toPx(74), LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = toPx(5) })
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        if (symbolCategory == "自定义") {
            content.addView(
                createPanelButton("管理自定义符号", ImeTypographyTokens.BODY_SP, true).apply {
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
        items.chunked(5).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { symbol ->
                row.addView(
                    createKey(
                        symbol,
                        false,
                        ImeTypographyTokens.KEY_LETTER_SP,
                    ) { onSymbolSelected(symbol) },
                    gridCellParams(54, 5, 0),
                )
            }
            repeat(5 - chunk.size) {
                row.addView(View(context), gridCellParams(54, 5, 0))
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
        applyTheme()
        if (notifyRebuilt) onHierarchyRebuilt()
    }

    private fun renderEmojiContent(
        body: LinearLayout,
        notifyRebuilt: Boolean,
    ) {
        body.removeAllViews()
        val categories = listOf("全部") + ImeData.emojiByCategory.keys.toList()
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
            (EmojiRecentRepository.load(context) + ImeData.emojiByCategory.values.flatten()).distinct()
        } else {
            val catalog = ImeData.emojiByCategory[emojiCategory].orEmpty()
            if (emojiCategory == "笑脸") (listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣",
                "🥹", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍",
                "🥰", "😘", "😗", "😙", "😚", "☺️", "😛", "😝",
                "😜", "🤪", "😳", "🥺", "🤓", "😎", "🥸", "🤩",
                "🥳", "😏", "😒", "😞", "😔", "😟", "😕", "🙁",
            ) + catalog).distinct() else catalog
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
            LinearLayout.LayoutParams(toPx(24), toPx(24)).apply {
                bottomMargin = toPx(10)
            },
        )
        card.addView(
            TextView(context).apply {
                text = label
                typeface = android.graphics.Typeface.DEFAULT
                textSize = ImeTypographyTokens.BODY_SP
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

    private fun keyboardChoice(mode: KeyboardMode, label: String): View {
        val selected = currentMode() == mode
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(toPx(14), toPx(12), toPx(14), toPx(8))
            tag = if (selected) "keyboard-choice-selected" else "keyboard-choice"
            contentDescription = "$label，${if (selected) "已选中" else "未选中"}"
            isClickable = true
            isFocusable = true
            setOnClickListener { onFeedback(); onModeSelected(mode) }
            addView(object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: Canvas) {
                    val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    val tokens = ImeTheme.IOS.tokens(ImeSettingsRepository.loadAppearance(context), dark, AccentPalette.parse(ImeSettingsRepository.loadSkinColor(context)))
                    val h = height / 4f
                    paint.color = tokens.functionKeyBackground
                    for (i in 0..3) {
                        val y = i * h
                        val w = if (mode == KeyboardMode.PINYIN_9 || mode == KeyboardMode.DIGITS) width * 0.19f else width * (0.12f + 0.03f * i)
                        canvas.drawRoundRect(0f, y, w, y + h * 0.72f, toPx(2).toFloat(), toPx(2).toFloat(), paint)
                        if (mode != KeyboardMode.DIGITS || i == 0 || i == 3) {
                            paint.color = if (selected && i >= 2) tokens.primary else tokens.functionKeyBackground
                            canvas.drawRoundRect(width * 0.81f, y, width.toFloat(), y + h * 0.72f, toPx(2).toFloat(), toPx(2).toFloat(), paint)
                            paint.color = tokens.functionKeyBackground
                        }
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { text = label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, toPx(28), 1f))
                addView(TextView(context).apply {
                    text = if (selected) "✓" else "○"
                    tag = if (selected) "keyboard-radio-selected" else "panel-note"
                    gravity = Gravity.CENTER; textSize = 20f
                }, LinearLayout.LayoutParams(toPx(24), toPx(24)))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(28)))
        }
    }

    private fun symbolItems(category: String): List<String> = when (category) {
        "中文" -> listOf("，", "。", "、", "；", "：", "？", "！", "…", "—", "～", "·", "「", "」", "『", "』", "（", "）", "《", "》", "【", "】", "“", "”", "‘", "’")
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
            ImeData.symbols["几何图形"].orEmpty(),
            ImeData.symbols["箭头线条"].orEmpty(),
            ImeData.symbols["括号边框"].orEmpty(),
        ).flatten()
        "网络颜文字" -> ImeData.symbols["网络颜文字"].orEmpty()
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
}
