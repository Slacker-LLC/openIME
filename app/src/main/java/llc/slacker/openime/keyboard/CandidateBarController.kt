package llc.slacker.openime.keyboard

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import llc.slacker.openime.R
import llc.slacker.openime.setup.SetupUi
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeMotionTokens
import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.theme.ImeTheme
import llc.slacker.openime.theme.ImeTypographyTokens
import llc.slacker.openime.widget.ImeKeyView
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
    private val expandButton: ImageView,
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
    private val onExpandedBackspace: () -> Unit,
) {
    private var renderedCandidates: List<String>? = null
    private var renderedComposition: String? = null
    private var renderedExpandedCandidates: List<String>? = null
    private var renderedExpandedComposition: String? = null
    private var renderedExpandedFilter: ExpandedCandidateFilter? = null
    private var expandedFilter = ExpandedCandidateFilter.ALL
    private var candidateTouchDownX = 0f
    private var candidateTouchDownY = 0f
    private var candidateTouchActive = false
    private var scrollingActiveUntilMs = 0L

    init {
        // Keep ordinary horizontal scrolling native. A deliberate downward
        // swipe on the candidate strip is only an alternate affordance for
        // the existing expand button, so it does not create another state
        // owner or candidate-navigation path.
        scroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    candidateTouchActive = true
                    candidateTouchDownX = event.x
                    candidateTouchDownY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    candidateTouchActive = false
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
                    candidateTouchActive = false
                    candidateTouchDownX = 0f
                    candidateTouchDownY = 0f
                }
            }
            false
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            scrollingActiveUntilMs =
                SystemClock.uptimeMillis() + SCROLL_SETTLE_WINDOW_MS
        }
    }

    fun isInteractionActive(): Boolean =
        candidateTouchActive ||
            SystemClock.uptimeMillis() < scrollingActiveUntilMs

    var expandedOpen: Boolean = false
        private set

    fun render(
        candidates: List<String>,
        compositionPreview: String,
        showCompositionWhenEmpty: Boolean,
    ) {
        syncExpandControl(candidates.isNotEmpty())
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
                        toPx(41),
                    ).apply { marginEnd = toPx(4) },
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
        expandButton.alpha =
            if (canExpandOrClose) 1f else ImeSurfacePolicy.DISABLED_ALPHA
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
            expandButton.rotation = 0f
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
            renderedExpandedFilter = null
            expandedFilter = ExpandedCandidateFilter.ALL
            syncExpandControl(candidates.isNotEmpty())
            return
        }

        if (
            expandedOpen &&
            overlay.visibility == View.VISIBLE &&
            renderedExpandedCandidates == candidates &&
            renderedExpandedComposition == compositionPreview &&
            renderedExpandedFilter == expandedFilter
        ) {
            return
        }

        val previousScroll = if (renderedExpandedComposition == compositionPreview) {
            overlay.findViewWithTag<ScrollView>(EXPANDED_SCROLL_TAG)?.scrollY ?: 0
        } else {
            0
        }

        renderedExpandedCandidates = candidates.toList()
        renderedExpandedComposition = compositionPreview
        renderedExpandedFilter = expandedFilter
        expandedOpen = true
        expandButton.rotation = 180f
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
        val scroll = expandedCandidateList(expandedFilter.apply(candidates))
        val card = expandedCard(scroll)
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                createFilterRow(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(FILTER_ROW_HEIGHT_UNITS),
                ),
            )
            addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        overlay.addView(
            page,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
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
        renderedExpandedFilter = null
        expandedFilter = ExpandedCandidateFilter.ALL
        expandButton.rotation = 0f
        expandButton.contentDescription = "展开更多候选"
    }

    /**
     * 全部 / 单字, right-aligned above the list (`input_more_cand.xml:7-14`).
     * The chips repaint through their tags, so only their selected state is
     * set here.
     */
    private fun createFilterRow(): LinearLayout =
        LinearLayout(context).apply {
            tag = EXPANDED_FILTER_ROW_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            val container = LinearLayout(context).apply {
                tag = EXPANDED_FILTER_CONTAINER_TAG
                orientation = LinearLayout.HORIZONTAL
                val inset = toPx(FILTER_CONTAINER_INSET_UNITS)
                setPadding(inset, inset, inset, inset)
            }
            ExpandedCandidateFilter.entries.forEach { filter ->
                container.addView(
                    createFilterChip(filter),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                )
            }
            addView(
                container,
                LinearLayout.LayoutParams(
                    toPx(FILTER_CONTAINER_WIDTH_UNITS),
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

    private fun createFilterChip(filter: ExpandedCandidateFilter): TextView =
        TextView(context).apply {
            tag = EXPANDED_FILTER_CHIP_TAG
            text = filter.label
            textSize = ImeTypographyTokens.BODY_SP
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            isSelected = filter == expandedFilter
            contentDescription = "筛选${filter.label}"
            if (Build.VERSION.SDK_INT >= 30) {
                stateDescription = if (isSelected) "已选择" else "未选择"
            }
            setOnClickListener {
                if (expandedFilter == filter) return@setOnClickListener
                onFeedback()
                expandedFilter = filter
                // The whole page is rebuilt, so the chips are handed their new
                // selected state with it; the cache key includes the filter.
                renderExpanded(
                    open = true,
                    candidates = renderedExpandedCandidates.orEmpty(),
                    compositionPreview = renderedExpandedComposition.orEmpty(),
                )
            }
        }

    private fun expandedCandidateList(shown: List<String>): ScrollView {
        val scroll = ScrollView(context).apply { tag = EXPANDED_SCROLL_TAG }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (shown.isEmpty()) {
            column.addView(
                createEmptyLabel(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        } else {
            expandedCandidateRows(shown).forEach { chunk ->
                val rowView = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                chunk.forEach { candidate ->
                    rowView.addView(
                        createExpandedCandidate(candidate).apply {
                            tag = if (candidate == shown.first()) "candidate-grid-first" else "candidate-grid"
                        },
                        LinearLayout.LayoutParams(
                            0,
                            keyRowHeightPx(),
                            candidateColumnSpan(candidate).toFloat(),
                        ),
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
        return scroll
    }

    /**
     * The rounded list the candidates sit in, with the reference's own
     * backspace key floating in the bottom-right corner of that list
     * (`input_more_cand.xml:18-26`).
     */
    private fun expandedCard(list: ScrollView): FrameLayout {
        val card = FrameLayout(context).apply {
            tag = EXPANDED_CARD_TAG
            clipToOutline = true
            addView(
                list,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                ImageView(context).apply {
                    tag = EXPANDED_BACKSPACE_TAG
                    setImageResource(R.drawable.ic_backspace)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    contentDescription = "退格"
                    isClickable = true
                    isFocusable = true
                    setPadding(toPx(6), toPx(6), toPx(6), toPx(6))
                    setOnClickListener {
                        onFeedback()
                        onExpandedBackspace()
                    }
                },
                FrameLayout.LayoutParams(0, 0, Gravity.BOTTOM or Gravity.END),
            )
        }
        // Fractions of the list, not fixed dp: the key keeps its share of the
        // panel in both orientations and at every keyboard height.
        card.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            sizeCornerBackspace(view, right - left, bottom - top)
        }
        return card
    }

    private fun sizeCornerBackspace(card: View, width: Int, height: Int) {
        val key = card.findViewWithTag<View>(EXPANDED_BACKSPACE_TAG) ?: return
        val keyParams = key.layoutParams as? FrameLayout.LayoutParams ?: return
        val targetWidth = (width * BACKSPACE_WIDTH_FRACTION).toInt()
        val targetHeight = (height * BACKSPACE_HEIGHT_FRACTION).toInt()
        if (keyParams.width == targetWidth && keyParams.height == targetHeight) return
        keyParams.width = targetWidth
        keyParams.height = targetHeight
        key.layoutParams = keyParams
    }

    private fun createItem(index: Int, candidate: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = toPx(41)
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true
            addView(
                TextView(context).apply {
                    textSize = ImeTypographyTokens.CANDIDATE_SP
                    maxLines = 1
                    includeFontPadding = false
                    // The reference item pads 2.7% of the strip per side and
                    // adds 4 units between items; 8dp keeps that rhythm.
                    setPadding(toPx(8), 0, toPx(8), 0)
                    isClickable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                // WRAP_CONTENT, not MATCH_PARENT: items are reused across
                // queries, and a TextView with a non-wrap width keeps its old
                // width on setText, clipping a wider new candidate.
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
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
        // The first candidate is the same size in the accent colour one weight
        // heavier, with no tile behind it: the reference's own first-item class
        // (skin-full/assets/skin/default/style.xml:551-554) names
        // first_cand_font (weight 500 against 400, style.xml:83,98) and
        // first_cand_text_color (rgba(79,132,255,1), values/colors.xml:4).
        word.typeface = if (index == 0) MEDIUM_WEIGHT else Typeface.DEFAULT

        val palette = tokens()
        val pressedSurface =
            if (index == 0) {
                ImeSurfacePolicy.selectedSurface(palette)
            } else {
                palette.candidateBackground
            }
        word.setTextColor(
            if (index == 0) {
                ImeSurfacePolicy.selectedText(palette)
            } else {
                palette.candidateText
            },
        )
        item.background = statefulBackground(
            Color.TRANSPARENT,
            ImeSurfacePolicy.pressedSurface(pressedSurface, palette),
            toPx(ImeGeometryTokens.KEY_RADIUS_DP),
        )
        item.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> candidateTouchActive = true
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> candidateTouchActive = false
            }
            false
        }
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

    fun confirmCandidateDeletion(candidate: String, onConfirm: () -> Unit) {
        val dialog = AlertDialog.Builder(context)
            .setTitle("删除此词")
            .setMessage("从个人学习词中删除“$candidate”？")
            .setPositiveButton("删除") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            SetupUi.styleDialog(dialog, context, destructivePositive = true)
        }
        SetupUi.showDialog(dialog, context, row)
    }

    private companion object {
        /** The reference's 500 weight: one step above the regular candidate. */
        val MEDIUM_WEIGHT: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        const val STRIP_LIMIT = 24
        const val CANDIDATE_EXPAND_SWIPE_DP = 36
        const val SCROLL_SETTLE_WINDOW_MS = 96L

        // The expanded page of the reference skin (`input_more_cand.xml`), in
        // its own 390-unit canvas: the filter group is 150 wide with a 3 inset,
        // and the corner backspace takes 25.2% by 20.2% of the list.
        const val EXPANDED_SCROLL_TAG = "candidate-expanded-scroll"
        const val EXPANDED_CARD_TAG = "candidate-expanded-card"
        const val EXPANDED_FILTER_ROW_TAG = "candidate-filter-row"
        const val EXPANDED_FILTER_CONTAINER_TAG = "candidate-filter-container"
        const val EXPANDED_FILTER_CHIP_TAG = "candidate-filter-chip"
        const val EXPANDED_BACKSPACE_TAG = "candidate-expanded-backspace"
        const val FILTER_ROW_HEIGHT_UNITS = 40
        const val FILTER_CONTAINER_WIDTH_UNITS = 150
        const val FILTER_CONTAINER_INSET_UNITS = 3
        const val BACKSPACE_WIDTH_FRACTION = 0.252f
        const val BACKSPACE_HEIGHT_FRACTION = 0.202f
    }
}
