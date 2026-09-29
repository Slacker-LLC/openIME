package llc.slacker.openime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class AboutDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(ImeSpacingTokens.XL_DP),
                dp(ImeSpacingTokens.LG_DP),
                dp(ImeSpacingTokens.XL_DP),
                dp(ImeSpacingTokens.XXL_DP),
            )
            addView(
                SetupUi.activityTopBar(
                    context = this@AboutDataActivity,
                    title = "关于与数据",
                    onBack = ::finish,
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(ImeGeometryTokens.TOP_BAR_HEIGHT_DP),
                ),
            )
            addView(
                infoCard(
                    title = "openIME " + versionName(),
                    body = "本应用不含联网权限，数据只存在本机。",
                ),
                wrap().apply { topMargin = dp(ImeSpacingTokens.MD_DP) },
            )
            addView(
                infoCard(
                    title = "用户数据",
                    body = "JSON 导出包含常用语、自定义符号、备用用户词条和设置项；剪贴板历史不导出。Rime 自动学习词库在已加载时一并导出，并在导入时按词库合并。",
                ),
                wrap().apply { topMargin = dp(ImeSpacingTokens.MD_DP) },
            )

            val actions = LinearLayout(this@AboutDataActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    SetupUi.primaryButton(this@AboutDataActivity, "导出") {
                        requestExportDocument()
                    },
                    LinearLayout.LayoutParams(
                        0,
                        dp(ImeGeometryTokens.PRIMARY_ROW_HEIGHT_DP),
                        1f,
                    ).apply { marginEnd = dp(ImeSpacingTokens.SM_DP) },
                )
                addView(
                    SetupUi.secondaryButton(this@AboutDataActivity, "导入") {
                        requestImportDocument()
                    },
                    LinearLayout.LayoutParams(
                        0,
                        dp(ImeGeometryTokens.PRIMARY_ROW_HEIGHT_DP),
                        1f,
                    ),
                )
            }
            addView(
                actions,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(ImeGeometryTokens.PRIMARY_ROW_HEIGHT_DP),
                ).apply { topMargin = dp(ImeSpacingTokens.LG_DP) },
            )
            addView(
                infoCard(
                    title = "卸载前",
                    body = "卸载会清除本机全部数据，包括学习的用户词库。卸载前可先导出用户数据。",
                ),
                wrap().apply { topMargin = dp(ImeSpacingTokens.LG_DP) },
            )
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT -> exportTo(uri)
            REQUEST_IMPORT -> importFrom(uri)
        }
    }

    private fun requestExportDocument() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "openIME-user-data.json")
            },
            REQUEST_EXPORT,
        )
    }

    private fun requestImportDocument() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            },
            REQUEST_IMPORT,
        )
    }

    private fun exportTo(uri: Uri) {
        val service = LocalVoiceImeService.activeInstance
        val rimeDir = File(cacheDir, "rime-user-export").apply {
            deleteRecursively()
            mkdirs()
        }
        if (service == null) {
            confirmExportWithoutRime(uri)
            return
        }
        val queued = service.exportRimeUserData(rimeDir) { dictionaries ->
            runCatching {
                writeArchive(uri, UserDataRepository.snapshot(this, dictionaries))
            }.onSuccess {
                runOnUiThread {
                    Toast.makeText(this, "用户数据已导出", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                runOnUiThread {
                    showError("导出失败：" + (error.message ?: "无法写入文件"))
                }
            }
            rimeDir.deleteRecursively()
        }
        if (!queued) {
            rimeDir.deleteRecursively()
            confirmExportWithoutRime(uri)
        }
    }

    private fun confirmExportWithoutRime(uri: Uri) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("自动学习词库尚未加载")
            .setMessage("继续导出的 JSON 不包含 Rime 自动学习词库。先切换到 openIME 并等待输入法就绪后再导出，可包含这部分数据。")
            .setNegativeButton("取消", null)
            .setPositiveButton("继续导出") { _, _ ->
                Thread {
                    runCatching {
                        writeArchive(uri, UserDataRepository.snapshot(this))
                    }.onSuccess {
                        runOnUiThread {
                            Toast.makeText(this, "用户数据已导出", Toast.LENGTH_SHORT).show()
                        }
                    }.onFailure { error ->
                        runOnUiThread {
                            showError("导出失败：" + (error.message ?: "无法写入文件"))
                        }
                    }
                }.start()
            }
            .create()
        dialog.setOnShowListener { SetupUi.styleDialog(dialog, this) }
        dialog.show()
    }

    private fun writeArchive(uri: Uri, archive: UserDataArchive) {
        val output = contentResolver.openOutputStream(uri, "wt")
            ?: error("无法打开目标文件")
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(UserDataArchiveCodec.encode(archive))
        }
    }

    private fun importFrom(uri: Uri) {
        Thread {
            val result = runCatching {
                val input = contentResolver.openInputStream(uri)
                    ?: error("无法打开所选文件")
                val archive = input.bufferedReader(Charsets.UTF_8).use { reader ->
                    UserDataArchiveCodec.decode(reader.readText())
                }
                archive to UserDataRepository.preview(this, archive)
            }
            runOnUiThread {
                result.onSuccess { pair ->
                    confirmImport(pair.first, pair.second)
                }.onFailure { error ->
                    showError(
                        "无法读取 openIME 用户数据：" +
                            (error.message ?: "文件格式错误"),
                    )
                }
            }
        }.start()
    }

    private fun confirmImport(
        archive: UserDataArchive,
        preview: UserDataImportPreview,
    ) {
        val settingsLine = if (preview.settingsWillChange) "设置项将更新。" else "设置项无变化。"
        val rimeLine =
            if (preview.rimeDictionaryFiles > 0) {
                "Rime 自动学习词库 " + preview.rimeDictionaryFiles + " 个文件将合并。"
            } else {
                "文件中没有 Rime 自动学习词库。"
            }
        val message =
            "将新增常用语 " + preview.quickPhrasesToAdd +
                " 条、自定义符号 " + preview.customSymbolsToAdd +
                " 条、备用用户词 " + preview.userPhrasesToAdd +
                " 条。" + settingsLine + rimeLine + "现有条目不会删除。"
        val dialog = AlertDialog.Builder(this)
            .setTitle("导入用户数据")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入") { _, _ -> performImport(archive) }
            .create()
        dialog.setOnShowListener { SetupUi.styleDialog(dialog, this) }
        dialog.show()
    }

    private fun performImport(archive: UserDataArchive) {
        val rime = archive.rimeUserDictionaries
        if (rime.isEmpty()) {
            Thread {
                UserDataRepository.importKotlinData(this, archive)
                LocalVoiceImeService.activeInstance?.refreshPersistedSettingsFromActivity()
                runOnUiThread {
                    Toast.makeText(this, "用户数据已导入", Toast.LENGTH_SHORT).show()
                }
            }.start()
            return
        }

        val service = LocalVoiceImeService.activeInstance
        if (service == null) {
            showError("文件包含 Rime 自动学习词库。请先切换到 openIME，等待输入法就绪后再导入。")
            return
        }
        val sourceDir = File(cacheDir, "rime-user-import").apply {
            deleteRecursively()
            mkdirs()
        }
        val queued = service.importRimeUserData(sourceDir, rime) { imported ->
            if (imported == null) {
                sourceDir.deleteRecursively()
                runOnUiThread {
                    showError("Rime 自动学习词库导入失败，其他数据未修改。")
                }
                return@importRimeUserData
            }
            UserDataRepository.importKotlinData(this, archive)
            service.refreshPersistedSettingsFromActivity()
            sourceDir.deleteRecursively()
            runOnUiThread {
                Toast.makeText(
                    this,
                    "用户数据已导入，Rime 合并 " + imported + " 条词条",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        if (!queued) {
            sourceDir.deleteRecursively()
            showError("Rime 自动学习词库尚未就绪，请稍后重试。")
        }
    }

    private fun infoCard(title: String, body: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(ImeSpacingTokens.LG_DP),
                dp(ImeSpacingTokens.LG_DP),
                dp(ImeSpacingTokens.LG_DP),
                dp(ImeSpacingTokens.LG_DP),
            )
            background = SetupUi.rounded(
                getColor(R.color.setup_surface),
                dp(ImeGeometryTokens.CARD_RADIUS_DP).toFloat(),
                getColor(R.color.setup_input_line),
            )
            addView(
                TextView(this@AboutDataActivity).apply {
                    text = title
                    textSize = ImeTypographyTokens.TITLE_SP
                    setTextColor(getColor(R.color.setup_title))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                },
                wrap(),
            )
            addView(
                TextView(this@AboutDataActivity).apply {
                    text = body
                    textSize = ImeTypographyTokens.BODY_SP
                    setTextColor(getColor(R.color.setup_body))
                    setPadding(0, dp(ImeSpacingTokens.SM_DP), 0, 0)
                },
                wrap(),
            )
        }

    @Suppress("DEPRECATION")
    private fun versionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            .ifBlank { "未知版本" }

    private fun showError(message: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("操作未完成")
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .create()
        dialog.setOnShowListener { SetupUi.styleDialog(dialog, this) }
        dialog.show()
    }

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = SetupUi.dp(this, value)

    private companion object {
        const val REQUEST_EXPORT = 8101
        const val REQUEST_IMPORT = 8102
    }
}
