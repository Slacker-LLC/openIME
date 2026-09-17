from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)


def replace_at_least_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count < 1:
        raise SystemExit(f"{label}: expected at least 1 match, got 0")
    return text.replace(old, new)

view_path = Path('app/src/main/java/llc/slacker/openime/ImeKeyboardView.kt')
text = view_path.read_text()

# Restore the product-specified low-latency voice hold threshold.
old_threshold = '''    // Voice arms at the platform long-press threshold instead of a hard-coded
    // 150 ms, so a deliberate-but-brief space press no longer opens the mic.
    private val spaceVoiceTriggerMs = ViewConfiguration.getLongPressTimeout().toLong()
'''
new_threshold = '''    // Voice long-press intentionally arms at 150 ms for low-latency dictation.
    private val spaceVoiceTriggerMs = 150L
'''
text = replace_once(text, old_threshold, new_threshold, 'voice threshold')

# Expose all bundled theme token sets instead of carrying unreachable enum values.
needle = '        content.addView(sectionTitle(context.getString(R.string.settings_section_appearance)), wrapParams())\n'
insert = '''        content.addView(sectionTitle(context.getString(R.string.settings_section_theme)), wrapParams())
        val themeLabels = linkedMapOf(
            ImeTheme.IOS to context.getString(R.string.theme_ios),
            ImeTheme.DARK to context.getString(R.string.theme_dark),
            ImeTheme.CYBERPUNK to context.getString(R.string.theme_cyberpunk),
            ImeTheme.CLASSIC to context.getString(R.string.theme_classic),
            ImeTheme.MACOS to context.getString(R.string.theme_macos),
        )
        content.addView(
            panelChipScroll(themeLabels.values.toList(), themeLabels.getValue(theme)) { label ->
                val selectedTheme = themeLabels.entries.first { it.value == label }.key
                if (selectedTheme != theme) {
                    setTheme(selectedTheme)
                    renderSettings(reusePanel = true)
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44),
            ).apply { bottomMargin = dp(12) },
        )
        content.addView(sectionTitle(context.getString(R.string.settings_section_appearance)), wrapParams())
'''
text = replace_once(text, needle, insert, 'settings theme insertion')

# Toolbar / composition / candidate accessibility strings.
for old, new, label in [
    ('toolbarIcon(R.drawable.ic_grid, "切换键盘", "keyboard-selector")', 'toolbarIcon(R.drawable.ic_grid, context.getString(R.string.tool_switch_keyboard), "keyboard-selector")', 'toolbar keyboard'),
    ('toolbarIcon(R.drawable.ic_clipboard, "剪贴板", "toolbar")', 'toolbarIcon(R.drawable.ic_clipboard, context.getString(R.string.tool_clipboard), "toolbar")', 'toolbar clipboard'),
    ('toolbarIcon(R.drawable.ic_emoji, "Emoji", "toolbar")', 'toolbarIcon(R.drawable.ic_emoji, context.getString(R.string.tool_emoji), "toolbar")', 'toolbar emoji'),
    ('toolbarIcon(R.drawable.ic_symbols, "符号", "toolbar")', 'toolbarIcon(R.drawable.ic_symbols, context.getString(R.string.tool_symbols), "toolbar")', 'toolbar symbols'),
    ('toolbarIcon(R.drawable.ic_more, "更多", "toolbar")', 'toolbarIcon(R.drawable.ic_more, context.getString(R.string.toolbar_more), "toolbar")', 'toolbar more'),
    ('contentDescription = "可编辑拼音预编辑"', 'contentDescription = context.getString(R.string.composition_edit_description)', 'composition description'),
    ('contentDescription = "表情"', 'contentDescription = context.getString(R.string.panel_emoji)', 'candidate emoji description'),
    ('contentDescription = "展开更多候选"', 'contentDescription = context.getString(R.string.candidate_expand_more)', 'candidate expand description'),
    ('text = "正在聆听…"', 'text = context.getString(R.string.voice_listening)', 'voice inline initial status'),
    ('contentDescription = "联想:$candidate"', 'contentDescription = context.getString(R.string.association_description, candidate)', 'association description'),
    ('row.contentDescription = "候选:$cand"', 'row.contentDescription = context.getString(R.string.candidate_description, cand)', 'candidate row description'),
    ('contentDescription = "强调色$label"', 'contentDescription = context.getString(R.string.accent_color_description, label)', 'accent description'),
    ('key("发送", true, null, 1.6f, 12f)', 'key(context.getString(R.string.action_send), true, null, 1.6f, 12f)', 'gaming send'),
]:
    text = replace_once(text, old, new, label)

text = replace_at_least_once(
    text,
    'contentDescription = "候选:$cand"',
    'contentDescription = context.getString(R.string.candidate_description, cand)',
    'expanded candidate descriptions',
)
text = replace_at_least_once(
    text,
    'showInlineVoiceState("正在聆听…")',
    'showInlineVoiceState(context.getString(R.string.voice_listening))',
    'voice listening inline status',
)

