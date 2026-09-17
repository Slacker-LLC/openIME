from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


path = Path("app/src/main/java/llc/slacker/openime/ImeKeyboardView.kt")
text = path.read_text()

# Tools / top-level panel names.
for old, new, label in [
    ('        addPanelHead("工具")\n', '        addPanelHead(context.getString(R.string.panel_tools))\n', 'tools head'),
    ('            ToolEntry("文本编辑", Panel.TEXT_EDITOR, R.drawable.ic_keyboard),\n', '            ToolEntry(context.getString(R.string.tool_text_edit), Panel.TEXT_EDITOR, R.drawable.ic_keyboard),\n', 'text edit tool'),
    ('            ToolEntry("游戏键盘", Panel.GAMING, R.drawable.ic_game),\n', '            ToolEntry(context.getString(R.string.tool_gaming_keyboard), Panel.GAMING, R.drawable.ic_game),\n', 'gaming tool'),
    ('            ToolEntry("设置", Panel.SETTINGS, R.drawable.ic_settings),\n', '            ToolEntry(context.getString(R.string.tool_settings), Panel.SETTINGS, R.drawable.ic_settings),\n', 'settings tool'),
    ('        addPanelHead("符号")\n', '        addPanelHead(context.getString(R.string.panel_symbols))\n', 'symbols head'),
    ('        addPanelHead("表情")\n', '        addPanelHead(context.getString(R.string.panel_emoji))\n', 'emoji head'),
    ('        addPanelHead("手写输入")\n', '        addPanelHead(context.getString(R.string.panel_handwriting))\n', 'handwriting head'),
    ('        addPanelHead("语音输入")\n', '        addPanelHead(context.getString(R.string.panel_voice))\n', 'voice head'),
    ('        addPanelHead("文本编辑")\n', '        addPanelHead(context.getString(R.string.panel_text_editor))\n', 'text editor head'),
    ('            addPanelHead("剪贴板")\n', '            addPanelHead(context.getString(R.string.panel_clipboard))\n', 'clipboard head'),
    ('            addPanelHead("偏好设置")\n', '            addPanelHead(context.getString(R.string.settings_title))\n', 'settings head'),
    ('        addPanelHead("模糊音纠错")\n', '        addPanelHead(context.getString(R.string.panel_fuzzy_settings))\n', 'fuzzy head'),
]:
    text = replace_once(text, old, new, label)

# Symbol category display labels while retaining canonical data keys.
old_symbols = '''            val cats = listOf("常用", "中文", "英文", "数学", "序号", "单位", "特殊", "编程", "自定义")
            val tabs = panelChipScroll(cats, symbolCategory) { cat ->
                if (cat != symbolCategory) {
                    symbolCategory = cat
                    renderContent(true)
                }
            }
'''
new_symbols = '''            val categoryLabels = linkedMapOf(
                "常用" to context.getString(R.string.symbol_category_common),
                "中文" to context.getString(R.string.symbol_category_chinese),
                "英文" to context.getString(R.string.symbol_category_english),
                "数学" to context.getString(R.string.symbol_category_math),
                "序号" to context.getString(R.string.symbol_category_numbering),
                "单位" to context.getString(R.string.symbol_category_units),
                "特殊" to context.getString(R.string.symbol_category_special),
                "编程" to context.getString(R.string.symbol_category_programming),
                "自定义" to context.getString(R.string.symbol_category_custom),
            )
            val tabs = panelChipScroll(
                categoryLabels.values.toList(),
                categoryLabels.getValue(symbolCategory),
            ) { label ->
                val category = categoryLabels.entries.first { it.value == label }.key
                if (category != symbolCategory) {
                    symbolCategory = category
                    renderContent(true)
                }
            }
'''
text = replace_once(text, old_symbols, new_symbols, 'symbol category labels')
text = replace_once(
    text,
    '                body.addView(button("管理自定义符号", 12f, true).apply {\n                    contentDescription = "管理自定义符号"\n',
    '                body.addView(button(context.getString(R.string.symbol_manage_custom), 12f, true).apply {\n                    contentDescription = context.getString(R.string.symbol_manage_custom)\n',
    'custom symbol manager label',
)

