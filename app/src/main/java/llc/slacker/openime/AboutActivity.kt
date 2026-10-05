package llc.slacker.openime

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import llc.slacker.openime.core.CrashGuard
import llc.slacker.openime.setup.SetupUi
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeSpacingTokens
import llc.slacker.openime.theme.ImeTypographyTokens

/** 关于: privacy, diagnostics and the version. User data lives in [DataManagementActivity]. */
class AboutActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(SetupUi.appearanceContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(ImeSpacingTokens.LG_DP),
                0,
                dp(ImeSpacingTokens.LG_DP),
                dp(ImeSpacingTokens.XXL_DP),
            )
            addView(
                SetupUi.activityTopBar(
                    context = this@AboutActivity,
                    title = "关于",
                    onBack = ::finish,
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(ImeGeometryTokens.TOP_BAR_HEIGHT_DP),
                ).apply { marginStart = -dp(16); marginEnd = -dp(16) },
            )
            addView(
                SetupUi.infoCard(
                    this@AboutActivity,
                    title = "隐私",
                    body = "本应用不含联网权限，数据只存在本机。",
                    iconRes = R.drawable.ic_pref_shield,
                ),
                wrap().apply { topMargin = dp(ImeSpacingTokens.SM_DP) },
            )
            addView(diagnosticsCard(), wrap().apply { topMargin = dp(ImeSpacingTokens.LG_DP) })
            addView(TextView(this@AboutActivity).apply {
                text = "openIME · 版本 " + versionName()
                textSize = ImeTypographyTokens.SMALL_SP
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.setup_body))
            }, wrap().apply { topMargin = dp(18) })
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(getColor(R.color.setup_page_bg))
                isFillViewport = true
                setOnApplyWindowInsetsListener { view, insets ->
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        val bars = insets.getInsets(
                            WindowInsets.Type.systemBars() or
                                WindowInsets.Type.displayCutout(),
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
    }

    private fun diagnosticsCard(): LinearLayout {
        val safeMode = CrashGuard.isSafeMode(this)
        val lastReport = CrashGuard.lastReport(this)
        val card = SetupUi.infoCard(
            this,
            title = "诊断",
            body = when {
                safeMode -> "输入法刚才多次异常退出，已临时关闭原生词库和语音预加载；约 10 分钟后自动恢复，也可以现在退出。"
                lastReport != null -> "最近一次异常：" + lastReport.lineSequence().first().substringAfter("| ").substringBefore(" | thread")
                else -> "没有异常记录。"
            } + "\n" + llc.slacker.openime.keyboard.KeyHaptics.describe(this) +
                "\n诊断信息只含异常类型和代码位置，不含任何输入内容；只有你点“复制”才会离开这里。",
            iconRes = R.drawable.ic_pref_info,
        )
        if (lastReport == null && !safeMode) return card
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            if (lastReport != null) {
                addView(
                    SetupUi.secondaryButton(this@AboutActivity, "复制诊断信息") {
                        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("openIME diagnostics", lastReport))
                        Toast.makeText(this@AboutActivity, "已复制", Toast.LENGTH_SHORT).show()
                    },
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(ImeSpacingTokens.SM_DP) },
                )
            }
            if (safeMode) {
                addView(
                    SetupUi.primaryButton(this@AboutActivity, "退出安全模式") {
                        CrashGuard.clearHistory(this@AboutActivity)
                        Toast.makeText(this@AboutActivity, "下次打开键盘时恢复完整功能", Toast.LENGTH_SHORT).show()
                        recreate()
                    },
                    LinearLayout.LayoutParams(0, dp(44), 1f),
                )
            }
        }
        card.addView(
            row,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(12)
                marginStart = dp(44)
            },
        )
        return card
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            .ifBlank { "未知版本" }

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = SetupUi.dp(this, value)
}
