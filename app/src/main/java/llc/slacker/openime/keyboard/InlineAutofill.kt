package llc.slacker.openime.keyboard

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlineContentView
import android.widget.inline.InlinePresentationSpec
import java.util.concurrent.Executor
import llc.slacker.openime.theme.ImeGeometryTokens

/**
 * Autofill suggestions inside the keyboard (Android 11+).
 *
 * Password managers and the platform autofill service hand the keyboard small
 * remote chips ("work account", "123456") to show in the suggestion strip.
 * The keyboard only hosts the chips: it never receives the underlying values,
 * the system fills the field itself when a chip is tapped, and nothing here
 * reads the field's content or touches the network.
 */
@TargetApi(Build.VERSION_CODES.R)
internal object InlineAutofill {
    const val MAX_SUGGESTIONS = 5

    private const val STYLE_VERSIONS_KEY = "androidx.autofill.inline.ui.version:key"
    private const val STYLE_VERSION_V1 = "androidx.autofill.inline.ui.version:v1"
    private const val STYLE_V1_MARKER = "style_v1"

    /**
     * The request the platform sends providers: up to [MAX_SUGGESTIONS] chips
     * that fit the 48dp toolbar row. The empty v1 style tells providers to draw
     * their default chip, which every provider understands.
     */
    fun request(context: Context): InlineSuggestionsRequest {
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val minSize = Size((MIN_CHIP_WIDTH_DP * density).toInt(), (MIN_CHIP_HEIGHT_DP * density).toInt())
        val maxSize = Size(
            (MAX_CHIP_WIDTH_DP * density).toInt().coerceAtMost(screenWidth).coerceAtLeast(minSize.width),
            ((ImeGeometryTokens.TOUCH_TARGET_DP - 4) * density).toInt().coerceAtLeast(minSize.height),
        )
        val spec = InlinePresentationSpec.Builder(minSize, maxSize)
            .setStyle(defaultStyles())
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(MAX_SUGGESTIONS)
            .build()
    }

    /**
     * Size every chip is rendered at. A chip is a remote surface, which has no
     * size of its own, so it is given a fixed width that fits two accounts on a
     * phone and scrolls beyond that; height leaves a margin in the toolbar row.
     */
    fun chipSize(context: Context): Size {
        val density = context.resources.displayMetrics.density
        val request = request(context).inlinePresentationSpecs.first()
        val width = (CHIP_WIDTH_DP * density).toInt()
            .coerceIn(request.minSize.width, request.maxSize.width)
        val height = ((ImeGeometryTokens.TOUCH_TARGET_DP - 8) * density).toInt()
            .coerceIn(request.minSize.height, request.maxSize.height)
        return Size(width, height)
    }

    /** Version table + one empty v1 style, as the androidx.autofill library writes it. */
    fun defaultStyles(): Bundle = Bundle().apply {
        putStringArrayList(STYLE_VERSIONS_KEY, arrayListOf(STYLE_VERSION_V1))
        putBundle(STYLE_VERSION_V1, Bundle().apply { putBoolean(STYLE_V1_MARKER, true) })
    }

    private const val CHIP_WIDTH_DP = 150
    private const val MIN_CHIP_WIDTH_DP = 52
    private const val MIN_CHIP_HEIGHT_DP = 32
    private const val MAX_CHIP_WIDTH_DP = 280
}

/**
 * Inflates the suggestions of one response and hands the ready chips to
 * [onChips]. Callbacks run on the main thread, which is also where the chips
 * are added to the toolbar.
 */
@TargetApi(Build.VERSION_CODES.R)
internal class InlineAutofillController(
    private val context: Context,
    private val mainExecutor: Executor,
    onChips: (List<InlineContentView>) -> Unit,
) {
    private val tracker = InlineChipTracker(InlineAutofill.MAX_SUGGESTIONS, onChips)

    /** The pixel size chips are inflated at; the strip lays them out at the same size. */
    val chipSize: Size = InlineAutofill.chipSize(context)

    /** Returns false (the system then uses its own dropdown) when there is nothing to host. */
    fun show(suggestions: List<InlineSuggestion>): Boolean {
        if (!tracker.accepts(suggestions.size)) {
            tracker.clear()
            return false
        }
        val token = tracker.begin(suggestions.size)
        suggestions.take(InlineAutofill.MAX_SUGGESTIONS).forEachIndexed { index, suggestion ->
            runCatching {
                suggestion.inflate(
                    context,
                    chipSize,
                    mainExecutor,
                ) { chip -> tracker.deliver(token, index, chip) }
            }.onFailure { tracker.deliver(token, index, null) }
        }
        return true
    }

    fun clear() = tracker.clear()
}
