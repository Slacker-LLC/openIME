package llc.slacker.openime

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Full-screen editor so the active IME can be used to edit the phrase itself. */
class QuickPhraseEditActivity : Activity() {
    companion object {
        const val EXTRA_ID = "quick_phrase_id"
        const val EXTRA_CATEGORY = "quick_phrase_category"
        const val EXTRA_TEXT = "quick_phrase_text"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
        val phrase = intent.getStringExtra(EXTRA_TEXT).orEmpty()

        val categoryEdit = EditText(this).apply {
            this.id = R.id.quick_phrase_category_editor
            hint = "分类，例如：工作"
            setText(category)
            setSingleLine(true)
            textSize = 16f
        }
        val phraseEdit = EditText(this).apply {
            this.id = R.id.quick_phrase_text_editor
            hint = "输入常用语"
            setText(phrase)
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            textSize = 17f
        }
        val title = TextView(this).apply {
            text = if (id > 0L) "编辑常用语" else "新增常用语"
            textSize = 22f
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@QuickPhraseEditActivity).apply {
                text = "‹"
                textSize = 32f
                gravity = Gravity.CENTER
                includeFontPadding = false
                contentDescription = "返回"
                minWidth = dp(48)
                minHeight = dp(48)
                isClickable = true
                isFocusable = true
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(56)))
            addView(title, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        val save = Button(this).apply {
            text = "保存"
            setOnClickListener {
                if (phraseEdit.text.isNullOrBlank()) {
                    phraseEdit.error = "请输入常用语内容"
                    phraseEdit.requestFocus()
                    return@setOnClickListener
                }
                if (QuickPhraseRepository.upsert(
                        this@QuickPhraseEditActivity,
                        id,
                        categoryEdit.text.toString(),
                        phraseEdit.text.toString(),
                    ) != null
                ) finish()
            }
        }
        val cancel = Button(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(save, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
            addView(cancel, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            addView(header, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56),
            ).apply { bottomMargin = dp(8) })
            addView(categoryEdit, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58),
            ).apply { bottomMargin = dp(10) })
            addView(phraseEdit, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(150),
            ).apply { bottomMargin = dp(14) })
            addView(actions, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52),
            ))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.setup_page_bg))
            isFillViewport = true
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
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
        })
        phraseEdit.requestFocus()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }
}
