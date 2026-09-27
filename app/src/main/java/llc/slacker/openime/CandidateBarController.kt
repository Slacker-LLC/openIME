package llc.slacker.openime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Owns candidate-strip presentation only: diff/reuse, scroll preservation,
 * item binding, click routing, and the expand affordance state.
 *
 * Candidate querying, generation identity, and composition ownership stay in
 * the existing candidate pipeline and host.
 */
internal class CandidateBarController(
    private val context: Context,
    private val row: LinearLayout,
    private val scroll: HorizontalScrollView,
    private val expandButton: TextView,
    private val toPx: (Int) -> Int,
    private val tokens: () -> ImeTheme.Tokens,
    private val statefulBackground: (Int, Int, Int) -> Drawable,
    private val onFeedback: () -> Unit,
    private val onCandidateSelected: (String) -> Unit,
) {
    private var renderedCandidates: List<String>? = null
    private var renderedComposition: String? = null

    fun render(
        candidates: List<String>,
        compositionPreview: String,
        showCompositionWhenEmpty: Boolean,
    ) {
        val visibleCandidates = candidates.take(STRIP_LIMIT)
        if (renderedCandidates == visibleCandidates && renderedComposition == compositionPreview) return

        val keepScroll = renderedComposition == compositionPreview
        val previousScrollX = if (keepScroll) scroll.scrollX else 0
        renderedCandidates = visibleCandidates.toList()
        renderedComposition = compositionPreview

        if (candidates.isEmpty()) {
            if (row.childCount != 1 || row.getChildAt(0) !is TextView ||
                row.getChildAt(0).tag != "candidate-empty"
            ) {
                row.removeAllViews()
                row.addView(
                    TextView(context).apply {
                        tag = "candidate-empty"
                        textSize = 12f
                        setPadding(toPx(10), 0, toPx(10), 0)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            (row.getChildAt(0) as TextView).text =
                if (showCompositionWhenEmpty) compositionPreview else ""
            if (!keepScroll) scroll.scrollTo(0, 0)
            return
        }

        if (row.childCount == 1 && row.getChildAt(0).tag == "candidate-empty") {
            row.removeAllViews()
        }
        val extra = row.childCount - visibleCandidates.size
        if (extra > 0) row.removeViews(visibleCandidates.size, extra)

        visibleCandidates.forEachIndexed { index, candidate ->
            val existing = row.getChildAt(index) as? LinearLayout
            if (existing == null) {
                row.addView(
                    createItem(index, candidate),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        toPx(48),
                    ).apply { marginEnd = toPx(6) },
                )
            } else {
                bindItem(existing, index, candidate)
            }
        }

        if (keepScroll && previousScrollX > 0) {
            scroll.post { scroll.scrollTo(previousScrollX.coerceAtMost(row.width), 0) }
        } else {
            scroll.scrollTo(0, 0)
        }
    }

    fun syncExpandControl(
        expandedOpen: Boolean,
        hasCandidates: Boolean,
    ) {
        val canExpandOrClose = expandedOpen || hasCandidates
        expandButton.isEnabled = canExpandOrClose
        expandButton.alpha = if (canExpandOrClose) 1f else 0.38f
        if (!canExpandOrClose) {
            expandButton.contentDescription = "暂无更多候选"
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                expandButton.stateDescription = "不可用"
            }
        } else if (android.os.Build.VERSION.SDK_INT >= 30) {
            expandButton.stateDescription = if (expandedOpen) "已展开" else "可展开"
        }
    }

    private fun createItem(index: Int, candidate: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = toPx(48)
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true
            addView(
                TextView(context).apply {
                    textSize = 14f
                    maxLines = 1
                    includeFontPadding = false
                    setPadding(toPx(12), 0, toPx(12), 0)
                    isClickable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            bindItem(this, index, candidate)
        }

    private fun bindItem(item: LinearLayout, index: Int, candidate: String) {
        item.tag = if (index == 0) "candidate-first-row" else "candidate-row"
        item.contentDescription = "候选:$candidate"
        val word = item.getChildAt(0) as TextView
        if (word.text.toString() != candidate) word.text = candidate
        word.tag = if (index == 0) "candidate-first" else "candidate-word"
        word.typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        val palette = tokens()
        word.setTextColor(if (index == 0) palette.keyText else palette.candidateText)
        item.background = statefulBackground(
            if (index == 0) palette.keyBackground else Color.TRANSPARENT,
            palette.keyPressedBackground,
            toPx(ImeGeometryTokens.KEY_RADIUS_DP),
        )
        item.setOnClickListener {
            onFeedback()
            onCandidateSelected(candidate)
        }
    }

    private companion object {
        const val STRIP_LIMIT = 24
    }
}
