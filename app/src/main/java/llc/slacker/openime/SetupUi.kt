package llc.slacker.openime

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.math.pow

/** Shared visual primitives for the non-IME setup and editor screens. */
object SetupUi {
    private const val DESTRUCTIVE = "#F4212E"

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun accent(context: Context): Int =
        AccentPalette.parse(ImeSettingsRepository.loadSkinColor(context))

    fun contrastText(background: Int): Int {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92 else {
                ((normalized + 0.055) / 1.055).pow(2.4)
            }
        }
        val luminance = 0.2126 * channel(Color.red(background)) +
            0.7152 * channel(Color.green(background)) +
            0.0722 * channel(Color.blue(background))
        val whiteContrast = 1.05 / (luminance + 0.05)
        val blackContrast = (luminance + 0.05) / 0.05
        return if (whiteContrast >= blackContrast) Color.WHITE else Color.rgb(7, 19, 29)
    }

    fun dim(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )

    fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp * 1f
            if (strokeColor != null) setStroke(1, strokeColor)
        }

    fun buttonBackground(context: Context, color: Int, radiusDp: Float = 18f): StateListDrawable {
        val pressed = dim(color, 0.86f)
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed),
                rounded(pressed, dp(context, radiusDp.toInt()).toFloat()),
            )
            addState(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused),
                rounded(
                    color,
                    dp(context, radiusDp.toInt()).toFloat(),
                    contrastText(color),
                ),
            )
            addState(
                intArrayOf(-android.R.attr.state_enabled),
                rounded(dim(color, 0.55f), dp(context, radiusDp.toInt()).toFloat()),
            )
            addState(
                intArrayOf(),
                rounded(color, dp(context, radiusDp.toInt()).toFloat()),
            )
        }
    }

    fun secondaryButton(context: Context, label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 12f
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
        val radius = dp(context, 18).toFloat()
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                rounded(dim(surface, 0.94f), radius, line),
            )
            addState(
                intArrayOf(android.R.attr.state_focused),
                rounded(surface, radius, accent(context)),
            )
            addState(intArrayOf(), rounded(surface, radius, line))
        }
    }

    fun primaryButton(context: Context, label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            minHeight = dp(context, 52)
            background = buttonBackground(context, accent(context), 16f)
            setTextColor(contrastText(accent(context)))
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    fun inputBackground(context: Context): StateListDrawable {
        val surface = context.getColor(R.color.setup_surface)
        val line = context.getColor(R.color.setup_input_line)
        val accent = accent(context)
        val radius = dp(context, 16).toFloat()
        fun field(fill: Int, stroke: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius
            setStroke(dp(context, 1), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), field(surface, accent))
            addState(intArrayOf(), field(surface, line))
        }
    }

    /** Transparent focus ring for inputs placed on an already styled surface. */
    fun focusRingBackground(context: Context): StateListDrawable {
        val accent = accent(context)
        val radius = dp(context, 14).toFloat()
        fun ring(stroke: Int?): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
            stroke?.let { setStroke(dp(context, 2), it) }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ring(accent))
            addState(intArrayOf(), ring(null))
        }
    }

    fun styleInput(context: Context, input: EditText) {
        input.background = inputBackground(context)
        input.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        input.setTextColor(context.getColor(R.color.setup_title))
        input.setHintTextColor(context.getColor(R.color.setup_muted_text))
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
            if (destructivePositive) Color.parseColor(DESTRUCTIVE) else accent,
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(accent)
        dialog.window?.setBackgroundDrawable(
            rounded(
                context.getColor(R.color.setup_surface),
                dp(context, 24).toFloat(),
            ),
        )
    }

}
