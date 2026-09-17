package llc.slacker.openime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Upgrades the legacy 9-key punctuation stack into a real vertical symbol rail.
 * When one digit code has several valid Pinyin paths, the first cell becomes a
 * real path filter; the remaining cells stay the normal scrollable symbols.
 *
 * The same production hierarchy hook also applies editor-specific numeric
 * decoration and lightweight discoverability fixes shared by production keys.
 */
internal object NineKeySymbolRailDecorator {
    private const val LEGACY_TAG = "nine-punct-stack"
    private const val CONTENT_TAG = "nine-symbol-scroll-content"
    private const val FILTER_TAG = "nine-pinyin-path-filter"
    private const val ALT_PREVIEW_TAG = "pinyin-long-press-preview"
    private const val CELL_HEIGHT_DP = 48
    private const val WATCHER_TAG = 0x1F000081
    private const val GESTURE_HINT_PREFS = "openime_ui_hints"
    private const val GESTURE_HINT_SHOWN = "gesture_hint_v1_shown"

    private val pinyin26LongPressDigits = linkedMapOf(
        "q" to "1",
        "w" to "2",
        "e" to "3",
        "r" to "4",
        "t" to "5",
        "y" to "6",
        "u" to "7",
        "i" to "8",
        "o" to "9",
        "p" to "0",
    )

    fun decorate(root: View, onCommit: (String) -> Unit) {
        installPinyin26LongPressDigits(root, onCommit)
        installEnglish26Space(root, onCommit)
        decorateGestureDescriptions(root)
        maybeShowGestureHint(root)
        decoratePhoneKeypad(root, onCommit)

        val tagged = root.findViewWithTag<View>(LEGACY_TAG) ?: return
        val scroll = when (tagged) {
            is ScrollView -> tagged
            is LinearLayout -> wrapLegacyStack(tagged)
            else -> return
        }
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        val symbols = commonSymbols()
        val filter = content.findViewWithTag<TextView>(FILTER_TAG)
        val offset = if (filter != null) 1 else 0
        val alreadyDecorated = tagged is ScrollView &&
            content.childCount == symbols.size + offset &&
            symbols.indices.all { index ->
                (content.getChildAt(index + offset) as? TextView)?.text?.toString() == symbols[index]
            }

        if (!alreadyDecorated) {
            val inheritedTextColor = (0 until content.childCount)
                .asSequence()
                .mapNotNull { content.getChildAt(it) as? TextView }
                .firstOrNull { it.tag != FILTER_TAG }
                ?.currentTextColor

            content.removeAllViews()
            content.contentDescription = null
            if (filter != null) {
                inheritedTextColor?.let(filter::setTextColor)
                content.addView(filter, cellParams(content.context, withGap = true))
            }
            symbols.forEachIndexed { index, symbol ->
                content.addView(
                    symbolCell(content.context, symbol, inheritedTextColor, onCommit),
                    cellParams(content.context, withGap = index < symbols.lastIndex),
                )
            }
        }

        installFilterWatcher(root)
        refreshPinyinFilters(root)
    }

    /**
     * English uses Space both as the acceptance gesture and as a literal word
     * separator. Route the short tap through the generic character funnel: the
     * service first commits the currently rendered first completion, then writes
     * the literal space. The inherited touch listener still owns long-press
     * voice, so this does not change the voice gesture.
     */
    private fun installEnglish26Space(root: View, onCommit: (String) -> Unit) {
        val hasShift = root.findViewWithTag<View>("key-shift") != null ||
            root.findViewWithTag<View>("key-shift-active") != null ||
            root.findViewWithTag<View>("key-shift-caps") != null
        if (!hasShift || root.findViewWithTag<View>("key-segment") != null) return
        root.findViewWithTag<View>("key-space")?.setOnClickListener { onCommit(" ") }
    }

