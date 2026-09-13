package llc.slacker.openime

import android.content.res.ColorStateList
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.View
import android.view.MotionEvent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

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
    mainTextSize: Float = 17f,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private var touchFeedbackPending = false
    private var touchGeneration = 0L

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchGeneration++
            touchFeedbackPending = isEnabled
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touchFeedbackPending = false
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val generation = touchGeneration
            post { if (generation == touchGeneration) touchFeedbackPending = false }
        }
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
        super.onDetachedFromWindow()
    }
    private val mainTextView: TextView?
    private val secondaryTextView: TextView?
    private val iconView: ImageView?

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
                textSize = mainTextSize
                gravity = Gravity.CENTER
                isAllCaps = false
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isDuplicateParentStateEnabled = true
            }
        } else {
            null
        }

        secondaryTextView = secondary?.takeIf { it.isNotEmpty() }?.let { sub ->
            TextView(context).apply {
                this.text = sub
                textSize = 9f
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
                    dp(if (text.isEmpty()) 18 else 13),
                    dp(if (text.isEmpty()) 18 else 13),
                ).apply {
                    gravity = if (text.isEmpty()) {
                        Gravity.CENTER
                    } else {
                        Gravity.BOTTOM or Gravity.END
                    }
                    if (text.isNotEmpty()) {
                        rightMargin = dp(6)
                        bottomMargin = dp(4)
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
    }

    fun setColors(mainColor: Int = Color.BLACK, secondaryColor: Int = Color.GRAY, iconColor: Int = mainColor) {
        mainTextView?.setTextColor(mainColor)
        secondaryTextView?.setTextColor(secondaryColor)
        iconView?.imageTintList = ColorStateList.valueOf(iconColor)
    }

    val currentMainText: String
        get() = mainTextView?.text?.toString().orEmpty()

    fun setMainText(value: String) {
        if (iconView != null) return
        mainTextView?.text = value
        contentDescription = value
    }

    fun allowTwoLineLabel() {
        mainTextView?.maxLines = 2
    }

    fun setIcon(value: Int) {
        if (iconView == null) return
        iconView.setImageResource(value)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
