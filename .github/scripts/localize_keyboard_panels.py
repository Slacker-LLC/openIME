from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


path = Path("app/src/main/java/llc/slacker/openime/ImeKeyboardView.kt")
text = path.read_text()

text = replace_once(
    text,
    '                contentDescription = "返回键盘"\n',
    '                contentDescription = context.getString(R.string.panel_back_to_keyboard)\n',
    'panel back accessibility',
)
text = replace_once(
    text,
    '        addPanelHead("切换键盘")\n',
    '        addPanelHead(context.getString(R.string.panel_keyboard_select))\n',
    'keyboard selector head',
)
text = replace_once(
    text,
    '            text = "选择输入布局"\n',
    '            text = context.getString(R.string.keyboard_select_title)\n',
    'keyboard selector title',
)
old_modes = '''        val modes = listOf(
            KeyboardMode.PINYIN_26 to "拼音 26 键",
            KeyboardMode.PINYIN_9 to "拼音 9 键",
            KeyboardMode.ENGLISH_26 to "英文 26 键",
            KeyboardMode.DIGITS to "数字键盘",
        )
'''
new_modes = '''        val modes = listOf(
            KeyboardMode.PINYIN_26 to context.getString(R.string.keyboard_mode_pinyin_26),
            KeyboardMode.PINYIN_9 to context.getString(R.string.keyboard_mode_pinyin_9),
            KeyboardMode.ENGLISH_26 to context.getString(R.string.keyboard_mode_english_26),
            KeyboardMode.DIGITS to context.getString(R.string.keyboard_mode_digits),
        )
'''
text = replace_once(text, old_modes, new_modes, 'keyboard selector modes')
text = replace_once(
    text,
    '                        contentDescription = modeValue.name\n',
    '                        contentDescription = label\n',
    'keyboard selector accessibility',
)

text = replace_once(
    text,
    '        addPanelHead("游戏键盘")\n',
    '        addPanelHead(context.getString(R.string.panel_gaming))\n',
    'gaming head',
)
text = replace_once(
    text,
    '        val macros = listOf("收到！", "集合进攻！", "稳住能赢！", "请求集合！", "保护输出！")\n',
    '''        val macros = listOf(
            context.getString(R.string.gaming_macro_received),
            context.getString(R.string.gaming_macro_attack),
            context.getString(R.string.gaming_macro_hold),
            context.getString(R.string.gaming_macro_regroup),
            context.getString(R.string.gaming_macro_protect),
        )
''',
    'gaming macros',
)
text = replace_once(
    text,
    '            text = "⠿  拖动键盘"\n',
    '            text = context.getString(R.string.gaming_drag_keyboard_label)\n',
    'gaming drag label',
)
text = replace_once(
    text,
    '            contentDescription = "拖动键盘"\n',
    '            contentDescription = context.getString(R.string.gaming_drag_keyboard)\n',
    'gaming drag accessibility',
)
old_toggle = '''        val floatingToggle = button(if (floatingKeyboard) "贴底固定" else "恢复浮动", 11f, true).apply {
            contentDescription = if (floatingKeyboard) "贴底固定" else "恢复浮动"
            setOnClickListener { view ->
                floatingKeyboard = !floatingKeyboard
                listener.onFloatingKeyboardChanged(floatingKeyboard)
                val label = if (floatingKeyboard) "贴底固定" else "恢复浮动"
                (view as TextView).text = label
                view.contentDescription = label
            }
        }
'''
new_toggle = '''        fun floatingLabel(): String = if (floatingKeyboard) {
            context.getString(R.string.gaming_dock_bottom)
        } else {
            context.getString(R.string.gaming_restore_float)
        }
        val floatingToggle = button(floatingLabel(), 11f, true).apply {
            contentDescription = floatingLabel()
            setOnClickListener { view ->
                floatingKeyboard = !floatingKeyboard
                listener.onFloatingKeyboardChanged(floatingKeyboard)
                val label = floatingLabel()
                (view as TextView).text = label
                view.contentDescription = label
            }
        }
'''
text = replace_once(text, old_toggle, new_toggle, 'gaming floating toggle')
text = replace_once(
    text,
    '                    key("空格", true, null, 1.2f, 11f) { listener.onSpace() }.apply {\n',
    '                    key(context.getString(R.string.key_space_label), true, null, 1.2f, 11f) { listener.onSpace() }.apply {\n',
    'gaming space label',
)

path.write_text(text)
