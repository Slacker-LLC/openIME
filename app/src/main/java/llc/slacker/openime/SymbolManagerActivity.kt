package llc.slacker.openime

import android.app.Activity
import android.content.ClipData
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Touch-friendly manager for user symbols and their order. */
class SymbolManagerActivity : Activity() {
    private val density by lazy { resources.displayMetrics.density }
    private lateinit var content: LinearLayout
    private lateinit var groupEdit: EditText
    private lateinit var symbolEdit: EditText
    private var editingId = 0L
    private var savedScrollY = 0

    private fun dp(value: Int): Int = (value * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedScrollY = savedInstanceState?.getInt("scroll_y", 0) ?: 0
        render()
        savedInstanceState?.let {
            editingId = it.getLong("editing_id")
            groupEdit.setText(it.getString("draft_group", ""))
            symbolEdit.setText(it.getString("draft_symbol", ""))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("editing_id", editingId)
        outState.putString("draft_group", groupEdit.text.toString())
        outState.putString("draft_symbol", symbolEdit.text.toString())
        outState.putInt("scroll_y", (content.parent as? ScrollView)?.scrollY ?: savedScrollY)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val draftGroup = if (::groupEdit.isInitialized) groupEdit.text.toString() else ""
        val draftSymbol = if (::symbolEdit.isInitialized) symbolEdit.text.toString() else ""
        val accent = SetupUi.accent(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val title = TextView(this).apply {
            text = "自定义符号"
            textSize = 22f
            setTextColor(getColor(R.color.setup_title))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            if (Build.VERSION.SDK_INT >= 28) setAccessibilityHeading(true)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageButton(this@SymbolManagerActivity).apply {
                setImageResource(R.drawable.ic_arrow_back)
                imageTintList = ColorStateList.valueOf(accent)
                contentDescription = "返回"
                setMinimumWidth(dp(48))
                setMinimumHeight(dp(48))
                isClickable = true
                isFocusable = true
                applySelectableBackground(this)
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    finish()
                }
            }, LinearLayout.LayoutParams(dp(48), dp(56)))
            addView(title, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        content.addView(header, fullHeight(56).apply { bottomMargin = dp(8) })
        content.addView(TextView(this).apply {
            text = "可添加、分类、固定和删除。点击箭头调整顺序，也可长按符号行拖动排序。"
            textSize = 13f
            setTextColor(getColor(R.color.setup_body))
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, dp(12))
        }, fullWrap())
        content.addView(fieldLabel("分组（可选）"), fullWrap())
        groupEdit = EditText(this).apply {
            hint = "分组，例如：常用箭头"
            setSingleLine(true)
            textSize = 16f
            setText(draftGroup)
        }
        SetupUi.styleInput(this, groupEdit)
        content.addView(groupEdit, fullHeight(56).apply { bottomMargin = dp(10) })
        content.addView(fieldLabel("符号或自定义文本"), fullWrap().apply { bottomMargin = dp(2) })
        symbolEdit = EditText(this).apply {
            hint = "符号，例如：⇢ 或 自定义文本"
            setSingleLine(true)
            textSize = 20f
            setText(draftSymbol)
        }
        SetupUi.styleInput(this, symbolEdit)
        content.addView(symbolEdit, fullHeight(58).apply { bottomMargin = dp(12) })
        content.addView(SetupUi.primaryButton(this, "保存符号") {
            if (symbolEdit.text.isNullOrBlank()) {
                symbolEdit.error = "请输入符号或自定义文本"
                symbolEdit.requestFocus()
            } else if (CustomSymbolRepository.upsert(
                    this@SymbolManagerActivity,
                    editingId,
                    groupEdit.text.toString(),
                    symbolEdit.text.toString(),
                ) != null
            ) {
                groupEdit.text.clear()
                symbolEdit.text.clear()
                editingId = 0L
                render()
            }
        }, fullHeight(52).apply { bottomMargin = dp(20) })
        content.addView(TextView(this).apply {
            text = "已保存符号"
            textSize = 16f
            setTextColor(getColor(R.color.setup_title))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
            if (Build.VERSION.SDK_INT >= 28) setAccessibilityHeading(true)
        }, fullWrap())

        CustomSymbolRepository.load(this)
            .groupBy { it.group }
            .forEach { (group, symbols) ->
                content.addView(TextView(this).apply {
                    text = group
                    textSize = 14f
                    setTextColor(accent)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(8), 0, dp(4))
                }, fullWrap())
                symbols.forEach { item ->
                    content.addView(symbolRow(item, accent), fullWrap().apply { bottomMargin = dp(12) })
                }
            }
        content.addView(SetupUi.primaryButton(this, "完成") {
            finish()
        }, fullHeight(52).apply { topMargin = dp(12) })
        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.setup_page_bg))
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setOnScrollChangeListener { _, _, scrollY, _, _ -> savedScrollY = scrollY }
            setOnApplyWindowInsetsListener { view, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
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
            addView(content)
        }
        setContentView(scroll)
        scroll.post { scroll.scrollTo(0, savedScrollY.coerceAtLeast(0)) }
    }

    private fun symbolRow(item: CustomSymbol, accent: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(12), dp(10))
        background = SetupUi.rounded(
            getColor(R.color.setup_surface),
            dp(18).toFloat(),
            getColor(R.color.setup_input_line),
        )
        addView(TextView(this@SymbolManagerActivity).apply {
            text = item.symbol
            textSize = 21f
            setTextColor(getColor(R.color.setup_title))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            contentDescription = "自定义符号：${item.symbol}，${if (item.pinned) "已固定" else "未固定"}"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }, fullWrap())
        addView(TextView(this@SymbolManagerActivity).apply {
            text = if (item.pinned) "已固定" else "未固定"
            textSize = 12f
            setTextColor(if (item.pinned) accent else getColor(R.color.setup_body))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, fullWrap())
        val actions = LinearLayout(this@SymbolManagerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun action(label: String, onClick: () -> Unit) {
            actions.addView(smallButton(label, onClick), LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(4)
            })
        }
        action("编辑") {
            editingId = item.id
            groupEdit.setText(item.group)
            symbolEdit.setText(item.symbol)
            symbolEdit.requestFocus()
        }
        action(if (item.pinned) "取消固定" else "固定") {
            CustomSymbolRepository.togglePinned(this@SymbolManagerActivity, item.id)
            render()
        }
        action("上移") {
            CustomSymbolRepository.move(this@SymbolManagerActivity, item.id, -1)
            render()
        }
        action("下移") {
            CustomSymbolRepository.move(this@SymbolManagerActivity, item.id, 1)
            render()
        }
        action("删除") {
            val dialog = android.app.AlertDialog.Builder(this@SymbolManagerActivity)
                .setTitle("删除自定义符号？")
                .setMessage(item.symbol)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    CustomSymbolRepository.remove(this@SymbolManagerActivity, item.id)
                    if (editingId == item.id) editingId = 0L
                    render()
                }
                .create()
            dialog.setOnShowListener {
                SetupUi.styleDialog(dialog, this@SymbolManagerActivity, destructivePositive = true)
            }
            dialog.show()
        }
        addView(actions, fullWrap())
        tag = "symbol-row:${item.id}"
        contentDescription = null
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnLongClickListener {
            alpha = 0.55f
            val data = ClipData.newPlainText("custom-symbol-id", item.id.toString())
            startDragAndDrop(data, View.DragShadowBuilder(this), item.id, 0)
            true
        }
        setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_ENTERED -> {
                    alpha = 0.72f
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    alpha = 1f
                    true
                }
                DragEvent.ACTION_DROP -> {
                    alpha = 1f
                    val movingId = event.localState as? Long ?: return@setOnDragListener false
                    CustomSymbolRepository.moveBefore(this@SymbolManagerActivity, movingId, item.id)
                    render()
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    alpha = 1f
                    true
                }
                else -> true
            }
        }
    }

    private fun smallButton(label: String, action: () -> Unit) =
        SetupUi.secondaryButton(this, label, action)

    private fun fieldLabel(label: String) = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(getColor(R.color.setup_body))
        setPadding(dp(4), 0, dp(4), dp(4))
    }

    private fun applySelectableBackground(view: View) {
        val value = TypedValue()
        if (
            theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                value,
                true,
            ) && value.resourceId != 0
        ) {
            view.setBackgroundResource(value.resourceId)
        }
    }

    private fun fullWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun fullHeight(height: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(height),
    )
}
