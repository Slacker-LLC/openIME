from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)

view_path = Path('app/src/main/java/llc/slacker/openime/ImeKeyboardView.kt')
text = view_path.read_text()
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
view_path.write_text(text)

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