    /**
     * Pinyin 26 previously committed the first-row digit as soon as Android's
     * long-click callback fired. That made accidental holds irreversible and
     * gave no preview when the ordinary key-popup setting was disabled.
     *
     * Own the touch sequence for q-p instead: normal taps edit the Pinyin
     * preedit, a hold swaps the popup to a 76dp digit preview, and the digit is
     * committed only on ACTION_UP. ACTION_CANCEL never commits anything.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installPinyin26LongPressDigits(root: View, onCommit: (String) -> Unit) {
        if (root.findViewWithTag<View>("key-segment") == null) {
            hideAlternatePreview(root)
            return
        }
        val touchSlop = ViewConfiguration.get(root.context).scaledTouchSlop.toFloat()
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

        pinyin26LongPressDigits.forEach { (letter, digit) ->
            val key = root.findViewWithTag<ImeKeyView>("key:$letter") ?: return@forEach
            var downX = 0f
            var downY = 0f
            var moved = false
            var longPressed = false
            val armLongPress = Runnable {
                if (!moved && key.isPressed) {
                    longPressed = true
                    if (ImeSettingsRepository.loadHaptic(root.context)) {
                        key.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    showAlternatePreview(root, key, digit, tall = true)
                }
            }

            // Accessibility click/long-click actions do not supply the touch
            // sequence below, so keep explicit semantic fallbacks.
            key.setOnClickListener { insertPinyinLetter(root, letter, onCommit) }
            key.isLongClickable = true
            key.setOnLongClickListener {
                showAlternatePreview(root, key, digit, tall = true)
                onCommit(digit)
                root.postDelayed({ hideAlternatePreview(root) }, 320L)
                true
            }
            key.contentDescription = root.context.getString(
                R.string.key_long_press_digit_description,
                letter,
                digit,
            )

            key.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        key.removeCallbacks(armLongPress)
                        downX = event.x
                        downY = event.y
                        moved = false
                        longPressed = false
                        key.isPressed = true
                        playKeyFeedback(key)
                        if (ImeSettingsRepository.loadPopup(root.context)) {
                            showAlternatePreview(root, key, letter, tall = false)
                        } else {
                            hideAlternatePreview(root)
                        }
                        key.postDelayed(armLongPress, longPressTimeout)
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!longPressed &&
                            (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                        ) {
                            moved = true
                            key.removeCallbacks(armLongPress)
                            hideAlternatePreview(root)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        key.removeCallbacks(armLongPress)
                        key.isPressed = false
                        hideAlternatePreview(root)
                        when {
                            longPressed -> onCommit(digit)
                            !moved -> insertPinyinLetter(root, letter, onCommit)
                        }
                        longPressed = false
                        moved = false
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        key.removeCallbacks(armLongPress)
                        key.isPressed = false
                        longPressed = false
                        moved = false
                        hideAlternatePreview(root)
                        true
                    }

                    else -> true
                }
            }
        }
    }

    private fun insertPinyinLetter(root: View, letter: String, onCommit: (String) -> Unit) {
        val editor = root.findViewWithTag<EditText>("pinyin-composition-editor")
        if (editor == null) {
            onCommit(letter)
            return
        }
        (root.findViewWithTag<View>("association-row") as? LinearLayout)?.removeAllViews()
        val startRaw = editor.selectionStart.takeIf { it >= 0 } ?: editor.length()
        val endRaw = editor.selectionEnd.takeIf { it >= 0 } ?: startRaw
        val start = minOf(startRaw, endRaw).coerceIn(0, editor.length())
        val end = maxOf(startRaw, endRaw).coerceIn(start, editor.length())
        editor.text.replace(start, end, letter)
        editor.setSelection((start + letter.length).coerceAtMost(editor.length()))
    }

    private fun playKeyFeedback(key: View) {
        if (ImeSettingsRepository.loadSound(key.context)) {
            key.playSoundEffect(SoundEffectConstants.CLICK)
        }
        if (ImeSettingsRepository.loadHaptic(key.context)) {
            key.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun showAlternatePreview(root: View, anchor: View, text: String, tall: Boolean) {
        val host = root as? FrameLayout ?: return
        hideAlternatePreview(root)
        val context = root.context
        val popupWidth = (anchor.width * 1.1f).toInt().coerceAtLeast(dp(context, 44))
        val popupHeight = dp(context, if (tall) 76 else 48)
        val backgroundColor = resolveThemeColor(
            context,
            android.R.attr.colorBackground,
            if (isNight(context)) Color.rgb(48, 50, 56) else Color.WHITE,
        )
        val textColor = resolveThemeColor(
            context,
            android.R.attr.textColorPrimary,
            if (isNight(context)) Color.WHITE else Color.rgb(32, 33, 36),
        )
        val preview = TextView(context).apply {
            tag = ALT_PREVIEW_TAG
            this.text = text
            textSize = if (tall) 24f else 20f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(textColor)
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            elevation = dp(context, 10).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 11).toFloat()
                setColor(backgroundColor)
                setStroke(dp(context, 1), Color.argb(36, 127, 127, 127))
            }
        }

        val anchorLocation = IntArray(2)
        val rootLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        root.getLocationOnScreen(rootLocation)
        val anchorLeft = anchorLocation[0] - rootLocation[0]
        val anchorTop = anchorLocation[1] - rootLocation[1]
        val maxLeft = (root.width - popupWidth - dp(context, 4)).coerceAtLeast(dp(context, 4))
        val left = (anchorLeft + (anchor.width - popupWidth) / 2).coerceIn(dp(context, 4), maxLeft)
        val top = (anchorTop - popupHeight - dp(context, 8)).coerceAtLeast(dp(context, 4))
        host.addView(
            preview,
            FrameLayout.LayoutParams(popupWidth, popupHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = left
                topMargin = top
            },
        )
    }

    private fun hideAlternatePreview(root: View) {
        val host = root as? ViewGroup ?: return
        host.findViewWithTag<View>(ALT_PREVIEW_TAG)?.let(host::removeView)
    }

    private fun decorateGestureDescriptions(root: View) {
        root.findViewWithTag<View>("key-backspace")?.contentDescription =
            root.context.getString(R.string.backspace_gesture_description)
        root.findViewWithTag<View>("key-space")?.contentDescription =
            root.context.getString(R.string.space_voice_gesture_description)
        root.findViewWithTag<View>("key-segment")?.contentDescription =
            root.context.getString(R.string.segment_gesture_description)
    }

    private fun maybeShowGestureHint(root: View) {
        if (root.findViewWithTag<View>("key-segment") == null) return
        val prefs = root.context.getSharedPreferences(GESTURE_HINT_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(GESTURE_HINT_SHOWN, false)) return
        prefs.edit().putBoolean(GESTURE_HINT_SHOWN, true).apply()
        root.post {
            Toast.makeText(
                root.context,
                root.context.getString(R.string.gesture_hint_first_use),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * TYPE_CLASS_PHONE needs literal 0-9, *, # and +, not the finance rail,
     * space/voice key, decimal point and @ key inherited from DIGITS. Reuse the
     * stable numeric renderer but replace those three bottom/side actions and
     * remove the finance column. The 0-9 grid, backspace and Enter stay intact.
     */
    private fun decoratePhoneKeypad(root: View, onCommit: (String) -> Unit) {
        val service = root.context as? InputMethodService ?: return
        val kind = EditorInfoAdapter.kind(service.currentInputEditorInfo)
        if (kind != EditorInfoAdapter.EditorKind.PHONE) return
        if (root.findViewWithTag<View>("digits-layout") == null) return

        (root.findViewWithTag<View>("digits-symbol-stack")?.parent as? View)?.visibility = View.GONE

        PhoneKeypadPolicy.literalByTag.forEach { (tag, literal) ->
            val key = root.findViewWithTag<ImeKeyView>(tag) ?: return@forEach
            key.setMainText(literal)
            key.contentDescription = literal
            key.isLongClickable = false
            key.setOnLongClickListener(null)
            if (tag == "key-space") {
                // Remove the inherited voice gesture from a phone-only key.
                key.setIcon(0)
                key.setOnTouchListener(null)
            }
            key.setOnClickListener { onCommit(literal) }
        }
    }

