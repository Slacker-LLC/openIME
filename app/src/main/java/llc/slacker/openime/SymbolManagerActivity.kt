package llc.slacker.openime

import android.app.Activity
import android.content.ClipData
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback

/** Touch-friendly manager for user symbols and their order. */
class SymbolManagerActivity : Activity() {
    private val density by lazy { resources.displayMetrics.density }
    private lateinit var content: LinearLayout
    private lateinit var groupEdit: EditText
    private lateinit var symbolEdit: EditText
    private var editingId = 0L
    private var initialEditingId = 0L
    private var initialGroup = ""
    private var initialSymbol = ""
    private var savedScrollY = 0
    private var backCallback: OnBackInvokedCallback? = null

    private fun dp(value: Int): Int = (value * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedScrollY = savedInstanceState?.getInt("scroll_y", 0) ?: 0
        initialEditingId = savedInstanceState?.getLong("baseline_editing_id", 0L) ?: 0L
        initialGroup = savedInstanceState?.getString("baseline_group").orEmpty()
        initialSymbol = savedInstanceState?.getString("baseline_symbol").orEmpty()
        render()
        savedInstanceState?.let {
            editingId = it.getLong("editing_id")
            groupEdit.setText(it.getString("draft_group", ""))
            symbolEdit.setText(it.getString("draft_symbol", ""))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { requestClose() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback!!,
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        requestClose()
    }

    override fun onDestroy() {
        LocalVoiceImeService.activeInstance?.refreshAuxiliaryContentFromActivity()
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            backCallback = null
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("editing_id", editingId)
        outState.putLong("baseline_editing_id", initialEditingId)
        outState.putString("baseline_group", initialGroup)
        outState.putString("baseline_symbol", initialSymbol)
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
                    requestClose()
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
        content.addView(fieldLabel("分组（可选）", R.id.custom_symbol_group_editor), fullWrap())
        groupEdit = EditText(this).apply {
            id = R.id.custom_symbol_group_editor
            hint = "分组，例如：常用箭头"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_NEXT
            textSize = 16f
            setText(draftGroup)
        }
        SetupUi.styleInput(this, groupEdit)
        content.addView(groupEdit, fullHeight(ImeGeometryTokens.FIELD_HEIGHT_DP).apply { bottomMargin = dp(10) })
        content.addView(fieldLabel("符号或自定义文本", R.id.custom_symbol_text_editor), fullWrap().apply { bottomMargin = dp(2) })
        symbolEdit = EditText(this).apply {
            id = R.id.custom_symbol_text_editor
            hint = "符号，例如：⇢ 或 自定义文本"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            textSize = 20f
            setText(draftSymbol)
        }
        SetupUi.styleInput(this, symbolEdit)
        content.addView(symbolEdit, fullHeight(ImeGeometryTokens.FIELD_HEIGHT_DP).apply { bottomMargin = dp(12) })
        val save = SetupUi.primaryButton(this, "保存符号") {
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
                initialEditingId = 0L
                initialGroup = ""
                initialSymbol = ""
                render()
            }
        }
        groupEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                symbolEdit.requestFocus()
                true
            } else {
                false
            }
        }
        symbolEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save.performClick()
                true
            } else {
                false
            }
        }
        fun refreshSaveState() {
            val valid = symbolEdit.text.toString().isNotBlank()
            save.isEnabled = valid
            save.alpha = 1f
            save.contentDescription = if (valid) {
                "保存符号"
            } else {
                "保存符号，不可用：请输入符号或自定义文本"
            }
            if (Build.VERSION.SDK_INT >= 30) {
                save.stateDescription = if (valid) "可用" else "不可用"
            }
            if (valid) symbolEdit.error = null
        }
        symbolEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshSaveState()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refreshSaveState()
        content.addView(save, fullHeight(ImeGeometryTokens.PRIMARY_ROW_HEIGHT_DP).apply { bottomMargin = dp(20) })
        content.addView(TextView(this).apply {
            text = "已保存符号"
            textSize = 16f
            setTextColor(getColor(R.color.setup_title))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
            if (Build.VERSION.SDK_INT >= 28) setAccessibilityHeading(true)
        }, fullWrap())

        val symbols = CustomSymbolRepository.load(this)
        if (symbols.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "还没有保存的自定义符号；在上方填写后点击保存符号。"
                textSize = 13f
                setTextColor(getColor(R.color.setup_body))
                setPadding(dp(4), dp(4), dp(4), dp(8))
                tag = "symbol-empty-state"
            }, fullWrap())
        } else {
            symbols.groupBy { it.group }
                .forEach { (group, groupedSymbols) ->
                    content.addView(TextView(this).apply {
                        text = group
                        textSize = 14f
                        setTextColor(accent)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(0, dp(8), 0, dp(4))
                    }, fullWrap())
                    groupedSymbols.forEach { item ->
                        content.addView(symbolRow(item, accent), fullWrap().apply { bottomMargin = dp(12) })
                    }
                }
        }
        content.addView(SetupUi.primaryButton(this, "完成") {
            requestClose()
        }, fullHeight(ImeGeometryTokens.PRIMARY_ROW_HEIGHT_DP).apply { topMargin = dp(12) })
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
            dp(ImeGeometryTokens.CARD_RADIUS_DP).toFloat(),
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
            initialEditingId = item.id
            initialGroup = item.group
            initialSymbol = item.symbol
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
                    if (editingId == item.id) {
                        editingId = 0L
                        initialEditingId = 0L
                        initialGroup = ""
                        initialSymbol = ""
                        groupEdit.text.clear()
                        symbolEdit.text.clear()
                    }
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

    private fun fieldLabel(label: String, targetId: Int) = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(getColor(R.color.setup_body))
        setPadding(dp(4), 0, dp(4), dp(4))
        labelFor = targetId
    }

    private fun requestClose() {
        if (!::groupEdit.isInitialized || !::symbolEdit.isInitialized ||
            !hasUnsavedDraft()
        ) {
            finish()
            return
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("放弃未保存内容？")
            .setMessage("当前符号编辑尚未保存，离开后将丢失。")
            .setNegativeButton("继续编辑", null)
            .setPositiveButton("放弃", null)
            .create()
        dialog.setOnShowListener {
            SetupUi.styleDialog(dialog, this@SymbolManagerActivity)
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                finish()
            }
        }
        dialog.show()
    }

    private fun hasUnsavedDraft(): Boolean =
        editingId != initialEditingId ||
            groupEdit.text.toString() != initialGroup ||
            symbolEdit.text.toString() != initialSymbol

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
