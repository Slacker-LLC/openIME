package llc.slacker.openime

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import llc.slacker.openime.core.LandscapeLayout
import llc.slacker.openime.core.PinyinLocation
import llc.slacker.openime.core.ImeState
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.keyboard.InlineChipTracker
import llc.slacker.openime.keyboard.ImeTopZone
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

/** Real Activity/IME flows; only the remote inflate failure is injected at its callback boundary. */
@RunWith(AndroidJUnit4::class)
class OptimizationAuditE2E {
    @Before fun requireAuditDriver() {
        assumeTrue("Run scripts/feedback_audit_e2e.py with emulator fixtures",
            InstrumentationRegistry.getArguments().containsKey("phase"))
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val out = File(context.getExternalFilesDir(null), "optimization-audit/" +
        InstrumentationRegistry.getArguments().getString("phase", "run")).apply { mkdirs() }

    private fun <T : Any> await(probe: () -> T?): T {
        val until = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < until) {
            probe()?.let { return it }
            SystemClock.sleep(50)
        }
        error("Timed out waiting for audit UI")
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass
        .getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun tap(point: IntArray) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, point[0].toFloat(), point[1].toFloat(), 0)
            check(instrumentation.uiAutomation.injectInputEvent(event.apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }, true))
            event.recycle()
        }
    }

    private fun rebindIme() {
        // Instrumentation restarts its target process; rebind the platform IME connection afterwards.
        val enabled = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("ime list -s"),
        ).bufferedReader().use { it.readLines() }
        val other = enabled.first { it.contains('/') && it.substringBefore('/') != context.packageName }
        for (id in listOf(other, context.packageName + "/llc.slacker.openime.LocalVoiceImeService")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("ime set " + id),
            ).bufferedReader().use { it.readText() }
        }
    }

    private fun shot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }

    private fun clickDialog(text: String) {
        await { find(instrumentation.uiAutomation.rootInActiveWindow, text) }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun documentResult(activity: DataManagementActivity, request: Int, file: File) {
        DataManagementActivity::class.java.getDeclaredMethod(
            "onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java,
        ).apply { isAccessible = true }.invoke(activity, request, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
    }

    private fun settings(): JSONObject = JSONObject()
        .put("landscape_layout", ImeSettingsRepository.loadLandscapeLayout(context).key)
        .put("split_mirrored", ImeSettingsRepository.loadSplitMirrored(context))
        .put("sms_code_chip", ImeSettingsRepository.loadSmsCodeChip(context))
        .put("clipboard_chip", ImeSettingsRepository.loadClipboardChip(context))
        .put("pinyin_location", ImeSettingsRepository.loadPinyinLocation(context).key)

    private fun setSettings(value: JSONObject) {
        ImeSettingsRepository.saveLandscapeLayout(context, LandscapeLayout.fromKey(value.getString("landscape_layout")))
        ImeSettingsRepository.saveSplitMirrored(context, value.getBoolean("split_mirrored"))
        ImeSettingsRepository.saveSmsCodeChip(context, value.getBoolean("sms_code_chip"))
        ImeSettingsRepository.saveClipboardChip(context, value.getBoolean("clipboard_chip"))
        ImeSettingsRepository.savePinyinLocation(context, PinyinLocation.fromKey(value.getString("pinyin_location")))
    }

    @Test
    fun beta10CornerSymbolsAndCommaKeyReachTheEditor() {
        rebindIme()
        val report = JSONObject()
        val english = "1234567890~!@#$%&*?()-_:;/".map { it.toString() }
        val chinese = "1234567890~！@#￥%&*？（）-_：；/".map { it.toString() }
        val rows = org.json.JSONArray()
        try {
            DirectActivityHarness(ImeTestLabActivity::class.java).use { harness ->
                harness.launch(intent = Intent().putExtra("focus_id", "lab_multiline"))
                harness.awaitMain { if (it.hasWindowFocus()) true else null }
                tap(harness.awaitMain { activity ->
                    val edit = activity.findViewById<EditText>(R.id.lab_multiline)
                    IntArray(2).also { edit.getLocationOnScreen(it); it[0] += edit.width / 2; it[1] += edit.height / 2 }
                })
                val service = harness.awaitMain {
                    LocalVoiceImeService.activeInstance?.takeIf { (field(it, "keyboardView") as? View)?.isShown == true && it.currentInputEditorInfo?.fieldId == R.id.lab_multiline && it.currentInputConnection != null }
                }
                for ((mode, hints) in listOf("ENGLISH_26" to english, "PINYIN_26" to chinese)) {
                    harness.awaitMain { service.handleTestCommand("mode:$mode"); true }
                    fun keyPoint(tag: String): IntArray = harness.awaitMain {
                        val view = field(service, "keyboardView") as ImeKeyboardView
                        view.findTestTarget(tag)?.takeIf { it.isShown && it.width > 0 && it.height > 0 }?.let { key ->
                            IntArray(2).also { key.getLocationOnScreen(it); it[0] += key.width / 2; it[1] += key.height / 2 }
                        }
                    }
                    harness.awaitMain { service.onClearAll(); true }
                    harness.awaitMain { if (it.findViewById<EditText>(R.id.lab_multiline).text.isEmpty()) true else null }
                    harness.awaitMain {
                        val keyboard = field(service, "keyboardView") as ImeKeyboardView
                        if ((field(keyboard, "renderedMode") as? llc.slacker.openime.core.KeyboardMode)?.name != mode) return@awaitMain null
                        keyboard.findTestTarget("key-punctuation")?.takeIf { it.isShown }?.let { it.performClick(); true }
                    }
                    val expectedComma = if (mode == "ENGLISH_26") "," else "，"
                    val actualComma = harness.awaitMain { it.findViewById<EditText>(R.id.lab_multiline).text.toString().takeIf { text -> text.isNotEmpty() } }
                    rows.put(JSONObject().put("mode", mode).put("commaExpected", expectedComma).put("commaActual", actualComma))
                    assertTrue("$mode comma key typed $actualComma", expectedComma == actualComma)
                    var expected = expectedComma
                    for ((index, letter) in "qwertyuiopasdfghjklzxcvbnm".withIndex()) {
                        val point = keyPoint(letter.toString())
                        val distance = harness.awaitMain { (field(service, "keyboardView") as ImeKeyboardView).scaledPx(60) }
                        val down = SystemClock.uptimeMillis()
                        for ((action, dy) in listOf(android.view.MotionEvent.ACTION_DOWN to 0,
                            android.view.MotionEvent.ACTION_MOVE to -distance,
                            android.view.MotionEvent.ACTION_UP to -distance)) {
                            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0].toFloat(), (point[1] + dy).toFloat(), 0)
                            harness.awaitMain {
                                val key = (field(service, "keyboardView") as ImeKeyboardView).findTestTarget(letter.toString())!!
                                val local = android.view.MotionEvent.obtain(event)
                                local.setLocation(key.width / 2f, key.height / 2f + dy)
                                local.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                                key.dispatchTouchEvent(local)
                                local.recycle()
                                true
                            }
                            event.recycle()
                            SystemClock.sleep(40)
                        }
                        expected += hints[index]
                        val actual = harness.awaitMain { it.findViewById<EditText>(R.id.lab_multiline).text.toString().takeIf { text -> text.length >= expected.length } }
                        rows.put(JSONObject().put("mode", mode).put("letter", letter.toString()).put("expected", expected).put("actual", actual))
                        assertTrue("$mode $letter corner did not reach editor", actual == expected)
                    }
                    shot("beta10-symbols-$mode")
                }
                report.put("passed", true)
            }
        } finally {
            report.put("checks", rows)
            File(out, "beta10-symbols.json").writeText(report.toString(2))
        }
    }

    @Test
    fun captureTypingAndFontBaseline() {
        val report = JSONObject()
        rebindIme()
        DirectActivityHarness(ImeTestLabActivity::class.java).use { harness ->
            harness.launch(intent = Intent().putExtra("focus_id", "lab_multiline"))
            harness.awaitMain { if (it.hasWindowFocus()) true else null }
            harness.awaitMain { activity ->
                val edit = activity.findViewById<EditText>(R.id.lab_multiline)
                edit.requestFocus()
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
                true
            }
            tap(harness.awaitMain { activity ->
                val edit = activity.findViewById<EditText>(R.id.lab_multiline)
                IntArray(2).also { edit.getLocationOnScreen(it); it[0] += edit.width / 2; it[1] += edit.height / 2 }
            })
            val service = harness.awaitMain {
                LocalVoiceImeService.activeInstance?.takeIf { (field(it, "keyboardView") as? View)?.isShown == true && it.currentInputEditorInfo?.fieldId == R.id.lab_multiline && it.currentInputConnection != null }
            }
            var keyboard = field(service, "keyboardView") as ImeKeyboardView
            harness.awaitMain { service.handleTestCommand("mode:PINYIN_26"); true }
            val corpus = listOf("nihao" to "你好", "women" to "我们", "zhongguo" to "中国",
                "shurufa" to "输入法", "mingtian" to "明天", "pengyou" to "朋友",
                "jintianxingqiwu" to "今天星期五", "jintiantianqihenhao" to "今天天气很好")
            val rows = org.json.JSONArray()
            val times = mutableListOf<Double>()
            for ((code, expected) in corpus) {
                harness.awaitMain { keyboard = field(service, "keyboardView") as ImeKeyboardView; true }
                harness.awaitMain { keyboard.hardwareCancelComposition(); true }
                for (letter in code) {
                    harness.awaitMain {
                        val start = System.nanoTime()
                        check(keyboard.tapTestTarget(letter.toString()))
                        times += (System.nanoTime() - start) / 1_000_000.0
                        true
                    }
                    SystemClock.sleep(35)
                }
                SystemClock.sleep(400)
                harness.awaitMain {
                    val state = field(service, "state") as ImeState
                    rows.put(JSONObject().put("pinyin", code).put("expected", expected)
                        .put("rank", state.candidates.indexOf(expected) + 1)
                        .put("top3", org.json.JSONArray(state.candidates.take(3))))
                    true
                }
            }
            harness.awaitMain {
                keyboard = field(service, "keyboardView") as ImeKeyboardView
                keyboard.hardwareCancelComposition()
                keyboard.tapTestTarget("n")
                keyboard.tapTestTarget("i")
                true
            }
            SystemClock.sleep(300)
            harness.awaitMain {
                val text = (field(keyboard, "topZone") as ImeTopZone).composition
                val bounds = android.graphics.Rect()
                text.paint.getTextBounds(text.text.toString(), 0, text.text.length, bounds)
                report.put("fontScale", text.resources.configuration.fontScale)
                    .put("preeditHeightPx", text.height)
                    .put("glyphHeightPx", bounds.height())
                    .put("availableHeightPx", text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                    .put("fontHeightPx", text.paint.fontMetrics.descent - text.paint.fontMetrics.ascent)
                    .put("preeditText", text.text.toString())
                    .put("glyphTopPx", text.baseline + bounds.top - text.scrollY)
                    .put("glyphBottomPx", text.baseline + bounds.bottom - text.scrollY)
                    .put("clipTopPx", text.extendedPaddingTop)
                    .put("clipBottomPx", text.height - text.extendedPaddingBottom)
                    .put("glyphFits", text.baseline + bounds.top - text.scrollY >= text.extendedPaddingTop &&
                        text.baseline + bounds.bottom - text.scrollY <= text.height - text.extendedPaddingBottom)
                true
            }
            shot("typing-font")
            report.put("corpus", rows)
            times.sort()
            report.put("clickHandlerP50Ms", times[times.size / 2])
                .put("clickHandlerP95Ms", times[(times.size * .95).toInt()])
                .put("timingScope", "emulator main-thread click handler, excludes display latency")
            File(out, "typing-font.json").writeText(report.toString(2))
            assertTrue("Pre-edit glyphs are outside the drawable area", report.getBoolean("glyphFits"))
        }
    }

    @Test
    fun floatingPopupKeepsWideGlyphsAndCommitsSelectedLetter() {
        rebindIme()
        val savedWidth = ImeSettingsRepository.loadFloatingWidthPercent(context)
        val report = JSONObject()
        try {
            ImeSettingsRepository.saveFloatingWidthPercent(context, 72)
            DirectActivityHarness(ImeTestLabActivity::class.java).use { harness ->
                harness.launch(intent = Intent().putExtra("focus_id", "lab_multiline"))
                harness.awaitMain { if (it.hasWindowFocus()) true else null }
                tap(harness.awaitMain { activity ->
                    val edit = activity.findViewById<EditText>(R.id.lab_multiline)
                    IntArray(2).also { edit.getLocationOnScreen(it); it[0] += edit.width / 2; it[1] += edit.height / 2 }
                })
                val service = harness.awaitMain {
                    LocalVoiceImeService.activeInstance?.takeIf { (field(it, "keyboardView") as? View)?.isShown == true && it.currentInputEditorInfo?.fieldId == R.id.lab_multiline && it.currentInputConnection != null }
                }
                harness.awaitMain {
                    service.handleTestCommand("mode:PINYIN_9")
                    service.onFloatingKeyboardChanged(true)
                    true
                }
                SystemClock.sleep(500)
                val keyboard = field(service, "keyboardView") as ImeKeyboardView
                val point = harness.awaitMain {
                    val key = keyboard.findTestTarget("9") ?: return@awaitMain null
                    IntArray(2).also { key.getLocationOnScreen(it); it[0] += key.width / 2; it[1] += key.height / 2 }
                }
                val down = SystemClock.uptimeMillis()
                fun touch(action: Int, at: IntArray) {
                    val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, at[0].toFloat(), at[1].toFloat(), 0)
                    check(instrumentation.uiAutomation.injectInputEvent(event.apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }, true))
                    event.recycle()
                }
                harness.awaitMain {
                    keyboard.findTestTarget("9")?.takeIf { it.isShown && it.width > 0 }?.let { it.performLongClick(); true }
                }
                SystemClock.sleep(200)
                val cells = harness.awaitMain {
                    val controller = field(keyboard, "keyPopupController")!!
                    @Suppress("UNCHECKED_CAST")
                    (field(controller, "choiceCells") as List<Pair<TextView, String>>).takeIf { it.isNotEmpty() }?.toList()
                }
                var fits = true
                val choices = org.json.JSONArray()
                val target = harness.awaitMain {
                    for ((view, symbol) in cells) {
                        val glyph = android.graphics.Rect()
                        view.paint.getTextBounds(symbol, 0, symbol.length, glyph)
                        val available = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
                        if (glyph.width() > available) fits = false
                        choices.put(JSONObject().put("text", symbol).put("glyphWidthPx", glyph.width()).put("availableWidthPx", available))
                    }
                    val letter = cells.first { it.second == "W" }.first
                    IntArray(2).also { letter.getLocationOnScreen(it); it[0] += letter.width / 2; it[1] += letter.height / 2 }
                }
                shot("floating-popup")
                harness.awaitMain { cells.first { it.second == "W" }.first.performClick(); true }
                harness.awaitMain { activity ->
                    if (activity.findViewById<EditText>(R.id.lab_multiline).text.toString() == "W") true else null
                }
                report.put("choices", choices).put("glyphsFit", fits).put("selectedWCommitted", true)
                harness.awaitMain { service.onFloatingKeyboardChanged(false); true }
                assertTrue("Popup glyphs exceed their drawing space", fits)
            }
        } finally {
            instrumentation.runOnMainSync { LocalVoiceImeService.activeInstance?.onFloatingKeyboardChanged(false) }
            ImeSettingsRepository.saveFloatingWidthPercent(context, savedWidth)
            File(out, "floating-popup.json").writeText(report.toString(2))
        }
    }

    @Test
    fun backupRestoresPreferencesAndLegacyBackupPreservesThem() {
        val saved = settings()
        val expected = JSONObject("""{"landscape_layout":"split","split_mirrored":true,"sms_code_chip":true,"clipboard_chip":false,"pinyin_location":"input_box"}""")
        val changed = JSONObject("""{"landscape_layout":"full","split_mirrored":false,"sms_code_chip":false,"clipboard_chip":true,"pinyin_location":"candidate_bar"}""")
        val report = JSONObject()
        try {
            setSettings(expected)
            DirectActivityHarness(DataManagementActivity::class.java).use { harness ->
                harness.launch()
                val archive = File(context.cacheDir, "optimization-audit.json").apply { delete() }
                harness.awaitMain { activity ->
                    documentResult(activity, 8101, archive)
                    true
                }
                // A cold service explicitly asks before exporting without its Rime user dictionary.
                val until = SystemClock.uptimeMillis() + 30_000
                while (!archive.isFile && SystemClock.uptimeMillis() < until) {
                    find(instrumentation.uiAutomation.rootInActiveWindow, "继续导出")
                        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    SystemClock.sleep(100)
                }
                val root = JSONObject(await { archive.takeIf { it.isFile && it.length() > 0 } }.readText())
                val encoded = root.getJSONObject("settings")
                report.put("exported", JSONObject().apply {
                    expected.keys().forEach { key -> put(key, encoded.opt(key) ?: JSONObject.NULL) }
                })
                // Keep the exported settings, but avoid merging any existing emulator user dictionary.
                root.put("rime_user_dictionaries", org.json.JSONArray())
                archive.writeText(root.toString())
                setSettings(changed)
                harness.awaitMain { activity ->
                    documentResult(activity, 8102, archive)
                    true
                }
                val message = await { find(instrumentation.uiAutomation.rootInActiveWindow, "导入用户数据")?.let {
                    instrumentation.uiAutomation.rootInActiveWindow
                } }
                fun texts(node: AccessibilityNodeInfo): String = buildString {
                    append(node.text ?: "")
                    for (i in 0 until node.childCount) node.getChild(i)?.let { append(texts(it)) }
                }
                report.put("preview", texts(message))
                shot("backup-preview")
                clickDialog("导入")
                await { find(instrumentation.uiAutomation.rootInActiveWindow, "导入用户数据").let { if (it == null) true else null } }
                SystemClock.sleep(1_000)
                val restored = settings()
                report.put("restored", restored)
                // An old archive omits these fields: importing it must keep the current choices.
                expected.keys().forEach { encoded.remove(it) }
                archive.writeText(root.toString())
                harness.awaitMain { activity ->
                    documentResult(activity, 8102, archive)
                    true
                }
                val legacy = await { find(instrumentation.uiAutomation.rootInActiveWindow, "导入用户数据")?.let {
                    instrumentation.uiAutomation.rootInActiveWindow
                } }
                report.put("legacyPreview", texts(legacy))
                shot("legacy-preview")
                clickDialog("导入")
                SystemClock.sleep(1_000)
                report.put("legacyRestored", settings())
                report.put("passed", expected.toString() == restored.toString() && expected.toString() == settings().toString() &&
                    report.getString("preview").contains("设置项将更新") && report.getString("legacyPreview").contains("设置项无变化"))
            }
        } finally {
            setSettings(saved)
            File(out, "backup.json").writeText(report.toString(2))
        }
        assertTrue(report.toString(), report.optBoolean("passed"))
    }

    @Test
    fun failedAutofillReplacementClearsOldAccountAndCanRecover() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 30)
        val report = JSONObject()
        try {
            rebindIme()
            DirectActivityHarness(AutofillTestActivity::class.java).use { harness ->
                harness.launch()
                harness.awaitMain { if (it.hasWindowFocus()) true else null }
                harness.awaitMain { activity ->
                    val edit = activity.window.decorView.findViewWithTag<EditText>("af_username")
                    edit.requestFocus()
                    activity.getSystemService(InputMethodManager::class.java).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
                    true
                }
                tap(harness.awaitMain { activity ->
                    val edit = activity.window.decorView.findViewWithTag<EditText>("af_username")
                    IntArray(2).also { edit.getLocationOnScreen(it); it[0] += edit.width / 2; it[1] += edit.height / 2 }
                })
                val keyboard = harness.awaitMain {
                    val service = LocalVoiceImeService.activeInstance ?: return@awaitMain null
                    val view = field(service, "keyboardView") as? ImeKeyboardView ?: return@awaitMain null
                    val zone = field(view, "topZone") as ImeTopZone
                    val controller = field(view, "inlineAutofillController") ?: return@awaitMain null
                    val tracker = field(controller, "tracker") ?: return@awaitMain null
                    val slots = field(tracker, "slots") as List<*>
                    if (view.isShown && zone.autofillChipsShown && slots.any { it != null }) view else null
                }
                shot("autofill-before")
                val removed = harness.awaitMain {
                    val zone = field(keyboard, "topZone") as ImeTopZone
                    val controller = field(keyboard, "inlineAutofillController")!!
                    @Suppress("UNCHECKED_CAST")
                    val tracker = field(controller, "tracker") as InlineChipTracker<android.widget.inline.InlineContentView>
                    val oldToken = field(tracker, "generation") as Int
                    @Suppress("UNCHECKED_CAST")
                    val oldChip = (field(tracker, "slots") as List<android.widget.inline.InlineContentView?>).first { it != null }!!
                    val token = tracker.begin(2)
                    report.put("replacementGeneration", token)
                    tracker.deliver(token, 1, null)
                    tracker.deliver(token, 0, null)
                    tracker.deliver(oldToken, 0, oldChip)
                    !zone.autofillChipsShown
                }
                shot("autofill-after-failure")
                report.put("oldAccountRemoved", removed)
                harness.awaitMain { activity ->
                    val manager = activity.getSystemService(android.view.autofill.AutofillManager::class.java)
                    manager.cancel()
                    manager.requestAutofill(activity.window.decorView.findViewWithTag<View>("af_username"))
                    true
                }
                harness.awaitMain {
                    val controller = field(keyboard, "inlineAutofillController")!!
                    val tracker = field(controller, "tracker")!!
                    if ((field(keyboard, "topZone") as ImeTopZone).autofillChipsShown &&
                        (field(tracker, "generation") as Int) > report.getInt("replacementGeneration") &&
                        (field(tracker, "slots") as List<*>).any { it != null }) true else null
                }
                shot("autofill-recovered")
                report.put("recovered", true)
                tap(harness.awaitMain {
                    val tracker = field(field(keyboard, "inlineAutofillController")!!, "tracker")!!
                    @Suppress("UNCHECKED_CAST")
                    val chip = (field(tracker, "slots") as List<View?>).first { it != null }!!
                    IntArray(2).also { chip.getLocationOnScreen(it); it[0] += chip.width / 2; it[1] += chip.height / 2 }
                })
                harness.awaitMain { activity ->
                    if (activity.window.decorView.findViewWithTag<EditText>("af_username").text.toString() == "work@openime.dev") true else null
                }
                report.put("accountFilled", true)
                assertTrue("Old account survived an all-null replacement", removed)
            }
        } finally {
            File(out, "autofill.json").writeText(report.toString(2))
        }
    }
}
