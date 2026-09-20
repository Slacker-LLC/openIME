package llc.slacker.openime

import android.app.Activity
import android.content.ClipData
import android.text.TextUtils
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Small, touch-friendly manager for user symbols and their order. */
class SymbolManagerActivity : Activity() {
    private val density by lazy { resources.displayMetrics.density }
    private lateinit var content: LinearLayout
    private lateinit var groupEdit: EditText
    private lateinit var symbolEdit: EditText
    private var editingId = 0L

    private fun dp(value: Int): Int = (value * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val draftGroup = if (::groupEdit.isInitialized) groupEdit.text.toString() else ""
        val draftSymbol = if (::symbolEdit.isInitialized) symbolEdit.text.toString() else ""
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(22))
        }
        content.addView(TextView(this).apply {
            text = "自定义符号"
            textSize = 22f
            setPadding(0, 0, 0, dp(8))
        }, fullWrap())
        content.addView(TextView(this).apply {
            text = "可添加、分类、固定和删除。点击箭头调整顺序，也可长按符号行拖动排序。"
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        }, fullWrap())
        groupEdit = EditText(this).apply {
            hint = "分组，例如：常用箭头"
            setSingleLine(true)
            textSize = 16f
            setText(draftGroup)
        }
        symbolEdit = EditText(this).apply {
            hint = "符号，例如：⇢ 或 自定义文本"
            setSingleLine(true)
            textSize = 20f
            setText(draftSymbol)
        }
        content.addView(groupEdit, fullHeight(56).apply { bottomMargin = dp(8) })
        content.addView(symbolEdit, fullHeight(56).apply { bottomMargin = dp(8) })
        content.addView(Button(this).apply {
            text = "保存符号"
            minHeight = dp(52)
            setOnClickListener {
                if (symbolEdit.text.isNullOrBlank()) {
                    symbolEdit.error = "请输入符号或自定义文本"
                    symbolEdit.requestFocus()
                    return@setOnClickListener
                }
                if (CustomSymbolRepository.upsert(
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
            }
        }, fullHeight(52).apply { bottomMargin = dp(16) })
        content.addView(TextView(this).apply {
            text = "已保存符号"
            textSize = 16f
            setPadding(0, 0, 0, dp(8))
        }, fullWrap())

        CustomSymbolRepository.load(this)
            .groupBy { it.group }
            .forEach { (group, symbols) ->
                content.addView(TextView(this).apply {
                    text = group
                    textSize = 14f
                    setPadding(0, dp(8), 0, dp(4))
                }, fullWrap())
                symbols.forEach { item -> content.addView(symbolRow(item), fullWrap().apply { bottomMargin = dp(12) }) }
            }
        content.addView(Button(this).apply {
            text = "完成"
            minHeight = dp(52)
            setOnClickListener { finish() }
        }, fullHeight(52).apply { topMargin = dp(12) })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun symbolRow(item: CustomSymbol): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), 0, dp(4), 0)
        addView(TextView(this@SymbolManagerActivity).apply {
            text = item.symbol
            textSize = 21f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            contentDescription = item.symbol
        }, fullWrap())
        addView(TextView(this@SymbolManagerActivity).apply {
            text = if (item.pinned) "已固定" else "未固定"
            textSize = 12f
        }, fullWrap())
        val actions = LinearLayout(this@SymbolManagerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun action(label: String, onClick: () -> Unit) {
            actions.addView(smallButton(label, onClick), LinearLayout.LayoutParams(0, dp(48), 1f))
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
            CustomSymbolRepository.remove(this@SymbolManagerActivity, item.id)
            if (editingId == item.id) editingId = 0L
            render()
        }
        addView(actions, fullWrap())
        setOnLongClickListener {
            val data = ClipData.newPlainText("custom-symbol-id", item.id.toString())
            startDragAndDrop(data, View.DragShadowBuilder(this), item.id, 0)
            true
        }
        setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_ENTERED -> true
                DragEvent.ACTION_DROP -> {
                    val movingId = event.localState as? Long ?: return@setOnDragListener false
                    CustomSymbolRepository.moveBefore(this@SymbolManagerActivity, movingId, item.id)
                    render()
                    true
                }
                else -> true
            }
        }
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        minHeight = dp(44)
        setPadding(dp(3), 0, dp(3), 0)
        setOnClickListener { action() }
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