# Shift / candidate overlay / generic key accessibility.
old_shift = '''            contentDescription = when (shiftState) {
                ShiftState.LOWERCASE -> "大写"
                ShiftState.SHIFT_ONCE -> "大写一次"
                ShiftState.CAPS_LOCK -> "大写锁定"
            }
'''
new_shift = '''            contentDescription = when (shiftState) {
                ShiftState.LOWERCASE -> context.getString(R.string.shift_uppercase)
                ShiftState.SHIFT_ONCE -> context.getString(R.string.shift_once)
                ShiftState.CAPS_LOCK -> context.getString(R.string.shift_caps_lock)
            }
'''
text = replace_once(text, old_shift, new_shift, 'shift descriptions')
text = replace_at_least_once(text, 'candidateExpandBtn.contentDescription = "展开更多候选"', 'candidateExpandBtn.contentDescription = context.getString(R.string.candidate_expand_more)', 'candidate expand')
text = replace_once(text, 'candidateExpandBtn.contentDescription = "收起候选"', 'candidateExpandBtn.contentDescription = context.getString(R.string.candidate_collapse)', 'candidate collapse')
text = replace_once(text, 'panelHead("候选字词")', 'panelHead(context.getString(R.string.candidate_list_title))', 'candidate panel title')
text = replace_once(text, 'title("暂无候选", small = true)', 'title(context.getString(R.string.candidate_empty), small = true)', 'candidate empty')
text = replace_once(
    text,
    'contentDescription = if (text.isNotEmpty()) text else if (iconRes != 0) "功能键" else " "',
    'contentDescription = if (text.isNotEmpty()) text else if (iconRes != 0) context.getString(R.string.function_key_description) else " "',
    'function key description',
)

# Backspace gestures / long-press choice accessibility.
text = replace_once(text, 'showPopup(it, "清空")', 'showPopup(it, context.getString(R.string.backspace_clear))', 'backspace clear popup')
text = replace_once(text, 'showPopup(it, "删词")', 'showPopup(it, context.getString(R.string.backspace_delete_word))', 'backspace delete-word popup')
text = replace_once(text, 'contentDescription = "删除，向左滑删词，向上滑清空"', 'contentDescription = context.getString(R.string.backspace_gesture_description)', 'backspace description')
text = replace_at_least_once(text, 'text = "↑ 清空"', 'text = context.getString(R.string.backspace_clear_hint)', 'backspace clear hint text')
text = replace_once(text, 'clearHint.text = "清空"', 'clearHint.text = context.getString(R.string.backspace_clear)', 'active backspace clear hint')
text = replace_at_least_once(text, 'clearHint.text = "↑ 清空"', 'clearHint.text = context.getString(R.string.backspace_clear_hint)', 'inactive backspace clear hint')
text = replace_once(text, 'contentDescription = "长按符号选择"', 'contentDescription = context.getString(R.string.long_press_symbol_selection)', 'choice popup description')
text = replace_once(text, 'contentDescription = "输入$symbol"', 'contentDescription = context.getString(R.string.input_symbol_description, symbol)', 'symbol choice description')

# Localize visible Enter labels while keeping EnterActionPolicy as the behavior authority.
old_enter = '''    private fun enterKeyLabel(english: Boolean, fallback: String? = null): String {
        val imeOptions = (context as? android.inputmethodservice.InputMethodService)
            ?.currentInputEditorInfo?.imeOptions
        if (imeOptions != null) return enterKeyPresentationFor(imeOptions).label
        return fallback ?: if (english) "Go" else "确定"
    }
'''
new_enter = '''    internal fun localizedEnterKeyLabel(imeOptions: Int): String {
        if ((imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
            return context.getString(R.string.enter_newline)
        }
        return when (imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION) {
            android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> context.getString(R.string.enter_send)
            android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> context.getString(R.string.enter_search)
            android.view.inputmethod.EditorInfo.IME_ACTION_GO -> context.getString(R.string.enter_go)
            android.view.inputmethod.EditorInfo.IME_ACTION_NEXT -> context.getString(R.string.enter_next)
            android.view.inputmethod.EditorInfo.IME_ACTION_PREVIOUS -> context.getString(R.string.enter_previous)
            android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> context.getString(R.string.enter_done)
            else -> context.getString(R.string.enter_return)
        }
    }

    private fun enterKeyLabel(english: Boolean, fallback: String? = null): String {
        val imeOptions = (context as? android.inputmethodservice.InputMethodService)
            ?.currentInputEditorInfo?.imeOptions
        if (imeOptions != null) return localizedEnterKeyLabel(imeOptions)
        return fallback ?: if (english) context.getString(R.string.enter_go) else context.getString(R.string.enter_confirm)
    }
'''
text = replace_once(text, old_enter, new_enter, 'localized enter labels')

view_path.write_text(text)

# V2 production post-pass must use the same localized Enter contract.
v2_path = Path('app/src/main/java/llc/slacker/openime/ImeKeyboardViewV2.kt')
v2 = v2_path.read_text()
v2 = replace_once(v2, '        val label = enterKeyPresentationFor(imeOptions).label\n', '        val label = localizedEnterKeyLabel(imeOptions)\n', 'V2 enter label')
old_enter_labels = '''        val enterLabels = setOf(
            "发送", "搜索", "前往", "下一项", "上一项", "完成", "换行", "回车", "确定", "Go",
        )
'''
new_enter_labels = '''        val enterLabels = setOf(
            context.getString(R.string.enter_send),
            context.getString(R.string.enter_search),
            context.getString(R.string.enter_go),
            context.getString(R.string.enter_next),
            context.getString(R.string.enter_previous),
            context.getString(R.string.enter_done),
            context.getString(R.string.enter_newline),
            context.getString(R.string.enter_return),
            context.getString(R.string.enter_confirm),
        )
'''
v2 = replace_once(v2, old_enter_labels, new_enter_labels, 'V2 enter label set')
v2_path.write_text(v2)

# Update the stale design-token documentation now that themes are reachable.
tokens_path = Path('app/src/main/java/llc/slacker/openime/ImeDesignTokens.kt')
tokens = tokens_path.read_text()
old = ''' * runtime. The legacy theme enum values stay available for state/API
 * compatibility, but the product intentionally exposes only this skin.
'''
new = ''' * runtime. The bundled theme enum values remain state/API compatible and are
 * also exposed through the keyboard settings theme selector.
'''
tokens = replace_once(tokens, old, new, 'theme enum comment')
tokens_path.write_text(tokens)
