package llc.slacker.openime

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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

    fun render(reusePanel: Boolean = false) {
        if (!reusePanel || expandedPanel.childCount == 0) {
            expandedPanel.removeAllViews()
            expandedPanel.addView(
                createHeader("剪贴板"),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(48),
                ),
            )
        } else {
            while (expandedPanel.childCount > 1) {
                expandedPanel.removeViewAt(expandedPanel.childCount - 1)
            }
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(toPx(10), toPx(10), toPx(10), toPx(10))
            tag = "clipboard-panel"
        }
        val tabs = createChipScroll(
            listOf("剪贴板", "常用语"),
            if (tab == 0) "剪贴板" else "常用语",
        ) { label ->
            tab = if (label == "剪贴板") 0 else 1
            render(reusePanel = true)
        }
        body.addView(
            tabs,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(48),
            ).apply { bottomMargin = toPx(8) },
        )

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (tab == 0) {
            renderClipboardHistory(column, body)
        } else {
            renderQuickPhrases(column)
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
        if (tab == 0) addRetentionControls(body)

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
        val loadingHint = TextView(context).apply {
            text = "正在读取剪贴板…"
            textSize = 13f
            setPadding(toPx(4), toPx(6), toPx(4), 0)
            tag = "panel-note"
        }
        column.addView(loadingHint, wrapParams())

        val generation = ++loadGeneration
        column.post {
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

    private fun addRefreshAction(column: LinearLayout) {
        column.addView(
            createPanelButton("重新读取", 12f, true).apply {
                tag = "clipboard-refresh"
                contentDescription = "重新读取剪贴板"
                setOnClickListener {
                    onFeedback()
                    render(reusePanel = true)
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                toPx(48),
            ).apply { topMargin = toPx(8) },
        )
    }

    private fun renderQuickPhrases(column: LinearLayout) {
        column.addView(
            createPanelButton("新增常用语", 13f, true).apply {
                tag = "quick-phrase-add"
                setOnClickListener {
                    onFeedback()
                    onOpenQuickPhraseEditor(null)
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(48),
            ).apply { bottomMargin = toPx(8) },
        )

        val phrases = QuickPhraseRepository.load(context)
        if (phrases.isEmpty()) {
            column.addView(
                statusText(
                    "还没有常用语；点击上方按钮添加后即可一键输入。",
                    error = false,
                    textSize = 12f,
                ),
                wrapParams(),
            )
        }

        phrases.groupBy { it.category }.forEach { (category, grouped) ->
            column.addView(createSectionTitle(category), wrapParams())
            grouped.forEach { phrase ->
                column.addView(
                    quickPhraseRow(phrase),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        toPx(48),
                    ).apply { bottomMargin = toPx(7) },
                )
            }
        }
    }

    private fun quickPhraseRow(phrase: QuickPhrase): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "phrase-card"
            addView(
                createKey(phrase.text, 13f) {
                    onCharacter(phrase.text)
                }.apply {
                    setPadding(toPx(12), 0, toPx(12), 0)
                    tag = "phrase:${phrase.id}"
                },
                LinearLayout.LayoutParams(0, toPx(48), 1f).apply {
                    marginEnd = toPx(5)
                },
            )
            addView(
                createPanelButton("编辑", 11f, true).apply {
                    tag = "phrase-edit:${phrase.id}"
                    setOnClickListener {
                        onFeedback()
                        onOpenQuickPhraseEditor(phrase)
                    }
                },
                LinearLayout.LayoutParams(toPx(48), toPx(48)).apply {
                    marginEnd = toPx(5)
                },
            )
            addView(
                createPanelButton("删除", 11f, true).apply {
                    tag = "phrase-delete:${phrase.id}"
                    setOnClickListener {
                        onFeedback()
                        val dialog = AlertDialog.Builder(context)
                            .setTitle("删除常用语？")
                            .setMessage(phrase.text)
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除") { _, _ ->
                                QuickPhraseRepository.remove(context, phrase.id)
                                render(reusePanel = true)
                            }
                            .create()
                        dialog.setOnShowListener {
                            SetupUi.styleDialog(
                                dialog,
                                context,
                                destructivePositive = true,
                            )
                        }
                        dialog.show()
                    }
                },
                LinearLayout.LayoutParams(toPx(48), toPx(48)),
            )
        }

    private fun historyCard(entry: ClipboardEntry): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(toPx(12), toPx(10), toPx(12), toPx(8))
            minimumHeight = toPx(70)
            tag = "clip-card"
            contentDescription = "剪贴板：${entry.text}，点击使用"
            if (Build.VERSION.SDK_INT >= 30) {
                stateDescription = if (entry.pinned) "已置顶" else "未置顶"
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onFeedback()
                onCharacter(entry.text)
            }

            addView(
                TextView(context).apply {
                    text = entry.text
                    textSize = 13f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
                wrapParams(),
            )

            val meta = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            meta.addView(
                TextView(context).apply {
                    text = if (entry.pinned) {
                        "已置顶"
                    } else {
                        DateUtils.getRelativeTimeSpanString(
                            entry.timestamp,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                        )
                    }
                    textSize = 11f
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            meta.addView(
                createPanelButton(
                    if (entry.pinned) "取消置顶" else "置顶",
                    10f,
                    true,
                ).apply {
                    tag = "clip-pin:${entry.text}"
                    setOnClickListener {
                        onFeedback()
                        ClipboardHistoryRepository.togglePin(context, entry.text)
                        render(reusePanel = true)
                    }
                },
                wrapParams(),
            )
            meta.addView(
                createPanelButton("使用", 10f, true).apply {
                    tag = "clip-use:${entry.text}"
                    setOnClickListener {
                        onFeedback()
                        onCharacter(entry.text)
                    }
                },
                wrapParams(),
            )
            addView(meta, wrapParams())
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
            LinearLayout.LayoutParams(0, toPx(48), 1f).apply {
                marginEnd = toPx(6)
            },
        )
        row.addView(
            retentionAction("清空全部", destructive = true) {
                showClearConfirmation(body)
            },
            LinearLayout.LayoutParams(0, toPx(48), 1f),
        )
        body.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(48),
            ).apply { topMargin = toPx(6) },
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
            LinearLayout.LayoutParams(0, toPx(48), 1f).apply {
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
            LinearLayout.LayoutParams(0, toPx(48), 1f),
        )
        body.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(48),
            ).apply { topMargin = toPx(6) },
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
        textSize = 12f
        gravity = Gravity.CENTER
        minHeight = toPx(48)
        minimumHeight = toPx(48)
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
