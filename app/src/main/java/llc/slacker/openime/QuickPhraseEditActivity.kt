package llc.slacker.openime

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
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

/** Full-screen editor so the active IME can be used to edit the phrase itself. */
class QuickPhraseEditActivity : Activity() {
    companion object {
        const val EXTRA_ID = "quick_phrase_id"
        const val EXTRA_CATEGORY = "quick_phrase_category"
        const val EXTRA_TEXT = "quick_phrase_text"
    }

    private val density by lazy { resources.displayMetrics.density }
    private lateinit var categoryEdit: EditText
    private lateinit var phraseEdit: EditText
    private var phraseId = 0L

    private fun dp(value: Int): Int = (value * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phraseId = intent.getLongExtra(EXTRA_ID, 0L)
        val category = savedInstanceState?.getString("draft_category")
            ?: intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
        val phrase = savedInstanceState?.getString("draft_phrase")
            ?: intent.getStringExtra(EXTRA_TEXT).orEmpty()
        render(category, phrase)
        phraseEdit.requestFocus()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft_category", categoryEdit.text.toString())
        outState.putString("draft_phrase", phraseEdit.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun render(category: String, phrase: String) {
        val accent = SetupUi.accent(this)
        val title = TextView(this).apply {
            text = if (phraseId > 0L) "编辑常用语" else "新增常用语"
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
            addView(ImageButton(this@QuickPhraseEditActivity).apply {
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

        categoryEdit = EditText(this).apply {
            id = R.id.quick_phrase_category_editor
            hint = "分类，例如：工作"
            setText(category)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_NEXT
            textSize = 16f
        }
        SetupUi.styleInput(this, categoryEdit)
        phraseEdit = EditText(this).apply {
            id = R.id.quick_phrase_text_editor
            hint = "输入常用语"
            setText(phrase)
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            imeOptions = EditorInfo.IME_ACTION_DONE
            textSize = 17f
        }
        SetupUi.styleInput(this, phraseEdit)
        val save = SetupUi.primaryButton(this, "保存") {
            if (phraseEdit.text.isNullOrBlank()) {
                phraseEdit.error = "请输入常用语内容"
                phraseEdit.requestFocus()
            } else if (QuickPhraseRepository.upsert(
                    this@QuickPhraseEditActivity,
                    phraseId,
                    categoryEdit.text.toString(),
                    phraseEdit.text.toString(),
                ) != null
            ) {
                finish()
            }
        }
        val cancel = SetupUi.secondaryButton(this, "取消") {
            finish()
        }
        categoryEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                phraseEdit.requestFocus()
                true
            } else {
                false
            }
        }
        phraseEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save.performClick()
                true
            } else {
                false
            }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(save, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) })
            addView(cancel, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = SetupUi.rounded(
                getColor(R.color.setup_surface),
                dp(20).toFloat(),
                getColor(R.color.setup_input_line),
            )
            addView(fieldLabel("分类（可选）"), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(categoryEdit, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58),
            ).apply { bottomMargin = dp(14) })
            addView(fieldLabel("常用语内容"), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(phraseEdit, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(154),
            ).apply { bottomMargin = dp(8) })
            addView(TextView(this@QuickPhraseEditActivity).apply {
                text = "保存后会在剪贴板面板中按分类显示，可直接点选输入。"
                textSize = 12f
                setTextColor(getColor(R.color.setup_body))
                setPadding(dp(4), 0, dp(4), dp(12))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(actions, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52),
            ))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            addView(header, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56),
            ).apply { bottomMargin = dp(12) })
            addView(form, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
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
    }

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
}
