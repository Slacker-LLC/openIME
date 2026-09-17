from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


def replace_count(text: str, old: str, new: str, expected: int, label: str) -> str:
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{label}: expected {expected} matches, got {count}")
    return text.replace(old, new)


path = Path("app/src/main/java/llc/slacker/openime/ImeKeyboardView.kt")
text = path.read_text()

text = replace_once(
    text,
    '        row.contentDescription = "候选:$cand"\n',
    '        row.contentDescription = context.getString(R.string.candidate_content_description, cand)\n',
    'candidate accessibility description',
)
text = replace_once(
    text,
    '                    key("分词", true, "@#/", 1f, 12f) { onPinyinSegment() }.apply {\n',
    '                    key(context.getString(R.string.key_segment_label), true, "@#/", 1f, 12f) { onPinyinSegment() }.apply {\n',
    'pinyin26 segment label',
)
text = replace_once(
    text,
    '                        contentDescription = "分词，长按输入@井号或斜杠"\n',
    '                        contentDescription = context.getString(R.string.segment_gesture_description)\n',
    'pinyin26 segment description',
)
text = replace_once(
    text,
    '            spaceVoiceKey(if (mode == KeyboardMode.ENGLISH_26) "space" else "空格", white = true) {\n',
    '            spaceVoiceKey(context.getString(R.string.key_space_label), white = true) {\n',
    'pinyin26 space label',
)
text = replace_count(
    text,
    '            key("中/英", true, null, 1f, 13f) { cycleMode() }.apply {\n',
    '            key(context.getString(R.string.key_language_toggle), true, null, 1f, 13f) { cycleMode() }.apply {\n',
    1,
    'nine-key language toggle',
)
text = replace_once(
    text,
    '            key("中/英", true, null, 1f, 14f) { cycleMode() }.apply { tag = "key:mode" },\n',
    '            key(context.getString(R.string.key_language_toggle), true, null, 1f, 14f) { cycleMode() }.apply { tag = "key:mode" },\n',
    'pinyin26 language toggle',
)
text = replace_once(
    text,
    '        return fallback ?: if (english) "Go" else "确定"\n',
    '        return fallback ?: if (english) "Go" else context.getString(R.string.enter_confirm)\n',
    'enter fallback confirm',
)
text = replace_count(
    text,
    '            key("符号", true, null, 1f, 13f) { showPanel(Panel.SYMBOLS) }\n',
    '            key(context.getString(R.string.key_symbols_label), true, null, 1f, 13f) { showPanel(Panel.SYMBOLS) }\n',
    2,
    'symbol side keys',
)
text = replace_count(
    text,
    '            spaceVoiceKey("空格", white = true)',
    '            spaceVoiceKey(context.getString(R.string.key_space_label), white = true)',
    2,
    'nine and digit space labels',
)
text = replace_once(
    text,
    '            key("重输", true, null, 1f, 13f) {\n',
    '            key(context.getString(R.string.key_retry), true, null, 1f, 13f) {\n',
    'nine-key retry label',
)
text = replace_once(
    text,
    '                val display = if (num == "1") "分词" else sub\n',
    '                val display = if (num == "1") context.getString(R.string.key_segment_label) else sub\n',
    'nine-key segment display',
)
text = replace_once(
    text,
    '                        contentDescription = if (num == "1") "1，分词" else num\n',
    '                        contentDescription = if (num == "1") context.getString(R.string.pinyin9_segment_description) else num\n',
    'nine-key segment accessibility',
)
text = replace_once(
    text,
    '            key("返回", true, null, 1f, 14f) { setMode(lastTextMode) }.apply {\n',
    '            key(context.getString(R.string.key_back_label), true, null, 1f, 14f) { setMode(lastTextMode) }.apply {\n',
    'digits back label',
)
text = replace_once(
    text,
    '            key(enterKeyLabel(false, "换行"), true, null, 1f, 13f) { listener.onEnter() }\n',
    '            key(enterKeyLabel(false, context.getString(R.string.enter_newline)), true, null, 1f, 13f) { listener.onEnter() }\n',
    'digits newline fallback',
)
text = replace_once(
    text,
    '        label: String = "空格",\n',
    '        label: String = context.getString(R.string.key_space_label),\n',
    'space voice default label',
)
text = replace_once(
    text,
    '        contentDescription = "$label，点击空格，长按语音输入"\n',
    '        contentDescription = context.getString(R.string.space_voice_key_description, label)\n',
    'space voice accessibility description',
)

path.write_text(text)
