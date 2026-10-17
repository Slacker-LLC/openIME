package llc.slacker.openime

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import llc.slacker.openime.core.LandscapeLayout
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.keyboard.ImeKeyboardView
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.widget.ImeKeyView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Real IME window geometry and pointer input, including rotation and floating mode. */
@RunWith(AndroidJUnit4::class)
class KeyboardPresentationE2E {
    @Before fun requireAuditDriver() {
        assumeTrue("Run scripts/feedback_audit_e2e.py with emulator fixtures",
            InstrumentationRegistry.getArguments().containsKey("phase"))
    }
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val out = File(context.getExternalFilesDir(null), "optimization-audit/" +
        InstrumentationRegistry.getArguments().getString("phase", "keyboard-presentation")).apply { mkdirs() }
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun tree(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { tree(v.getChildAt(it)) } else emptyList()
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun touch(at: IntArray) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, at[0].toFloat(), at[1].toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(inst.uiAutomation.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(50)
        }
        SystemClock.sleep(250)
    }
    private fun point(v: View) = IntArray(2).also { v.getLocationOnScreen(it); it[0] += v.width / 2; it[1] += v.height / 2 }
    private fun shot(name: String) {
        inst.uiAutomation.takeScreenshot()?.let { b ->
            File(out, "$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
        }
    }
    private fun currentKeyboard() = field(LocalVoiceImeService.activeInstance!!, "keyboardView") as ImeKeyboardView
    private fun keyboard(h: DirectActivityHarness<ImeTestLabActivity>) = h.awaitMain {
        LocalVoiceImeService.activeInstance?.let { field(it, "keyboardView") as? ImeKeyboardView }?.takeIf { it.isShown }
    }
    private fun focus(h: DirectActivityHarness<ImeTestLabActivity>) {
        touch(h.awaitMain { a ->
            val edit = a.findViewById<EditText>(R.id.lab_multiline)
            edit.requestFocus(); a.getSystemService(InputMethodManager::class.java).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            point(edit)
        })
        keyboard(h)
    }
    private fun withEditor(block: (DirectActivityHarness<ImeTestLabActivity>) -> Unit) {
        val other = shell("ime list -s").lineSequence().first { '/' in it && it.substringBefore('/') != context.packageName }
        shell("ime set $other"); shell("ime set ${context.packageName}/llc.slacker.openime.LocalVoiceImeService")
        DirectActivityHarness(ImeTestLabActivity::class.java).use { h ->
            h.launch(intent = Intent().putExtra("focus_id", "lab_multiline")); focus(h); block(h)
        }
    }

    @Test fun spaceWidthsAndModeKeyFollowEachWindowLayout() {
        val oldLayout = ImeSettingsRepository.loadLandscapeLayout(context)
        val oldWidth = ImeSettingsRepository.loadFloatingWidthPercent(context)
        val oldRotation = shell("settings get system user_rotation").trim()
        val oldAuto = shell("settings get system accelerometer_rotation").trim()
        val rows = JSONArray()
        try {
            ImeSettingsRepository.saveLandscapeLayout(context, LandscapeLayout.FULL)
            ImeSettingsRepository.saveFloatingWidthPercent(context, 72)
            withEditor { h ->
                for (rotation in listOf(0, 1)) {
                    shell("settings put system accelerometer_rotation 0"); shell("settings put system user_rotation $rotation")
                    h.awaitMain { a -> if ((a.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) == (rotation == 1)) true else null }
                    focus(h)
                    val variants = if (rotation == 0) listOf("docked", "floating") else listOf("docked", "floating", "floating-compact")
                    for (variant in variants) {
                        val floating = variant != "docked"
                        h.awaitMain { LocalVoiceImeService.activeInstance!!.onFloatingKeyboardChanged(floating); true }
                        if (variant == "floating-compact") h.awaitMain { currentKeyboard().setFloatingWindowMode(true, compact = true); true }
                        SystemClock.sleep(300)
                        val row = JSONObject().put("rotation", rotation).put("variant", variant)
                        val widths = mutableListOf<Int>()
                        val bottomKeys = JSONObject()
                        for (mode in listOf("PINYIN_9", "PINYIN_26", "ENGLISH_26")) {
                            h.awaitMain { LocalVoiceImeService.activeInstance!!.handleTestCommand("mode:$mode"); true }
                            SystemClock.sleep(300)
                            val key = h.awaitMain { currentKeyboard().findViewWithTag<ImeKeyView>("key-space")?.takeIf { it.isShown && it.width > 0 } }
                            val width = h.awaitMain { key.width }
                            widths += width; row.put(mode, width)
                            bottomKeys.put(mode, h.awaitMain {
                                val k = currentKeyboard()
                                val digits = k.findTestTarget("123")!!
                                val enter = k.findViewWithTag<ImeKeyView>("key-enter")!!
                                val spaceAt = IntArray(2).also(key::getLocationOnScreen)
                                val centerY = spaceAt[1] + key.height / 2
                                val bottomRow = JSONArray()
                                tree(k).filterIsInstance<ImeKeyView>().filter { it.isShown }.map { button ->
                                    button to IntArray(2).also(button::getLocationOnScreen)
                                }.filter { (button, at) -> at[1] <= centerY && at[1] + button.height > centerY }
                                    .sortedBy { it.second[0] }.forEach { (button, at) ->
                                        val cap = (button.background as? android.graphics.drawable.InsetDrawable)?.drawable?.bounds
                                        val labels = JSONArray()
                                        tree(button).filterIsInstance<TextView>().filter { it.isShown }.forEach { label ->
                                            labels.put(JSONObject().put("text", label.text.toString())
                                                .put("color", String.format("#%08X", label.currentTextColor)).put("textSizePx", label.textSize))
                                        }
                                        bottomRow.put(JSONObject().put("label", button.currentMainText).put("tag", button.tag)
                                            .put("x", at[0]).put("y", at[1]).put("width", button.width).put("height", button.height)
                                            .put("cap", cap?.let { JSONArray(listOf(at[0] + it.left, at[1] + it.top, at[0] + it.right, at[1] + it.bottom)) })
                                            .put("labels", labels).put("labelFits", button.mainLabelFits()))
                                    }
                                JSONObject().put("digits", digits.width).put("enter", enter.width)
                                    .put("spaceCentered", abs(key.left + key.width / 2f - (key.parent as View).width / 2f) <= 2f)
                                    .put("row", bottomRow)
                            })
                            if (rotation == 1 && !floating && mode != "PINYIN_9") {
                                val rowWidth = h.awaitMain { (key.parent as View).width }
                                row.put("$mode-landscapePreserved", abs(width - rowWidth * 210f / 446f) <= 2f)
                            }
                            shot("space-$rotation-$variant-$mode")
                        }
                        val spacesMatch = if (rotation == 1 && !floating) {
                            row.getBoolean("PINYIN_26-landscapePreserved") && row.getBoolean("ENGLISH_26-landscapePreserved")
                        } else widths.drop(1).all { abs(it - widths.first()) <= 2 }
                        val nineRow = bottomKeys.getJSONObject("PINYIN_9").getJSONArray("row")
                        val columnChecks = JSONObject()
                        for (mode in listOf("PINYIN_26", "ENGLISH_26")) {
                            val keys = bottomKeys.getJSONObject(mode)
                            val actual = keys.getJSONArray("row")
                            val aligned = if (rotation == 1 && !floating) {
                                actual.length() == 5 && abs(actual.getJSONObject(0).getInt("width") -
                                    actual.getJSONObject(4).getInt("width")) <= 2
                            } else actual.length() == 5 && nineRow.length() == 5 && (0 until 5).all { index ->
                                val target = nineRow.getJSONObject(index)
                                val measured = actual.getJSONObject(index)
                                listOf("x", "y", "width", "height").all { abs(target.getInt(it) - measured.getInt(it)) <= 2 } &&
                                    (0 until 4).all { abs(target.getJSONArray("cap").getInt(it) - measured.getJSONArray("cap").getInt(it)) <= 2 }
                            }
                            columnChecks.put(mode, aligned && keys.getBoolean("spaceCentered") &&
                                (0 until actual.length()).all { actual.getJSONObject(it).getBoolean("labelFits") })
                        }
                        row.put("bottomKeys", bottomKeys).put("columnsMatchNine", columnChecks)
                        val passed = spacesMatch && listOf("PINYIN_26", "ENGLISH_26").all { columnChecks.getBoolean(it) }
                        row.put("passed", passed); rows.put(row)
                        File(out, "space-widths.json").writeText(rows.toString(2))
                    }
                }
                h.awaitMain { LocalVoiceImeService.activeInstance!!.onFloatingKeyboardChanged(false); true }
                shell("settings put system user_rotation 0"); SystemClock.sleep(300); focus(h)
                h.awaitMain { LocalVoiceImeService.activeInstance!!.handleTestCommand("mode:ENGLISH_26"); true }
                val colors = JSONArray()
                for (appearance in listOf(ImeAppearance.LIGHT, ImeAppearance.DARK)) {
                    val row = h.awaitMain {
                        val k = currentKeyboard(); k.setAppearance(appearance)
                        val mode = k.findViewWithTag<ImeKeyView>("key:mode-en")!!
                        val digits = k.findTestTarget("123")!!
                        val modeColor = tree(mode).filterIsInstance<TextView>().first { it.text.toString() == "中/英" }.currentTextColor
                        val functionColor = tree(digits).filterIsInstance<TextView>().first { it.text.toString() == "123" }.currentTextColor
                        JSONObject().put("appearance", appearance.name).put("modeTextColor", modeColor).put("functionTextColor", functionColor).put("passed", modeColor == functionColor)
                    }
                    colors.put(row); shot("english-mode-${appearance.name}")
                }
                File(out, "mode-key-colors.json").writeText(colors.toString(2))
                assertTrue(colors.toString(2), (0 until colors.length()).all { colors.getJSONObject(it).getBoolean("passed") })
                h.awaitMain { a -> a.findViewById<EditText>(R.id.lab_multiline).text.clear(); true }
                touch(h.awaitMain { point(currentKeyboard().findTestTarget("123")!!) })
                h.awaitMain { currentKeyboard().findViewWithTag<View>("digits-layout")?.takeIf { it.isShown } }
                touch(h.awaitMain { point(currentKeyboard().findViewWithTag<ImeKeyView>("key:7")!!) })
                h.awaitMain { a -> a.findViewById<EditText>(R.id.lab_multiline).text.toString().takeIf { it == "7" } }
                touch(h.awaitMain { point(currentKeyboard().findViewWithTag<ImeKeyView>("key:mode")!!) })
                h.awaitMain { currentKeyboard().findViewWithTag<ImeKeyView>("key:mode-en")?.takeIf { it.isShown } }
                touch(h.awaitMain { point(currentKeyboard().findViewWithTag<ImeKeyView>("key-enter")!!) })
                val text = h.awaitMain { a -> a.findViewById<EditText>(R.id.lab_multiline).text.toString().takeIf { it == "7\n" } }
                File(out, "english-bottom-input.json").writeText(JSONObject()
                    .put("text", text).put("digitsSwitchAndReturnPassed", true).toString(2))
                shot("english-bottom-after-input")
            }
            assertTrue(rows.toString(2), (0 until rows.length()).all { rows.getJSONObject(it).getBoolean("passed") })
        } finally {
            ImeSettingsRepository.saveLandscapeLayout(context, oldLayout)
            ImeSettingsRepository.saveFloatingWidthPercent(context, oldWidth)
            shell("settings put system user_rotation $oldRotation"); shell("settings put system accelerometer_rotation $oldAuto")
            inst.runOnMainSync { LocalVoiceImeService.activeInstance?.onFloatingKeyboardChanged(false) }
        }
    }

    @Test fun nineKeyOneChangesBetweenSymbolsAndSegmentation() = withEditor { h ->
        h.awaitMain { LocalVoiceImeService.activeInstance!!.handleTestCommand("mode:PINYIN_9"); LocalVoiceImeService.activeInstance!!.onClearAll(); true }
        SystemClock.sleep(300)
        val k = keyboard(h)
        val rows = JSONArray()
        fun record(name: String, passed: Boolean) { rows.put(JSONObject().put("check", name).put("passed", passed)); File(out, "nine-key-one.json").writeText(rows.toString(2)) }
        val one = h.awaitMain { k.findViewWithTag<ImeKeyView>("key-9:1")?.takeIf { it.width > 0 } }
        record("空闲显示@/#", h.awaitMain { one.currentMainText == "@/#" })
        shot("nine-one-idle"); touch(h.awaitMain { point(one) })
        val slash = h.awaitMain {
            val popup = field(k, "keyPopupController")!!
            @Suppress("UNCHECKED_CAST")
            (field(popup, "choiceCells") as List<Pair<TextView, String>>).firstOrNull { it.second == "/" }?.first?.takeIf { it.isShown }?.let(::point)
        }
        touch(slash)
        h.awaitMain { a -> a.findViewById<EditText>(R.id.lab_multiline).text.toString().takeIf { it == "/" } }
        record("空闲符号真实上屏", true)
        for (digit in "64") touch(h.awaitMain { point(k.findTestTarget(digit.toString())!!) })
        record("拼音输入时显示分词", h.awaitMain { one.currentMainText == "分词" })
        touch(h.awaitMain { point(one) })
        h.awaitMain { k.findViewWithTag<EditText>("pinyin-composition-editor").text.toString().takeIf { it == "ni'" } }
        record("点击分词插入边界", true)
        for (digit in "426") touch(h.awaitMain { point(k.findTestTarget(digit.toString())!!) })
        touch(h.awaitMain { k.findTestTarget("候选:你好")?.takeIf { it.isShown && it.width > 0 }?.let(::point) })
        h.awaitMain { a -> a.findViewById<EditText>(R.id.lab_multiline).text.toString().takeIf { it == "/你好" } }
        h.awaitMain { if (one.currentMainText == "@/#") true else null }
        record("分词后你好上屏且恢复符号", true); shot("nine-one-after-commit")
        assertTrue(rows.toString(2), (0 until rows.length()).all { rows.getJSONObject(it).getBoolean("passed") })
    }
}
