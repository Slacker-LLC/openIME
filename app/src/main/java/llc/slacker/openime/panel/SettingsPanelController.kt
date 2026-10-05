package llc.slacker.openime.panel

import llc.slacker.openime.theme.ImeSurfacePolicy
import llc.slacker.openime.data.ImeSettingsRepository
import llc.slacker.openime.core.FuzzyRule
import android.content.Context
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import llc.slacker.openime.ImeSettingsActivity
import llc.slacker.openime.R
import llc.slacker.openime.data.HapticStyle
import llc.slacker.openime.data.KeySoundStyle
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeMotionTokens
import llc.slacker.openime.theme.ImeSpacingTokens
import llc.slacker.openime.theme.ImeTheme
import llc.slacker.openime.theme.ImeTypographyTokens

/**
 * Concrete presentation owner for Settings and Fuzzy Settings.
 *
 * Product settings remain owned by ImeKeyboardView/Service. This controller
 * owns only panel construction, scroll/focus restoration, and control gestures.
 *
 * The same settings render two ways. The preferences page is a grouped list:
 * one card per section, rows split by inset hairlines, a neutral icon tile per
 * row. The keyboard's own panel is only as tall as the keyboard, so it leads
 * with four quick toggles, puts appearance and height on the first screen and
 * uses compact rows without icons or descriptions below that.
 */