    /**
     * The candidate pipeline already decoded this key event before it changed
     * the visible preedit. Read its tiny path cache here instead of decoding the
     * same digits a second time on the UI thread.
     */
    private fun installFilterWatcher(root: View) {
        val editor = root.findViewWithTag<EditText>("pinyin-composition-editor") ?: return
        if (editor.getTag(WATCHER_TAG) != null) return
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                root.post { refreshPinyinFilters(root) }
            }
        }
        editor.addTextChangedListener(watcher)
        editor.setTag(WATCHER_TAG, watcher)
    }

    private fun refreshPinyinFilters(root: View) {
        val scroll = root.findViewWithTag<ScrollView>(LEGACY_TAG) ?: return
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        if (root.findViewWithTag<View>("pinyin9-layout") == null) {
            content.findViewWithTag<View>(FILTER_TAG)?.let(content::removeView)
            return
        }

        val editor = root.findViewWithTag<EditText>("pinyin-composition-editor") ?: return
        val text = editor.text?.toString().orEmpty()
        if (text.isBlank()) {
            setPinyinFilters(root, emptyList(), null) { }
            return
        }

        val lastSpace = text.lastIndexOf(' ')
        val prefix = if (lastSpace >= 0) text.substring(0, lastSpace + 1) else ""
        val suffix = if (lastSpace >= 0) text.substring(lastSpace + 1) else text
        val digits = CandidatePipeline.nineKeyDigitsFor(suffix)
        val code = digits?.let { NineKeyLocalDecoder.nativeCode(prefix, it) }
        if (code.isNullOrEmpty()) {
            setPinyinFilters(root, emptyList(), null) { }
            return
        }

        val choices = NineKeyUiState.pathsFor(code)
        val selected = NineKeyUiState.selectedPathFor(code) ?: text
        setPinyinFilters(
            root = root,
            filters = choices,
            selected = selected,
        ) { chosen ->
            NineKeyUiState.select(code, chosen)
            if (chosen == editor.text?.toString()) return@setPinyinFilters
            editor.setText(chosen)
            editor.setSelection(chosen.length)
        }
    }

    /**
     * Show a selectable Pinyin path only when the current T9 code is genuinely
     * ambiguous. Selecting it is a real input constraint supplied by the caller,
     * not a cosmetic label.
     */
    fun setPinyinFilters(
        root: View,
        filters: List<String>,
        selected: String?,
        onSelect: (String) -> Unit,
    ) {
        val scroll = root.findViewWithTag<ScrollView>(LEGACY_TAG) ?: return
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        val choices = filters
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(8)
            .toList()
        val existing = content.findViewWithTag<TextView>(FILTER_TAG)
        if (choices.size <= 1) {
            if (existing != null) content.removeView(existing)
            return
        }

        val active = selected?.takeIf { it in choices } ?: choices.first()
        val view = existing ?: TextView(content.context).apply {
            tag = FILTER_TAG
            textSize = 12f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minimumHeight = dp(content.context, CELL_HEIGHT_DP)
            val inherited = (0 until content.childCount)
                .asSequence()
                .mapNotNull { content.getChildAt(it) as? TextView }
                .firstOrNull { it.tag != FILTER_TAG }
                ?.currentTextColor
            inherited?.let(::setTextColor)
            applySelectableBackground(this)
            content.addView(this, 0, cellParams(content.context, withGap = true))
        }
        view.text = active.replace(" ", "·") + " ›"
        view.contentDescription = "九键拼音筛选，当前${active.replace(" ", "、")}，点击切换"
        view.setOnClickListener {
            val current = choices.indexOf(active).coerceAtLeast(0)
            onSelect(choices[(current + 1) % choices.size])
        }
        // Symbols can leave this column scrolled down between compositions.
        // A newly available ambiguity filter is more important than preserving
        // that symbol offset, so make the active filter immediately visible.
        if (scroll.scrollY != 0) scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun commonSymbols(): List<String> = ImeData.symbols["常用"]
        .orEmpty()
        .asSequence()
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
        .ifEmpty { listOf("，", "。", "？", "！") }

    private fun wrapLegacyStack(stack: LinearLayout): ScrollView {
        val parent = stack.parent as? ViewGroup ?: return ScrollView(stack.context)
        val index = parent.indexOfChild(stack)
        val slotParams = stack.layoutParams
        val legacyBackground = stack.background

        stack.tag = CONTENT_TAG
        stack.background = null
        stack.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        val scroll = ScrollView(stack.context).apply {
            tag = LEGACY_TAG
            background = legacyBackground
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            contentDescription = "九键常用符号，上下滑动查看更多"
        }

        parent.removeViewAt(index)
        scroll.addView(
            stack,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        parent.addView(scroll, index, slotParams)
        return scroll
    }

    private fun symbolCell(
        context: Context,
        symbol: String,
        inheritedTextColor: Int?,
        onCommit: (String) -> Unit,
    ): TextView = TextView(context).apply {
        text = symbol
        textSize = 17f
        gravity = Gravity.CENTER
        contentDescription = symbol
        isClickable = true
        isFocusable = true
        minimumHeight = dp(context, CELL_HEIGHT_DP)
        inheritedTextColor?.let(::setTextColor)
        applySelectableBackground(this)
        setOnClickListener { onCommit(symbol) }
    }

    private fun applySelectableBackground(view: TextView) {
        val selectable = TypedValue()
        if (
            view.context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                selectable,
                true,
            ) && selectable.resourceId != 0
        ) {
            view.setBackgroundResource(selectable.resourceId)
        }
    }

    private fun resolveThemeColor(context: Context, attr: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return fallback
        if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data
        }
        if (value.resourceId != 0) {
            return runCatching { context.getColor(value.resourceId) }.getOrDefault(fallback)
        }
        return fallback
    }

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun cellParams(context: Context, withGap: Boolean) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(context, CELL_HEIGHT_DP),
    ).apply {
        if (withGap) bottomMargin = dp(context, 1)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
