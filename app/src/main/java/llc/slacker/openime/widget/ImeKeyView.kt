package llc.slacker.openime.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import llc.slacker.openime.R
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeTypographyTokens

/**
 * Compact native key view used by the IME.
 *
 * The visual contract matches the HTML prototype:
 * - main label is centered
 * - secondary hint is pinned to the top-right corner
 * - optional monochrome icon is used for Shift / Caps / Backspace
 */
class ImeKeyView(
    context: Context,
    text: String = "",
    secondary: String? = null,
    iconRes: Int = 0,
    mainTextSize: Float = ImeTypographyTokens.KEY_LETTER_SP,
    fitMainText: Boolean = false,
    private val toPx: (Int) -> Int = { (it * context.resources.displayMetrics.density).toInt() },
) : FrameLayout(context) {
    /**
     * Optional upward-swipe action (the nine-key digit shortcut). When it fires
     * the press is cancelled, so neither the tap nor the long-press popup follows.
     */
    var onSwipeUp: (() -> Unit)? = null
    var swipeUpEnabled: () -> Boolean = { true }
    private val swipeUp = SwipeUpDetector(thresholdPx = toPx(SWIPE_UP_THRESHOLD_DP).toFloat())
    private var swipeConsumed = false
    // A long press already chose this gesture: a later upward move slides over the
    // popup (or nothing) and must not also fire the swipe-up digit.
    private var longPressed = false
    private var cancellingForSwipe = false

    /** True when the swipe logic consumed [event]; the normal touch path then must not see it. */
    private fun interceptSwipeUp(event: MotionEvent): Boolean {
        val action = onSwipeUp ?: return false
        if (cancellingForSwipe) return false
        if (longPressed) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeConsumed = false
                swipeUp.down(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeConsumed) return true
                if (swipeUpEnabled() && swipeUp.move(event.rawX, event.rawY)) {
                    swipeConsumed = true
                    // Cancel the press first: no tap, no long-press popup, pressed look released.
                    val cancel = MotionEvent.obtain(event).apply { this.action = MotionEvent.ACTION_CANCEL }
                    cancellingForSwipe = true
                    try {
                        dispatchTouchEvent(cancel)
                    } finally {
                        cancellingForSwipe = false
                        cancel.recycle()
                    }
                    action()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swipeConsumed) {
                swipeConsumed = false
                return true
            }
        }
        return false
    }


    /**
     * While true the key ignores every touch and is drawn dimmed. The host sets
     * it on the rest of the bottom row during a space-bar cursor drag, so a
     * second finger or a drifting thumb cannot press them by accident.
     */
    var touchLocked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            alpha = if (value) LOCKED_ALPHA else 1f
            if (value) {
                isPressed = false
                touchFeedbackPending = false
                animate().cancel()
                scaleX = 1f
                scaleY = 1f
            }
        }

    private val density = resources.displayMetrics.density
    private var touchFeedbackPending = false
    private var touchGeneration = 0L
    private val baseMainTextSize = mainTextSize
    private var fitMain = fitMainText

    /**
     * True when the label must be shrunk to the width the cap really has — a key
     * whose whole label is one wide glyph (the marks beside the space bar). The
     * platform's autosize cannot do this: it rejects a candidate size only when
     * the text needs more lines than `maxLines` or more height than the view has
     * (`TextView#suggestedSizeFitsInSpace`,
     * `frameworks/base/core/java/android/widget/TextView.java:11549-11577`), so a
     * single line that is simply too wide keeps the largest size and runs out of
     * the key. [fitMainLabelToWidth] shrinks it instead.
     */
    private var shrinkToWidth = false

    /** The size the label asks for before any shrinking, and the floor below it. */
    private var wantedMainTextPx = 0f
    private var smallestMainTextPx = 0f
    private val fitPaint = TextPaint()

    /**
     * The key's own margins in reference units: the cap is painted inside the
     * cell by this much, as the reference's key classes give every key its own
     * margin (`cls_keyboard_button_margin`, `style.xml:289-290`;
     * `cls_keyboard9_button_margin`, `style.xml:373-374`). The theme applier
     * reads them; a board sets its family's values on the keys it builds.
     */
    var marginLeftDp = ImeGeometryTokens.KEY_MARGIN_X_DP
        private set
    var marginTopDp = ImeGeometryTokens.KEY_MARGIN_Y_DP
        private set
    var marginRightDp = ImeGeometryTokens.KEY_MARGIN_X_DP
        private set
    var marginBottomDp = ImeGeometryTokens.KEY_MARGIN_Y_DP
        private set

    /** Sets the margins a board family paints its keys with. */
    fun setReferenceMargins(
        left: Float = marginLeftDp,
        top: Float = marginTopDp,
        right: Float = marginRightDp,
        bottom: Float = marginBottomDp,
    ) {
        marginLeftDp = left
        marginTopDp = top
        marginRightDp = right
        marginBottomDp = bottom
    }

    /**
     * Key labels follow the system font size only up to [MAX_LABEL_FONT_SCALE].
     * A key has a fixed width, so at 200% a plain "m" no longer fits and became
     * "…", and "中/英" lost its last glyph. Everything else (candidates, panels,
     * settings) still follows the system setting in full.
     */
    private fun labelPx(sp: Float): Float =
        sp * density * minOf(resources.configuration.fontScale, MAX_LABEL_FONT_SCALE)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (touchLocked) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) longPressed = false
        if (interceptSwipeUp(event)) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                touchGeneration++
                touchFeedbackPending = isEnabled
                animate().cancel()
                if (isEnabled) {
                    animate()
                        .scaleX(0.94f)
                        .scaleY(0.94f)
                        .setDuration(60L)
                        .setInterpolator(null)
                        .start()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    touchFeedbackPending = false
                }
                animate().cancel()
                animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(90L)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val generation = touchGeneration
            post { if (generation == touchGeneration) touchFeedbackPending = false }
        }
        return handled
    }

    override fun performLongClick(): Boolean {
        val handled = super.performLongClick()
        if (handled) longPressed = true
        return handled
    }

    internal fun consumeTouchFeedback(): Boolean {
        val pending = touchFeedbackPending
        touchFeedbackPending = false
        return pending
    }

    override fun onDetachedFromWindow() {
        touchGeneration++
        touchFeedbackPending = false
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        super.onDetachedFromWindow()
    }
    private val mainTextView: TextView?
    private val secondaryTextView: TextView?
    private val iconView: ImageView?
    private var returnIcon: ImageView? = null

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = if (iconRes != 0) text.ifEmpty { "功能键" } else text

        iconView = if (iconRes != 0) {
            ImageView(context).apply {
                setImageResource(iconRes)
                contentDescription = null
                isDuplicateParentStateEnabled = true
            }
        } else {
            null
        }

        mainTextView = if (text.isNotEmpty()) {
            object : TextView(context) {
                override fun setEnabled(enabled: Boolean) {
                    super.setEnabled(enabled)
                    // Production capability filtering historically disabled only
                    // the label child, while the enclosing ImeKeyView retained its
                    // click listener. Once attached, mirror the disabled state to
                    // the actual interactive node so accessibility/touch cannot
                    // still invoke an unsupported action.
                    if (parent != null) {
                        this@ImeKeyView.isEnabled = enabled
                        this@ImeKeyView.isClickable = enabled
                        this@ImeKeyView.isLongClickable = enabled
                    }
                }
            }.apply {
                this.text = text
                setWantedMainText(mainTextSize)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, wantedMainTextPx)
                gravity = Gravity.CENTER
                isAllCaps = false
                includeFontPadding = false
                maxLines = 1
                if (fitMainText) {
                    // Function labels must remain readable at large system font
                    // scales. Shrink within a controlled range instead of
                    // replacing the action with an ellipsis such as “中/…”.
                    setAutoSizeTextTypeUniformWithConfiguration(
                        smallestMainTextPx.toInt(),
                        wantedMainTextPx.toInt(),
                        1,
                        TypedValue.COMPLEX_UNIT_PX,
                    )
                    ellipsize = null
                } else {
                    ellipsize = TextUtils.TruncateAt.END
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isDuplicateParentStateEnabled = true
            }
        } else {
            null
        }

        secondaryTextView = secondary?.takeIf { it.isNotEmpty() }?.let { sub ->
            TextView(context).apply {
                this.text = sub
                setTextSize(TypedValue.COMPLEX_UNIT_PX, labelPx(ImeTypographyTokens.CAPTION_SP))
                gravity = Gravity.CENTER
                isAllCaps = false
                includeFontPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isDuplicateParentStateEnabled = true
            }
        }

        iconView?.let { view ->
            addView(
                view,
                FrameLayout.LayoutParams(
                    dp(if (text.isEmpty()) { if (iconRes == R.drawable.ic_backspace) 24 else 20 } else 10),
                    dp(if (text.isEmpty()) { if (iconRes == R.drawable.ic_backspace) 24 else 20 } else 10),
                ).apply {
                    gravity = if (text.isEmpty()) {
                        Gravity.CENTER
                    } else {
                        Gravity.TOP or Gravity.END
                    }
                    if (text.isNotEmpty()) {
                        rightMargin = dp(6)
                        topMargin = dp(4)
                    }
                },
            )
        }
        mainTextView?.let { view ->
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    gravity = Gravity.CENTER
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                },
            )
        }
        secondaryTextView?.let { view ->
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(2)
                    rightMargin = dp(4)
                },
            )
        }
        if (text == "↵") setMainText(text)
    }

    fun setColors(mainColor: Int = Color.BLACK, secondaryColor: Int = Color.GRAY, iconColor: Int = mainColor) {
        mainTextView?.setTextColor(mainColor)
        secondaryTextView?.setTextColor(secondaryColor)
        iconView?.imageTintList = ColorStateList.valueOf(iconColor)
        returnIcon?.imageTintList = ColorStateList.valueOf(mainColor)
    }

    /**
     * The key's own face in two states. The reference's action key is the one
     * that changes its label when the finger is down — the white press fill
     * takes a dark label (`action_button_pushedtext`, `values/colors.xml:38`) —
     * so its text and icon swap together with the cap.
     */
    fun setLabelColors(normalColor: Int, pressedColor: Int) {
        val colors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(pressedColor, normalColor),
        )
        mainTextView?.setTextColor(colors)
        iconView?.imageTintList = colors
        returnIcon?.imageTintList = colors
    }

    val currentMainText: String
        get() = mainTextView?.text?.toString().orEmpty()

    fun setMainText(value: String) {
        mainTextView?.text = value
        if (value == "↵" && returnIcon == null) {
            returnIcon = ImageView(context).apply {
                setImageResource(R.drawable.ic_return)
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = ColorStateList.valueOf(mainTextView?.currentTextColor ?: Color.BLACK)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }.also { addView(it, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)) }
        }
        returnIcon?.visibility = if (value == "↵") View.VISIBLE else View.GONE
        mainTextView?.visibility = if (value == "↵") View.INVISIBLE else View.VISIBLE
        contentDescription = value
    }

    /** Update key typography without rebuilding the keyboard hierarchy. */
    fun applyMainTextScale(scale: Float) {
        val sp = baseMainTextSize * scale.coerceAtLeast(0.4f)
        setWantedMainText(sp)
        mainTextView?.apply {
            if (fitMain) {
                // Function labels ("中/英", "完成") must keep shrinking to fit their key.
                setAutoSizeTextTypeUniformWithConfiguration(
                    smallestMainTextPx.toInt(),
                    wantedMainTextPx.toInt(),
                    1,
                    TypedValue.COMPLEX_UNIT_PX,
                )
            } else {
                setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, wantedMainTextPx)
            }
        }
        secondaryTextView?.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            labelPx(ImeTypographyTokens.CAPTION_SP) * (toPx(100) / (100f * density)),
        )
        if (shrinkToWidth) fitMainLabelToWidth()
    }

    /** The size the label asks for at [sp], and the smallest it may be shrunk to. */
    private fun setWantedMainText(sp: Float) {
        wantedMainTextPx = labelPx(sp).coerceAtLeast(2f)
        // A narrow/floating key can ask for less than 10sp. Android requires
        // the auto-size minimum to remain strictly below its pixel maximum.
        smallestMainTextPx = labelPx((sp * 0.68f).coerceAtLeast(10f))
            .coerceIn(1f, wantedMainTextPx - 1f)
    }

    /**
     * Shrinks a single-line label until it fits the cap it is drawn in, never
     * below [smallestMainTextPx]. Idempotent: a pass that finds the label
     * already fitting (or already at the size it computes) changes nothing, so
     * the extra layout a size change asks for settles after one round.
     */
    private fun fitMainLabelToWidth() {
        val view = mainTextView ?: return
        val available = view.width - view.paddingLeft - view.paddingRight
        if (available <= 0 || wantedMainTextPx <= 0f) return
        val label = view.text?.toString().orEmpty()
        if (label.isEmpty()) return
        fitPaint.set(view.paint)
        fitPaint.textSize = wantedMainTextPx
        val needed = fitPaint.measureText(label)
        // A hair under the width: the glyph then sits inside its cap even after
        // the platform's own rounding of a pixel.
        val target = if (needed <= available) {
            wantedMainTextPx
        } else {
            (wantedMainTextPx * (available - 0.5f) / needed).coerceAtLeast(smallestMainTextPx)
        }
        if (view.textSize != target) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (shrinkToWidth) fitMainLabelToWidth()
    }

    /** True when the main label is fully visible (no ellipsis, nothing clipped). Used by tests. */
    internal fun mainLabelFits(): Boolean {
        val view = mainTextView ?: return true
        if (view.visibility != View.VISIBLE || view.width == 0) return true
        val layout = view.layout ?: return false
        val available = view.width - view.paddingLeft - view.paddingRight
        return layout.getEllipsisCount(0) == 0 && layout.getLineWidth(0) <= available + 0.5f
    }

    /**
     * Hide the small corner long-press hint without removing the long-press
     * action itself. Used to keep the 26-key letter surface visually clean.
     */
    fun setSecondaryVisible(visible: Boolean) {
        val view = secondaryTextView ?: return
        val target = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != target) {
            view.visibility = target
            invalidate()
        }
    }

    fun setSecondaryAlpha(alpha: Float) {
        secondaryTextView?.alpha = alpha.coerceIn(0f, 1f)
    }

    fun allowTwoLineLabel() {
        mainTextView?.maxLines = 2
    }

    /**
     * Let a wide single glyph shrink to its key instead of turning into "…".
     *
     * The reference gives the bottom row's punctuation keys their own fonts so
     * the mark always fits (`text_comma_cn` is 72 units for the merged key
     * against `key_letter`'s 57, `style.xml:127`, `:4`); our one shared size
     * would ellipsize the mark in a key that is only 8% of the row. The key
     * keeps its label either way, as it does for the function keys.
     */
    fun allowLabelShrink() {
        shrinkToWidth = true
        mainTextView?.apply {
            ellipsize = null
            setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, wantedMainTextPx)
        }
        requestLayout()
    }

    fun setIcon(value: Int) {
        if (iconView == null) return
        iconView.setImageResource(value)
    }

    private fun dp(value: Int): Int = toPx(value)

    private companion object {
        const val MAX_LABEL_FONT_SCALE = 1.3f
        const val SWIPE_UP_THRESHOLD_DP = 22
        const val LOCKED_ALPHA = 0.38f
    }
}