# Emoji category display labels while retaining canonical data keys.
old_emoji = '''            val cats = listOf("最近") + ImeData.fluentSmileysByCategory.keys.toList()
            val tabs = panelChipScroll(cats, emojiCategory) { cat ->
                if (cat != emojiCategory) {
                    emojiCategory = cat
                    renderContent(true)
                }
            }
'''
new_emoji = '''            val categoryLabels = linkedMapOf(
                "最近" to context.getString(R.string.emoji_recent),
                "笑脸" to context.getString(R.string.emoji_category_smileys),
                "亲昵" to context.getString(R.string.emoji_category_affection),
                "中性" to context.getString(R.string.emoji_category_neutral),
                "困倦/不适" to context.getString(R.string.emoji_category_sleepy_unwell),
                "担忧" to context.getString(R.string.emoji_category_worried),
                "怪诞" to context.getString(R.string.emoji_category_weird),
                "爱心" to context.getString(R.string.emoji_category_hearts),
                "情绪符号" to context.getString(R.string.emoji_category_emotion_symbols),
            )
            val canonicalCategories = listOf("最近") + ImeData.fluentSmileysByCategory.keys.toList()
            val tabs = panelChipScroll(
                canonicalCategories.map { categoryLabels[it] ?: it },
                categoryLabels[emojiCategory] ?: emojiCategory,
            ) { label ->
                val category = categoryLabels.entries.firstOrNull { it.value == label }?.key ?: label
                if (category != emojiCategory) {
                    emojiCategory = category
                    renderContent(true)
                }
            }
'''
text = replace_once(text, old_emoji, new_emoji, 'emoji category labels')
text = replace_once(
    text,
    '                    text = "最近使用的表情会显示在这里"\n',
    '                    text = context.getString(R.string.emoji_recent_empty)\n',
    'emoji recent empty',
)

# Handwriting panel strings (panel remains capability-gated by the production wrapper).
for old, new, label in [
    ('        candRow.addView(title("在下方区域落笔手写...", small = true), wrapParams())\n', '        candRow.addView(title(context.getString(R.string.handwriting_start_hint), small = true), wrapParams())\n', 'handwriting hint'),
    ('                candRow.addView(title("当前未配置手写识别引擎", small = true), wrapParams())\n', '                candRow.addView(title(context.getString(R.string.handwriting_engine_unavailable), small = true), wrapParams())\n', 'handwriting unavailable'),
    ('        actions.addView(key("撤销", true, null, 1f, 13f) { pad.undo() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })\n', '        actions.addView(key(context.getString(R.string.action_undo), true, null, 1f, 13f) { pad.undo() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })\n', 'handwriting undo'),
    ('        actions.addView(key("清空", true, null, 1f, 13f) { pad.clear() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })\n', '        actions.addView(key(context.getString(R.string.action_clear), true, null, 1f, 13f) { pad.clear() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })\n', 'handwriting clear'),
    ('        actions.addView(key("空格", true, null, 1f, 13f) { listener.onSpace() }, LinearLayout.LayoutParams(0, dp(44), 1f))\n', '        actions.addView(key(context.getString(R.string.key_space_label), true, null, 1f, 13f) { listener.onSpace() }, LinearLayout.LayoutParams(0, dp(44), 1f))\n', 'handwriting space'),
]:
    text = replace_once(text, old, new, label)

