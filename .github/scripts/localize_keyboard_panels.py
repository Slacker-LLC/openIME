from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


# Remaining tool-card labels in the legacy/native renderer.
view_path = Path("app/src/main/java/llc/slacker/openime/ImeKeyboardView.kt")
view = view_path.read_text()
for old, new, label in [
    ('            ToolEntry("表情", Panel.EMOJI, R.drawable.ic_emoji),\n', '            ToolEntry(context.getString(R.string.tool_emoji), Panel.EMOJI, R.drawable.ic_emoji),\n', 'emoji tool'),
    ('            ToolEntry("剪贴板", Panel.CLIPBOARD, R.drawable.ic_clipboard),\n', '            ToolEntry(context.getString(R.string.tool_clipboard), Panel.CLIPBOARD, R.drawable.ic_clipboard),\n', 'clipboard tool'),
    ('            ToolEntry("手写输入", Panel.HANDWRITING, R.drawable.ic_handwriting, enabled = handwritingAvailable),\n', '            ToolEntry(context.getString(R.string.tool_handwriting), Panel.HANDWRITING, R.drawable.ic_handwriting, enabled = handwritingAvailable),\n', 'handwriting tool'),
    ('            ToolEntry("符号", Panel.SYMBOLS, R.drawable.ic_symbols),\n', '            ToolEntry(context.getString(R.string.tool_symbols), Panel.SYMBOLS, R.drawable.ic_symbols),\n', 'symbols tool'),
    ('            ToolEntry("切换键盘", Panel.KEYBOARD_SELECT, R.drawable.ic_grid),\n', '            ToolEntry(context.getString(R.string.tool_switch_keyboard), Panel.KEYBOARD_SELECT, R.drawable.ic_grid),\n', 'keyboard switch tool'),
]:
    view = replace_once(view, old, new, label)
view_path.write_text(view)


# Production wrapper capability and retention messages.
v2_path = Path("app/src/main/java/llc/slacker/openime/ImeKeyboardViewV2.kt")
v2 = v2_path.read_text()
v2 = replace_once(
    v2,
    '''    private fun syncHandwritingCapability() {
        if (HandwritingFeaturePolicy.entryEnabled(UnavailableHandwritingProvider)) return

        fun markUnavailableLabel(view: View) {
            if (view is TextView && view.text.toString() == "手写输入") {
                view.text = "手写输入·未配置"
            }
''',
    '''    private fun syncHandwritingCapability() {
        if (HandwritingFeaturePolicy.entryEnabled(UnavailableHandwritingProvider)) return
        val handwritingLabel = context.getString(R.string.tool_handwriting)

        fun markUnavailableLabel(view: View) {
            if (view is TextView && view.text.toString() == handwritingLabel) {
                view.text = context.getString(R.string.handwriting_unconfigured_label)
            }
''',
    'handwriting label localization',
)
v2 = replace_once(
    v2,
    '''            if (view.contentDescription?.toString() == "手写输入") {
                view.isEnabled = false
                view.isClickable = false
                view.isLongClickable = false
                view.alpha = 0.38f
                view.contentDescription = "手写输入（未配置）"
''',
    '''            if (view.contentDescription?.toString() == handwritingLabel) {
                view.isEnabled = false
                view.isClickable = false
                view.isLongClickable = false
                view.alpha = 0.38f
                view.contentDescription = context.getString(R.string.handwriting_unconfigured_description)
''',
    'handwriting description localization',
)
v2 = replace_once(
    v2,
    '                view.contentDescription = "${view.text}（当前编辑器暂不支持）"\n',
    '                view.contentDescription = context.getString(R.string.text_edit_unavailable_description, view.text)\n',
    'unsupported text edit accessibility',
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
    'clear all clipboard label',
)
v2 = replace_once(
    v2,
    '            if (view is TextView && view.text.toString() == "已置顶") return true\n',
    '            if (view is TextView && view.text.toString() == context.getString(R.string.clipboard_pinned)) return true\n',
    'pinned clipboard marker',
)
v2_path.write_text(v2)


# Service-layer user-visible messages.
service_path = Path("app/src/main/java/llc/slacker/openime/LocalVoiceImeService.kt")
service = service_path.read_text()
service = replace_once(
    service,
    '            events.onError("本地语音服务尚未初始化")\n',
    '            events.onError(getString(R.string.voice_service_not_initialized))\n',
    'voice service not initialized',
)
service = replace_once(
    service,
    '            android.widget.Toast.makeText(this, "当前应用未能清空全部文本", android.widget.Toast.LENGTH_SHORT).show()\n',
    '            android.widget.Toast.makeText(this, getString(R.string.clear_all_failed), android.widget.Toast.LENGTH_SHORT).show()\n',
    'clear all toast',
)
service = replace_once(
    service,
    '                message = "语音输入已取消",\n',
    '                message = getString(R.string.voice_cancelled),\n',
    'voice cancelled state',
)
service_path.write_text(service)
