package llc.slacker.openime.hotword

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import llc.slacker.openime.R
import llc.slacker.openime.setup.SetupUi
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeSpacingTokens
import llc.slacker.openime.theme.ImeTypographyTokens

/**
 * Voice hotword lists: switch the bundled packs on or off, import a list from a
 * text file, delete imported lists. All data stays on the device.
 */
class HotwordPacksActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(SetupUi.appearanceContext(newBase))
    }

    private val store by lazy { HotwordRuntime.store(this) }
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(ImeSpacingTokens.XXL_DP))
        }
        content.addView(
            SetupUi.activityTopBar(context = this, title = "语音词表", onBack = ::finish),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(ImeGeometryTokens.TOP_BAR_HEIGHT_DP),
            ).apply { marginStart = -dp(16); marginEnd = -dp(16) },
        )
        content.addView(
            card().apply {
                addView(body(
                    "语音识别结束后，把读音相同、字不同的词改成词表里的写法，例如“大爷”改成“打野”。" +
                        "词表只在本机使用，不联网。\n\n" +
                        "导入的文件为 UTF-8 文本，每行一个词，只支持 2 到 ${HotwordParser.MAX_WORD_LENGTH} 个汉字；" +
                        "# 开头的行是注释，可以用 “# title: 名称” 给词表命名。",
                ), wrap())
                addView(
                    SetupUi.primaryButton(this@HotwordPacksActivity, "导入词表文件") { pickFile() },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
                        .apply { topMargin = dp(12) },
                )
            },
            wrap().apply { topMargin = dp(ImeSpacingTokens.LG_DP) },
        )
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, wrap())

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(getColor(R.color.setup_page_bg))
                isFillViewport = true
                setOnApplyWindowInsetsListener { view, insets ->
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        val bars = insets.getInsets(
                            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
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
                addView(content)
            },
        )
        renderPacks()
    }

    private fun renderPacks() {
        list.removeAllViews()
        store.packs().forEach { pack -> list.addView(packRow(pack), wrap().apply { topMargin = dp(12) }) }
    }

    private fun packRow(pack: HotwordPack): View = card().apply {
        val header = LinearLayout(this@HotwordPacksActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this@HotwordPacksActivity).apply {
            text = pack.title
            textSize = ImeTypographyTokens.BODY_SP
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(getColor(R.color.setup_title))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Switch(this@HotwordPacksActivity).apply {
            isChecked = store.isEnabled(pack)
            contentDescription = "启用${pack.title}"
            val accent = SetupUi.accent(this@HotwordPacksActivity)
            trackTintList = ColorStateList.valueOf(accent)
            setOnCheckedChangeListener { _, checked ->
                store.setEnabled(pack, checked)
                HotwordRuntime.reload(this@HotwordPacksActivity)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(ImeGeometryTokens.TOUCH_TARGET_DP)))
        addView(header, wrap())

        val origin = if (pack.origin == HotwordPack.Origin.BUNDLED) "内置" else "已导入"
        val skipped = if (pack.rejectedLines > 0) " · 跳过 ${pack.rejectedLines} 行" else ""
        addView(body(listOf("${pack.words.size} 个词 · $origin$skipped", pack.description)
            .filter { it.isNotBlank() }.joinToString("\n")), wrap())
        if (pack.origin == HotwordPack.Origin.IMPORTED) {
            addView(
                SetupUi.secondaryButton(this@HotwordPacksActivity, "删除") { confirmDelete(pack) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
                    .apply { topMargin = dp(8) },
            )
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (val result = store.importFrom(uri)) {
            is HotwordPackStore.ImportResult.Imported -> {
                HotwordRuntime.reload(this)
                renderPacks()
                val note = if (result.truncated) {
                    "，超过 ${HotwordParser.MAX_WORDS} 个词的部分已忽略"
                } else {
                    ""
                }
                Toast.makeText(this, "已导入 ${result.pack.words.size} 个词$note", Toast.LENGTH_SHORT).show()
            }
            is HotwordPackStore.ImportResult.Failed -> showError(result.reason)
        }
    }

    private fun confirmDelete(pack: HotwordPack) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除“${pack.title}”？")
            .setMessage("这个词表会从本机移除，原文件不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                store.delete(pack)
                HotwordRuntime.reload(this)
                renderPacks()
            }
            .create()
        dialog.setOnShowListener { SetupUi.styleDialog(dialog, this, destructivePositive = true) }
        dialog.show()
    }

    private fun showError(message: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("导入未完成")
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .create()
        dialog.setOnShowListener { SetupUi.styleDialog(dialog, this) }
        dialog.show()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ImeSpacingTokens.LG_DP), dp(ImeSpacingTokens.LG_DP), dp(ImeSpacingTokens.LG_DP), dp(ImeSpacingTokens.LG_DP))
        background = SetupUi.rounded(
            getColor(R.color.setup_surface),
            dp(ImeGeometryTokens.CARD_RADIUS_DP).toFloat(),
            getColor(R.color.setup_input_line),
        )
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = ImeTypographyTokens.BODY_SP
        setTextColor(getColor(R.color.setup_body))
        setLineSpacing(0f, 1.3f)
    }

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = SetupUi.dp(this, value)

    private companion object {
        const val REQUEST_IMPORT = 8201
    }
}
