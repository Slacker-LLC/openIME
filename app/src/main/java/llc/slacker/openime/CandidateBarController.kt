package llc.slacker.openime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * Owns candidate presentation: strip diff/reuse, scroll preservation,
 * expanded candidate overlay, item binding, and expand affordance state.
 *
 * Candidate querying, generation identity, and composition ownership stay in
 * the existing candidate pipeline and host.
 */
internal class CandidateBarController(
    private val context: Context,
    private val row: LinearLayout,
    private val scroll: HorizontalScrollView,
    private val expandButton: TextView,
    private val overlay: LinearLayout,
    private val keyboardBody: LinearLayout,
    private val toPx: (Int) -> Int,
    private val keyRowHeightPx: () -> Int,
    private val createHeader: () -> LinearLayout,
    private val createExpandedCandidate: (String) -> ImeKeyView,
    private val createEmptyLabel: () -> TextView,
    private val applyTheme: () -> Unit,
    private val tokens: () -> ImeTheme.Tokens,
    private val statefulBackground: (Int, Int, Int) -> Drawable,
    private val onFeedback: () -> Unit,
    private val onCandidateSelected: (String) -> Unit,
    private val onCandidateLongPressed: (String) -> Unit,
) {
    private var renderedCandidates: List<String>? = null
    private var renderedComposition: String? = null
    private var renderedExpandedCandidates: List<String>? = null
    private var renderedExpandedComposition: String? = null
    private var candidateTouchDownX = 0f
    private var candidateTouchDownY = 0f

    init {
        // Keep ordinary horizontal scrolling native. A deliberate downward
        // swipe on the candidate strip is only an alternate affordance for
        // the existing expand button, so it does not create another state
        // owner or candidate-navigation path.
        scroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    candidateTouchDownX = event.x
                    candidateTouchDownY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - candidateTouchDownX
                    val dy = event.y - candidateTouchDownY
                    if (
                        !expandedOpen &&
                        expandButton.isEnabled &&
                        dy >= toPx(CANDIDATE_EXPAND_SWIPE_DP) &&
                        dy > abs(dx) * 1.15f
                    ) {
                        expandButton.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    candidateTouchDownX = 0f
                    candidateTouchDownY = 0f
                }
            }
            false
        }
    }

    var expandedOpen: Boolean = false
        private set

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
                        textSize = ImeTypographyTokens.CANDIDATE_SP
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
                        toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
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

    fun renderExpanded(
        open: Boolean,
        candidates: List<String>,
        compositionPreview: String,
    ) {
        if (!open) {
            expandButton.text = "⌄"
            expandButton.contentDescription = "展开更多候选"
            overlay.animate().cancel()
            keyboardBody.animate().cancel()
            overlay.visibility = View.GONE
            overlay.alpha = 1f
            overlay.translationY = 0f
            keyboardBody.visibility = View.VISIBLE
            keyboardBody.alpha = 0.96f
            keyboardBody.animate()
                .alpha(1f)
                .setDuration(ImeMotionTokens.CANDIDATE_COLLAPSE_MS)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
            expandedOpen = false
            renderedExpandedCandidates = null
            renderedExpandedComposition = null
            syncExpandControl(candidates.isNotEmpty())
            return
        }

        if (
            expandedOpen &&
            overlay.visibility == View.VISIBLE &&
            renderedExpandedCandidates == candidates &&
            renderedExpandedComposition == compositionPreview
        ) {
            return
        }

        val previousScroll = if (renderedExpandedComposition == compositionPreview) {
            (overlay.getChildAt(1) as? ScrollView)?.scrollY ?: 0
        } else {
            0
        }

        renderedExpandedCandidates = candidates.toList()
        renderedExpandedComposition = compositionPreview
        expandedOpen = true
        expandButton.text = "⌃"
        expandButton.contentDescription = "收起候选"
        if (Build.VERSION.SDK_INT >= 30) {
            expandButton.stateDescription = "已展开"
        }

        keyboardBody.visibility = View.GONE
        keyboardBody.alpha = 1f
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.translationY = toPx(8).toFloat()
        overlay.removeAllViews()
        overlay.addView(
            createHeader(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ),
        )

        val scroll = ScrollView(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (candidates.isEmpty()) {
            column.addView(
                createEmptyLabel(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        } else {
            expandedCandidateRows(candidates).forEach { chunk ->
                val rowView = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                chunk.forEach { candidate ->
                    rowView.addView(
                        createExpandedCandidate(candidate),
                        LinearLayout.LayoutParams(
                            0,
                            keyRowHeightPx(),
                            candidateColumnSpan(candidate).toFloat(),
                        ).apply { marginEnd = toPx(5) },
                    )
                }
                val remaining = 4 - chunk.sumOf(::candidateColumnSpan)
                if (remaining > 0) {
                    rowView.addView(
                        View(context),
                        LinearLayout.LayoutParams(0, 1, remaining.toFloat()),
                    )
                }
                column.addView(
                    rowView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        keyRowHeightPx(),
                    ),
                )
            }
        }
        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        overlay.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        applyTheme()
        if (previousScroll > 0) {
            scroll.post { scroll.scrollTo(0, previousScroll) }
        }
        overlay.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ImeMotionTokens.STANDARD_TRANSITION_MS)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    fun resetExpandedState() {
        expandedOpen = false
        renderedExpandedCandidates = null
        renderedExpandedComposition = null
        expandButton.text = "⌄"
        expandButton.contentDescription = "展开更多候选"
    }

    private fun createItem(index: Int, candidate: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
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
        item.setOnLongClickListener {
            onFeedback()
            onCandidateLongPressed(candidate)
            true
        }
    }

    private companion object {
        const val STRIP_LIMIT = 24
        const val CANDIDATE_EXPAND_SWIPE_DP = 36
    }
}