internal class SettingsPanelController(
    private val context: Context,
    private val expandedPanel: LinearLayout,
    private val toPx: (Int) -> Int,
    private val createHeader: (String) -> LinearLayout,
    private val createSectionTitle: (String) -> TextView,
    private val createChipScroll: (
        List<String>,
        String,
        (String) -> Unit,
    ) -> HorizontalScrollView,
    private val currentTheme: () -> ImeTheme,
    private val currentAppearance: () -> ImeAppearance,
    private val currentSound: () -> Boolean,
    private val currentHaptic: () -> Boolean,
    private val currentHapticStrengthPercent: () -> Int,
    private val currentHapticStyle: () -> HapticStyle,
    private val currentKeySoundStyle: () -> KeySoundStyle,
    private val currentPopup: () -> Boolean,
    private val currentSwipeUpDigits: () -> Boolean,
    /** State of the toggles added after the first four, looked up by their label. */
    private val currentExtraToggle: (String) -> Boolean,
    private val currentFuzzy: () -> Boolean,
    private val currentKeyboardHeightPercent: () -> Int,
    private val currentFloatingWidthPercent: () -> Int,
    private val currentFloatingOpacityPercent: () -> Int,
    private val onThemeSelected: (ImeTheme) -> Unit,
    private val onAppearanceSelected: (ImeAppearance) -> Unit,
    private val onToggleChanged: (String, Boolean) -> Unit,
    private val onKeyboardHeightChanged: (Int) -> Unit,
    private val onHapticStrengthChanged: (Int) -> Unit,
    private val onHapticStyleChanged: (HapticStyle) -> Unit,
    private val onKeySoundStyleChanged: (KeySoundStyle) -> Unit,
    private val onFloatingStyleChanged: (Int, Int) -> Unit,
    private val onShowFuzzySettings: () -> Unit,
    /** A 模糊音 pair was switched; the new set is already saved. */
    private val onFuzzyRulesChanged: () -> Unit,
    private val onOpenAbout: () -> Unit,
    private val onOpenDataManagement: () -> Unit,
    private val onFeedback: () -> Unit,
    private val applyTheme: () -> Unit,
    private val onHierarchyRebuilt: () -> Unit,
) {
    private val standalone = context is ImeSettingsActivity
    private var storedScrollY = 0
    private var settingsScroll: ScrollView? = null

    fun scrollPosition(): Int =
        (settingsScroll?.scrollY ?: storedScrollY).coerceAtLeast(0)

    fun restoreScrollPosition(scrollY: Int) {
        storedScrollY = scrollY.coerceAtLeast(0)
        settingsScroll?.post {
            settingsScroll?.scrollTo(0, storedScrollY)
        }
    }

    fun renderSettings(reusePanel: Boolean = false) {
        val previousFocusKey =
            if (reusePanel) semanticFocusKey(expandedPanel.findFocus()) else null
        val previousScrollY =
            if (reusePanel) settingsScroll?.scrollY ?: storedScrollY else storedScrollY

        if (!reusePanel || expandedPanel.childCount == 0) {
            expandedPanel.addView(
                createHeader(if (standalone) "偏好设置" else "设置"),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    if (standalone) context.resources.getDimensionPixelSize(R.dimen.setup_top_bar_height) else toPx(ImeGeometryTokens.TOP_BAR_HEIGHT_DP),
                ),
            )
        } else {
            while (expandedPanel.childCount > 1) {
                expandedPanel.removeViewAt(expandedPanel.childCount - 1)
            }
        }

        val scroll = ScrollView(context).apply {
            tag = "settings-scroll"
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                storedScrollY = scrollY
            }
        }
        settingsScroll = scroll

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (standalone) {
                setPadding(toPx(ImeSpacingTokens.LG_DP), 0, toPx(ImeSpacingTokens.LG_DP), toPx(ImeSpacingTokens.XXL_DP))
            } else {
                setPadding(toPx(ImeSpacingTokens.MD_DP), toPx(ImeSpacingTokens.XS_DP), toPx(ImeSpacingTokens.MD_DP), toPx(ImeSpacingTokens.LG_DP))
            }
            tag = "settings-panel"
        }
        if (standalone) appSettings(content) else keyboardSettings(content)

        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        expandedPanel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        scroll.post {
            scroll.scrollTo(0, previousScrollY)
            previousFocusKey?.let { key ->
                findSemanticFocusTarget(expandedPanel, key)?.requestFocus()
            }
        }

        if (reusePanel) {
            applyTheme()
            onHierarchyRebuilt()
        }
    }

    private fun appSettings(content: LinearLayout) {
        content.addSection("外观", first = true)
        content.addCard(
            segmentedRow("外观", R.drawable.ic_pref_appearance),
            sliderRow("键盘高度", R.drawable.ic_pref_height, 80, 120, currentKeyboardHeightPercent(), onChange = onKeyboardHeightChanged),
        )
        content.addSection("浮动键盘")
        content.addCard(
            sliderRow("浮动宽度", R.drawable.ic_pref_width, 72, 100, currentFloatingWidthPercent()) {
                onFloatingStyleChanged(it, currentFloatingOpacityPercent())
            },
            sliderRow("浮动透明度", R.drawable.ic_pref_opacity, 82, 100, currentFloatingOpacityPercent()) {
                onFloatingStyleChanged(currentFloatingWidthPercent(), it)
            },
        )
        content.addSection("按键与输入")
        content.addCard(
            toggleRow("按键音效", "按键时播放提示音", R.drawable.ic_pref_sound),
            soundStyleRow(R.drawable.ic_pref_sound),
            toggleRow("触感震动", "清脆短促，按下即停", R.drawable.ic_pref_haptic),
            hapticStyleRow(R.drawable.ic_pref_haptic),
            sliderRow("震动强度", R.drawable.ic_pref_haptic, 10, 100, currentHapticStrengthPercent(), onChange = onHapticStrengthChanged),
            toggleRow("按键气泡", "按下时显示字母预览", R.drawable.ic_pref_bubble),
            toggleRow("数字和符号提示", "字母键右上角显示数字和符号", R.drawable.ic_pref_hints),
            toggleRow("上滑输入数字", "按键上滑输入右上角的数字或符号", R.drawable.ic_pref_swipe_up),
        )
        content.addSection("智能输入")
        content.addCard(
            navigationRow("模糊音与智能纠错", "z/zh、c/ch、s/sh 等规则", R.drawable.ic_pref_fuzzy, onShowFuzzySettings),
            toggleRow("表情联想", "选词后在联想栏先给出相关表情", R.drawable.ic_pref_emoji),
        )
        content.addSection("语音输入")
        content.addCard(
            toggleRow("语音去语气词", "去掉“嗯”“呃”等口头停顿", R.drawable.ic_pref_waveform),
            toggleRow("标点用空格代替", "语音里的逗号、句号等写成空格", R.drawable.ic_pref_space),
        )
        content.addSection("关于与数据")
        content.addCard(
            navigationRow("关于", "版本、隐私与诊断", R.drawable.ic_pref_info, onOpenAbout),
            navigationRow("数据管理", "导出与导入用户数据", R.drawable.ic_pref_data, onOpenDataManagement),
        )
    }

    private fun keyboardSettings(content: LinearLayout) {
        content.addView(
            quickToggles(
                Triple("按键音效", "按键音效", R.drawable.ic_pref_sound),
                Triple("触感震动", "触感震动", R.drawable.ic_pref_haptic),
                Triple("按键气泡", "按键气泡", R.drawable.ic_pref_bubble),
                Triple("数字和符号提示", "数字提示", R.drawable.ic_pref_hints),
            ),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        content.addCard(
            segmentedRow("外观", iconRes = 0),
            sliderRow("键盘高度", 0, 80, 120, currentKeyboardHeightPercent(), onChange = onKeyboardHeightChanged),
            sliderRow("震动强度", 0, 10, 100, currentHapticStrengthPercent(), onChange = onHapticStrengthChanged),
            topMarginDp = ImeSpacingTokens.MD_DP,
        )
        content.addSection("按键反馈")
        content.addCard(
            soundStyleRow(0),
            hapticStyleRow(0),
        )
        content.addSection("浮动键盘")
        content.addCard(
            sliderRow("浮动宽度", 0, 72, 100, currentFloatingWidthPercent(), shortLabel = "宽度") {
                onFloatingStyleChanged(it, currentFloatingOpacityPercent())
            },
            sliderRow("浮动透明度", 0, 82, 100, currentFloatingOpacityPercent(), shortLabel = "透明度") {
                onFloatingStyleChanged(currentFloatingWidthPercent(), it)
            },
        )
        content.addSection("输入")
        content.addCard(
            toggleRow("上滑输入数字", null, 0),
            toggleRow("表情联想", null, 0),
            navigationRow("模糊音与智能纠错", null, 0, onShowFuzzySettings),
        )
        content.addSection("语音")
        content.addCard(
            toggleRow("语音去语气词", null, 0),
            toggleRow("标点用空格代替", null, 0),
        )
        content.addSection("关于与数据")
        content.addCard(
            navigationRow("关于", null, 0, onOpenAbout),
            navigationRow("数据管理", null, 0, onOpenDataManagement),
        )
    }

    fun renderFuzzySettings() {
        expandedPanel.addView(
            createHeader("模糊音纠错"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (standalone) context.resources.getDimensionPixelSize(R.dimen.setup_top_bar_height) else toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ),
        )
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val inset = toPx(if (standalone) ImeSpacingTokens.LG_DP else ImeSpacingTokens.MD_DP)
            setPadding(inset, toPx(ImeSpacingTokens.SM_DP), inset, toPx(if (standalone) ImeSpacingTokens.XL_DP else ImeSpacingTokens.LG_DP))
            tag = "fuzzy-settings-panel"
        }
        content.addView(
            noteText("读不准的音也能打出来：例如打 zi 时，“知 zhi”也会出现在候选里。只影响候选，不改你输入的拼音。"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = toPx(ImeSpacingTokens.LG_DP)
                marginEnd = toPx(ImeSpacingTokens.LG_DP)
                bottomMargin = toPx(ImeSpacingTokens.MD_DP)
            },
        )
        // Each pair is its own switch; they only count while 启用模糊音 is on.
        val ruleRows = ArrayList<View>()
        fun syncRuleRows(masterOn: Boolean) {
            ruleRows.forEach { row ->
                row.isEnabled = masterOn
                row.alpha = if (masterOn) 1f else ImeSurfacePolicy.DISABLED_ALPHA
                (row as? ViewGroup)?.let { group ->
                    for (index in 0 until group.childCount) group.getChildAt(index).isEnabled = masterOn
                }
            }
        }
        content.addCard(
            toggleRow(
                "启用模糊音",
                if (standalone) "下面每一组都可以单独开关" else null,
                if (standalone) R.drawable.ic_pref_fuzzy else 0,
                onChanged = ::syncRuleRows,
            ),
        )
        content.addSection("声母")
        content.addCard(
            *FuzzyRule.entries.filter { it.initial }
                .map { rule -> toggleRow(rule.label, FUZZY_EXAMPLES[rule], 0).also(ruleRows::add) }
                .toTypedArray(),
        )
        content.addSection("韵母")
        content.addCard(
            *FuzzyRule.entries.filterNot { it.initial }
                .map { rule -> toggleRow(rule.label, FUZZY_EXAMPLES[rule], 0).also(ruleRows::add) }
                .toTypedArray(),
        )
        content.addView(
            noteText("每一组默认关闭，按自己容易混的音打开。开得越多，候选越杂。"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = toPx(ImeSpacingTokens.LG_DP)
                marginEnd = toPx(ImeSpacingTokens.LG_DP)
                topMargin = toPx(ImeSpacingTokens.SM_DP)
            },
        )
        syncRuleRows(currentFuzzy())
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        expandedPanel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
    }

    // ---- Sections and cards ----------------------------------------------

    private fun LinearLayout.addSection(title: String, first: Boolean = false) {
        addView(
            TextView(context).apply {
                text = title
                textSize = if (standalone) ImeTypographyTokens.DETAIL_SP else ImeTypographyTokens.SMALL_SP
                includeFontPadding = false
                tag = "panel-section-title"
                if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = toPx(if (standalone) ImeSpacingTokens.LG_DP else 14)
                topMargin = toPx(
                    when {
                        first -> ImeSpacingTokens.MD_DP
                        standalone -> 28
                        else -> ImeSpacingTokens.LG_DP
                    },
                )
                bottomMargin = toPx(if (standalone) ImeSpacingTokens.SM_DP else 6)
            },
        )
    }

    /**
     * One rounded card holding [rows], separated by hairlines that start where
     * the row text starts (after the icon tile on the preferences page).
     */
    private fun LinearLayout.addCard(vararg rows: View, topMarginDp: Int = 0) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "settings-card"
            clipToOutline = true
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                card.addView(
                    View(context).apply {
                        tag = "row-hairline"
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(1).coerceAtLeast(1)).apply {
                        marginStart = toPx(if (standalone) 60 else 14)
                    },
                )
            }
            card.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        addView(
            card,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = toPx(topMarginDp)
            },
        )
    }

    // ---- Rows ---------------------------------------------------------------

    /** Row shell: an optional icon tile, then [body] filling the rest. */
    private fun rowShell(iconRes: Int, alignTop: Boolean = false): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (alignTop) Gravity.TOP else Gravity.CENTER_VERTICAL
            tag = "setting-row"
            minimumHeight = toPx(if (standalone) ImeGeometryTokens.SETTING_ROW_HEIGHT_DP else 52)
            if (standalone) {
                setPadding(toPx(16), toPx(12), toPx(16), toPx(12))
            } else {
                setPadding(toPx(14), toPx(2), toPx(12), toPx(2))
            }
            if (iconRes != 0) {
                addView(
                    ImageView(context).apply {
                        setImageResource(iconRes)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(toPx(7), toPx(7), toPx(7), toPx(7))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        tag = "setting-icon"
                    },
                    LinearLayout.LayoutParams(toPx(32), toPx(32)).apply { marginEnd = toPx(12) },
                )
            }
        }

    private fun labelBlock(label: String, sub: String?): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(rowLabel(label), wrapParams())
            if (sub != null) {
                addView(
                    TextView(context).apply {
                        text = sub
                        textSize = ImeTypographyTokens.DETAIL_SP
                        includeFontPadding = false
                        setLineSpacing(0f, 1.2f)
                        tag = "setting-sub"
                        setPadding(0, toPx(ImeSpacingTokens.XS_DP), 0, 0)
                    },
                    wrapParams(),
                )
            }
        }

    private fun rowLabel(label: String): TextView = TextView(context).apply {
        text = label
        textSize = ImeTypographyTokens.ROW_SP
        includeFontPadding = false
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        tag = "setting-label"
    }

    private fun toggleRow(
        label: String,
        sub: String?,
        iconRes: Int,
        onChanged: (Boolean) -> Unit = {},
    ): LinearLayout {
        val row = rowShell(iconRes)
        fun updateRowAccessibility(enabled: Boolean) {
            row.contentDescription = listOfNotNull(label, sub, if (enabled) "已开启" else "已关闭").joinToString("，")
            if (Build.VERSION.SDK_INT >= 30) {
                row.stateDescription = if (enabled) "已开启" else "已关闭"
            }
        }
        val toggleView = toggle(label) { enabled ->
            updateRowAccessibility(enabled)
            onChanged(enabled)
        }.apply {
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        row.apply {
            isClickable = true
            isFocusable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Switch"
                    info.isCheckable = true
                    info.isChecked = toggleState(label)
                }
            }
            setOnClickListener { if (isEnabled) toggleView.performClick() }
            addView(labelBlock(label, sub), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggleView, wrapParams().apply { marginStart = toPx(ImeSpacingTokens.MD_DP) })
        }
        updateRowAccessibility(toggleState(label))
        return row
    }

    private fun navigationRow(label: String, sub: String?, iconRes: Int, onTap: () -> Unit): LinearLayout =
        rowShell(iconRes).apply {
            contentDescription = listOfNotNull(label, sub, "点击进入").joinToString("，")
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onFeedback()
                onTap()
            }
            addView(labelBlock(label, sub), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    tag = "setting-chevron"
                },
                LinearLayout.LayoutParams(toPx(18), toPx(18)).apply { marginStart = toPx(ImeSpacingTokens.MD_DP) },
            )
        }

    private fun soundStyleRow(iconRes: Int): View =
        choiceRow("音效", iconRes, KeySoundStyle.entries.map { it.label }, currentKeySoundStyle().label) { label ->
            onKeySoundStyleChanged(KeySoundStyle.entries.first { it.label == label })
        }

    private fun hapticStyleRow(iconRes: Int): View =
        choiceRow("震动手感", iconRes, HapticStyle.entries.map { it.label }, currentHapticStyle().label) { label ->
            onHapticStyleChanged(HapticStyle.entries.first { it.label == label })
        }

    /**
     * A label with a scrollable row of options under it. Choosing one saves it,
     * previews it (the callback plays the sound or vibration) and redraws the
     * row so the selection moves; it never plays the old feedback first.
     */
    private fun choiceRow(
        label: String,
        iconRes: Int,
        options: List<String>,
        selected: String,
        onChoose: (String) -> Unit,
    ): View {
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            options.forEachIndexed { index, option ->
                val active = option == selected
                addView(
                    TextView(context).apply {
                        text = option
                        textSize = ImeTypographyTokens.BODY_SP
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        minWidth = toPx(56)
                        setPadding(toPx(14), 0, toPx(14), 0)
                        tag = if (active) "tab-active" else "panel-tab"
                        typeface = android.graphics.Typeface.create(
                            "sans-serif-medium",
                            if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
                        )
                        contentDescription = "$label：$option，${if (active) "已选中" else "未选中"}"
                        if (Build.VERSION.SDK_INT >= 30) stateDescription = if (active) "已选中" else "未选中"
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            onChoose(option)
                            renderSettings(reusePanel = true)
                        }
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, toPx(40)).apply {
                        if (index > 0) marginStart = toPx(6)
                    },
                )
            }
        }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chips)
        }
        val row = rowShell(iconRes, alignTop = true)
        row.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(rowLabel(label), wrapParams().apply { topMargin = toPx(if (iconRes != 0) 6 else 8) })
                addView(
                    scroller,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(ImeGeometryTokens.TOUCH_TARGET_DP)).apply {
                        topMargin = toPx(ImeSpacingTokens.XS_DP)
                    },
                )
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        return row
    }

    /**
     * Appearance picker. On the preferences page the options sit under the
     * label, full width; in the keyboard they share the label's line.
     */
    private fun segmentedRow(label: String, iconRes: Int): View {
        val labels = ImeAppearance.entries.map { it.label }
        val selected = currentAppearance().label
        // When three equal columns cannot hold the longest option at the
        // current width and font scale, the options stack, each a full 48dp.
        val stacked = standalone && !segmentsFitInOneRow(labels)
        val track = LinearLayout(context).apply {
            orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            tag = if (stacked) "segmented-track" else "segmented-track-tall"
            setPadding(toPx(2), if (stacked) toPx(2) else 0, toPx(2), if (stacked) toPx(2) else 0)
            labels.forEach { value ->
                addView(
                    TextView(context).apply {
                        text = value
                        textSize = if (standalone) ImeTypographyTokens.BODY_SP else ImeTypographyTokens.DETAIL_SP
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        minimumHeight = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
                        tag = when {
                            stacked -> if (value == selected) "segment-selected" else "segment-option"
                            else -> if (value == selected) "segment-selected-tall" else "segment-option-tall"
                        }
                        contentDescription = "$value，${if (value == selected) "已选中" else "未选中"}"
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            onFeedback()
                            ImeAppearance.entries.first { it.label == value }.let(onAppearanceSelected)
                            renderSettings(reusePanel = true)
                        }
                    },
                    if (stacked) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                )
            }
        }
        val row = rowShell(iconRes, alignTop = standalone)
        if (standalone) {
            row.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(rowLabel(label), wrapParams().apply { topMargin = toPx(6) })
                    addView(
                        track,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            if (stacked) LinearLayout.LayoutParams.WRAP_CONTENT else toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
                        ).apply {
                            topMargin = toPx(if (stacked) ImeSpacingTokens.SM_DP else ImeSpacingTokens.XS_DP)
                        },
                    )
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        } else {
            row.addView(rowLabel(label), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(track, LinearLayout.LayoutParams(toPx(216), toPx(ImeGeometryTokens.TOUCH_TARGET_DP)))
        }
        return row
    }

    /**
     * Whether [labels] fit side by side in the preferences card: page and row
     * padding 16dp each side, the 32dp icon plus 12dp gap, 2dp track inset and
     * 8dp breathing room per option. Measured with the real paint, so system
     * font scale and narrow windows are both covered.
     */
    private fun segmentsFitInOneRow(labels: List<String>): Boolean {
        val metrics = context.resources.displayMetrics
        val contentDp = minOf(context.resources.configuration.screenWidthDp, 600) - 16 * 4 - 44 - 4
        val perOptionPx = contentDp * metrics.density / labels.size - 8 * metrics.density
        val paint = android.text.TextPaint().apply {
            textSize = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                ImeTypographyTokens.BODY_SP,
                metrics,
            )
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        return labels.all { paint.measureText(it) <= perOptionPx }
    }

    private fun sliderRow(
        label: String,
        iconRes: Int,
        min: Int,
        max: Int,
        initial: Int,
        shortLabel: String = label,
        onChange: (Int) -> Unit,
    ): LinearLayout {
        val valueView = TextView(context).apply {
            tag = "setting-value"
            textSize = ImeTypographyTokens.BODY_SP
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            includeFontPadding = false
            setSingleLine()
            fontFeatureSettings = "tnum"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // Room for half the thumb at each end, so 0% and 100% draw whole.
        val thumbInset = toPx(11)
        val seekBar = SeekBar(context).apply {
            this.min = min
            this.max = max
            progress = initial.coerceIn(min, max)
            minimumHeight = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
            setPadding(thumbInset, 0, thumbInset, 0)
            isFocusable = true
            tag = "settings-slider:$label"
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val description = "$label，$progress%"
                        valueView.text = "$progress%"
                        contentDescription = description
                        if (Build.VERSION.SDK_INT >= 30) stateDescription = description
                        if (fromUser) onChange(progress)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        val initialDescription = "$label，${seekBar.progress}%"
        valueView.text = "${seekBar.progress}%"
        seekBar.contentDescription = initialDescription
        if (Build.VERSION.SDK_INT >= 30) seekBar.stateDescription = initialDescription

        val row = rowShell(iconRes, alignTop = standalone)
        if (standalone) {
            row.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(rowLabel(label), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                            addView(valueView, wrapParams())
                        },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = toPx(6)
                        },
                    )
                    addView(
                        seekBar,
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(40)),
                    )
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.setPadding(row.paddingLeft, row.paddingTop, row.paddingRight, toPx(4))
        } else {
            row.addView(rowLabel(shortLabel), LinearLayout.LayoutParams(toPx(64), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(seekBar, LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f))
            row.addView(valueView, LinearLayout.LayoutParams(toPx(44), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        return row
    }

    /**
     * The keyboard panel's first row: four equal tiles that flip a setting in
     * one tap. "On" is the accent fill; "off" is a plain card.
     */
    private fun quickToggles(vararg tiles: Triple<String, String, Int>): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "quick-toggles"
            tiles.forEachIndexed { index, (seed, label, iconRes) ->
                val tile = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    tag = "quick-tile"
                    isClickable = true
                    isFocusable = true
                    isSelected = toggleState(seed)
                    fun describe(enabled: Boolean) {
                        contentDescription = "$seed，${if (enabled) "已开启" else "已关闭"}"
                        if (Build.VERSION.SDK_INT >= 30) stateDescription = if (enabled) "已开启" else "已关闭"
                    }
                    describe(isSelected)
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = "android.widget.ToggleButton"
                            info.isCheckable = true
                            info.isChecked = toggleState(seed)
                        }
                    }
                    addView(
                        ImageView(context).apply {
                            setImageResource(iconRes)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            tag = "quick-tile-icon"
                        },
                        LinearLayout.LayoutParams(toPx(20), toPx(20)),
                    )
                    addView(
                        TextView(context).apply {
                            text = label
                            textSize = ImeTypographyTokens.SMALL_SP
                            includeFontPadding = false
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            tag = "quick-tile-label"
                        },
                        wrapParams().apply { topMargin = toPx(6) },
                    )
                    setOnClickListener {
                        onFeedback()
                        val next = !toggleState(seed)
                        dispatchToggle(seed, next)
                        isSelected = next
                        describe(next)
                        applyTheme()
                    }
                }
                addView(
                    tile,
                    LinearLayout.LayoutParams(0, toPx(68), 1f).apply {
                        if (index > 0) marginStart = toPx(ImeSpacingTokens.SM_DP)
                    },
                )
            }
        }

    private fun toggle(
        seed: String,
        onChanged: (Boolean) -> Unit = {},
    ): View {
        val isOn = toggleState(seed)
        val knob = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                toPx(ImeGeometryTokens.SWITCH_KNOB_DP),
                toPx(ImeGeometryTokens.SWITCH_KNOB_DP),
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            tag = "toggle-knob"
            elevation = toPx(1).toFloat()
            translationX =
                if (isOn) toPx(ImeGeometryTokens.SWITCH_KNOB_TRAVEL_DP).toFloat()
                else 0f
        }

        return FrameLayout(context).apply {
            setPadding(
                toPx(ImeGeometryTokens.SWITCH_PADDING_DP),
                0,
                toPx(ImeGeometryTokens.SWITCH_PADDING_DP),
                0,
            )
            minimumWidth = toPx(ImeGeometryTokens.SWITCH_WIDTH_DP)
            minimumHeight = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
            tag = "toggle"
            isClickable = true
            isFocusable = true

            fun updateAccessibilityState(enabled: Boolean) {
                contentDescription =
                    "$seed，${if (enabled) "已开启" else "已关闭"}"
                if (Build.VERSION.SDK_INT >= 30) {
                    stateDescription = if (enabled) "已开启" else "已关闭"
                }
            }

            updateAccessibilityState(isOn)
            addView(knob)
            setOnClickListener {
                onFeedback()
                val next = !toggleState(seed)
                dispatchToggle(seed, next)
                updateAccessibilityState(next)
                onChanged(next)

                val knobView = getChildAt(0)
                knobView.animate().cancel()
                knobView.animate()
                    .translationX(
                        if (next) toPx(ImeGeometryTokens.SWITCH_KNOB_TRAVEL_DP).toFloat() else 0f,
                    )
                    .setDuration(ImeMotionTokens.STANDARD_TRANSITION_MS)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()
                applyTheme()
            }
        }
    }

    /** A 模糊音 pair is saved here; every other switch goes to the host. */
    private fun dispatchToggle(seed: String, enabled: Boolean) {
        val rule = FuzzyRule.fromLabel(seed)
        if (rule == null) {
            onToggleChanged(seed, enabled)
            return
        }
        val rules = ImeSettingsRepository.loadFuzzyRules(context)
        ImeSettingsRepository.saveFuzzyRules(context, if (enabled) rules + rule else rules - rule)
        onFuzzyRulesChanged()
    }

    private fun toggleState(seed: String): Boolean = FuzzyRule.fromLabel(seed)
        ?.let { it in ImeSettingsRepository.loadFuzzyRules(context) }
        ?: when (seed) {
            "按键音效" -> currentSound()
            "触感震动" -> currentHaptic()
            "模糊音纠错", "启用模糊音" -> currentFuzzy()
            "按键气泡" -> currentPopup()
            "上滑输入数字" -> currentSwipeUpDigits()
            else -> currentExtraToggle(seed)
        }

    private fun noteText(value: String): TextView = TextView(context).apply {
        text = value
        textSize = ImeTypographyTokens.DETAIL_SP
        setLineSpacing(0f, 1.2f)
        tag = "panel-note"
    }

    private fun semanticFocusKey(view: View?): String? {
        val description = view?.contentDescription?.toString()
            ?.substringBefore('，')
            ?.takeIf { it.isNotBlank() }
        return description ?: (view?.tag as? String)?.takeIf { it.isNotBlank() }
    }

    private fun findSemanticFocusTarget(root: View, key: String): View? {
        if (semanticFocusKey(root) == key && root.isFocusable) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findSemanticFocusTarget(root.getChildAt(index), key)?.let {
                    return it
                }
            }
        }
        return null
    }

    private fun wrapParams() =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    private companion object {
        /** A character pair each 模糊音 switch makes interchangeable. */
        val FUZZY_EXAMPLES: Map<FuzzyRule, String> = mapOf(
            FuzzyRule.Z_ZH to "资 zi · 知 zhi",
            FuzzyRule.C_CH to "此 ci · 吃 chi",
            FuzzyRule.S_SH to "四 si · 是 shi",
            FuzzyRule.N_L to "你 ni · 里 li",
            FuzzyRule.F_H to "飞 fei · 黑 hei",
            FuzzyRule.R_L to "热 re · 乐 le",
            FuzzyRule.AN_ANG to "山 shan · 上 shang",
            FuzzyRule.EN_ENG to "分 fen · 风 feng",
            FuzzyRule.IN_ING to "心 xin · 星 xing",
            FuzzyRule.IAN_IANG to "先 xian · 香 xiang",
            FuzzyRule.UAN_UANG to "关 guan · 光 guang",
        )
    }
}
