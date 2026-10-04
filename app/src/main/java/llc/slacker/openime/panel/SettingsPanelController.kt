package llc.slacker.openime.panel

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import llc.slacker.openime.ImeSettingsActivity
import llc.slacker.openime.R
import llc.slacker.openime.data.ImeHandedness
import llc.slacker.openime.setup.SetupUi
import llc.slacker.openime.theme.AccentPalette
import llc.slacker.openime.theme.ImeAppearance
import llc.slacker.openime.theme.ImeDrawableFactory
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
    private val currentPopup: () -> Boolean,
    private val currentSwipeUpDigits: () -> Boolean,
    /** State of the toggles added after the first four, looked up by their label. */
    private val currentExtraToggle: (String) -> Boolean,
    private val currentFuzzy: () -> Boolean,
    private val currentSkinOpacity: () -> Int,
    private val currentSkinRadius: () -> Int,
    private val currentSkinFontSize: () -> Int,
    private val currentSkinColor: () -> String,
    private val currentHandedness: () -> ImeHandedness,
    private val currentKeyboardHeightPercent: () -> Int,
    private val currentFloatingWidthPercent: () -> Int,
    private val currentFloatingOpacityPercent: () -> Int,
    private val onThemeSelected: (ImeTheme) -> Unit,
    private val onAppearanceSelected: (ImeAppearance) -> Unit,
    private val onToggleChanged: (String, Boolean) -> Unit,
    private val onSkinChanged: (Int, Int, Int, String) -> Unit,
    private val onHandednessChanged: (ImeHandedness) -> Unit,
    private val onKeyboardHeightChanged: (Int) -> Unit,
    private val onFloatingStyleChanged: (Int, Int) -> Unit,
    private val onShowFuzzySettings: () -> Unit,
    private val onShowSkinSettings: () -> Unit,
    private val onOpenAboutData: () -> Unit,
    private val onFeedback: () -> Unit,
    private val applyTheme: () -> Unit,
    private val onHierarchyRebuilt: () -> Unit,
) {
    private val standalone = context is ImeSettingsActivity
    private val stackControls: Boolean
        get() = standalone && (context.resources.configuration.screenWidthDp < 360 || context.resources.configuration.fontScale >= 1.2f)
    private var storedScrollY = 0
    private var settingsScroll: ScrollView? = null
    private var showingSkinOnly = false

    fun scrollPosition(): Int =
        (settingsScroll?.scrollY ?: storedScrollY).coerceAtLeast(0)

    fun restoreScrollPosition(scrollY: Int) {
        storedScrollY = scrollY.coerceAtLeast(0)
        settingsScroll?.post {
            settingsScroll?.scrollTo(0, storedScrollY)
        }
    }

    fun renderSettings(reusePanel: Boolean = false, skinOnly: Boolean = false) {
        showingSkinOnly = skinOnly
        val previousFocusKey =
            if (reusePanel) semanticFocusKey(expandedPanel.findFocus()) else null
        val previousScrollY =
            if (reusePanel) settingsScroll?.scrollY ?: storedScrollY else storedScrollY

        if (!reusePanel || expandedPanel.childCount == 0) {
            expandedPanel.addView(
                createHeader(if (skinOnly) "强调色与按键皮肤" else "偏好设置"),
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
            val inset = toPx(if (standalone) ImeSpacingTokens.LG_DP else ImeSpacingTokens.MD_DP)
            setPadding(inset, inset, inset, toPx(if (standalone) ImeSpacingTokens.XL_DP else 18))
            tag = "settings-panel"
        }

        if (skinOnly) {
            content.addView(createSectionTitle("强调色"), wrapParams())
            content.addView(accentColorRow(), groupParams())
            content.addView(createSectionTitle("按键皮肤"), wrapParams())
            content.addView(skinSliders(), groupParams())
        } else {
            content.addView(createSectionTitle("外观与布局"), wrapParams())
            content.addView(settingGroup(
                segmentedSetting("外观", ImeAppearance.entries.map { it.label }, currentAppearance().label) { label ->
                    ImeAppearance.entries.first { it.label == label }.let(onAppearanceSelected)
                    renderSettings(reusePanel = true)
                },
                segmentedSetting("单手模式", ImeHandedness.entries.map { it.label }, currentHandedness().label) { label ->
                    ImeHandedness.entries.first { it.label == label }.let(onHandednessChanged)
                    renderSettings(reusePanel = true)
                },
                settingsSlider("键盘高度", 80, 120, currentKeyboardHeightPercent(), onKeyboardHeightChanged),
            ), groupParams())
            content.addView(createSectionTitle("更多外观"), wrapParams())
            content.addView(settingGroup(settingNavigationRow("强调色与按键皮肤", "颜色、圆角、不透明度与字号", onShowSkinSettings)), groupParams())
            content.addView(createSectionTitle("浮动键盘"), wrapParams())
            content.addView(settingGroup(
                settingsSlider("浮动宽度", 72, 100, currentFloatingWidthPercent()) { onFloatingStyleChanged(it, currentFloatingOpacityPercent()) },
                settingsSlider("浮动透明度", 82, 100, currentFloatingOpacityPercent()) { onFloatingStyleChanged(currentFloatingWidthPercent(), it) },
            ), groupParams())
        content.addView(createSectionTitle("按键与输入"), wrapParams())
        content.addView(
            settingGroup(
                settingToggleRow("按键音效", "机械轴敲击反馈"),
                settingToggleRow("触感震动", "轻微触感反馈"),
                settingToggleRow("按键气泡", "按下时显示字母预览"),
                settingToggleRow("数字和符号提示", "字母键右上角显示数字和符号"),
                settingToggleRow("上滑输入数字", "按键上滑输入右上角的数字或符号"),
            ),
            groupParams(),
        )

        content.addView(createSectionTitle("智能输入"), wrapParams())
        content.addView(
            settingGroup(
                settingNavigationRow(
                    "模糊音与智能纠错",
                    "进入后配置 z/zh、c/ch、s/sh 等规则",
                    onShowFuzzySettings,
                ),
                settingToggleRow("表情联想", "选词后在联想栏先给出相关表情"),
            ),
            groupParams(),
        )

        content.addView(createSectionTitle("语音输入"), wrapParams())
        content.addView(
            settingGroup(
                settingToggleRow("语音去语气词", "去掉“嗯”“呃”等口头停顿"),
                settingToggleRow("标点用空格代替", "语音里的逗号、句号等写成空格"),
            ),
            groupParams(),
        )

        content.addView(createSectionTitle("数据"), wrapParams())
        content.addView(
            settingGroup(
                settingNavigationRow(
                    "关于与数据",
                    "版本、隐私、导出与导入",
                    onOpenAboutData,
                ),
            ),
            groupParams(),
        )

        }
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

    private fun skinSliders() = settingGroup(
        settingsSlider("圆角", 0, 24, currentSkinRadius()) { onSkinChanged(currentSkinOpacity(), it, currentSkinFontSize(), currentSkinColor()) },
        settingsSlider("不透明度", 70, 100, currentSkinOpacity()) { onSkinChanged(it, currentSkinRadius(), currentSkinFontSize(), currentSkinColor()) },
        settingsSlider("按键字号", 14, 22, currentSkinFontSize()) { onSkinChanged(currentSkinOpacity(), currentSkinRadius(), it, currentSkinColor()) },
    )

    private fun segmentedSetting(label: String, labels: List<String>, selected: String, onSelected: (String) -> Unit): View =
        LinearLayout(context).apply {
            val verticalOptions = standalone && context.resources.configuration.fontScale >= 1.6f
            orientation = if (stackControls) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(toPx(16), toPx(if (standalone) 8 else 0), toPx(16), toPx(if (standalone) 8 else 0))
            addView(labelText(label, ImeTypographyTokens.BODY_SP).apply {
                gravity = Gravity.CENTER_VERTICAL
                if (!standalone) typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
                if (stackControls) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                else if (standalone) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = toPx(ImeSpacingTokens.MD_DP) }
                else LinearLayout.LayoutParams(0, toPx(56), 1f))
            addView(LinearLayout(context).apply {
                orientation = if (verticalOptions) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                tag = if (verticalOptions) "segmented-track" else "segmented-track-tall"
                setPadding(toPx(2), toPx(2), toPx(2), toPx(2))
                labels.forEach { value ->
                    addView(TextView(context).apply {
                        text = value; textSize = ImeTypographyTokens.BODY_SP; gravity = Gravity.CENTER; includeFontPadding = false
                        minimumHeight = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
                        tag = if (value == selected) { if (verticalOptions) "segment-selected" else "segment-selected-tall" }
                            else if (verticalOptions) "segment-option" else "segment-option-tall"
                        contentDescription = "$value，${if (value == selected) "已选中" else "未选中"}"
                        isClickable = true; isFocusable = true
                        setOnClickListener { onFeedback(); onSelected(value) }
                    }, if (verticalOptions) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                }
            }, if (stackControls) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (verticalOptions) LinearLayout.LayoutParams.WRAP_CONTENT else toPx(ImeGeometryTokens.TOUCH_TARGET_DP)).apply { topMargin = toPx(ImeSpacingTokens.SM_DP) }
            else LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 2f))
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
            setPadding(inset, inset, inset, toPx(if (standalone) ImeSpacingTokens.XL_DP else 18))
            tag = "fuzzy-settings-panel"
        }
        content.addView(
            TextView(context).apply {
                text = "近音输入时，候选会同时尝试相近声母；不会改变你已输入的拼音。"
                textSize = ImeTypographyTokens.PANEL_BODY_SP
                setLineSpacing(0f, 1.15f)
                tag = "panel-note"
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (standalone) LinearLayout.LayoutParams.WRAP_CONTENT else toPx(54),
            ).apply { bottomMargin = toPx(if (standalone) ImeSpacingTokens.XL_DP else 10) },
        )
        content.addView(
            settingGroup(
                settingToggleRow("启用模糊音", "z/zh · c/ch · s/sh · l/n"),
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = toPx(if (standalone) ImeSpacingTokens.XL_DP else 14) },
        )
        content.addView(createSectionTitle("当前规则"), wrapParams())
        content.addView(
            TextView(context).apply {
                text = "z / zh · c / ch · s / sh · l / n · en / eng · in / ing"
                textSize = ImeTypographyTokens.PANEL_BODY_SP
                setPadding(toPx(16), toPx(16), toPx(16), toPx(16))
                tag = "fuzzy-rules"
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = toPx(if (standalone) ImeSpacingTokens.XL_DP else 14) },
        )
        content.addView(
            TextView(context).apply {
                text = "规则由输入法自动参与候选计算，暂不单独修改每一组映射。"
                textSize = ImeTypographyTokens.PANEL_NOTE_SP
                tag = "panel-note"
            },
            wrapParams(),
        )
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

    private fun settingGroup(vararg rows: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = if (standalone) "app-setting-group" else "setting-group"
            rows.forEachIndexed { index, row ->
                if (index > 0 && !standalone) {
                    addView(
                        View(context).apply { tag = "setting-divider" },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            toPx(1),
                        ).apply {
                            marginStart = 0
                            marginEnd = 0
                        },
                    )
                }
                if (standalone) row.tag = "app-setting-key"
                addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        if (row is TextView) toPx(54)
                        else LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { if (standalone && index > 0) topMargin = toPx(ImeSpacingTokens.SM_DP) },
                )
            }
        }

    private fun settingIcon(label: String): ImageView =
        ImageView(context).apply {
            setImageResource(
                when (label) {
                    "按键音效" -> R.drawable.ic_volume
                    "触感震动" -> R.drawable.ic_vibration
                    "按键气泡" -> R.drawable.ic_bubble
                    "模糊音与智能纠错", "启用模糊音" -> R.drawable.ic_tune
                    "关于与数据" -> R.drawable.ic_info
                    else -> R.drawable.ic_tune
                },
            )
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            tag = "setting-icon"
        }

    private fun settingToggleRow(label: String, sub: String): LinearLayout {
        val row = LinearLayout(context)
        fun updateRowAccessibility(enabled: Boolean) {
            row.contentDescription =
                "$label，$sub，${if (enabled) "已开启" else "已关闭"}"
            if (Build.VERSION.SDK_INT >= 30) {
                row.stateDescription = if (enabled) "已开启" else "已关闭"
            }
        }

        val toggleView = toggle(label, ::updateRowAccessibility).apply {
            isFocusable = false
            importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        row.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(toPx(if (standalone) 16 else 14), toPx(if (standalone) 8 else 0), toPx(if (standalone) 16 else 14), toPx(if (standalone) 8 else 0))
            tag = "setting-row"
            minimumHeight = toPx(ImeGeometryTokens.SETTING_ROW_HEIGHT_DP)
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
            setOnClickListener { toggleView.performClick() }
            addView(
                settingIcon(label),
                LinearLayout.LayoutParams(toPx(if (standalone) 24 else 26), toPx(if (standalone) 24 else 26)).apply {
                    marginEnd = toPx(if (standalone) 12 else 8)
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(labelText(label, ImeTypographyTokens.BODY_SP), wrapParams())
                    addView(
                        labelText(sub, ImeTypographyTokens.CAPTION_SP).apply {
                            setPadding(0, toPx(if (standalone) 4 else 3), 0, 0)
                        },
                        wrapParams(),
                    )
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
            addView(toggleView, wrapParams().apply { if (standalone) marginStart = toPx(ImeSpacingTokens.MD_DP) })
        }
        updateRowAccessibility(toggleState(label))
        return row
    }

    private fun settingNavigationRow(
        label: String,
        sub: String,
        onTap: () -> Unit,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(toPx(if (standalone) 16 else 14), toPx(if (standalone) 8 else 0), toPx(if (standalone) 16 else 14), toPx(if (standalone) 8 else 0))
            tag = "setting-row"
            contentDescription = "$label，$sub，点击进入"
            minimumHeight = toPx(ImeGeometryTokens.SETTING_ROW_HEIGHT_DP)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onFeedback()
                onTap()
            }
            addView(
                settingIcon(label),
                LinearLayout.LayoutParams(toPx(if (standalone) 24 else 26), toPx(if (standalone) 24 else 26)).apply {
                    marginEnd = toPx(if (standalone) 12 else 8)
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(labelText(label, ImeTypographyTokens.BODY_SP), wrapParams())
                    addView(
                        labelText(sub, ImeTypographyTokens.CAPTION_SP).apply {
                            setPadding(0, toPx(if (standalone) 4 else 3), 0, 0)
                        },
                        wrapParams(),
                    )
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_arrow_back)
                    rotation = 180f
                    imageTintList = ColorStateList.valueOf(
                        ImeTheme.IOS.tokens(
                            appearance = currentAppearance(),
                            accentOverride = AccentPalette.parse(currentSkinColor()),
                        ).keySecondaryText,
                    )
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    tag = "setting-chevron"
                },
                LinearLayout.LayoutParams(toPx(if (standalone) 24 else 28), toPx(if (standalone) 48 else 44)).apply { if (standalone) marginStart = toPx(ImeSpacingTokens.MD_DP) },
            )
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
            elevation = toPx(2).toFloat()
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
                onToggleChanged(seed, next)
                updateAccessibilityState(next)
                onChanged(next)

                val knobView = getChildAt(0)
                knobView.layoutParams = FrameLayout.LayoutParams(
                    toPx(ImeGeometryTokens.SWITCH_KNOB_DP),
                    toPx(ImeGeometryTokens.SWITCH_KNOB_DP),
                ).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
                knobView.animate().cancel()
                knobView.animate()
                    .translationX(
                        if (next) {
                            toPx(ImeGeometryTokens.SWITCH_KNOB_TRAVEL_DP).toFloat()
                        } else {
                            0f
                        },
                    )
                    .setDuration(ImeMotionTokens.STANDARD_TRANSITION_MS)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()
                applyTheme()
            }
        }
    }

    private fun toggleState(seed: String): Boolean = when (seed) {
        "按键音效" -> currentSound()
        "触感震动" -> currentHaptic()
        "模糊音纠错", "启用模糊音" -> currentFuzzy()
        "按键气泡" -> currentPopup()
        "上滑输入数字" -> currentSwipeUpDigits()
        else -> currentExtraToggle(seed)
    }

    private fun accentColorRow(): LinearLayout {
        val current = AccentPalette.normalize(currentSkinColor())
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "setting-group"
            setPadding(toPx(12), toPx(12), toPx(12), toPx(12))
        }
        val swatchGrid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        AccentPalette.presets.chunked(6).forEach { presetRow ->
            val swatchRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            presetRow.forEach { (hex, label) ->
                val selected = AccentPalette.normalize(hex) == current
                swatchRow.addView(
                    FrameLayout(context).apply {
                        tag = "accent-swatch"
                        contentDescription =
                            "强调色$label，${if (selected) "已选中" else "未选中"}"
                        if (Build.VERSION.SDK_INT >= 30) {
                            stateDescription =
                                if (selected) "已选中" else "未选中"
                        }
                        isClickable = true
                        isFocusable = true
                        addView(
                            View(context).apply {
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(AccentPalette.parse(hex))
                                    if (selected) {
                                        setStroke(
                                            toPx(2),
                                            ImeDrawableFactory.contrastText(
                                                AccentPalette.parse(hex),
                                            ),
                                        )
                                    }
                                }
                            },
                            FrameLayout.LayoutParams(toPx(32), toPx(32)).apply {
                                gravity = Gravity.CENTER
                            },
                        )
                        if (selected) {
                            addView(
                                ImageView(context).apply {
                                    setImageResource(R.drawable.ic_check)
                                    imageTintList = ColorStateList.valueOf(
                                        ImeDrawableFactory.contrastText(
                                            AccentPalette.parse(hex),
                                        ),
                                    )
                                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                                    tag = "accent-selected-mark:$hex"
                                    importantForAccessibility =
                                        View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                },
                                FrameLayout.LayoutParams(toPx(32), toPx(32)).apply {
                                    gravity = Gravity.CENTER
                                },
                            )
                        }
                        setOnClickListener {
                            onFeedback()
                            applyAccentColor(hex)
                        }
                    },
                    LinearLayout.LayoutParams(0, toPx(44), 1f),
                )
            }
            swatchGrid.addView(
                swatchRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(44),
                ),
            )
        }
        row.addView(
            swatchGrid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(132),
            ),
        )

        val customSelected = AccentPalette.presets.none {
            AccentPalette.normalize(it.first) == current
        }
        row.addView(
            TextView(context).apply {
                text =
                    "自定义颜色\n输入 6 位十六进制，如 5B6B7A"
                textSize = ImeTypographyTokens.PANEL_NOTE_SP
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                includeFontPadding = false
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(toPx(10), 0, toPx(10), 0)
                tag = "accent-custom"
                minHeight = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
                minimumHeight = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
                isClickable = true
                isFocusable = true
                contentDescription =
                    "自定义强调色，${if (customSelected) "已选中" else "未选中"}"
                if (Build.VERSION.SDK_INT >= 30) {
                    stateDescription =
                        if (customSelected) "已选中" else "未选中"
                }
                setOnClickListener {
                    onFeedback()
                    showCustomAccentDialog()
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(48)).apply {
                topMargin = toPx(6)
            },
        )
        return row
    }

    private fun applyAccentColor(hex: String) {
        val normalized = AccentPalette.normalize(hex)
        onSkinChanged(
            currentSkinOpacity(),
            currentSkinRadius(),
            currentSkinFontSize(),
            normalized,
        )
        renderSettings(reusePanel = true, skinOnly = showingSkinOnly)
    }

    fun showCustomAccentDialog() {
        if (context is android.inputmethodservice.InputMethodService) {
            context.startActivity(android.content.Intent(context, ImeSettingsActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ImeSettingsActivity.EXTRA_EDIT_ACCENT, true))
            return
        }
        val field = EditText(context).apply {
            setText(AccentPalette.normalize(currentSkinColor()).removePrefix("#"))
            hint = "RRGGBB"
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setSelectAllOnFocus(true)
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        val palette = ImeTheme.IOS.tokens(currentAppearance(), resourcesDark(), AccentPalette.parse(currentSkinColor()))
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(toPx(12), 0, toPx(12), 0)
            background = ImeDrawableFactory.rounded(palette.toolbarBackground, toPx(8), palette.primary, toPx(1))
            addView(View(context).apply {
                background = ImeDrawableFactory.rounded(palette.primary, toPx(99))
            }, LinearLayout.LayoutParams(toPx(16), toPx(16)).apply { marginEnd = toPx(8) })
            addView(TextView(context).apply { text = "#"; textSize = ImeTypographyTokens.BODY_SP; setTextColor(palette.keyText) }, wrapParams())
            field.textSize = ImeTypographyTokens.BODY_SP; field.background = null; field.setPadding(0, 0, 0, 0)
            field.setTextColor(palette.keyText)
            addView(field, LinearLayout.LayoutParams(0, toPx(44), 1f))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("自定义强调色")
            .setMessage("输入 6 位十六进制颜色，例如 5B6B7A")
            .setView(FrameLayout(context).apply {
                setPadding(toPx(24), toPx(8), toPx(24), toPx(8))
                addView(inputRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, toPx(44)))
            })
            .setPositiveButton("应用", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            SetupUi.styleDialog(dialog, context)
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            SetupUi.styleCursor(context, field)
            field.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                    true
                } else {
                    false
                }
            }
            field.requestFocus()
            field.selectAll()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().trim().removePrefix("#")
                if (!value.matches(Regex("[0-9a-fA-F]{6}"))) {
                    field.error = "请输入 6 位十六进制颜色"
                    field.requestFocus()
                    return@setOnClickListener
                }
                applyAccentColor("#$value")
                dialog.dismiss()
            }
        }
        SetupUi.showDialog(dialog, context, expandedPanel)
    }

    private fun resourcesDark(): Boolean = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun settingsSlider(
        labelText: String,
        min: Int,
        max: Int,
        initial: Int,
        onChange: (Int) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = if (stackControls) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(toPx(16), toPx(if (standalone) 8 else 4), toPx(16), toPx(if (standalone) 8 else 4))
            tag = "setting-row"
            minimumHeight = toPx(ImeGeometryTokens.SETTING_ROW_HEIGHT_DP)
        }
        val title = TextView(context).apply {
            text = labelText
            textSize = ImeTypographyTokens.PANEL_BODY_SP
            if (!standalone) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val valueView = TextView(context).apply {
            tag = "setting-value"
            textSize = ImeTypographyTokens.BODY_SP
            gravity = Gravity.CENTER
            includeFontPadding = false
            if (standalone) setSingleLine()
            minWidth = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
            contentDescription = "$labelText 当前值"
        }
        val suffix = when (labelText) {
            "圆角" -> " dp"
            "不透明度" -> "%"
            "按键字号" -> " sp"
            "键盘高度", "浮动宽度", "浮动透明度" -> "%"
            else -> ""
        }
        val seekBar = SeekBar(context).apply {
            this.min = min
            this.max = max
            progress = initial.coerceIn(min, max)
            minimumHeight = toPx(ImeGeometryTokens.TOUCH_TARGET_DP)
            isFocusable = true
            tag = "settings-slider:$labelText"
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        val description = "$labelText，$progress$suffix"
                        valueView.text = "$progress$suffix"
                        contentDescription = description
                        if (Build.VERSION.SDK_INT >= 30) {
                            stateDescription = description
                        }
                        if (fromUser) onChange(progress)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        if (stackControls) {
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(valueView, wrapParams())
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(seekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        } else {
            row.addView(title, LinearLayout.LayoutParams(toPx(if (standalone) 80 else 72), LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (standalone) marginEnd = toPx(ImeSpacingTokens.MD_DP) })
            row.addView(seekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(valueView, LinearLayout.LayoutParams(if (standalone) LinearLayout.LayoutParams.WRAP_CONTENT else toPx(44), toPx(ImeGeometryTokens.TOUCH_TARGET_DP)))
        }
        val initialDescription =
            "$labelText，${seekBar.progress}$suffix"
        valueView.text = "${seekBar.progress}$suffix"
        seekBar.contentDescription = initialDescription
        if (Build.VERSION.SDK_INT >= 30) {
            seekBar.stateDescription = initialDescription
        }
        return row
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

    private fun labelText(value: String, size: Float): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            includeFontPadding = false
            tag = if (size <= 11.5f) "panel-note" else "setting-label"
        }

    private fun chipParams() =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
        ).apply { bottomMargin = toPx(if (standalone) ImeSpacingTokens.XL_DP else ImeSpacingTokens.MD_DP) }

    private fun groupParams() =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = toPx(if (standalone) ImeSpacingTokens.XL_DP else ImeSpacingTokens.MD_DP) }

    private fun wrapParams() =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
}