# Voice panel static and dynamic status text.
voice_replacements = {
    '"离线模型已就绪 · 音频不出设备"': 'context.getString(R.string.voice_model_ready_private)',
    '"离线模型后台准备中 · 未启用联网识别"': 'context.getString(R.string.voice_model_preparing_private)',
    '"只需长按空格；松开自动上屏，上滑取消"': 'context.getString(R.string.voice_hold_space_instruction)',
    '"普通话" to "zh-CN"': 'context.getString(R.string.voice_language_mandarin) to "zh-CN"',
    'contentDescription = "语音状态，仅支持长按空格启动"': 'contentDescription = context.getString(R.string.voice_status_hold_space_only)',
    'button("长按空格开始", 13f, true)': 'button(context.getString(R.string.voice_start_hold_space), 13f, true)',
    'contentDescription = "长按空格开始语音，松开自动上屏，上滑取消"': 'contentDescription = context.getString(R.string.voice_start_hold_space_description)',
    'showInlineVoiceState("正在准备麦克风…")': 'showInlineVoiceState(context.getString(R.string.voice_preparing_microphone))',
    'gestureHint.text = "松开空格上屏 · 上滑取消"': 'gestureHint.text = context.getString(R.string.voice_release_or_cancel)',
    'modelStatus.text = "正在使用离线模型 · 音频不出设备"': 'modelStatus.text = context.getString(R.string.voice_model_active_private)',
    'transcript.text = "正在聆听… 松开空格结束"': 'transcript.text = context.getString(R.string.voice_listening_release)',
    'modelStatus.text = "正在聆听 · 松开空格结束"': 'modelStatus.text = context.getString(R.string.voice_listening_release_compact)',
    'text.ifBlank { "正在聆听…" }': 'text.ifBlank { context.getString(R.string.voice_listening) }',
    'gestureHint.text = "长按空格开始"': 'gestureHint.text = context.getString(R.string.voice_start_hold_space)',
    'modelStatus.text = "离线识别完成 · 已自动上屏"': 'modelStatus.text = context.getString(R.string.voice_recognition_complete)',
    'if (text.isBlank()) "没有识别到语音" else "已上屏"': 'if (text.isBlank()) context.getString(R.string.voice_no_speech) else context.getString(R.string.voice_committed)',
    'if (modelPrepared) "正在聆听…" else "正在录音 · 模型准备中…"': 'if (modelPrepared) context.getString(R.string.voice_listening) else context.getString(R.string.voice_recording_model_preparing)',
    'modelStatus.text = "语音未完成 · 请检查本地模型和麦克风权限"': 'modelStatus.text = context.getString(R.string.voice_failed_check_model_permission)',
    'message.ifBlank { "语音输入失败" }': 'message.ifBlank { context.getString(R.string.voice_failed) }',
    'modelStatus.text = "正在录音 · 本地模型准备中"': 'modelStatus.text = context.getString(R.string.voice_recording_model_preparing_compact)',
    'showInlineVoiceState("正在录音 · 模型准备中…")': 'showInlineVoiceState(context.getString(R.string.voice_recording_model_preparing))',
    'gestureHint.text = "整理识别结果…"': 'gestureHint.text = context.getString(R.string.voice_organizing_result)',
    'modelStatus.text = "正在整理识别结果…"': 'modelStatus.text = context.getString(R.string.voice_organizing_result)',
    'showInlineVoiceState("正在识别…")': 'showInlineVoiceState(context.getString(R.string.voice_recognizing))',
    'modelStatus.text = "正在识别 · 松开空格结束"': 'modelStatus.text = context.getString(R.string.voice_recognizing_release)',
    'transcript.text = "已取消语音输入"': 'transcript.text = context.getString(R.string.voice_cancelled)',
    'modelStatus.text = "语音已取消 · 音频未保存"': 'modelStatus.text = context.getString(R.string.voice_cancelled_private)',
    'showInlineVoiceState("已取消")': 'showInlineVoiceState(context.getString(R.string.voice_cancelled_short))',
    'transcript.text = "上滑取消 · 松开丢弃本次语音"': 'transcript.text = context.getString(R.string.voice_swipe_cancel_release_discard)',
    'modelStatus.text = "取消状态 · 松开将丢弃"': 'modelStatus.text = context.getString(R.string.voice_cancel_state_release_discard)',
    'showInlineVoiceState("松开取消", cancelling = true)': 'showInlineVoiceState(context.getString(R.string.voice_release_to_cancel), cancelling = true)',
    'recognizedText.ifBlank { "正在聆听… 松开空格结束" }': 'recognizedText.ifBlank { context.getString(R.string.voice_listening_release) }',
    'recognizedText.ifBlank { "正在聆听…" }': 'recognizedText.ifBlank { context.getString(R.string.voice_listening) }',
}
for old, new in voice_replacements.items():
    text = text.replace(old, new)

