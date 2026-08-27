package llc.slacker.openime

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Debug-only keyboard renderer used by instrumented and visual tests. */
class DebugKeyboardActivity : Activity(), ImeKeyboardView.Listener {

    private lateinit var input: TextView
    private lateinit var status: TextView
    private lateinit var keyboard: ImeKeyboardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        input = TextView(this).apply {
            textSize = 18f
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        status = TextView(this).apply {
            textSize = 13f
            text = "DebugKeyboardActivity"
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(
                Button(this@DebugKeyboardActivity).apply {
                    text = "清空输入"
                    setOnClickListener {
                        input.text = ""
                        status.text = "已清空"
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }

        keyboard = ImeKeyboardView(this, this)
        val keyboardHost = FrameLayout(this).apply {
            addView(
                keyboard,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            keyboardHost,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
    }

    override fun onModeChanged(mode: KeyboardMode) {
        status.text = "mode=$mode"
    }

    override fun onPanelChanged(panel: Panel) {
        status.text = "panel=$panel"
    }

    override fun onCharacter(char: String) {
        input.append(char)
        status.text = "char=$char"
    }

    override fun onBackspace() {
        val t = input.text.toString()
        if (t.isNotEmpty()) input.text = t.dropLast(1)
        status.text = "backspace"
    }

    override fun onClearAll() {
        input.text = ""
        status.text = "clear-all"
    }

    override fun onSpace() {
        input.append(" ")
        status.text = "space"
    }

    override fun onFloatingKeyboardChanged(floating: Boolean) {
        status.text = "floating=$floating"
    }

    override fun onFloatingKeyboardDragged(deltaX: Float, deltaY: Float) {
        status.text = "floating-drag=${deltaX.toInt()},${deltaY.toInt()}"
    }

    override fun onVoiceToggle() {
        keyboard.toggleVoiceFromSpace()
        status.text = "voice-toggle"
    }

    override fun onEnter() {
        input.append("\n")
        status.text = "enter"
    }

    override fun onCompositionChanged(composition: String, candidates: List<String>) {
        status.text = "composition=$composition candidates=${candidates.joinToString("/")}"
    }

    override fun onCandidateSelected(candidate: String) {
        input.append(candidate)
        status.text = "candidate=$candidate"
    }

    override fun onCompositionBackspace() = onBackspace()

    override fun onThemeChanged(theme: ImeTheme) {
        status.text = "theme=$theme"
    }

    override fun onAppearanceChanged(appearance: ImeAppearance) {
        status.text = "appearance=$appearance"
    }

    override fun onShiftStateChanged(state: ShiftState) {
        status.text = "shift=$state"
    }

    override fun onCandidateExpanded(open: Boolean) {
        status.text = "candidateExpanded=$open"
    }

    override fun onSymbolSelected(symbol: String) {
        input.append(symbol)
        status.text = "symbol=$symbol"
    }

    override fun onEmojiSelected(emoji: String) {
        input.append(emoji)
        status.text = "emoji=$emoji"
    }

    override fun onTextEdit(action: String) {
        status.text = "textEdit=$action"
    }

    override fun onSoundChanged(enabled: Boolean) = Unit

    override fun onHapticChanged(enabled: Boolean) = Unit

    override fun onPopupChanged(enabled: Boolean) = Unit

    override fun onFuzzyChanged(enabled: Boolean) = Unit
}
