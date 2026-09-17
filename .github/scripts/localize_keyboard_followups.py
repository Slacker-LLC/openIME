from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


view_path = Path("app/src/main/java/llc/slacker/openime/ImeKeyboardView.kt")
view = view_path.read_text()
for old, new, label in [
    ('            ToolEntry("表情", Panel.EMOJI, R.drawable.ic_emoji),\n', '            ToolEntry(context.getString(R.string.tool_emoji), Panel.EMOJI, R.drawable.ic_emoji),\n', 'emoji tool'),
    ('            ToolEntry("剪贴板", Panel.CLIPBOARD, R.drawable.ic_clipboard),\n', '            ToolEntry(context.getString(R.string.tool_clipboard), Panel.CLIPBOARD, R.drawable.ic_clipboard),\n', 'clipboard tool'),
    ('            ToolEntry("手写输入", Panel.HANDWRITING, R.drawable.ic_handwriting, enabled = handwritingAvailable),\n', '            ToolEntry(context.getString(R.string.tool_handwriting), Panel.HANDWRITING, R.drawable.ic_handwriting, enabled = handwritingAvailable),\n', 'handwriting tool'),
    ('            ToolEntry("符号", Panel.SYMBOLS, R.drawable.ic_symbols),\n', '            ToolEntry(context.getString(R.string.tool_symbols), Panel.SYMBOLS, R.drawable.ic_symbols),\n', 'symbols tool'),
    ('            ToolEntry("切换键盘", Panel.KEYBOARD_SELECT, R.drawable.ic_grid),\n', '            ToolEntry(context.getString(R.string.tool_switch_keyboard), Panel.KEYBOARD_SELECT, R.drawable.ic_grid),\n', 'switch keyboard tool'),
]:
    view = replace_once(view, old, new, label)
view_path.write_text(view)


v2_path = Path("app/src/main/java/llc/slacker/openime/ImeKeyboardViewV2.kt")
v2 = v2_path.read_text()
v2 = replace_once(
    v2,
    '            if (view is TextView && view.text.toString() == "手写输入") {\n                view.text = "手写输入·未配置"\n',
    '            if (view is TextView && view.text.toString() == context.getString(R.string.tool_handwriting)) {\n                view.text = context.getString(R.string.handwriting_unconfigured_label)\n',
    'handwriting unavailable label',
)
v2 = replace_once(
    v2,
    '            if (view.contentDescription?.toString() == "手写输入") {\n',
    '            if (view.contentDescription?.toString() == context.getString(R.string.tool_handwriting)) {\n',
    'handwriting capability comparison',
)
v2 = replace_once(
    v2,
    '                view.contentDescription = "手写输入（未配置）"\n',
    '                view.contentDescription = context.getString(R.string.handwriting_unconfigured_description)\n',
    'handwriting unavailable description',
)
v2 = replace_once(
    v2,
    '                view.contentDescription = "${view.text}（当前编辑器暂不支持）"\n',
    '                view.contentDescription = context.getString(R.string.textedit_unavailable_description, view.text)\n',
    'unsupported text edit description',
)
v2 = replace_once(
    v2,
    '            clipboardRetentionAction("清除未固定") {\n',
    '            clipboardRetentionAction(context.getString(R.string.clipboard_clear_unpinned)) {\n',
    'clear unpinned label',
)
v2 = replace_once(
    v2,
    '            clipboardRetentionAction("清空全部") {\n',
    '            clipboardRetentionAction(context.getString(R.string.clipboard_clear_all)) {\n',
    'clear all label',
)
v2_path.write_text(v2)
