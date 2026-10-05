package llc.slacker.openime

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import llc.slacker.openime.core.CrashGuard
import llc.slacker.openime.core.ImeData
import llc.slacker.openime.data.RailSymbolRepository
import llc.slacker.openime.setup.SetupUi
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeSpacingTokens
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeTypographyTokens

/**
 * Edits the left symbol rail of the nine-key and stroke keyboards, opened from
 * the rail's ＋: add a symbol (typed, or one tap on a common mark), move it up
 * or down, remove it, or go back to the ten defaults. Every change is saved at
 * once and the keyboard picks it up when this page closes.
 */
class RailSymbolsActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(SetupUi.appearanceContext(newBase))
    }

    private lateinit var content: LinearLayout
    private lateinit var input: EditText
    private var savedScrollY = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        savedScrollY = savedInstanceState?.getInt(STATE_SCROLL, 0) ?: 0
        render(savedInstanceState?.getString(STATE_DRAFT).orEmpty())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_DRAFT, input.text.toString())
        outState.putInt(STATE_SCROLL, (content.parent as? ScrollView)?.scrollY ?: savedScrollY)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        LocalVoiceImeService.activeInstance?.refreshAuxiliaryContentFromActivity()
        super.onDestroy()
    }

    private fun render(draft: String = if (::input.isInitialized) input.text.toString() else "") {
        val symbols = RailSymbolRepository.load(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ImeSpacingTokens.LG_DP), 0, dp(ImeSpacingTokens.LG_DP), dp(ImeSpacingTokens.LG_DP))
        }
        content.addView(
            SetupUi.activityTopBar(context = this, title = "常用符号栏", onBack = ::finish),
            fullHeight(ImeGeometryTokens.TOP_BAR_HEIGHT_DP).apply { marginStart = -dp(16); marginEnd = -dp(16) },
        )
        content.addView(note("九键和笔画键盘左侧的符号栏，从上到下按这里的顺序排列。"), fullWrap().apply { topMargin = dp(4) })

        // Add: type anything short, or tap one of the common marks below.
        val form = card()
        input = EditText(this).apply {
            id = R.id.rail_symbol_editor
            hint = "输入符号，例如 ～"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            textSize = ImeTypographyTokens.TITLE_SP
            setText(draft)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    addTyped()
                    true
                } else {
                    false
                }
            }
        }
        SetupUi.styleInput(this, input)
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
            addView(SetupUi.primaryButton(this@RailSymbolsActivity, "添加") { addTyped() }, LinearLayout.LayoutParams(dp(88), dp(48)))
        }
        form.addView(addRow, fullWrap())
        val suggestions = (ImeData.symbols["常用"].orEmpty() + ImeData.symbols["中文标点"].orEmpty())
            .distinct()
            .filter { it !in symbols }
        if (suggestions.isNotEmpty()) {
            form.addView(note("点一下加入："), fullWrap().apply { topMargin = dp(12) })
            val grid = GridLayout(this).apply { columnCount = SUGGESTION_COLUMNS }
            suggestions.forEach { symbol ->
                grid.addView(
                    SetupUi.secondaryButton(this, symbol) { add(symbol) }.apply {
                        contentDescription = "加入 $symbol"
                    },
                    GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED),
                        GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    ).apply {
                        width = 0
                        height = dp(ImeGeometryTokens.TOUCH_TARGET_DP)
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    },
                )
            }
            form.addView(grid, fullWrap().apply { topMargin = dp(4) })
        }
        content.addView(form, fullWrap().apply { topMargin = dp(12) })

        content.addView(sectionTitle("符号栏（${symbols.size}）"), fullWrap().apply { topMargin = dp(18) })
        val list = card(padded = false)
        if (symbols.isEmpty()) {
            list.addView(note("符号栏是空的，上方添加，或恢复默认。").apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }, fullWrap())
        }
        symbols.forEachIndexed { index, symbol ->
            if (index > 0) {
                list.addView(
                    View(this).apply { setBackgroundColor(getColor(R.color.setup_input_line)); alpha = 0.25f },
                    fullHeight(1),
                )
            }
            list.addView(symbolRow(symbols, index), fullHeight(56))
        }
        content.addView(list, fullWrap().apply { topMargin = dp(6) })

        if (RailSymbolRepository.isCustomized(this)) {
            content.addView(
                SetupUi.secondaryButton(this, "恢复默认") {
                    RailSymbolRepository.reset(this)
                    render()
                },
                fullHeight(48).apply { topMargin = dp(16) },
            )
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.setup_page_bg))
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setOnScrollChangeListener { _, _, scrollY, _, _ -> savedScrollY = scrollY }
            addView(content)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.setup_page_bg))
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(
                SetupUi.secondaryButton(this@RailSymbolsActivity, "完成") { finish() },
                fullHeight(48).apply { setMargins(dp(16), dp(12), dp(16), dp(12)) },
            )
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
                    )
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom,
                    )
                }
                insets
            }
        })
        scroll.post { scroll.scrollTo(0, savedScrollY.coerceAtLeast(0)) }
    }

    private fun symbolRow(symbols: List<String>, index: Int): LinearLayout = LinearLayout(this).apply {
        val symbol = symbols[index]
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(8), 0)
        tag = "rail-symbol-row"
        addView(TextView(this@RailSymbolsActivity).apply {
            text = symbol
            textSize = ImeTypographyTokens.SYMBOL_SP
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(getColor(R.color.setup_title))
            contentDescription = "第 ${index + 1} 个：$symbol"
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        fun action(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
            addView(
                SetupUi.secondaryButton(this@RailSymbolsActivity, label, onClick).apply {
                    contentDescription = "$description $symbol"
                    isEnabled = enabled
                    alpha = if (enabled) 1f else ImeSurfacePolicy.DISABLED_ALPHA
                },
                LinearLayout.LayoutParams(dp(64), dp(ImeGeometryTokens.TOUCH_TARGET_DP)).apply { marginStart = dp(4) },
            )
        }
        action("上移", "上移", index > 0) { move(symbols, index, -1) }
        action("下移", "下移", index < symbols.lastIndex) { move(symbols, index, 1) }
        action("删除", "删除", true) {
            RailSymbolRepository.save(this@RailSymbolsActivity, symbols.filterIndexed { i, _ -> i != index })
            render()
        }
    }

    private fun move(symbols: List<String>, index: Int, by: Int) {
        val target = index + by
        if (target !in symbols.indices) return
        val reordered = symbols.toMutableList().apply { add(target, removeAt(index)) }
        RailSymbolRepository.save(this, reordered)
        render()
    }

    private fun addTyped() {
        val value = input.text.toString().trim()
        when {
            value.isEmpty() -> input.error = "请输入符号"
            value.codePointCount(0, value.length) > RailSymbolRepository.MAX_SYMBOL_LENGTH ->
                input.error = "最多 ${RailSymbolRepository.MAX_SYMBOL_LENGTH} 个字"
            else -> {
                input.text.clear()
                add(value)
            }
        }
    }

    private fun add(symbol: String) {
        val current = RailSymbolRepository.load(this)
        if (current.size >= RailSymbolRepository.MAX_SYMBOLS) {
            input.error = "符号栏最多 ${RailSymbolRepository.MAX_SYMBOLS} 个"
            return
        }
        // A symbol already in the rail moves to the end instead of doubling.
        RailSymbolRepository.save(this, current.filter { it != symbol } + symbol)
        render(draft = "")
    }

    private fun card(padded: Boolean = true) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (padded) setPadding(dp(16), dp(16), dp(16), dp(16))
        background = SetupUi.rounded(getColor(R.color.setup_surface), dp(16).toFloat())
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = ImeTypographyTokens.SMALL_SP
        setTextColor(getColor(R.color.setup_body))
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = ImeTypographyTokens.SMALL_SP
        setTextColor(getColor(R.color.setup_body))
        setPadding(dp(4), 0, 0, 0)
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun dp(value: Int): Int = SetupUi.dp(this, value)

    private fun fullWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun fullHeight(height: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(height),
    )

    private companion object {
        const val STATE_DRAFT = "draft"
        const val STATE_SCROLL = "scroll_y"
        const val SUGGESTION_COLUMNS = 6
    }
}
