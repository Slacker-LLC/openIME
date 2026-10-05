package llc.slacker.openime

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import llc.slacker.openime.data.QuickPhraseRepository
import llc.slacker.openime.setup.SetupUi
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeTypographyTokens

/** Full-screen editor so the active IME can be used to edit the phrase itself. */
class QuickPhraseEditActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(SetupUi.appearanceContext(newBase))
    }

    companion object {
        const val EXTRA_ID = "quick_phrase_id"
        const val EXTRA_CATEGORY = "quick_phrase_category"
        const val EXTRA_TEXT = "quick_phrase_text"
        const val EXTRA_INPUT_CODE = "quick_phrase_input_code"
    }

    private val density by lazy { resources.displayMetrics.density }
    private lateinit var categoryEdit: EditText
    private lateinit var codeEdit: EditText
    private lateinit var phraseEdit: EditText
    private var phraseId = 0L
    private var initialCategory = ""
    private var initialCode = ""
    private var initialPhrase = ""
    private var savedScrollY = 0
    private var savedFocusId = R.id.quick_phrase_text_editor
    private var backCallback: OnBackInvokedCallback? = null

    private fun dp(value: Int): Int = (value * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phraseId = intent.getLongExtra(EXTRA_ID, 0L)
        savedScrollY = savedInstanceState?.getInt("scroll_y", 0) ?: 0
        savedFocusId = savedInstanceState?.getInt("focused_field", R.id.quick_phrase_text_editor)
            ?: R.id.quick_phrase_text_editor
        initialCategory = savedInstanceState?.getString("baseline_category")
            ?: intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
        initialCode = savedInstanceState?.getString("baseline_code")
            ?: intent.getStringExtra(EXTRA_INPUT_CODE).orEmpty()
        initialPhrase = savedInstanceState?.getString("baseline_phrase")
            ?: intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val category = savedInstanceState?.getString("draft_category") ?: initialCategory
        val code = savedInstanceState?.getString("draft_code") ?: initialCode
        val phrase = savedInstanceState?.getString("draft_phrase") ?: initialPhrase
        render(category, code, phrase)
        when (savedFocusId) {
            R.id.quick_phrase_category_editor -> categoryEdit.requestFocus()
            R.id.quick_phrase_code_editor -> codeEdit.requestFocus()
            else -> phraseEdit.requestFocus()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { requestClose() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback!!,
            )
        }
    }

    // Android 13+ gets the back gesture through the OnBackInvokedCallback
    // registered in onCreate; this override only serves Android 12 and below,
    // where back still arrives here. The app does not use AndroidX.
    @Deprecated("Deprecated in Java")
    @android.annotation.SuppressLint("GestureBackNavigation")
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
        outState.putString("baseline_category", initialCategory)
        outState.putString("baseline_code", initialCode)
        outState.putString("baseline_phrase", initialPhrase)
        outState.putString("draft_category", categoryEdit.text.toString())
        outState.putString("draft_code", codeEdit.text.toString())
        outState.putString("draft_phrase", phraseEdit.text.toString())
        outState.putInt("focused_field", currentFocus?.id ?: savedFocusId)
        outState.putInt(
            "scroll_y",
            (window.decorView.findViewById<ScrollView>(R.id.quick_phrase_scroll)?.scrollY ?: savedScrollY),
        )
        super.onSaveInstanceState(outState)
    }

    private fun render(category: String, code: String, phrase: String) {
        val header = SetupUi.activityTopBar(
            context = this,
            title = if (phraseId > 0L) "编辑常用语" else "新增常用语",
        ) {
            requestClose()
        }

        categoryEdit = EditText(this).apply {
            id = R.id.quick_phrase_category_editor
            hint = "分类，例如：工作"
            setText(category)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_NEXT
            textSize = ImeTypographyTokens.TITLE_SP
        }
        SetupUi.styleInput(this, categoryEdit)
        codeEdit = EditText(this).apply {
            id = R.id.quick_phrase_code_editor
            hint = "例如：dz、mail、addr"
            setText(code)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_NEXT
            textSize = ImeTypographyTokens.TITLE_SP
        }
        SetupUi.styleInput(this, codeEdit)
        phraseEdit = EditText(this).apply {
            id = R.id.quick_phrase_text_editor
            hint = "输入常用语"
            setText(phrase)
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            imeOptions = EditorInfo.IME_ACTION_DONE
            textSize = ImeTypographyTokens.TITLE_SP
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
                    inputCode = codeEdit.text.toString(),
                ) != null
            ) {
                finish()
            }
        }
        fun refreshSaveState() {
            val valid = phraseEdit.text.toString().isNotBlank()
            save.isEnabled = valid
            save.alpha = if (valid) 1f else ImeSurfacePolicy.DISABLED_ALPHA
            save.contentDescription = if (valid) {
                "保存"
            } else {
                "保存，不可用：请输入常用语内容"
            }
            if (Build.VERSION.SDK_INT >= 30) {
                save.stateDescription = if (valid) "可用" else "不可用"
            }
            if (valid) phraseEdit.error = null
        }
        phraseEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshSaveState()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refreshSaveState()
        val cancel = SetupUi.secondaryButton(this, "取消") {
            requestClose()
        }
        categoryEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                codeEdit.requestFocus()
                true
            } else {
                false
            }
        }
        codeEdit.setOnEditorActionListener { _, actionId, _ ->
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
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) })
            addView(save, LinearLayout.LayoutParams(0, dp(48), 1.7f))
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            val fields = LinearLayout(this@QuickPhraseEditActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(Triple("分类（可选）", R.id.quick_phrase_category_editor, categoryEdit), Triple("输入码（可选）", R.id.quick_phrase_code_editor, codeEdit)).forEachIndexed { index, (label, target, field) ->
                fields.addView(LinearLayout(this@QuickPhraseEditActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(fieldLabel(label, target))
                    addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (index == 0) marginEnd = dp(12) })
            }
            addView(fields)
            addView(fieldLabel("常用语内容", R.id.quick_phrase_text_editor), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
            addView(phraseEdit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(132)))
            addView(TextView(this@QuickPhraseEditActivity).apply {
                text = "保存后会在剪贴板面板的“常用语”中按分类显示，点选即输入。\n\n设置输入码后，输入至少 2 个字符的短码即可在候选栏召回这条常用语。"
                textSize = ImeTypographyTokens.SMALL_SP; setTextColor(getColor(R.color.setup_body)); setLineSpacing(0f, 1.25f)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        val scroll = ScrollView(this).apply {
            id = R.id.quick_phrase_scroll; isFillViewport = true; isVerticalScrollBarEnabled = false
            setOnScrollChangeListener { _, _, scrollY, _, _ -> savedScrollY = scrollY }
            addView(form); post { scrollTo(0, savedScrollY.coerceAtLeast(0)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.setup_page_bg))
            addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(actions)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        })
    }

    private fun fieldLabel(label: String, targetId: Int) = TextView(this).apply {
        text = label
        textSize = ImeTypographyTokens.TITLE_SP
        setTextColor(getColor(R.color.setup_body))
        setPadding(dp(4), 0, dp(4), dp(4))
        labelFor = targetId
    }

    private fun requestClose() {
        if (!::categoryEdit.isInitialized || !::codeEdit.isInitialized || !::phraseEdit.isInitialized ||
            (
                categoryEdit.text.toString() == initialCategory &&
                    codeEdit.text.toString() == initialCode &&
                    phraseEdit.text.toString() == initialPhrase
            )
        ) {
            finish()
            return
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("放弃未保存内容？")
            .setMessage("当前编辑内容尚未保存，离开后将丢失。")
            .setNegativeButton("继续编辑", null)
            .setPositiveButton("放弃", null)
            .create()
        dialog.setOnShowListener {
            SetupUi.styleDialog(dialog, this@QuickPhraseEditActivity, destructivePositive = true)
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                finish()
            }
        }
        dialog.show()
    }


}