# Clipboard display labels, keeping tab state numeric rather than localized-text based.
text = replace_once(
    text,
    '            text = if (entry.pinned) "已置顶" else android.text.format.DateUtils.getRelativeTimeSpanString(\n',
    '            text = if (entry.pinned) context.getString(R.string.clipboard_pinned) else android.text.format.DateUtils.getRelativeTimeSpanString(\n',
    'clipboard pinned',
)
text = replace_once(
    text,
    '        meta.addView(button(if (entry.pinned) "取消置顶" else "置顶", 10f, true).apply {\n',
    '        meta.addView(button(if (entry.pinned) context.getString(R.string.clipboard_unpin) else context.getString(R.string.clipboard_pin), 10f, true).apply {\n',
    'clipboard pin button',
)
text = replace_once(text, '        meta.addView(button("使用", 10f, true).apply {\n', '        meta.addView(button(context.getString(R.string.action_use), 10f, true).apply {\n', 'clipboard use')
old_tabs = '''        val tabs = panelChipScroll(listOf("剪贴板", "常用语"), if (clipboardTab == 0) "剪贴板" else "常用语") { label ->
            clipboardTab = if (label == "剪贴板") 0 else 1
            renderClipboard(reusePanel = true)
        }
'''
new_tabs = '''        val clipboardLabel = context.getString(R.string.panel_clipboard)
        val quickPhraseLabel = context.getString(R.string.clipboard_quick_phrases)
        val tabs = panelChipScroll(
            listOf(clipboardLabel, quickPhraseLabel),
            if (clipboardTab == 0) clipboardLabel else quickPhraseLabel,
        ) { label ->
            clipboardTab = if (label == clipboardLabel) 0 else 1
            renderClipboard(reusePanel = true)
        }
'''
text = replace_once(text, old_tabs, new_tabs, 'clipboard tabs')
for old, new, label in [
    ('            col.addView(sectionTitle("最近复制"), wrapParams())\n', '            col.addView(sectionTitle(context.getString(R.string.clipboard_recent_copied)), wrapParams())\n', 'clipboard section'),
    ('                text = "正在读取剪贴板…"\n', '                text = context.getString(R.string.clipboard_loading)\n', 'clipboard loading'),
    ('                            text = "暂无剪贴历史；复制文本后重新打开这里即可看到。"\n', '                            text = context.getString(R.string.clipboard_empty)\n', 'clipboard empty'),
    ('            col.addView(button("新增常用语", 13f, true).apply {\n', '            col.addView(button(context.getString(R.string.quick_phrase_add), 13f, true).apply {\n', 'quick phrase add'),
    ('                        row.addView(button("编辑", 11f, true).apply {\n', '                        row.addView(button(context.getString(R.string.quick_phrase_edit), 11f, true).apply {\n', 'quick phrase edit'),
    ('                        row.addView(button("删除", 11f, true).apply {\n', '                        row.addView(button(context.getString(R.string.quick_phrase_delete), 11f, true).apply {\n', 'quick phrase delete'),
]:
    text = replace_once(text, old, new, label)

# Text editor.
old_quick = '        listOf("全选" to "select-all", "复制" to "copy", "剪切" to "cut", "粘贴" to "paste", "撤销" to "undo")\n'
new_quick = '''        listOf(
            context.getString(R.string.action_select_all) to "select-all",
            context.getString(R.string.action_copy) to "copy",
            context.getString(R.string.action_cut) to "cut",
            context.getString(R.string.action_paste) to "paste",
            context.getString(R.string.action_undo) to "undo",
        )
'''
text = replace_once(text, old_quick, new_quick, 'text editor quick actions')
text = replace_once(text, '                if (center) text = "光标"\n', '                if (center) text = context.getString(R.string.text_cursor)\n', 'cursor label')

# Settings appearance display labels and localized labels used by toggle logic.
appearance_marker = '    private fun renderSettings(reusePanel: Boolean = false) {\n'
appearance_helper = '''    private fun appearanceLabel(value: ImeAppearance): String = when (value) {
        ImeAppearance.SYSTEM -> context.getString(R.string.appearance_system)
        ImeAppearance.LIGHT -> context.getString(R.string.appearance_light)
        ImeAppearance.DARK -> context.getString(R.string.appearance_dark)
    }

'''
if appearance_helper not in text:
    text = text.replace(appearance_marker, appearance_helper + appearance_marker, 1)
