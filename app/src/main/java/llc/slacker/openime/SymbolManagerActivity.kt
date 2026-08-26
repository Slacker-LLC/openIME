package llc.slacker.openime

import android.app.Activity
import android.content.ClipData
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
    }

    private fun render() {
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
            text = "可添加、分类、置顶、删除；长按排序按钮可快速调整顺序。"
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        }, fullWrap())
        groupEdit = EditText(this).apply {
            hint = "分组，例如：常用箭头"
            setSingleLine(true)
            textSize = 16f
        }
        symbolEdit = EditText(this).apply {
            hint = "符号，例如：⇢ 或 自定义文本"
            setSingleLine(true)
            textSize = 20f
        }
        content.addView(groupEdit, fullHeight(56).apply { bottomMargin = dp(8) })
        content.addView(symbolEdit, fullHeight(56).apply { bottomMargin = dp(8) })
        content.addView(Button(this).apply {
            text = "保存符号"
            minHeight = dp(52)
            setOnClickListener {
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
                symbols.forEach { item -> content.addView(symbolRow(item), fullHeight(56).apply { bottomMargin = dp(6) }) }
            }
        content.addView(Button(this).apply {
            text = "完成"
            minHeight = dp(52)
            setOnClickListener { finish() }
        }, fullHeight(52).apply { topMargin = dp(12) })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun symbolRow(item: CustomSymbol): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(4), 0)
        addView(TextView(this@SymbolManagerActivity).apply {
            text = item.symbol
            textSize = 21f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(TextView(this@SymbolManagerActivity).apply {
            text = if (item.pinned) "已固定" else "未固定"
            textSize = 12f
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        addView(smallButton("改") {
            editingId = item.id
            groupEdit.setText(item.group)
            symbolEdit.setText(item.symbol)
            symbolEdit.requestFocus()
        })
        addView(smallButton(if (item.pinned) "取消固定" else "固定") {
            CustomSymbolRepository.togglePinned(this@SymbolManagerActivity, item.id)
            render()
        })
        addView(smallButton("↑") {
            CustomSymbolRepository.move(this@SymbolManagerActivity, item.id, -1)
            render()
        })
        addView(smallButton("↓") {
            CustomSymbolRepository.move(this@SymbolManagerActivity, item.id, 1)
            render()
        })
        addView(smallButton("删") {
            CustomSymbolRepository.remove(this@SymbolManagerActivity, item.id)
            render()
        })
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
