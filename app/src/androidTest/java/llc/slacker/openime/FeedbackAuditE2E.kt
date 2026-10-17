package llc.slacker.openime

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import llc.slacker.openime.core.Panel
import llc.slacker.openime.data.ClipboardHistoryRepository
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.data.QuickPhraseRepository
import llc.slacker.openime.keyboard.ImeKeyboardView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

/** End-to-end feedback audit. Fixtures are confined to the development emulator. */
@RunWith(AndroidJUnit4::class)
class FeedbackAuditE2E {
    @Before fun requireAuditDriver() {
        assumeTrue("Run scripts/feedback_audit_e2e.py with emulator fixtures",
            InstrumentationRegistry.getArguments().containsKey("phase"))
    }
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    init {
        inst.uiAutomation.serviceInfo = inst.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }
    private val out = File(context.getExternalFilesDir(null), "optimization-audit/" +
        InstrumentationRegistry.getArguments().getString("phase", "feedback")).apply { mkdirs() }
    private val checks = JSONArray()
    private var reportName = "feedback"
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun tree(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun rebind() {
        val other = shell("ime list -s").lineSequence().first { '/' in it && it.substringBefore('/') != context.packageName }
        shell("ime set $other")
        shell("ime set ${context.packageName}/llc.slacker.openime.LocalVoiceImeService")
    }
    private fun shot(name: String) {
        inst.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private fun record(name: String, passed: Boolean, detail: Any? = null) {
        checks.put(JSONObject().put("check", name).put("passed", passed).put("detail", detail))
        File(out, "$reportName-checks.json").writeText(checks.toString(2))
    }
    private fun finishChecks() = assertTrue(checks.toString(2), (0 until checks.length()).all { checks.getJSONObject(it).getBoolean("passed") })
    private fun node(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) node(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun dialogClick(text: String) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            (listOf(inst.uiAutomation.rootInActiveWindow) + inst.uiAutomation.windows.map { it.root })
                .firstNotNullOfOrNull { root -> node(root) { it.text?.toString() == text } }?.let {
                if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            }
            SystemClock.sleep(50)
        }
        error("Dialog action missing: $text")
    }
    private fun copy(h: DirectActivityHarness<ImeTestLabActivity>, text: String, sensitive: Boolean = false) {
        h.awaitMain { activity ->
            val clip = ClipData.newPlainText("audit fixture", text)
            if (sensitive) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip); true
        }
        SystemClock.sleep(300)
    }
    private fun keyboard(h: DirectActivityHarness<ImeTestLabActivity>): ImeKeyboardView = h.awaitMain {
        LocalVoiceImeService.activeInstance?.let { field(it, "keyboardView") as? ImeKeyboardView }?.takeIf { it.isShown && LocalVoiceImeService.activeInstance?.currentInputEditorInfo?.fieldId == R.id.lab_multiline }
    }
    private fun focus(h: DirectActivityHarness<ImeTestLabActivity>) {
        h.awaitMain { activity ->
            val edit = activity.findViewById<EditText>(R.id.lab_multiline)
            edit.requestFocus(); activity.getSystemService(InputMethodManager::class.java).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT); true
        }
        keyboard(h)
    }
    private fun fixturePrefs(name: String): () -> Unit {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val previous = prefs.all
        prefs.edit().clear().commit()
        return {
            val edit = prefs.edit().clear()
            previous.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
            } }; edit.commit(); Unit
        }
    }

    @Test fun clipboardRefreshPinClearAndEditorInsertion() {
        reportName = "clipboard"
        rebind()
        val restore = fixturePrefs("ime_clipboard_history")
        val oldChip = ImeSettingsRepository.loadClipboardChip(context)
        val oldCandidate = ImeSettingsRepository.loadClipboardCandidate(context)
        ImeSettingsRepository.saveClipboardChip(context, true)
        ImeSettingsRepository.saveClipboardCandidate(context, true)
        try {
            DirectActivityHarness(ImeTestLabActivity::class.java).use { h ->
                h.launch(intent = Intent().putExtra("focus_id", "lab_multiline")); focus(h)
                SystemClock.sleep(1000)
                copy(h, "审计复制 A")
                val key = keyboard(h)
                val liveChip = h.awaitMain { tree(key).filter { it.tag == "suggestion-text" }.map { (it as TextView).text.toString() }.takeIf { "审计复制 A" in it } }
                record("复制后提示即时刷新", liveChip.contains("审计复制 A"), liveChip)
                val idle = h.awaitMain { tree(key).filterIsInstance<TextView>().any { it.text.toString() == "审计复制 A" && it.tag == "association-candidate" }.takeIf { it } }
                record("复制后候选即时刷新", idle)
                h.awaitMain { key.showPanel(Panel.CLIPBOARD); true }
                h.awaitMain { tree(key).firstOrNull { it.tag == "clip-card" } }
                val stamp = ClipboardHistoryRepository.load(context).first { it.text == "审计复制 A" }.timestamp
                SystemClock.sleep(100)
                h.awaitMain { key.findTestTarget("clipboard-refresh")!!.performClick(); true }
                SystemClock.sleep(400)
                val refreshedStamp = ClipboardHistoryRepository.load(context).first { it.text == "审计复制 A" }.timestamp
                record("刷新不改变复制时间", stamp == refreshedStamp, "$stamp → $refreshedStamp")
                copy(h, "审计复制 B\n第二行 😀")
                val automatic = h.awaitMain { tree(key).any { it.tag == "clip-card" && it.contentDescription.toString().contains("审计复制 B") }.takeIf { it } }
                record("已开历史面板实时新增", automatic)
                h.awaitMain { key.findTestTarget("clipboard-refresh")!!.performClick(); true }
                h.awaitMain { key.findTestTarget("clip-pin:审计复制 A")?.let { it.performClick(); true } }
                SystemClock.sleep(400)
                val order = h.awaitMain { tree(key).filter { it.tag == "clip-card" }.map { it.contentDescription.toString() } }
                record("置顶后显示在首位", order.firstOrNull()?.contains("审计复制 A") == true, order)
                h.awaitMain { key.findTestTarget("clip-pin:审计复制 B\n第二行 😀")?.let { tree(key).first { v -> v.tag == "clip-card" && v.contentDescription.toString().contains("审计复制 B") }.performClick(); true } }
                val inserted = h.awaitMain { it.findViewById<EditText>(R.id.lab_multiline).text.toString().takeIf { text -> text.isNotEmpty() } }
                record("剪贴板多行Emoji完整上屏", inserted == "审计复制 B\n第二行 😀", inserted)
                h.awaitMain { key.findTestTarget("清除未固定")?.let { it.performClick(); true } }
                SystemClock.sleep(400)
                record("清除未固定保留置顶", ClipboardHistoryRepository.load(context).map { it.text } == listOf("审计复制 A"))
                h.awaitMain { key.findTestTarget("清空全部")?.let { it.performClick(); true } }
                h.awaitMain { key.findTestTarget("取消")!!.performClick(); true }
                record("取消清空保留内容", ClipboardHistoryRepository.load(context).isNotEmpty())
                h.awaitMain { key.findTestTarget("清空全部")?.let { it.performClick(); true } }
                h.awaitMain { key.findTestTarget("clipboard-clear-confirm")?.let { it.performClick(); true } }
                SystemClock.sleep(400)
                h.awaitMain { key.findTestTarget("clipboard-refresh")!!.performClick(); true }
                SystemClock.sleep(300)
                record("清空刷新不复活", ClipboardHistoryRepository.load(context).isEmpty())
                copy(h, "审计复制 B\n第二行 😀")
                h.awaitMain { key.findTestTarget("clipboard-refresh")!!.performClick(); true }
                SystemClock.sleep(400)
                record("同文新复制仍可收录", ClipboardHistoryRepository.load(context).size == 1)
                copy(h, "AUDIT-SENSITIVE", sensitive = true)
                record("敏感剪贴板不持久化", ClipboardHistoryRepository.load(context).none { it.text == "AUDIT-SENSITIVE" })
                shot("clipboard-history")
            }
            finishChecks()
        } finally { restore(); ImeSettingsRepository.saveClipboardChip(context, oldChip); ImeSettingsRepository.saveClipboardCandidate(context, oldCandidate) }
    }

    @Test fun phoneNumberPasswordTransitionsKeepSymbolRail() {
        reportName = "digits"
        rebind()
        DirectActivityHarness(ImeTestLabActivity::class.java).use { h ->
            h.launch(intent = Intent().putExtra("focus_id", "lab_multiline")); focus(h)
            val kinds = listOf("phone" to InputType.TYPE_CLASS_PHONE,
                "number" to InputType.TYPE_CLASS_NUMBER,
                "text-password" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
                "number-password" to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
                "decimal" to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL))
            for ((name, inputType) in kinds) {
                h.awaitMain { activity ->
                    val edit = activity.findViewById<EditText>(R.id.lab_multiline)
                    edit.inputType = inputType; edit.setText("")
                    activity.getSystemService(InputMethodManager::class.java).restartInput(edit); true
                }
                h.awaitMain { LocalVoiceImeService.activeInstance?.takeIf { it.currentInputEditorInfo?.inputType == inputType } }
                h.awaitMain { LocalVoiceImeService.activeInstance!!.handleTestCommand("mode:DIGITS"); true }
                SystemClock.sleep(250)
                val key = keyboard(h)
                val rail = h.awaitMain { key.findTestTarget("digits-symbol-scroll") }
                record("$name 左侧符号区", if (name == "phone") rail.visibility == View.GONE else rail.isShown,
                    "visibility=${rail.visibility}, width=${rail.width}")
                h.awaitMain { key.findTestTarget("1")!!.performClick(); true }
                val value = h.awaitMain { it.findViewById<EditText>(R.id.lab_multiline).text.toString().takeIf { text -> text.isNotEmpty() } }
                record("$name 数字上屏", value == "1")
                shot("digits-$name")
            }
        }
        finishChecks()
    }

    @Test fun quickPhraseCreateEditUseShortcutAndDelete() {
        reportName = "quick-phrases"
        rebind()
        val restore = fixturePrefs("ime_quick_phrases")
        context.getSharedPreferences("ime_quick_phrases", Context.MODE_PRIVATE).edit().putString("items", "[]").commit()
        try {
            DirectActivityHarness(ImeTestLabActivity::class.java).use { h ->
                h.launch(intent = Intent().putExtra("focus_id", "lab_multiline")); focus(h)
                fun panel(): ImeKeyboardView {
                    val key = keyboard(h)
                    h.awaitMain { key.showPanel(Panel.CLIPBOARD); key.findTestTarget("常用语")!!.performClick(); true }
                    return key
                }
                var key = panel()
                DirectActivityHarness(QuickPhraseEditActivity::class.java).use { editor ->
                    h.awaitMain { key.findTestTarget("quick-phrase-add")!!.performClick(); true }
                    editor.awaitMain { activity ->
                        val save = tree(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == "保存" }
                        record("空常用语不能保存", !save.isEnabled)
                        activity.findViewById<EditText>(R.id.quick_phrase_category_editor).setText("审计分类")
                        activity.findViewById<EditText>(R.id.quick_phrase_code_editor).setText("ZZA")
                        activity.findViewById<EditText>(R.id.quick_phrase_text_editor).setText("审计常用语\n完整内容 😀")
                        save.performClick(); true
                    }
                    h.awaitMain { if (it.hasWindowFocus()) true else null }
                }
                key = panel()
                val created = QuickPhraseRepository.load(context).single()
                record("新增及输入码规范化", created.category == "审计分类" && created.inputCode == "zza")
                record("列表显示分类", h.awaitMain { tree(key).filterIsInstance<TextView>().any { it.text.toString() == "审计分类" } })
                h.awaitMain { key.findTestTarget("phrase:${created.id}")!!.performClick(); true }
                val typed = h.awaitMain { it.findViewById<EditText>(R.id.lab_multiline).text.toString().takeIf { text -> text.isNotEmpty() } }
                record("常用语多行Emoji完整上屏", typed == created.text)
                DirectActivityHarness(QuickPhraseEditActivity::class.java).use { editor ->
                    h.awaitMain { key.findTestTarget("phrase-edit:${created.id}")!!.performClick(); true }
                    editor.awaitMain { activity ->
                        activity.findViewById<EditText>(R.id.quick_phrase_text_editor).setText("编辑后的常用语")
                        tree(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == "保存" }.performClick(); true
                    }
                    h.awaitMain { if (it.hasWindowFocus()) true else null }
                }
                key = panel()
                record("编辑持久化且保留输入码", QuickPhraseRepository.load(context).single().let { it.text == "编辑后的常用语" && it.inputCode == "zza" })
                h.awaitMain { key.findTestTarget("phrase-delete:${created.id}")!!.performClick(); true }
                dialogClick("取消")
                record("取消删除保留常用语", QuickPhraseRepository.load(context).size == 1)
                h.awaitMain {
                    LocalVoiceImeService.activeInstance!!.handleTestCommand("mode:PINYIN_26")
                    it.findViewById<EditText>(R.id.lab_multiline).setText("")
                    true
                }
                SystemClock.sleep(300)
                h.awaitMain {
                    "zza".forEach { letter -> key.findTestTarget(letter.toString())!!.performClick() }; true
                }
                h.awaitMain { key.findTestTarget("候选:编辑后的常用语") }
                h.awaitMain { key.findTestTarget("候选:编辑后的常用语")!!.performClick(); true }
                record("输入码候选真实上屏", h.awaitMain { it.findViewById<EditText>(R.id.lab_multiline).text.toString().takeIf { text -> text.isNotEmpty() } } == "编辑后的常用语")
                key = panel()
                shot("quick-phrase-list")
                h.awaitMain { key.findTestTarget("phrase-delete:${created.id}")!!.performClick(); true }
                dialogClick("删除")
                h.awaitMain { if (QuickPhraseRepository.load(context).isEmpty()) true else null }
                record("确认删除且候选不再匹配", QuickPhraseRepository.candidatesForInputCode(context, "zza").isEmpty())
                DirectActivityHarness(QuickPhraseEditActivity::class.java).use { editor ->
                    h.awaitMain { key.findTestTarget("quick-phrase-add")!!.performClick(); true }
                    editor.awaitMain { activity ->
                        activity.findViewById<EditText>(R.id.quick_phrase_text_editor).setText("应放弃的草稿")
                        tree(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == "取消" }.performClick(); true
                    }
                    dialogClick("放弃")
                    h.awaitMain { if (it.hasWindowFocus()) true else null }
                }
                record("放弃草稿不写入", QuickPhraseRepository.load(context).isEmpty())
            }
            finishChecks()
        } finally { restore() }
    }

    @Test fun smsCodeArrivalDismissReopenAndNewMessage() {
        reportName = "sms"
        rebind()
        shell("pm grant ${context.packageName} android.permission.READ_SMS")
        shell("appops set com.android.shell WRITE_SMS allow")
        val oldSms = ImeSettingsRepository.loadSmsCodeChip(context)
        val oldClip = ImeSettingsRepository.loadClipboardChip(context)
        val oldCandidate = ImeSettingsRepository.loadClipboardCandidate(context)
        ImeSettingsRepository.saveSmsCodeChip(context, true)
        ImeSettingsRepository.saveClipboardChip(context, false)
        ImeSettingsRepository.saveClipboardCandidate(context, false)
        val messages = JSONArray()
        fun send(body: String) {
            val sequence = messages.length()
            messages.put(body)
            File(out, "sms-fixtures.json").writeText(messages.toString())
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (!File(out, "sms-ack-$sequence").exists() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            check(File(out, "sms-ack-$sequence").exists()) { "Run with run_feedback_e2e.py to deliver emulator SMS fixtures" }
            SystemClock.sleep(300)
        }
        try {
            DirectActivityHarness(ImeTestLabActivity::class.java).use { h ->
                h.launch(intent = Intent().putExtra("focus_id", "lab_multiline")); focus(h)
                SystemClock.sleep(500)
                send("【openIME审计】验证码736281")
                var key = keyboard(h)
                h.awaitMain { tree(key).filterIsInstance<TextView>().firstOrNull { it.tag == "suggestion-text" && it.text.toString() == "验证码 736281" } }
                record("短信到达自动显示验证码", true)
                h.awaitMain { key.findTestTarget("autofill-close")!!.performClick(); true }
                h.awaitMain { key.findTestTarget("keyboard-hide")!!.performClick(); true }
                h.awaitMain { if (field(LocalVoiceImeService.activeInstance!!, "keyboardView") == null) true else null }
                focus(h); key = keyboard(h)
                SystemClock.sleep(600)
                record("关闭后重开不重复提示同一短信", h.awaitMain { tree(key).filterIsInstance<TextView>().none { it.tag == "suggestion-text" && it.text.toString() == "验证码 736281" } })
                send("【openIME审计】验证码736281")
                h.awaitMain { tree(key).filterIsInstance<TextView>().firstOrNull { it.tag == "suggestion-text" && it.text.toString() == "验证码 736281" } }
                record("新短信相同号码仍可提示", true)
                h.awaitMain { tree(key).first { it.tag == "suggestion-row" && it.contentDescription.toString().startsWith("验证码736281") }.performClick(); true }
                record("点选验证码真实上屏", h.awaitMain { it.findViewById<EditText>(R.id.lab_multiline).text.toString().takeIf { text -> text.isNotEmpty() } } == "736281")
                ImeSettingsRepository.saveSmsCodeChip(context, false)
                h.awaitMain { LocalVoiceImeService.activeInstance!!.refreshAuxiliaryContentFromActivity(); true }
                send("【openIME审计】验证码864209")
                SystemClock.sleep(600)
                record("关闭设置后不再显示", h.awaitMain { tree(key).filterIsInstance<TextView>().none { it.tag == "suggestion-text" && it.text.toString().startsWith("验证码") } })
                shot("sms-code-state")
            }
            finishChecks()
        } finally {
            ImeSettingsRepository.saveSmsCodeChip(context, oldSms)
            ImeSettingsRepository.saveClipboardChip(context, oldClip)
            ImeSettingsRepository.saveClipboardCandidate(context, oldCandidate)
        }
    }

    @Test fun clipboardRetentionPrivacyAndSplitToPhrase() {
        reportName = "clipboard-boundaries"
        rebind()
        val restoreClips = fixturePrefs("ime_clipboard_history")
        val restorePhrases = fixturePrefs("ime_quick_phrases")
        val oldCandidate = ImeSettingsRepository.loadClipboardCandidate(context)
        ImeSettingsRepository.saveClipboardCandidate(context, true)
        try {
            DirectActivityHarness(ImeTestLabActivity::class.java).use { h ->
                h.launch(intent = Intent().putExtra("focus_id", "lab_multiline")); focus(h)
                val key = keyboard(h)
                copy(h, "AUDIT-SENSITIVE", sensitive = true)
                SystemClock.sleep(500)
                record("敏感剪贴板不自动推荐", h.awaitMain { tree(key).filterIsInstance<TextView>().none { it.tag == "association-candidate" && it.text.toString() == "AUDIT-SENSITIVE" } })
                h.awaitMain { activity ->
                    val edit = activity.findViewById<EditText>(R.id.lab_multiline)
                    edit.imeOptions = edit.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    activity.getSystemService(InputMethodManager::class.java).restartInput(edit); true
                }
                h.awaitMain { LocalVoiceImeService.activeInstance?.takeIf { it.currentInputEditorInfo.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0 } }
                copy(h, "AUDIT-NO-LEARNING")
                record("不学习字段不保存复制内容", ClipboardHistoryRepository.load(context).none { it.text == "AUDIT-NO-LEARNING" })
                h.awaitMain { activity ->
                    val edit = activity.findViewById<EditText>(R.id.lab_multiline)
                    edit.imeOptions = edit.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
                    activity.getSystemService(InputMethodManager::class.java).restartInput(edit); true
                }
                h.awaitMain { LocalVoiceImeService.activeInstance?.takeIf { it.currentInputEditorInfo.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0 } }
                val now = System.currentTimeMillis()
                val seeded = JSONArray()
                (0 until 26).forEach { i -> seeded.put(JSONObject().put("text", "fixture-$i").put("timestamp", now - i * 1000).put("pinned", i == 25)) }
                seeded.put(JSONObject().put("text", "expired").put("timestamp", now - 25 * 60 * 60 * 1000L))
                context.getSharedPreferences("ime_clipboard_history", Context.MODE_PRIVATE).edit().putString("items", seeded.toString()).commit()
                record("24小时过期清理", ClipboardHistoryRepository.load(context).none { it.text == "expired" })
                copy(h, "数量上限新复制")
                val bounded = ClipboardHistoryRepository.load(context)
                record("数量上限且保留固定内容", bounded.size == 24 && bounded.any { it.text == "fixture-25" && it.pinned }, bounded.size)
                copy(h, "你好，世界 😀\n明天见")
                h.awaitMain { key.showPanel(Panel.CLIPBOARD); true }
                h.awaitMain { tree(key).firstOrNull { it.tag == "clip-card" && it.contentDescription.toString().contains("你好，世界") }?.let { it.performLongClick(); true } }
                val flow = h.awaitMain { key.findTestTarget("word-seg-flow") as? ViewGroup }
                h.awaitMain { if (flow.childCount > 0 && flow.width > 0) true else null }
                record("未选择文字禁止添加常用语", h.awaitMain { !key.findTestTarget("word-seg-add")!!.isEnabled })
                h.awaitMain {
                    val word = (0 until flow.childCount).map { flow.getChildAt(it) }.first { it.tag.toString().startsWith("word-token:") }
                    val down = SystemClock.uptimeMillis()
                    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, word.x + word.width / 2, word.y + word.height / 2, 0)
                        flow.dispatchTouchEvent(event); event.recycle()
                    }; true
                }
                h.awaitMain { key.findTestTarget("word-seg-add")?.takeIf { it.isEnabled }?.let { it.performClick(); true } }
                record("拆词添加常用语", QuickPhraseRepository.load(context).any { it.text == "你好" })
                shot("split-to-phrase")
            }
            finishChecks()
        } finally { restoreClips(); restorePhrases(); ImeSettingsRepository.saveClipboardCandidate(context, oldCandidate) }
    }

    @Test fun copyFromAnotherAppWithKeyboardOpenAndHidden() {
        reportName = "cross-app-clipboard"
        rebind()
        val restore = fixturePrefs("ime_clipboard_history")
        val oldClip = ImeSettingsRepository.loadClipboardChip(context)
        val oldCandidate = ImeSettingsRepository.loadClipboardCandidate(context)
        ImeSettingsRepository.saveClipboardChip(context, true)
        ImeSettingsRepository.saveClipboardCandidate(context, false)
        try {
            DirectActivityHarness(ImeTestLabActivity::class.java).use { h ->
                h.launch(intent = Intent().putExtra("focus_id", "lab_multiline")); focus(h)
                val sourcePackage = inst.context.packageName
                shell("am start -W -n $sourcePackage/llc.slacker.openime.ClipboardSourceActivity")
                var sourceEditor: AccessibilityNodeInfo? = null
                val editorDeadline = SystemClock.uptimeMillis() + 10_000
                while (sourceEditor == null && SystemClock.uptimeMillis() < editorDeadline) {
                    sourceEditor = (listOf(inst.uiAutomation.rootInActiveWindow) + inst.uiAutomation.windows.map { it.root })
                        .firstNotNullOfOrNull { root -> node(root) {
                            it.packageName?.toString() == sourcePackage && it.className?.toString() == EditText::class.java.name && it.isVisibleToUser
                        } }
                    if (sourceEditor == null) SystemClock.sleep(100)
                }
                check(sourceEditor != null) { "External editor is not visible" }
                val bounds = android.graphics.Rect().also(sourceEditor!!::getBoundsInScreen)
                val down = SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    inst.uiAutomation.injectInputEvent(event, true); event.recycle()
                }
                shell("am start -W -n $sourcePackage/llc.slacker.openime.ClipboardSourceActivity --ez request_keyboard true")
                h.awaitMain { LocalVoiceImeService.activeInstance?.takeIf { it.currentInputEditorInfo?.packageName == sourcePackage && (field(it, "keyboardView") as? View)?.isShown == true } }
                dialogClick("复制跨应用测试内容")
                h.awaitMain { if (ClipboardHistoryRepository.load(context).any { it.text == "外部App复制 · 第一条 😀" }) true else null }
                record("其他App复制自动进入历史", true)
                h.awaitMain {
                    val key = field(LocalVoiceImeService.activeInstance!!, "keyboardView") as ImeKeyboardView
                    tree(key).filterIsInstance<TextView>().firstOrNull { it.tag == "suggestion-text" && it.text.toString() == "外部App复制 · 第一条 😀" && it.isShown }
                }
                record("其他App输入时工具栏即时提示", true)
                val uid = h.awaitMain { it.getSystemService(ClipboardManager::class.java).primaryClip?.description?.label?.toString() }
                record("复制来源确实为不同UID", uid != "fixture-uid:${android.os.Process.myUid()}", uid)
                shot("cross-app-copy-open")
                shell("am start -W -f 0x14000000 -n $sourcePackage/llc.slacker.openime.ClipboardSourceActivity --ez copy_long true")
                val longText = "跨应用长内容 😀。\n".repeat(80)
                h.awaitMain { if (ClipboardHistoryRepository.load(context).any { it.text == longText }) true else null }
                record("外部App长文本完整收录", true)
                SystemClock.sleep(700)
                record("外部App长文本也显示工具栏提示", h.awaitMain {
                    val current = field(LocalVoiceImeService.activeInstance!!, "keyboardView") as ImeKeyboardView
                    tree(current).filterIsInstance<TextView>().any { it.tag == "suggestion-text" && it.text.toString().startsWith("跨应用长内容") && it.isShown }
                })
                shot("cross-app-copy-long")
                h.awaitMain {
                    val current = field(LocalVoiceImeService.activeInstance!!, "keyboardView") as ImeKeyboardView
                    tree(current).firstOrNull { row -> row.tag == "suggestion-row" && tree(row).filterIsInstance<TextView>().any { it.text.toString().startsWith("跨应用长内容") } }
                        ?.let { it.performClick(); true }
                }
                h.awaitMain { if (LocalVoiceImeService.activeInstance!!.currentInputConnection?.getTextBeforeCursor(longText.length + 10, 0)?.toString() == longText) true else null }
                record("长文本工具栏点选完整上屏", true)
                h.awaitMain { LocalVoiceImeService.activeInstance!!.requestHideSelf(0); true }
                SystemClock.sleep(400)
                dialogClick("收起键盘后复制")
                h.awaitMain { if (ClipboardHistoryRepository.load(context).any { it.text == "外部App复制 · 键盘收起时" }) true else null }
                record("键盘收起时仍收录外部复制", true)
                dialogClick("返回原输入框")
                h.awaitMain { if (it.hasWindowFocus()) true else null }
                focus(h)
                val key = keyboard(h)
                h.awaitMain { tree(key).filterIsInstance<TextView>().firstOrNull { it.tag == "suggestion-text" && it.text.toString() == "外部App复制 · 键盘收起时" && it.isShown } }
                record("回到原App仍显示复制提示", true)
                h.awaitMain { key.showPanel(Panel.CLIPBOARD); true }
                h.awaitMain { tree(key).firstOrNull { it.tag == "clip-card" && it.contentDescription.toString().contains("外部App复制 · 键盘收起时") } }
                record("历史面板可见外部复制记录", true)
                shot("cross-app-history")
            }
            finishChecks()
        } finally {
            restore()
            ImeSettingsRepository.saveClipboardChip(context, oldClip)
            ImeSettingsRepository.saveClipboardCandidate(context, oldCandidate)
        }
    }

    @Test fun aboutDocumentsAndRepositoryLinks() {
        reportName = "about"
        val links = mutableListOf<String>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                links += intent.dataString.orEmpty()
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        inst.addMonitor(monitor)
        try {
            DirectActivityHarness(AboutActivity::class.java).use { h ->
                h.launch()
                fun action(label: String) = h.awaitMain { activity -> tree(activity.window.decorView).filterIsInstance<TextView>().firstOrNull { it.text.toString() == label }?.let { it.performClick(); true } }
                action("GitHub 仓库"); action("反馈问题（Issue）")
                record("GitHub 与Issue链接", links == listOf("https://github.com/Slacker-LLC/openIME", "https://github.com/Slacker-LLC/openIME/issues/new"), links)
                for ((label, content) in listOf("开源许可证（GPL-3.0）" to "GNU GENERAL PUBLIC LICENSE", "隐私政策" to "openIME 隐私政策")) {
                    action(label)
                    SystemClock.sleep(500)
                    val found = node(inst.uiAutomation.rootInActiveWindow) { it.text?.toString()?.contains(content) == true }
                    val deadline = SystemClock.uptimeMillis() + 6000
                    var document = found
                    while (document == null && SystemClock.uptimeMillis() < deadline) {
                        SystemClock.sleep(100)
                        document = node(inst.uiAutomation.rootInActiveWindow) { it.text?.toString()?.contains(content) == true }
                    }
                    record("$label 离线全文", document != null)
                    shot(if (label.startsWith("隐私")) "about-privacy" else "about-gpl")
                    dialogClick("关闭")
                }
                action("开源软件说明")
                dialogClick("Fluent Emoji · MIT")
                SystemClock.sleep(150)
                record("第三方许可可读", node(inst.uiAutomation.rootInActiveWindow) { it.text?.toString()?.contains("Permission is hereby granted") == true } != null)
                shot("about-third-party")
                dialogClick("关闭")
                SystemClock.sleep(500)
                shot("about-page")
                record("APK中的所有许可资源", context.assets.list("licenses")!!.all { context.assets.open("licenses/$it").use { stream -> stream.read() >= 0 } })
                record("项目声明来自正式文档", context.assets.open("legal/THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }.contains("# Third-party notices"))
            }
            finishChecks()
        } finally { inst.removeMonitor(monitor) }
    }
}