text = replace_once(text, '        content.addView(sectionTitle("外观"), wrapParams())\n', '        content.addView(sectionTitle(context.getString(R.string.settings_section_appearance)), wrapParams())\n', 'settings appearance section')
old_appearance = '''        content.addView(panelChipScroll(ImeAppearance.entries.map { it.label }, appearance.label) { label ->
            appearance = ImeAppearance.entries.first { it.label == label }
            setAppearance(appearance)
            listener.onAppearanceChanged(appearance)
            renderSettings(reusePanel = true)
        }, LinearLayout.LayoutParams(
'''
new_appearance = '''        val appearanceLabels = ImeAppearance.entries.associateWith(::appearanceLabel)
        content.addView(panelChipScroll(appearanceLabels.values.toList(), appearanceLabels.getValue(appearance)) { label ->
            appearance = appearanceLabels.entries.first { it.value == label }.key
            setAppearance(appearance)
            listener.onAppearanceChanged(appearance)
            renderSettings(reusePanel = true)
        }, LinearLayout.LayoutParams(
'''
text = replace_once(text, old_appearance, new_appearance, 'appearance labels')
for old, new, label in [
    ('        content.addView(sectionTitle("强调色"), wrapParams())\n', '        content.addView(sectionTitle(context.getString(R.string.settings_accent_title)), wrapParams())\n', 'accent section'),
    ('        content.addView(sectionTitle("按键皮肤"), wrapParams())\n', '        content.addView(sectionTitle(context.getString(R.string.settings_section_key_skin)), wrapParams())\n', 'key skin section'),
    ('                settingsSlider("圆角", 0, 24, skinRadius) { v ->\n', '                settingsSlider(context.getString(R.string.settings_corner_radius), 0, 24, skinRadius) { v ->\n', 'corner slider'),
    ('                settingsSlider("不透明度", 70, 100, skinOpacity) { v ->\n', '                settingsSlider(context.getString(R.string.settings_opacity), 70, 100, skinOpacity) { v ->\n', 'opacity slider'),
    ('                settingsSlider("按键字号", 14, 22, skinFontSize) { v ->\n', '                settingsSlider(context.getString(R.string.settings_key_font_size), 14, 22, skinFontSize) { v ->\n', 'font slider'),
    ('        content.addView(sectionTitle("按键与输入"), wrapParams())\n', '        content.addView(sectionTitle(context.getString(R.string.settings_section_keys_input)), wrapParams())\n', 'keys section'),
    ('                settingToggleRow("按键音效", "机械轴敲击反馈"),\n', '                settingToggleRow(context.getString(R.string.settings_key_sound), context.getString(R.string.settings_key_sound_desc)),\n', 'sound row'),
    ('                settingToggleRow("触感震动", "轻微触感反馈"),\n', '                settingToggleRow(context.getString(R.string.settings_haptic), context.getString(R.string.settings_haptic_desc)),\n', 'haptic row'),
    ('                settingToggleRow("按键气泡", "可选字母预览，默认仅按键变色"),\n', '                settingToggleRow(context.getString(R.string.settings_key_popup), context.getString(R.string.settings_key_popup_desc)),\n', 'popup row'),
    ('        content.addView(sectionTitle("智能输入"), wrapParams())\n', '        content.addView(sectionTitle(context.getString(R.string.settings_section_smart_input)), wrapParams())\n', 'smart input section'),
    ('                    "模糊音与智能纠错",\n                    "进入后配置 z/zh、c/ch、s/sh 等规则",\n', '                    context.getString(R.string.settings_fuzzy_navigation),\n                    context.getString(R.string.settings_fuzzy_navigation_desc),\n', 'fuzzy nav row'),
]:
    text = replace_once(text, old, new, label)

# Labels are localized, so all label-driven setting dispatch must compare localized values too.
icon_old = '''        text = when (label) {
            "按键音效" -> "◖"
            "触感震动" -> "✦"
            "按键气泡" -> "A"
            "模糊音与智能纠错", "启用模糊音" -> "✧"
            "外观与键盘高度" -> "◐"
            else -> "•"
        }
'''
icon_new = '''        text = when (label) {
            context.getString(R.string.settings_key_sound) -> "◖"
            context.getString(R.string.settings_haptic) -> "✦"
            context.getString(R.string.settings_key_popup) -> "A"
            context.getString(R.string.settings_fuzzy_navigation), context.getString(R.string.fuzzy_enable) -> "✧"
            else -> "•"
        }
'''
text = replace_once(text, icon_old, icon_new, 'setting icon dispatch')

# Fuzzy panel text.
for old, new, label in [
    ('            text = "用于处理常见的近音输入。开启后，候选会同时尝试相近声母，不会改变用户已经输入的拼音。"\n', '            text = context.getString(R.string.fuzzy_note)\n', 'fuzzy note'),
    ('            settingGroup(settingToggleRow("启用模糊音", "z/zh · c/ch · s/sh · l/n")),\n', '            settingGroup(settingToggleRow(context.getString(R.string.fuzzy_enable), context.getString(R.string.fuzzy_enable_desc))),\n', 'fuzzy toggle'),
    ('        content.addView(sectionTitle("当前规则"), wrapParams())\n', '        content.addView(sectionTitle(context.getString(R.string.fuzzy_current_rules)), wrapParams())\n', 'fuzzy current rules'),
    ('            text = "z / zh · c / ch · s / sh · l / n · en / eng · in / ing"\n', '            text = context.getString(R.string.fuzzy_rule_list)\n', 'fuzzy rules list'),
    ('            text = "规则由输入法自动参与候选计算，暂不单独修改每一组映射。"\n', '            text = context.getString(R.string.fuzzy_rule_info)\n', 'fuzzy rule info'),
]:
    text = replace_once(text, old, new, label)

