package llc.slacker.openime

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import llc.slacker.openime.core.CrashGuard
import llc.slacker.openime.data.UserPhraseRepository
import llc.slacker.openime.setup.SetupUi
import llc.slacker.openime.theme.ImeDrawableFactory
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeSpacingTokens
import llc.slacker.openime.theme.ImeTypographyTokens
import llc.slacker.openime.voice.VoiceHotwordProvider
import llc.slacker.openime.voice.VoiceModelDownloads
import llc.slacker.openime.voice.VoiceModelManifest
import llc.slacker.openime.voice.VoiceModelRepository
import llc.slacker.openime.voice.VoiceModelSelection
import java.io.File
import llc.slacker.openime.voice.VoiceModelArchive

/**
 * 语音模型: 查看当前使用的模型，获取下载地址或导入兼容的流式模型包，
 * 在内置与已导入模型之间切换，或删除已导入的模型。
 *
 * 精简版 APK 不带内置模型，这里是唯一的来源；完整版也可以在这里换成自己的模型。
 */
class VoiceModelActivity : Activity() {
    private lateinit var content: LinearLayout
    private val repository by lazy { VoiceModelRepository(this) }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(SetupUi.appearanceContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        // 管理页可能先于输入法服务启动；先把词库读进来，「语音热词」才能显示和保存。
        UserPhraseRepository.configure(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "voice-model-content"
            setPadding(dp(ImeSpacingTokens.LG_DP), 0, dp(ImeSpacingTokens.LG_DP), dp(ImeSpacingTokens.XXL_DP))
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(getColor(R.color.setup_page_bg))
                isFillViewport = true
                setOnApplyWindowInsetsListener { view, insets ->
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    } else {
                        @Suppress("DEPRECATION")
                        view.setPadding(
                            insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                            insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                        )
                    }
                    insets
                }
                addView(content, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ))
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    val width = minOf(
                        (view.width - view.paddingLeft - view.paddingRight).coerceAtLeast(0),
                        dp(600),
                    )
                    val params = content.layoutParams as FrameLayout.LayoutParams
                    if (params.width != width) {
                        params.width = width
                        content.layoutParams = params
                    }
                }
            },
        )
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(
            SetupUi.activityTopBar(context = this, title = "语音模型", onBack = ::finish).apply {
                // Keep the title centered even though only the start side has a back button.
                (getChildAt(1) as TextView).apply {
                    gravity = Gravity.CENTER
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(ImeGeometryTokens.TOUCH_TARGET_DP)
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ImeGeometryTokens.TOP_BAR_HEIGHT_DP))
                .apply { marginStart = -dp(ImeSpacingTokens.LG_DP); marginEnd = -dp(ImeSpacingTokens.LG_DP) },
        )

        val selection = repository.selectAvailable()
        val current = selection.manifest
        val currentBody = when {
            current == null -> selection.reason.ifBlank { "尚未安装语音模型。" } +
                "\n可从下方复制兼容模型的下载地址，下载后导入即可使用语音输入。"
            selection.source == VoiceModelSelection.Source.BUILT_IN -> "内置模型：${current.modelId}\n版本 ${current.modelVersion}，语言 ${current.language}"
            else -> "已导入：${current.modelId}\n版本 ${current.modelVersion}，语言 ${current.language}"
        }
        content.addView(
            infoCard("当前使用", currentBody),
            wrap().apply { topMargin = dp(ImeSpacingTokens.SM_DP) },
        )

        content.addView(
            hotwordCard(selection),
            wrap().apply { topMargin = dp(ImeSpacingTokens.SM_DP) },
        )

        val builtIn = repository.isBuiltInAvailable()
        val installed = repository.installedModels()
        val selectedId = repository.selectedModelId()

        if (builtIn || installed.isNotEmpty()) {
            content.addView(sectionTitle("可用模型"), wrap().apply { topMargin = dp(ImeSpacingTokens.LG_DP) })
            if (builtIn) {
                val active = selection.source == VoiceModelSelection.Source.BUILT_IN
                content.addView(
                    modelRow("内置模型", "随安装包提供，不可删除", active,
                        onUse = { repository.useBuiltInModel(); render() }, onDelete = null),
                    wrap().apply { topMargin = dp(ImeSpacingTokens.SM_DP) },
                )
            }
            installed.forEach { manifest ->
                val active = selection.source == VoiceModelSelection.Source.DOWNLOADED && selectedId == manifest.modelId
                content.addView(
                    modelRow(
                        manifest.modelId,
                        "版本 ${manifest.modelVersion} · ${manifest.language}" +
                            if (manifest.supportsPunctuation) " · 带标点" else "",
                        active,
                        onUse = {
                            if (repository.setSelectedModel(manifest.modelId)) render()
                            else showError("这个模型校验失败，无法使用。可以删除后重新导入。")
                        },
                        onDelete = { confirmDelete(manifest) },
                    ),
                    wrap().apply { topMargin = dp(ImeSpacingTokens.SM_DP) },
                )
            }
        }

        content.addView(
            infoCard(
                "导入模型",
                "选择 ZIP、TAR、TAR.GZ 或 TGZ 模型包。支持 sherpa-onnx 的流式 transducer、paraformer、" +
                    "zipformer2-ctc、nemo-ctc、t-one-ctc。\n" +
                    "Paraformer 和 transducer 可自动识别模型文件；单文件 CTC 模型需要附带 manifest.json 声明类型和文件角色。" +
                    "导入前会校验文件完整性，并让你确认一次。",
            ).also { card ->
                card.addView(
                    SetupUi.primaryButton(this, "选择模型包") { pickZip() },
                    actionParams(),
                )
            },
            wrap().apply { topMargin = dp(ImeSpacingTokens.LG_DP) },
        )
        content.addView(
            infoCard("模型下载", VoiceModelDownloads.IMPORT_INSTRUCTIONS),
            wrap().apply { topMargin = dp(ImeSpacingTokens.LG_DP) },
        )
        VoiceModelDownloads.models.forEach { model ->
            content.addView(downloadCard(model), wrap().apply { topMargin = dp(ImeSpacingTokens.SM_DP) })
        }
    }

    /** All cards share one text and action column instead of mixing icon and non-icon indents. */
    private fun infoCard(title: String, body: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = "voice-model-card"
        val pad = dp(ImeSpacingTokens.LG_DP)
        setPadding(pad, pad, pad, pad)
        background = SetupUi.cardBackground(this@VoiceModelActivity)
        addView(TextView(this@VoiceModelActivity).apply {
            text = title
            textSize = ImeTypographyTokens.ROW_SP
            setTextColor(getColor(R.color.setup_title))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, wrap())
        addView(bodyText(body), wrap().apply { topMargin = dp(ImeSpacingTokens.XS_DP) })
    }

    private fun bodyText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = ImeTypographyTokens.DETAIL_SP
        setTextColor(getColor(R.color.setup_body))
        gravity = Gravity.START
        setLineSpacing(0f, 1.3f)
    }

    private fun actionParams() = wrap().apply {
        topMargin = dp(ImeSpacingTokens.MD_DP)
    }

    private fun downloadCard(model: VoiceModelDownloads.Model): LinearLayout =
        infoCard(model.title, model.downloadDescription).apply {
            tag = "voice-model-download-${model.id}"
            addView(bodyText(model.url).apply {
                tag = "voice-model-url-${model.id}"
                setTextIsSelectable(true)
            }, wrap().apply { topMargin = dp(ImeSpacingTokens.SM_DP) })
            addView(
                SetupUi.secondaryButton(this@VoiceModelActivity, "复制下载地址") {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText(model.title, model.url))
                    Toast.makeText(this@VoiceModelActivity, "下载地址已复制", Toast.LENGTH_SHORT).show()
                }.apply { contentDescription = "复制${model.title}下载地址" },
                actionParams(),
            )
        }

    /** 「语音热词」卡片：手动词表只有 transducer 模型会用到。 */
    private fun hotwordCard(selection: VoiceModelSelection): LinearLayout {
        val hotwords = UserPhraseRepository.manualVoiceHotwords()
        val supported = selection.manifest?.modelType == "transducer"
        val preview = hotwords.take(8).joinToString("、") + if (hotwords.size > 8) " 等" else ""
        val body = buildString {
            append("人名、产品名等专有名词加进热词会更容易被正确识别。一行一个词；只采纳含中文、2 到 16 个字的词。")
            append('\n')
            append(if (hotwords.isEmpty()) "还没有设置热词。" else "已设置 ${hotwords.size} 个词：$preview")
            append('\n')
            append(
                if (supported) {
                    "当前 transducer 模型已启用热词，保存后下一次录音生效。"
                } else {
                    "热词只对 transducer 模型生效；当前模型不支持，切换到 transducer 模型后自动生效。"
                },
            )
        }
        return infoCard("语音热词", body).apply {
            addView(
                SetupUi.secondaryButton(this@VoiceModelActivity, "编辑热词") { editHotwords() },
                actionParams(),
            )
        }
    }

    private fun editHotwords() {
        val input = EditText(this).apply {
            setText(UserPhraseRepository.manualVoiceHotwords().joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            minLines = 5
            maxLines = 10
            textSize = ImeTypographyTokens.BODY_SP
            hint = "一行一个词，例如：\n澎湃OS\n语音识别"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ImeDrawableFactory.focusedRounded(
                fillColor = getColor(R.color.setup_surface),
                radiusPx = dp(14).toFloat(),
                focusedStrokeColor = SetupUi.accent(this@VoiceModelActivity),
                defaultStrokeColor = getColor(R.color.setup_hairline),
                strokeWidthPx = dp(1).coerceAtLeast(1) + dp(1) / 2,
            )
            SetupUi.styleCursor(this@VoiceModelActivity, this)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("语音热词")
            .setView(container)
            .setNegativeButton("取消", null)
            .setNeutralButton("清空", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            SetupUi.styleDialog(dialog, this)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveHotwords(input.text.toString())
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                saveHotwords("")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveHotwords(raw: String) {
        val hotwords = VoiceHotwordProvider.parseLines(raw)
        if (!UserPhraseRepository.setManualVoiceHotwords(hotwords)) {
            showError("热词保存失败，请重试。")
            return
        }
        val modelType = repository.selectAvailable().manifest?.modelType
        val message = when {
            hotwords.isEmpty() -> "语音热词已清空。"
            modelType == "transducer" -> "已保存 ${hotwords.size} 个热词，下一次录音生效。"
            else -> "已保存 ${hotwords.size} 个热词。当前模型（${modelType ?: "未知"}）不支持热词，切换到 transducer 模型后生效。"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        render()
    }

    private fun modelRow(
        title: String,
        subtitle: String,
        active: Boolean,
        onUse: () -> Unit,
        onDelete: (() -> Unit)?,
    ): LinearLayout = infoCard(if (active) "$title（使用中）" else title, subtitle).apply {
        tag = "voice-model-row"
        val buttons = LinearLayout(this@VoiceModelActivity).apply { orientation = LinearLayout.HORIZONTAL }
        if (!active) {
            buttons.addView(
                SetupUi.secondaryButton(this@VoiceModelActivity, "使用") { onUse() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
            )
        }
        if (onDelete != null) {
            buttons.addView(
                SetupUi.secondaryButton(this@VoiceModelActivity, "删除") { onDelete() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        if (buttons.childCount > 0) {
            addView(buttons, actionParams())
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = ImeTypographyTokens.SMALL_SP
        setTextColor(getColor(R.color.setup_body))
        setPadding(dp(ImeSpacingTokens.LG_DP), 0, dp(ImeSpacingTokens.LG_DP), 0)
    }

    private fun pickZip() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/gzip",
                        "application/x-gzip",
                        "application/x-tar",
                        // The official model archives are tar.bz2, and a SAF
                        // picker filters on the provider's MIME type, not on the
                        // file name.
                        "application/x-bzip2",
                        "application/x-bzip",
                        "application/octet-stream",
                    ),
                )
            },
            REQUEST_PICK,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        inspectInBackground(uri)
    }

    private fun inspectInBackground(uri: Uri) {
        Toast.makeText(this, "正在校验模型包…", Toast.LENGTH_SHORT).show()
        Thread {
            val staging = File(cacheDir, "voice-model-import").apply { deleteRecursively(); mkdirs() }
            val outcome = runCatching {
                val name = displayName(uri)
                contentResolver.openInputStream(uri).use { raw ->
                    requireNotNull(raw) { "无法读取所选文件" }
                    val buffered = java.io.BufferedInputStream(raw)
                    buffered.mark(1024 * 1024)
                    val header = ByteArray(512)
                    val read = buffered.read(header)
                    buffered.reset()
                    val format = VoiceModelArchive.formatOf(name)
                        ?: VoiceModelArchive.sniff(if (read > 0) header.copyOf(read) else ByteArray(0))
                        ?: error("无法识别的压缩格式（支持 zip / tar / tar.gz / tgz / tar.bz2）")
                    VoiceModelArchive.extract(buffered, format, staging)
                }
                val root = findPackageRoot(staging) ?: error("压缩包里找不到模型文件")
                root to repository.inspectPackage(root).getOrThrow()
            }
            runOnUiThread {
                outcome.onSuccess { (root, info) -> confirmImport(root, info, staging) }
                    .onFailure {
                        staging.deleteRecursively()
                        showError("导入失败：" + (it.message ?: "未知错误"))
                    }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun displayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull().orEmpty()
    }

    private fun confirmImport(root: File, info: VoiceModelRepository.PackageInfo, staging: File) {
        val sizeMb = info.totalBytes / 1_048_576
        val trust = (if (info.autoDetected) "压缩包里没有清单，已自动识别：${info.note}。语言未知，可能影响标点和断句。\n\n" else "") +
            (if (info.trusted) "该模型在受信目录内。" else "该模型不在受信目录内，来源由你自己负责。请只导入你信任的模型包。")
        val dialog = AlertDialog.Builder(this)
            .setTitle("导入语音模型？")
            .setMessage("${info.modelId}\n版本 ${info.modelVersion} · ${info.language}\n约 $sizeMb MB${if (info.supportsPunctuation) " · 带标点" else ""}\n\n$trust")
            .setNegativeButton("取消") { _, _ -> staging.deleteRecursively() }
            .setOnCancelListener { staging.deleteRecursively() }
            .setPositiveButton("导入并使用") { _, _ ->
                Thread {
                    val result = repository.importUserPackage(root)
                    staging.deleteRecursively()
                    runOnUiThread {
                        result.onSuccess {
                            Toast.makeText(this, "已导入并切换到 $it", Toast.LENGTH_SHORT).show()
                            render()
                        }.onFailure { showError("安装失败：" + (it.message ?: "未知错误")) }
                    }
                }.apply { isDaemon = true }.start()
            }
            .create()
        dialog.setOnShowListener { SetupUi.styleDialog(dialog, this) }
        dialog.show()
    }

    private fun confirmDelete(manifest: VoiceModelManifest) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除语音模型？")
            .setMessage("${manifest.modelId} 将从本机移除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                repository.deleteDownloadedModel(manifest.modelId)
                render()
            }
            .create()
        dialog.setOnShowListener { SetupUi.styleDialog(dialog, this, destructivePositive = true) }
        dialog.show()
    }

    /** 模型在压缩包根，或在唯一的一层子目录里（官方发布包通常是后者）。 */
    private fun findPackageRoot(dir: File): File? {
        fun looksLikePackage(candidate: File): Boolean =
            File(candidate, "manifest.json").isFile || File(candidate, "tokens.txt").isFile ||
                candidate.walkTopDown().any { it.isFile && it.name.endsWith(".onnx") }
        if (looksLikePackage(dir)) return dir
        val children = dir.listFiles().orEmpty().filter { it.isDirectory }
        return children.singleOrNull()?.takeIf(::looksLikePackage)
            ?: dir.takeIf { d -> d.walkTopDown().any { it.isFile && it.name == "tokens.txt" } }
    }

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
        const val REQUEST_PICK = 8201
    }
}
