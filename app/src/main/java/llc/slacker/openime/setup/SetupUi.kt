package llc.slacker.openime.setup

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
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import llc.slacker.openime.R
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeDrawableFactory
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeSpacingTokens
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeTheme
import llc.slacker.openime.theme.ImeTypographyTokens

/** Shared visual primitives for the non-IME setup and editor screens. */
object SetupUi {

    fun appearanceContext(context: Context): Context {
        val appearance = ImeSettingsRepository.loadAppearance(context)
        val configuration = android.content.res.Configuration(context.resources.configuration)
        val night = when (appearance) {
            ImeAppearance.DARK -> android.content.res.Configuration.UI_MODE_NIGHT_YES
            ImeAppearance.LIGHT -> android.content.res.Configuration.UI_MODE_NIGHT_NO
            ImeAppearance.SYSTEM -> configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        }
        configuration.uiMode = (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return context.createConfigurationContext(configuration)
    }


    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** The app accent: interactive controls and their on/current state, nothing else. */
    fun accent(context: Context): Int = context.getColor(R.color.setup_primary)

    internal fun primaryButtonColor(context: Context): Int = accent(context)

    /** The accent as text, e.g. a tinted secondary button's label. */
    internal fun secondaryTextColor(context: Context): Int = context.getColor(R.color.setup_primary_text)

    /**
     * App-page palette in the keyboard's token shape, so the standalone
     * preferences page (rendered by the keyboard's own panel code) paints with
     * the same colours as every other app page instead of the key colours.
     */
    internal fun tokens(context: Context): ImeTheme.Tokens {
        val nightMask =
            context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val base = ImeTheme.IOS.tokens(
            appearance = ImeSettingsRepository.loadAppearance(context),
            systemDark = nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES,
        )
        val page = context.getColor(R.color.setup_page_bg)
        val surface = context.getColor(R.color.setup_surface)
        val body = context.getColor(R.color.setup_body)
        return base.copy(
            primary = accent(context),
            keyboardBackground = page,
            toolbarBackground = page,
            expandedBackground = page,
            panelHeadBackground = page,
            keyBackground = surface,
            toolCardBackground = surface,
            keyText = context.getColor(R.color.setup_title),
            keySecondaryText = body,
            textSecondaryRole = body,
            functionKeyBackground = context.getColor(R.color.setup_muted),
            keyPressedBackground = context.getColor(R.color.setup_muted_pressed),
            border = context.getColor(R.color.setup_hairline),
        )
    }

    fun contrastText(background: Int): Int = ImeDrawableFactory.contrastText(background)

    /** A grouped-list card: one rounded surface, no shadow. */
    fun cardBackground(context: Context) = ImeDrawableFactory.rounded(
        context.getColor(R.color.setup_surface),
        dp(context, ImeGeometryTokens.CARD_RADIUS_DP).toFloat(),
    )

    /**
     * A titled explanation card for the About and Data pages: neutral icon tile,
     * title, body. Callers may append buttons below the text.
     */
    fun infoCard(context: Context, title: String, body: String, iconRes: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(context, ImeSpacingTokens.LG_DP)
            setPadding(pad, pad, pad, pad)
            background = cardBackground(context)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(android.widget.ImageView(context).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(context.getColor(R.color.setup_icon))
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(context, 7), dp(context, 7), dp(context, 7), dp(context, 7))
                    background = rounded(context.getColor(R.color.setup_icon_tile), dp(context, 9).toFloat())
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)).apply { marginEnd = dp(context, 12) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = title
                        textSize = ImeTypographyTokens.ROW_SP
                        setTextColor(context.getColor(R.color.setup_title))
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                    })
                    addView(TextView(context).apply {
                        text = body
                        textSize = ImeTypographyTokens.DETAIL_SP
                        setTextColor(context.getColor(R.color.setup_body))
                        setLineSpacing(0f, 1.3f)
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(context, ImeSpacingTokens.XS_DP) })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        }

    /** Press feedback for a row inside a card; the card itself paints the surface. */
    fun rowBackground(context: Context): StateListDrawable =
        ImeDrawableFactory.statefulRounded(
            Color.TRANSPARENT,
            context.getColor(R.color.setup_muted),
            0,
            accent(context),
            dp(context, 1),
        )

    fun rounded(
        color: Int,
        radiusPx: Float,
        strokeColor: Int? = null,
        strokeWidthPx: Int = 1,
    ) = ImeDrawableFactory.rounded(
        color = color,
        radiusPx = radiusPx,
        strokeColor = strokeColor,
        strokeWidthPx = strokeWidthPx,
    )

    fun dim(color: Int, factor: Float): Int =
        ImeDrawableFactory.dim(color, factor, preserveAlpha = false)

    fun buttonBackground(
        context: Context,
        color: Int,
        radiusDp: Float = ImeGeometryTokens.PILL_RADIUS_DP.toFloat(),
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

    /** Tinted secondary action, sharing the primary button's capsule shape. */
    fun secondaryBackground(context: Context): StateListDrawable {
        val t = tokens(context)
        val surface = context.getColor(R.color.setup_primary_tint)
        val pressed = ImeSurfacePolicy.pressedSurface(surface, t)
        val accent = accent(context)
        val radius = dp(context, ImeGeometryTokens.PILL_RADIUS_DP).toFloat()
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
            elevation = 0f
            stateListAnimator = null
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            minHeight = dp(context, 48)
            minWidth = 0
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
            val accent = accent(context)
            background = secondaryBackground(context)
            setTextColor(secondaryTextColor(context))
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
            elevation = 0f
            stateListAnimator = null
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            minHeight = dp(context, ImeGeometryTokens.PRIMARY_ROW_HEIGHT_DP)
            background = buttonBackground(
                context,
                primaryButtonColor(context),
                ImeGeometryTokens.PILL_RADIUS_DP.toFloat(),
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
                        Color.WHITE,
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
        val radius = dp(context, ImeGeometryTokens.CONTROL_RADIUS_DP).toFloat()
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
            minimumHeight = context.resources.getDimensionPixelSize(R.dimen.setup_top_bar_height)
            setPadding(dp(context, ImeSpacingTokens.LG_DP), 0, dp(context, ImeSpacingTokens.LG_DP), 0)

            addView(
                ImageButton(context).apply {
                    setImageResource(R.drawable.ic_arrow_back)
                    imageTintList = ColorStateList.valueOf(context.getColor(R.color.setup_title))
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
                    textSize = ImeTypographyTokens.PAGE_TITLE_SP
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(context.getColor(R.color.setup_title))
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
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

    fun showDialog(dialog: AlertDialog, context: Context, anchor: android.view.View) {
        if (context is android.inputmethodservice.InputMethodService) {
            dialog.window?.apply {
                setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
                attributes = attributes.apply { token = anchor.windowToken }
                addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            }
        }
        dialog.show()
    }

    fun styleDialog(dialog: AlertDialog, context: Context, destructivePositive: Boolean = false) {
        val palette = tokens(context)
        val accent = secondaryTextColor(context)
        val alertTitleId = context.resources.getIdentifier("alertTitle", "id", "android")
        if (alertTitleId != 0) {
            dialog.findViewById<TextView>(alertTitleId)?.setTextColor(
                palette.keyText,
            )
        }
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(
            palette.keySecondaryText,
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            if (destructivePositive) tokens(context).danger else accent,
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
            if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.text == "取消") palette.keySecondaryText else accent,
        )
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(accent)
        dialog.window?.setBackgroundDrawable(
            ImeDrawableFactory.rounded(
                palette.toolCardBackground,
                dp(context, ImeGeometryTokens.DIALOG_RADIUS_DP).toFloat(),
            ),
        )
    }

}
