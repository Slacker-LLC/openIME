package llc.slacker.openime.panel

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import llc.slacker.openime.R
import llc.slacker.openime.data.ClipboardEntry
import llc.slacker.openime.data.ClipboardHistoryRepository
import llc.slacker.openime.data.QuickPhrase
import llc.slacker.openime.data.QuickPhraseRepository
import llc.slacker.openime.setup.SetupUi
import llc.slacker.openime.text.HanToken
import llc.slacker.openime.text.HanWordSegmenter
import llc.slacker.openime.text.TextWordIndex
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeTypographyTokens
import llc.slacker.openime.widget.ImeKeyView
import llc.slacker.openime.widget.WordFlowLayout

/**
 * Owns clipboard/quick-phrase panel presentation and transient load state.
 *
 * Repository I/O stays on the existing repositories; editor mutation is still
 * routed through the host's onCharacter callback.
 */
internal class ClipboardPanelController(
    private val context: Context,
    private val expandedPanel: LinearLayout,
    private val toPx: (Int) -> Int,
    private val panelBodyHeightPx: () -> Int,
    private val createHeader: (String) -> LinearLayout,
    private val createKey: (
        text: String,
        textSize: Float?,
        onTap: () -> Unit,
    ) -> ImeKeyView,
    private val createPanelButton: (String, Float, Boolean) -> TextView,
    private val createSectionTitle: (String) -> TextView,
    private val createChipScroll: (
        List<String>,
        String,
        (String) -> Unit,
    ) -> HorizontalScrollView,
    private val createVerticalScroll: (View, String) -> ScrollView,
    private val rememberVerticalScroll: (ScrollView, String) -> Unit,
    private val onCharacter: (String) -> Unit,
    private val onOpenQuickPhraseEditor: (QuickPhrase?) -> Unit,
    private val onFeedback: () -> Unit,
    private val applyTheme: () -> Unit,
    private val onHierarchyRebuilt: () -> Unit,
    private val onContentLoaded: () -> Unit,
    private val focusEntryPoint: () -> Unit,
) {
    private var tab = 0
    private var loadGeneration = 0

    /** The entry whose text is being split into words, when that view is open. */
    private var wordSegEntry: ClipboardEntry? = null

    /** The words of [wordSegEntry], in the order they read. */
    private var wordSegTokens: List<HanToken> = emptyList()

    /** The token range the finger has swept, when one has. */
    private var wordSegSelection: IntRange? = null

    /** The token the current slide started on. */
    private var wordSegAnchor: Int? = null

    /** 0 = clipboard history, 1 = quick phrases; applied on the next [render]. */
    fun selectTab(index: Int) {
        tab = index.coerceIn(0, 1)
    }

    /** The panel was closed or replaced: abandon the load and any 拆词 view with it. */
    fun invalidatePendingLoad() {
        loadGeneration++
        wordSegEntry = null
        wordSegTokens = emptyList()
        wordSegSelection = null
        wordSegAnchor = null
    }

    /** A copy event must not replace an in-progress split selection or phrase tab. */
    fun refreshHistory() {
        if (tab == 0 && wordSegEntry == null &&
            expandedPanel.findViewWithTag<View>("clipboard-clear-confirmation") == null) render(true)
    }

    fun render(reusePanel: Boolean = false) {
        expandedPanel.removeAllViews()
        val header = createHeader("")
        while (header.childCount > 1) header.removeViewAt(header.childCount - 1)
        if (wordSegEntry == null) {
            header.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; tag = "segmented-track"
                setPadding(toPx(2), toPx(2), toPx(2), toPx(2))
                listOf("剪贴板", "常用语").forEachIndexed { index, label ->
                    addView(TextView(context).apply {
                        text = label; textSize = ImeTypographyTokens.BODY_SP; gravity = Gravity.CENTER; includeFontPadding = false
                        tag = if (tab == index) "segment-selected" else "segment-option"
                        contentDescription = label; isClickable = true; isFocusable = true
                        setOnClickListener { onFeedback(); tab = index; render(true) }
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                }
            }, LinearLayout.LayoutParams(0, toPx(34), 1f).apply { marginStart = toPx(36); marginEnd = toPx(20) })
        }
        header.addView(TextView(context).apply {
            val splitting = wordSegEntry != null
            text = when {
                splitting -> "返回"
                tab == 0 -> "↻"
                else -> "+ 新增"
            }
            tag = when {
                splitting -> "word-seg-back"
                tab == 0 -> "clipboard-refresh"
                else -> "quick-phrase-add"
            }
            textSize = if (tab == 0 && !splitting) 24f else 12f; gravity = Gravity.CENTER
            contentDescription = when {
                splitting -> "返回剪贴板列表"
                tab == 0 -> "重新读取剪贴板"
                else -> "新增常用语"
            }
            isClickable = true; isFocusable = true
            setOnClickListener {
                onFeedback()
                when {
                    splitting -> {
                        wordSegEntry = null
                        wordSegTokens = emptyList()
                        wordSegSelection = null
                        render(true)
                    }
                    tab == 0 -> render(true)
                    else -> onOpenQuickPhraseEditor(null)
                }
            }
        }, LinearLayout.LayoutParams(toPx(if (tab == 0 && wordSegEntry == null) 48 else 70), toPx(34)))
        expandedPanel.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(48)))
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(toPx(12), toPx(10), toPx(12), toPx(6))
            tag = "clipboard-panel"
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        when {
            wordSegEntry != null -> renderWordSegmentation(column, body)
            tab == 0 -> renderClipboardHistory(column, body)
            else -> renderQuickPhrases(column)
        }

        val scroll = createVerticalScroll(column, "clipboard-scroll")
        rememberVerticalScroll(scroll, "clipboard:$tab")
        body.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        expandedPanel.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                panelBodyHeightPx(),
            ),
        )
        expandedPanel.post { expandedPanel.requestLayout() }
        applyTheme()
        onHierarchyRebuilt()
    }

    private fun renderClipboardHistory(
        column: LinearLayout,
        body: LinearLayout,
    ) {
        column.addView(createSectionTitle("最近复制"), wrapParams())
        // The reference tells the gesture on this list itself
        // (`clipboard_history_word_seg_tip`「长按列表可进入拆词」).
        column.addView(
            TextView(context).apply {
                text = "长按任意一条可进入拆词"
                textSize = ImeTypographyTokens.PANEL_NOTE_SP
                setPadding(toPx(4), 0, toPx(4), toPx(4))
                tag = "panel-note"
            },
            wrapParams(),
        )
        val loadingHint = TextView(context).apply {
            text = "正在读取剪贴板…"
            textSize = ImeTypographyTokens.PANEL_BODY_SP
            setPadding(toPx(4), toPx(6), toPx(4), 0)
            tag = "panel-note"
        }
        column.addView(loadingHint, wrapParams())

        val generation = ++loadGeneration
        column.post {
            // The panel may have been replaced before this posted task gets a
            // main-thread turn. Do not even read/capture the clipboard for a
            // stale surface; the existing generation check below still guards
            // the asynchronous result on its way back.
            if (
                generation != loadGeneration ||
                tab != 0 ||
                column.parent == null
            ) {
                return@post
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val primaryClip = runCatching { clipboard?.primaryClip }.getOrNull()
            Thread {
                val historyResult = runCatching {
                    if (primaryClip != null) {
                        ClipboardHistoryRepository.captureClip(context, primaryClip)
                    }
                    ClipboardHistoryRepository.load(context)
                }
                expandedPanel.post {
                    if (
                        generation != loadGeneration ||
                        tab != 0 ||
                        column.parent == null
                    ) {
                        return@post
                    }
                    (loadingHint.parent as? ViewGroup)?.removeView(loadingHint)
                    when {
                        historyResult.isFailure -> {
                            column.addView(
                                statusText(
                                    "暂时无法读取剪贴板，请重试。",
                                    error = true,
                                ),
                                wrapParams(),
                            )
                            addRefreshAction(column)
                        }
                        historyResult.getOrThrow().isEmpty() -> {
                            column.addView(
                                statusText(
                                    "暂无剪贴历史；复制文本后重新打开这里即可看到。",
                                    error = false,
                                ),
                                wrapParams(),
                            )
                            addRefreshAction(column)
                        }
                        else -> {
                            historyResult.getOrThrow().forEach { entry ->
                                column.addView(
                                    historyCard(entry),
                                    LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                    ).apply { bottomMargin = toPx(7) },
                                )
                            }
                            addRetentionControls(body)
                        }
                    }
                    applyTheme()
                    onContentLoaded()
                }
            }.apply { isDaemon = true }.start()
        }
    }

    /**
     * 拆词: the entry's text split into the words the tables carry, with the
     * reference's own gesture on them — 「滑动选词」, slide across the words to
     * select the run you want (`word_segmentation_title`), then hand the run to
     * the quick phrases (`word_segmentation_add_to_common_phrase`
     * 「添加到常用语」, disabled until something is selected —
     * `word_segmentation_add_to_common_phrase_bg_disabled`).
     *
     * The words are the panel's own rows rather than the reference's item grid,
     * but the selection is the reference's: a slide, not a tap per word.
     */
    private fun renderWordSegmentation(column: LinearLayout, body: LinearLayout) {
        val entry = wordSegEntry ?: return
        column.addView(createSectionTitle("滑动选词"), wrapParams())
        val loadingHint = TextView(context).apply {
            text = "正在分词…"
            textSize = ImeTypographyTokens.PANEL_NOTE_SP
            setPadding(toPx(4), 0, toPx(4), toPx(4))
            tag = "panel-note"
        }
        column.addView(loadingHint, wrapParams())
        val flow = WordFlowLayout(context).apply {
            tag = "word-seg-flow"
            setPadding(toPx(2), toPx(6), toPx(2), toPx(6))
        }
        column.addView(
            flow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val addButton = createPanelButton("添加到常用语", ImeTypographyTokens.BODY_SP, false).apply {
            tag = "word-seg-add"
            contentDescription = "添加到常用语"
        }
        val addRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "word-seg-actions"
        }
        addRow.addView(
            addButton,
            LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f),
        )
        column.addView(
            addRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ).apply { topMargin = toPx(8) },
        )

        val generation = ++loadGeneration
        flow.setOnTouchListener { _, event -> onWordSegTouch(flow, event) }
        Thread {
            val words = runCatching { TextWordIndex.words(context) }.getOrNull()
            val tokens = if (words == null) {
                emptyList()
            } else {
                HanWordSegmenter.segment(entry.text) { it in words }
            }
            expandedPanel.post {
                if (generation != loadGeneration || wordSegEntry !== entry || column.parent == null) {
                    return@post
                }
                (loadingHint.parent as? ViewGroup)?.removeView(loadingHint)
                if (tokens.isEmpty()) {
                    column.addView(
                        statusText("这段内容没有可分词的文字。", error = false),
                        wrapParams(),
                    )
                    return@post
                }
                wordSegTokens = tokens
                tokens.forEachIndexed { index, token -> flow.addView(wordTokenView(index, token)) }
                syncWordSegSelection()
                applyTheme()
                onContentLoaded()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun wordTokenView(index: Int, token: HanToken): TextView = TextView(context).apply {
        text = token.text
        textSize = ImeTypographyTokens.TITLE_SP
        gravity = Gravity.CENTER
        includeFontPadding = false
        tag = if (token.selectable) "word-token" else "word-token-plain"
        contentDescription = token.text
        setPadding(toPx(8), toPx(5), toPx(8), toPx(5))
        minimumHeight = toPx(28)
    }.apply {
        val params = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.setMargins(0, toPx(2), toPx(4), toPx(2))
        layoutParams = params
        this.tag = "${this.tag}:$index"
    }

    /**
     * The slide: the word under the finger at DOWN is the anchor, every
     * selectable word the finger then reaches extends the run, and the run
     * stays when the finger lifts so the action can be taken after.
     */
    private fun onWordSegTouch(flow: WordFlowLayout, event: MotionEvent): Boolean {
        val index = flow.childIndexAt(event.x, event.y)
        val selectable = index >= 0 && wordSegTokens.getOrNull(index)?.selectable == true
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!selectable) {
                    false
                } else {
                    wordSegAnchor = index
                    wordSegSelection = index..index
                    syncWordSegSelection()
                    true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val anchor = wordSegAnchor
                if (anchor != null && selectable) {
                    wordSegSelection = minOf(anchor, index)..maxOf(anchor, index)
                    syncWordSegSelection()
                }
                anchor != null
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                wordSegAnchor = null
                true
            }
            else -> false
        }
    }

    /** Paints the selection onto the word rows and the action button. */
    private fun syncWordSegSelection() {
        val flow = expandedPanel.findViewWithTag<View>("word-seg-flow") as? WordFlowLayout ?: return
        val selection = wordSegSelection
        for (index in 0 until flow.childCount) {
            val token = wordSegTokens.getOrNull(index) ?: continue
            val view = flow.getChildAt(index) as? TextView ?: continue
            val selected = token.selectable && selection != null && index in selection
            view.tag = when {
                !token.selectable -> "word-token-plain:$index"
                selected -> "word-token-selected:$index"
                else -> "word-token:$index"
            }
            view.contentDescription = if (selected) "${token.text}，已选" else token.text
        }
        val text = selectedWordSegText()
        val add = expandedPanel.findViewWithTag<View>("word-seg-add")
        if (add != null) {
            add.isEnabled = text != null
            add.isClickable = text != null
            add.alpha = if (text != null) 1f else llc.slacker.openime.theme.ImeSurfacePolicy.DISABLED_ALPHA
            add.setOnClickListener {
                val value = selectedWordSegText() ?: return@setOnClickListener
                onFeedback()
                QuickPhraseRepository.upsert(context, id = 0L, category = "常用", text = value)
                wordSegEntry = null
                wordSegTokens = emptyList()
                wordSegSelection = null
                tab = 1
                render(reusePanel = true)
            }
        }
        applyTheme()
    }

    /**
     * The text the selected words cover, exactly as the entry spells it: the
     * span from the first selected word to the last, so the punctuation written
     * between two words comes along rather than being stitched out.
     */
    private fun selectedWordSegText(): String? {
        val selection = wordSegSelection ?: return null
        val indexes = wordSegTokens.indices.filter { it in selection && wordSegTokens[it].selectable }
        if (indexes.isEmpty()) return null
        val text = wordSegEntry?.text ?: return null
        val first = wordSegTokens[indexes.first()]
        val last = wordSegTokens[indexes.last()]
        return text.substring(first.start, last.end)
    }

    private fun addRefreshAction(column: LinearLayout) {
        column.addView(
            createPanelButton("重新读取", ImeTypographyTokens.CAPTION_SP, true).apply {
                tag = "clipboard-refresh"
                contentDescription = "重新读取剪贴板"
                setOnClickListener {
                    render(reusePanel = true)
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                toPx(36),
            ).apply { topMargin = toPx(8) },
        )
    }

    private fun renderQuickPhrases(column: LinearLayout) {
        val phrases = QuickPhraseRepository.load(context)
        if (phrases.isEmpty()) {
            column.addView(
                statusText(
                    "还没有常用语；点击上方按钮添加后即可一键输入。",
                    error = false,
                    textSize = ImeTypographyTokens.PANEL_NOTE_SP,
                ),
                wrapParams(),
            )
        }

        column.addView(createSectionTitle("${phrases.size} 条常用语 · 点选即输入"), wrapParams())
        phrases.groupBy { it.category }.forEach { (category, grouped) ->
            column.addView(createSectionTitle(category), wrapParams())
            grouped.forEach { phrase ->
                column.addView(
                    quickPhraseRow(phrase),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        toPx(60),
                    ).apply { bottomMargin = toPx(7) },
                )
            }
        }
    }

    private fun quickPhraseRow(phrase: QuickPhrase): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            tag = "phrase-card"; setPadding(toPx(14), 0, toPx(4), 0)
            addView(TextView(context).apply {
                text = phrase.text; textSize = ImeTypographyTokens.TITLE_SP; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                tag = "phrase:${phrase.id}"; contentDescription = "常用语：${phrase.text}"
                isClickable = true; isFocusable = true
                setOnClickListener { onFeedback(); onCharacter(phrase.text) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(iconButton(R.drawable.ic_edit, "编辑常用语", "phrase-edit:${phrase.id}") { onOpenQuickPhraseEditor(phrase) },
                LinearLayout.LayoutParams(toPx(40), toPx(48)))
            addView(iconButton(R.drawable.ic_delete, "删除常用语", "phrase-delete:${phrase.id}") {
                val dialog = AlertDialog.Builder(context).setTitle("删除常用语？").setMessage("“${phrase.text}” 将被移除。")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> QuickPhraseRepository.remove(context, phrase.id); render(true) }.create()
                dialog.setOnShowListener { SetupUi.styleDialog(dialog, context, destructivePositive = true) }
                SetupUi.showDialog(dialog, context, expandedPanel)
            }, LinearLayout.LayoutParams(toPx(40), toPx(48)))
        }

    private fun historyCard(entry: ClipboardEntry): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(toPx(14), toPx(4), toPx(4), toPx(4)); minimumHeight = toPx(56)
            tag = "clip-card"; contentDescription = "剪贴板：${entry.text}，点击使用，长按拆词"
            isClickable = true; isFocusable = true
            setOnClickListener { onFeedback(); onCharacter(entry.text) }
            setOnLongClickListener {
                onFeedback()
                wordSegEntry = entry
                wordSegTokens = emptyList()
                wordSegSelection = null
                render(true)
                true
            }
            addView(TextView(context).apply { text = entry.text; textSize = ImeTypographyTokens.TITLE_SP; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton(R.drawable.ic_pin, if (entry.pinned) "取消置顶" else "置顶", "clip-pin:${entry.text}") {
                ClipboardHistoryRepository.togglePin(context, entry.text); render(true)
            }.apply { isSelected = entry.pinned }, LinearLayout.LayoutParams(toPx(40), toPx(48)))
        }

    private fun iconButton(icon: Int, label: String, tagValue: String, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(icon); scaleType = ImageView.ScaleType.CENTER_INSIDE
            tag = tagValue; contentDescription = label; isClickable = true; isFocusable = true
            setOnClickListener { onFeedback(); onClick() }
        }

    private fun addRetentionControls(body: LinearLayout) {
        if (body.findViewWithTag<View>("clipboard-retention-actions") != null) return
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "clipboard-retention-actions"
        }
        row.addView(
            retentionAction("清除未固定", destructive = false) {
                ClipboardHistoryRepository.clearUnpinned(context)
                render(reusePanel = true)
                focusEntryPoint()
            },
            LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f).apply {
                marginEnd = toPx(6)
            },
        )
        row.addView(
            retentionAction("清空全部", destructive = true) {
                showClearConfirmation(body)
            },
            LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f),
        )
        // The buttons are painted 36dp inside this 48dp row (see the theme),
        // so the visible gap above them is unchanged.
        body.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ),
        )
        applyTheme()
    }

    private fun showClearConfirmation(body: LinearLayout) {
        body.findViewWithTag<View>("clipboard-retention-actions")?.let(body::removeView)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "clipboard-clear-confirmation"
            contentDescription = "确认清空全部剪贴历史"
        }
        row.addView(
            retentionAction("取消", destructive = false) {
                render(reusePanel = true)
                focusEntryPoint()
            },
            LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f).apply {
                marginEnd = toPx(6)
            },
        )
        row.addView(
            retentionAction("确认清空", destructive = true) {
                ClipboardHistoryRepository.clearAll(context)
                render(reusePanel = true)
                focusEntryPoint()
            }.apply {
                tag = "clipboard-clear-confirm"
                contentDescription = "确认清空全部剪贴历史"
            },
            LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f),
        )
        body.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ),
        )
        applyTheme()
        row.findViewWithTag<View>("clipboard-clear-confirm")?.requestFocus()
    }

    private fun retentionAction(
        label: String,
        destructive: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        textSize = ImeTypographyTokens.PANEL_NOTE_SP
        gravity = Gravity.CENTER
        minHeight = 0
        minimumHeight = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
        isClickable = true
        isFocusable = true
        tag = if (destructive) {
            "clipboard-retention-destructive"
        } else {
            "clipboard-retention-action"
        }
        contentDescription = if (destructive) {
            "$label，删除全部剪贴历史"
        } else {
            "$label，保留已固定内容"
        }
        setOnClickListener {
            onFeedback()
            onClick()
        }
    }

    private fun statusText(
        value: String,
        error: Boolean,
        textSize: Float = 13f,
    ): TextView = TextView(context).apply {
        text = value
        this.textSize = textSize
        setPadding(toPx(4), toPx(6), toPx(4), 0)
        tag = if (error) "panel-error" else "panel-note"
    }

    private fun wrapParams() =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
}