# Toggle state/callback dispatch.
toggle_start = text.index('    private fun toggle(seed: String): View {\n')
onstate_start = text.index('    private fun onState(seed: String): Boolean', toggle_start)
new_toggle = '''    private fun toggle(seed: String): View {
        val isOn = onState(seed)
        val knob = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                gravity = if (isOn) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
            }
            background = rounded(Color.WHITE, dp(99))
        }
        return FrameLayout(context).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            minimumWidth = dp(48)
            minimumHeight = dp(26)
            contentDescription = seed
            tag = "toggle"
            addView(knob)
            setOnClickListener {
                feedback()
                val next = !onState(seed)
                toggleCallback(seed)?.invoke(next)
                (getChildAt(0)).layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                    gravity = if (next) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
                }
                applyTheme()
            }
        }
    }

'''
text = text[:toggle_start] + new_toggle + text[onstate_start:]
onstate_old = '''    private fun onState(seed: String): Boolean = when (seed) {
        "按键音效" -> soundEnabled
        "触感震动" -> hapticEnabled
        "模糊音纠错", "启用模糊音" -> fuzzyEnabled
        "按键气泡" -> popupEnabled
        else -> true
    }

    private fun toggleCallback(seed: String): ((Boolean) -> Unit)? = when (seed) {
        "按键音效" -> { { soundEnabled = it; listener.onSoundChanged(it) } }
        "触感震动" -> { { hapticEnabled = it; listener.onHapticChanged(it) } }
        "模糊音纠错", "启用模糊音" -> { { fuzzyEnabled = it; listener.onFuzzyChanged(it) } }
        "按键气泡" -> { { popupEnabled = it; listener.onPopupChanged(it) } }
        else -> null
    }
'''
onstate_new = '''    private fun onState(seed: String): Boolean = when (seed) {
        context.getString(R.string.settings_key_sound) -> soundEnabled
        context.getString(R.string.settings_haptic) -> hapticEnabled
        context.getString(R.string.settings_fuzzy_navigation), context.getString(R.string.fuzzy_enable) -> fuzzyEnabled
        context.getString(R.string.settings_key_popup) -> popupEnabled
        else -> true
    }

    private fun toggleCallback(seed: String): ((Boolean) -> Unit)? = when (seed) {
        context.getString(R.string.settings_key_sound) -> { { soundEnabled = it; listener.onSoundChanged(it) } }
        context.getString(R.string.settings_haptic) -> { { hapticEnabled = it; listener.onHapticChanged(it) } }
        context.getString(R.string.settings_fuzzy_navigation), context.getString(R.string.fuzzy_enable) -> { { fuzzyEnabled = it; listener.onFuzzyChanged(it) } }
        context.getString(R.string.settings_key_popup) -> { { popupEnabled = it; listener.onPopupChanged(it) } }
        else -> null
    }
'''
text = replace_once(text, onstate_old, onstate_new, 'toggle dispatch')

# Accent custom control/dialog.
for old, new, label in [
    ('                text = "自定义"\n', '                text = context.getString(R.string.settings_accent_custom)\n', 'accent custom label'),
    ('                contentDescription = "自定义强调色"\n', '                contentDescription = context.getString(R.string.settings_custom_accent_description)\n', 'accent custom description'),
    ('            .setTitle("自定义强调色")\n', '            .setTitle(context.getString(R.string.settings_custom_accent_description))\n', 'accent dialog title'),
    ('            .setMessage("输入 6 位十六进制颜色，例如 5B6B7A")\n', '            .setMessage(context.getString(R.string.settings_accent_hint))\n', 'accent dialog message'),
    ('            .setPositiveButton("应用") { _, _ -> applyAccentColor(field.text.toString()) }\n', '            .setPositiveButton(context.getString(R.string.action_apply)) { _, _ -> applyAccentColor(field.text.toString()) }\n', 'accent apply'),
    ('            .setNegativeButton("取消", null)\n', '            .setNegativeButton(context.getString(R.string.action_cancel), null)\n', 'accent cancel'),
]:
    text = replace_once(text, old, new, label)

path.write_text(text)
