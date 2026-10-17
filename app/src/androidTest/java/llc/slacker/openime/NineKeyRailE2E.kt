package llc.slacker.openime

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.candidate.CandidatePipeline
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

/** Real IME, physical touch coordinates, and an actual editor; no synthetic performClick on readings. */
@RunWith(AndroidJUnit4::class)
class NineKeyRailE2E {
    @Before fun requireAuditDriver() {
        assumeTrue("Run scripts/feedback_audit_e2e.py with emulator fixtures",
            InstrumentationRegistry.getArguments().containsKey("phase"))
    }
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val out = File(context.getExternalFilesDir(null), "optimization-audit/" +
        InstrumentationRegistry.getArguments().getString("phase", "nine-rail")).apply { mkdirs() }
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun tree(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun touch(x: Int, y: Int) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(inst.uiAutomation.injectInputEvent(event, true)); event.recycle()
            SystemClock.sleep(60)
        }
        SystemClock.sleep(350)
    }
    private fun shot(name: String) {
        inst.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(out, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun currentBottomVisibleReadingAcceptsTouch() {
        val selected = "${context.packageName}/llc.slacker.openime.LocalVoiceImeService"
        val other = shell("ime list -s").lineSequence().first { '/' in it && it.substringBefore('/') != context.packageName }
        shell("ime set $other"); shell("ime set $selected")
        val rows = JSONArray()
        DirectActivityHarness(ImeTestLabActivity::class.java).use { h ->
            h.launch(intent = Intent().putExtra("focus_id", "lab_multiline"))
            h.awaitMain { activity ->
                val edit = activity.findViewById<EditText>(R.id.lab_multiline)
                edit.requestFocus(); activity.getSystemService(InputMethodManager::class.java).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT); true
            }
            val service = h.awaitMain {
                LocalVoiceImeService.activeInstance?.takeIf { (field(it, "keyboardView") as? View)?.isShown == true && it.currentInputEditorInfo?.fieldId == R.id.lab_multiline }
            }
            h.awaitMain { service.handleTestCommand("mode:PINYIN_9"); true }
            SystemClock.sleep(300)
            val keyboard = field(service, "keyboardView") as ImeKeyboardView
            fun enterDigits(digits: String) {
                for (digit in digits) {
                    val point = h.awaitMain {
                        keyboard.findTestTarget(digit.toString())?.takeIf { it.isShown && it.width > 0 }?.let { key ->
                            IntArray(2).also { key.getLocationOnScreen(it); it[0] += key.width / 2; it[1] += key.height / 2 }
                        }
                    }
                    touch(point[0], point[1])
                }
            }
            for (digits in InstrumentationRegistry.getArguments().getString("digits", "7426936,7426,64,426,64426,6,26")!!.split(",")) {
                h.awaitMain { service.onClearAll(); true }
                SystemClock.sleep(200)
                enterDigits(digits)
                SystemClock.sleep(400)
                val report = JSONObject().put("digits", digits)
                h.awaitMain {
                    val rail = keyboard.findViewWithTag<ScrollView>("nine-punct-stack")
                    val content = rail?.getChildAt(0) as? ViewGroup ?: return@awaitMain null
                    if (content.tag != "nine-pinyin-panel") return@awaitMain null
                    val readings = (0 until content.childCount).mapNotNull { (content.getChildAt(it) as? TextView)?.text?.toString() }
                    report.put("initialReadings", JSONArray(readings))
                    report.put("longestReadingsFirst", readings.zipWithNext().all { (a, b) -> a.length >= b.length })
                    true
                }
                if (digits == "64426") {
                    val initial = report.getJSONArray("initialReadings").let { a -> (0 until a.length()).map { a.getString(it) } }
                    val complete = initial.containsAll(listOf("ni", "mi", "m", "n"))
                    report.put("completeSyllablesAndInitials", complete)
                    if (!complete) {
                        report.put("passed", false); rows.put(report)
                        File(out, "bottom-visible-readings.json").writeText(rows.toString(2))
                        shot("$digits-missing-initials"); continue
                    }
                    val initials = JSONArray()
                    for (initial in listOf("m", "n")) {
                        val at = h.awaitMain {
                            tree(keyboard).filterIsInstance<TextView>().firstOrNull { it.text.toString() == initial && it.tag.toString().startsWith("nine-pinyin-path-") }?.let { cell ->
                                cell.requestRectangleOnScreen(Rect(0, 0, cell.width, cell.height), true)
                                IntArray(2).also { cell.getLocationOnScreen(it); it[0] += cell.width / 2; it[1] += cell.height / 2 }
                            }
                        }
                        touch(at[0], at[1])
                        val fixed = h.awaitMain { keyboard.nineKeyFixedPrefix().takeIf { it == "$initial'" } }
                        val remaining = h.awaitMain { (field(keyboard, "lastNineDigits") as String).takeIf { it == "4426" } }
                        initials.put(JSONObject().put("initial", initial).put("fixedPrefix", fixed).put("remainingDigits", remaining).put("passed", true))
                        shot("$digits-initial-$initial")
                        h.awaitMain { service.onClearAll(); true }; SystemClock.sleep(200); enterDigits(digits)
                        SystemClock.sleep(300)
                    }
                    report.put("initialSelections", initials)
                }
                if (digits == "7426936" || digits == "64426") {
                    val first = h.awaitMain {
                        tree(keyboard).filterIsInstance<TextView>().firstOrNull { it.tag == "nine-pinyin-path-selected" }?.let { cell ->
                            report.put("firstChosen", cell.text.toString())
                            IntArray(2).also { cell.getLocationOnScreen(it); it[0] += cell.width / 2; it[1] += cell.height / 2 }
                        }
                    }
                    touch(first[0], first[1])
                    SystemClock.sleep(400)
                    shot("$digits-after-first")
                }
                var chosen = ""
                var prefixAtTap = ""
                var railMatchesOpenDigits = false
                var fullReadingsFirst = false
                val point = h.awaitMain {
                    val rail = tree(keyboard).filterIsInstance<ScrollView>().firstOrNull { it.tag == "nine-punct-stack" } ?: return@awaitMain null
                    val content = rail.getChildAt(0) as? ViewGroup ?: return@awaitMain null
                    if (content.tag != "nine-pinyin-panel") return@awaitMain null
                    fun bounds(view: View): Rect = IntArray(2).let { at ->
                        view.getLocationOnScreen(at); Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
                    }
                    val viewport = bounds(rail)
                    val visible = (0 until content.childCount).mapNotNull { content.getChildAt(it) as? TextView }
                        .mapNotNull { cell -> bounds(cell).let { rect -> if (rect.intersect(viewport) && rect.height() > 3) cell to rect else null } }
                    if (visible.isEmpty()) return@awaitMain null
                    val desired = when (digits) { "64426", "426" -> "hao"; "64" -> "ni"; else -> null }
                    val (cell, rect) = if (desired != null) visible.firstOrNull { it.first.text.toString() == desired }
                        ?: return@awaitMain null else visible.last()
                    chosen = cell.text.toString()
                    prefixAtTap = keyboard.nineKeyFixedPrefix()
                    val actualDigits = field(keyboard, "lastNineDigits") as String
                    railMatchesOpenDigits = (0 until content.childCount).mapNotNull { content.getChildAt(it) as? TextView }
                        .all { reading -> CandidatePipeline.nineKeyDigitsFor(reading.text.toString())?.let(actualDigits::startsWith) == true }
                    val allReadings = (0 until content.childCount).mapNotNull { content.getChildAt(it) as? TextView }.map { it.text.toString() }
                    var seenShort = false
                    fullReadingsFirst = allReadings.all { reading ->
                        if (CandidatePipeline.nineKeyDigitsFor(reading) == actualDigits) !seenShort else { seenShort = true; true }
                    }
                    report.put("allReadings", JSONArray(allReadings)).put("fullReadingsFirst", fullReadingsFirst)
                    if (digits == "7426") {
                        fullReadingsFirst = fullReadingsFirst && allReadings.containsAll(listOf("qian", "qiao", "shan", "shao", "qi", "si"))
                    }
                    report.put("railMatchesOpenDigits", railMatchesOpenDigits)
                    report.put("before", keyboard.findViewWithTag<EditText>("pinyin-composition-editor").text.toString())
                        .put("actualDigitBuffer", field(keyboard, "lastNineDigits")).put("fixedPrefix", keyboard.nineKeyFixedPrefix()).put("chosen", chosen).put("railHeight", rail.height).put("paddingTop", rail.paddingTop).put("paddingBottom", rail.paddingBottom)
                        .put("visible", JSONArray().also { a -> visible.forEach { (v, r) -> a.put(JSONObject().put("text", v.text).put("rect", r.toShortString()).put("height", v.height)) } })
                    intArrayOf(rect.centerX(), rect.centerY())
                }
                shot("$digits-before")
                touch(point[0], point[1])
                SystemClock.sleep(500)
                val actual = h.awaitMain { keyboard.findViewWithTag<EditText>("pinyin-composition-editor").text.toString() }
                val passed = report.getBoolean("longestReadingsFirst") && railMatchesOpenDigits && fullReadingsFirst && actual.removePrefix(prefixAtTap).replace("'", "").replace(" ", "").startsWith(chosen.replace("'", ""))
                report.put("after", actual).put("passed", passed)
                if ((digits == "7426936" || digits == "64426") && passed) {
                    var committed = ""
                    val candidatePoint = h.awaitMain {
                        val target = if (digits == "64426") keyboard.findTestTarget("候选:你好") else keyboard.findViewWithTag<View>("candidate-first-row")
                        target?.takeIf { it.isShown && it.width > 0 }?.let { cell ->
                            committed = cell.contentDescription.toString().removePrefix("候选:")
                            IntArray(2).also { cell.getLocationOnScreen(it); it[0] += cell.width / 2; it[1] += cell.height / 2 }
                        }
                    }
                    touch(candidatePoint[0], candidatePoint[1])
                    h.awaitMain { activity ->
                        activity.findViewById<EditText>(R.id.lab_multiline).text.toString().takeIf { it.endsWith(committed) }
                    }
                    report.put("candidateCommitted", true).put("committedCandidate", committed)
                    shot("$digits-committed")
                }
                rows.put(report); File(out, "bottom-visible-readings.json").writeText(rows.toString(2))
                shot("$digits-after")
            }
            h.awaitMain { service.onClearAll(); true }
        }
        assertTrue(rows.toString(2), (0 until rows.length()).all { rows.getJSONObject(it).getBoolean("passed") })
    }
}
