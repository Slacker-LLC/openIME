package llc.slacker.openime

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Shared visual primitives for the non-IME setup and editor screens. */
object SetupUi {

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun accent(context: Context): Int =
        AccentPalette.parse(ImeSettingsRepository.loadSkinColor(context))

    private fun tokens(context: Context): ImeTheme.Tokens {
        val nightMask =
            context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return ImeTheme.IOS.tokens(
            appearance = ImeSettingsRepository.loadAppearance(context),
            systemDark = nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES,
            accentOverride = accent(context),
        )
    }

    fun contrastText(background: Int): Int = ImeDrawableFactory.contrastText(background)

    fun dim(color: Int, factor: Float): Int =
        ImeDrawableFactory.dim(color, factor, preserveAlpha = false)

    fun buttonBackground(
        context: Context,
        color: Int,
        radiusDp: Float = ImeGeometryTokens.CONTROL_RADIUS_DP.toFloat(),
    ): StateListDrawable {
        val pressed =
            if (color == accent(context)) tokens(context).accentPressed else dim(color, 0.86f)
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed),
                ImeDrawableFactory.rounded(
                    pressed,
                    dp(context, radiusDp.toInt()).toFloat(),
                ),
            )
            addState(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused),
                ImeDrawableFactory.rounded(
                    color = color,
                    radiusPx = dp(context, radiusDp.toInt()).toFloat(),
                    strokeColor = contrastText(color),
                    strokeWidthPx = dp(context, 1),
                ),
            )
            addState(
                intArrayOf(-android.R.attr.state_enabled),
                ImeDrawableFactory.rounded(
                    context.getColor(R.color.setup_muted),
                    dp(context, radiusDp.toInt()).toFloat(),
                ),
            )
            addState(
                intArrayOf(),
                ImeDrawableFactory.rounded(
                    color,
                    dp(context, radiusDp.toInt()).toFloat(),
                ),
            )
        }
    }

    /** Secondary rounded-rectangle control used by setup actions. */
    fun secondaryBackground(context: Context): StateListDrawable {
        val surface = context.getColor(R.color.setup_muted)
        val pressed = context.getColor(R.color.setup_muted_pressed)
        val accent = accent(context)
        val radius = dp(context, ImeGeometryTokens.CONTROL_RADIUS_DP).toFloat()
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed),
                ImeDrawableFactory.rounded(pressed, radius),
            )
            addState(
                intArrayOf(android.R.attr.state_focused),
                ImeDrawableFactory.rounded(
                    color = surface,
                    radiusPx = radius,
                    strokeColor = accent,
                    strokeWidthPx = dp(context, 1),
                ),
            )
            addState(
                intArrayOf(-android.R.attr.state_enabled),
                ImeDrawableFactory.rounded(dim(surface, 0.72f), radius),
            )
            addState(intArrayOf(), ImeDrawableFactory.rounded(surface, radius))
        }
    }

    fun secondaryButton(context: Context, label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = ImeTypographyTokens.BODY_SP
            isAllCaps = false
            minHeight = dp(context, 48)
            minWidth = 0
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
            val accent = accent(context)
            background = outlineButtonBackground(context)
            setTextColor(accent)
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    private fun outlineButtonBackground(context: Context): StateListDrawable {
        val surface = context.getColor(R.color.setup_surface)
        val line = context.getColor(R.color.setup_input_line)
        val radius = dp(context, ImeGeometryTokens.CONTROL_RADIUS_DP).toFloat()
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                ImeDrawableFactory.rounded(
                    color = dim(surface, 0.94f),
                    radiusPx = radius,
                    strokeColor = line,
                    strokeWidthPx = dp(context, 1),
                ),
            )
            addState(
                intArrayOf(android.R.attr.state_focused),
                ImeDrawableFactory.rounded(
                    color = surface,
                    radiusPx = radius,
                    strokeColor = accent(context),
                    strokeWidthPx = dp(context, 1),
                ),
            )
            addState(
                intArrayOf(),
                ImeDrawableFactory.rounded(
                    color = surface,
                    radiusPx = radius,
                    strokeColor = line,
                    strokeWidthPx = dp(context, 1),
                ),
            )
        }
    }

    fun primaryButton(context: Context, label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = ImeTypographyTokens.BODY_SP
            isAllCaps = false
            minHeight = dp(context, ImeGeometryTokens.PRIMARY_ROW_HEIGHT_DP)
            background = buttonBackground(
                context,
                accent(context),
                ImeGeometryTokens.CONTROL_RADIUS_DP.toFloat(),
            )
            val accent = accent(context)
            setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(-android.R.attr.state_enabled),
                        intArrayOf(),
                    ),
                    intArrayOf(
                        context.getColor(R.color.setup_muted_text),
                        contrastText(accent),
                    ),
                ),
            )
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    fun inputBackground(context: Context): StateListDrawable {
        val surface = context.getColor(R.color.setup_surface)
        val line = context.getColor(R.color.setup_input_line)
        val accent = accent(context)
        val radius = dp(context, ImeGeometryTokens.CARD_RADIUS_DP).toFloat()
        return ImeDrawableFactory.focusedRounded(
            fillColor = surface,
            radiusPx = radius,
            focusedStrokeColor = accent,
            defaultStrokeColor = line,
            strokeWidthPx = dp(context, 1),
        )
    }

    /** Transparent focus ring for inputs placed on an already styled surface. */
    fun focusRingBackground(context: Context): StateListDrawable {
        val accent = accent(context)
        val radius = dp(context, ImeGeometryTokens.CARD_RADIUS_DP).toFloat()
        return ImeDrawableFactory.focusedRounded(
            fillColor = Color.TRANSPARENT,
            radiusPx = radius,
            focusedStrokeColor = accent,
            defaultStrokeColor = null,
            strokeWidthPx = dp(context, ImeSpacingTokens.XXS_DP),
        )
    }

    fun styleInput(context: Context, input: EditText) {
        input.background = inputBackground(context)
        input.setPadding(
            dp(context, ImeSpacingTokens.LG_DP),
            dp(context, ImeSpacingTokens.SM_DP),
            dp(context, ImeSpacingTokens.LG_DP),
            dp(context, ImeSpacingTokens.SM_DP),
        )
        input.setTextColor(context.getColor(R.color.setup_title))
        input.setHintTextColor(context.getColor(R.color.setup_muted_text))
        styleCursor(context, input)
    }

    /** Keep the platform cursor and selection highlight aligned with the accent. */
    fun styleCursor(context: Context, input: EditText) {
        val accent = accent(context)
        input.highlightColor = Color.argb(
            72,
            Color.red(accent),
            Color.green(accent),
            Color.blue(accent),
        )
        if (Build.VERSION.SDK_INT >= 29) {
            input.textCursorDrawable = ColorDrawable(accent)
        }
    }

    fun activityTopBar(
        context: Context,
        title: String,
        backContentDescription: String = "返回",
        onBack: () -> Unit,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, ImeGeometryTokens.TOP_BAR_HEIGHT_DP)

            addView(
                ImageButton(context).apply {
                    setImageResource(R.drawable.ic_arrow_back)
                    imageTintList = ColorStateList.valueOf(accent(context))
                    scaleType = android.widget.ImageView.ScaleType.CENTER
                    setPadding(
                        dp(context, ImeSpacingTokens.MD_DP),
                        dp(context, ImeSpacingTokens.LG_DP),
                        dp(context, ImeSpacingTokens.MD_DP),
                        dp(context, ImeSpacingTokens.LG_DP),
                    )
                    contentDescription = backContentDescription
                    minimumWidth = dp(context, ImeGeometryTokens.TOUCH_TARGET_DP)
                    minimumHeight = dp(context, ImeGeometryTokens.TOUCH_TARGET_DP)
                    isClickable = true
                    isFocusable = true
                    val selectable = TypedValue()
                    if (
                        context.theme.resolveAttribute(
                            android.R.attr.selectableItemBackgroundBorderless,
                            selectable,
                            true,
                        ) && selectable.resourceId != 0
                    ) {
                        setBackgroundResource(selectable.resourceId)
                    } else {
                        background = null
                    }
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onBack()
                    }
                },
                LinearLayout.LayoutParams(
                    dp(context, ImeGeometryTokens.TOUCH_TARGET_DP),
                    dp(context, ImeGeometryTokens.TOP_BAR_HEIGHT_DP),
                ),
            )
            addView(
                TextView(context).apply {
                    text = title
                    textSize = ImeTypographyTokens.TITLE_SP
                    setTextColor(context.getColor(R.color.setup_title))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    if (Build.VERSION.SDK_INT >= 28) setAccessibilityHeading(true)
                },
                LinearLayout.LayoutParams(
                    0,
                    dp(context, ImeGeometryTokens.TOP_BAR_HEIGHT_DP),
                    1f,
                ),
            )
        }

    fun styleDialog(dialog: AlertDialog, context: Context, destructivePositive: Boolean = false) {
        val accent = accent(context)
        val alertTitleId = context.resources.getIdentifier("alertTitle", "id", "android")
        if (alertTitleId != 0) {
            dialog.findViewById<TextView>(alertTitleId)?.setTextColor(
                context.getColor(R.color.setup_title),
            )
        }
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(
            context.getColor(R.color.setup_body),
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            if (destructivePositive) tokens(context).danger else accent,
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(accent)
        dialog.window?.setBackgroundDrawable(
            ImeDrawableFactory.rounded(
                context.getColor(R.color.setup_surface),
                dp(context, ImeGeometryTokens.DIALOG_RADIUS_DP).toFloat(),
            ),
        )
    }

}
