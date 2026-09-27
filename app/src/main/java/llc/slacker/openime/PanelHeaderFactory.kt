package llc.slacker.openime

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Builds the shared panel header/back affordance used by every IME panel and
 * the expanded-candidate surface.
 */
internal class PanelHeaderFactory(
    private val context: Context,
    private val toPx: (Int) -> Int,
    private val previousPanel: () -> Panel?,
    private val onBack: () -> Boolean,
    private val onFeedback: () -> Unit,
) {
    fun create(name: String): LinearLayout {
        val backTarget = previousPanel()?.let(::titleFor) ?: "键盘"
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(toPx(10), 0, toPx(10), 0)
            minimumHeight = toPx(48)
            tag = "panel-head"

            addView(
                ImageView(context).apply {
                    tag = "key-panel-back"
                    setImageResource(R.drawable.ic_arrow_back)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    isClickable = true
                    isFocusable = true
                    minimumHeight = toPx(48)
                    minimumWidth = toPx(48)
                    contentDescription = "返回$backTarget"
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            onFeedback()
                        }
                        false
                    }
                    setOnClickListener {
                        onFeedback()
                        onBack()
                    }
                },
                LinearLayout.LayoutParams(toPx(48), toPx(48)),
            )

            addView(
                TextView(context).apply {
                    text = name
                    textSize = 13f
                    setPadding(toPx(8), 0, 0, 0)
                    tag = "panel-title"
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun titleFor(panel: Panel): String = when (panel) {
        Panel.TOOLS -> "更多"
        Panel.KEYBOARD_SELECT -> "切换键盘"
        Panel.SYMBOLS -> "符号"
        Panel.EMOJI -> "表情"
        Panel.HANDWRITING -> "手写输入"
        Panel.VOICE -> "语音"
        Panel.CLIPBOARD -> "剪贴板"
        Panel.TEXT_EDITOR -> "文本编辑"
        Panel.SETTINGS -> "设置"
        Panel.FUZZY_SETTINGS -> "模糊音纠错"
        Panel.NONE, Panel.CANDIDATE_EXPANDED -> "键盘"
    }
}
