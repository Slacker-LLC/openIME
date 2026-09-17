from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


state_path = Path("app/src/main/java/llc/slacker/openime/ImeState.kt")
state = state_path.read_text()
state = replace_once(state, "    ENGLISH_T9,\n", "", "ImeState.ENGLISH_T9")
state_path.write_text(state)

pipeline_path = Path("app/src/main/java/llc/slacker/openime/CandidatePipeline.kt")
pipeline = pipeline_path.read_text()
pipeline = replace_once(
    pipeline,
    "        KeyboardMode.ENGLISH_T9 -> engine.getT9EnglishCandidates(composition)\n",
    "",
    "CandidatePipeline.ENGLISH_T9",
)
pipeline_path.write_text(pipeline)

engine_path = Path("app/src/main/java/llc/slacker/openime/CandidateEngine.kt")
engine = engine_path.read_text()
pattern = re.compile(
    r'\n    /\*\*\n     \* English T9 was removed from the product\..*?'
    r'    @Deprecated\("English T9 is no longer supported"\)\n'
    r'    fun getT9EnglishCandidates\(@Suppress\("UNUSED_PARAMETER"\) digits: String\): List<String> = emptyList\(\)\n',
    re.S,
)
engine, count = pattern.subn("\n", engine, count=1)
if count != 1:
    raise SystemExit(f"CandidateEngine.getT9EnglishCandidates: expected 1 match, got {count}")
engine_path.write_text(engine)

view_path = Path("app/src/main/java/llc/slacker/openime/ImeKeyboardView.kt")
view = view_path.read_text()
view = replace_once(view, '    private var lastT9Digits = ""\n', "", "lastT9Digits field")
view = replace_once(view, '    private var t9Filter = "T9"\n', "", "t9Filter field")
view = replace_once(
    view,
    "            KeyboardMode.ENGLISH_T9 -> preferredChineseMode\n",
    "",
    "cycleMode ENGLISH_T9",
)
view = replace_once(
    view,
    '''        val effectiveMode = if (newMode == KeyboardMode.ENGLISH_T9) {
            KeyboardMode.PINYIN_26
        } else {
            newMode
        }
''',
    "        val effectiveMode = newMode\n",
    "setMode compatibility redirect",
)
view = view.replace('        lastT9Digits = ""\n', "")
view = replace_once(
    view,
    "            if (mode == KeyboardMode.ENGLISH_T9) lastT9Digits = state.composition\n",
    "",
    "renderState ENGLISH_T9",
)
view = replace_once(
    view,
    "            KeyboardMode.ENGLISH_T9 -> renderEnglish9()\n",
    "",
    "renderModeBody ENGLISH_T9",
)
view = replace_once(
    view,
    '''        if (mode == KeyboardMode.ENGLISH_T9) {
            val (digits, selection) = replaceCompositionSelection(num)
            lastT9Digits = digits
            publishComposition(digits, candidatesForComposition(digits), selection)
        } else if (mode == KeyboardMode.PINYIN_9) {
''',
    "        if (mode == KeyboardMode.PINYIN_9) {\n",
    "onNineKey ENGLISH_T9",
)
view = replace_once(
    view,
    "            KeyboardMode.ENGLISH_T9 -> lastT9Digits = text\n",
    "",
    "onCompositionEdited ENGLISH_T9",
)
view = replace_once(
    view,
    '                    (view.parent as? View)?.tag in setOf("pinyin9-actions", "t9-actions", "digits-actions")\n',
    '                    (view.parent as? View)?.tag in setOf("pinyin9-actions", "digits-actions")\n',
    "theme t9-actions tag",
)

nine_start = view.index("    private fun renderPinyin9() = renderNine(true)\n")
nine_end = view.index("    /** Adaptive-width gray punct column", nine_start)
nine_block = '''    private fun renderPinyin9() = renderNine()

    /** Chinese 9-key layout. Column widths are weights, not prototype pixels. */
    private fun renderNine() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "pinyin9-layout"
        }

        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(punctStack(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(156),
        ))
        left.addView(
            key("符号", true, null, 1f, 13f) { showPanel(Panel.SYMBOLS) }
                .apply { setTag(MARK_SIDE_KEY, true) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(6) },
        )
        container.addView(left, adaptiveColumnParams(1f))

        val center = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        center.addView(
            nineGrid().apply { tag = "pinyin9-grid" },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(156),
            ),
        )
        val centerBottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        centerBottom.addView(
            key("123", true, null, 1f, 13f) { setMode(KeyboardMode.DIGITS) }
                .apply { setTag(MARK_SIDE_KEY, true) },
            flexKeyParams(0.9f, gapDp = 2),
        )
        centerBottom.addView(
            spaceVoiceKey("空格", white = true) { commitFirstCandidateOrSpace() },
            flexKeyParams(3.4f, gapDp = 2),
        )
        centerBottom.addView(
            key("中/英", true, null, 1f, 13f) { cycleMode() }.apply {
                tag = "key:mode"
                setTag(MARK_SIDE_KEY, true)
            },
            flexKeyParams(0.95f, gapDp = 2),
        )
        center.addView(centerBottom, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { topMargin = dp(6) })
        container.addView(center, adaptiveColumnParams(3.7f))

        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "pinyin9-actions"
        }
        side.addView(backspaceKey().apply { setTag(MARK_SIDE_KEY, true) }, sideKeyParams(48, true))
        side.addView(
            key("重输", true, null, 1f, 13f) {
                publishComposition("", emptyList())
            }.apply { setTag(MARK_SIDE_KEY, true) },
            sideKeyParams(48, true),
        )
        side.addView(
            key(enterKeyLabel(false), true, null, 1f, 13f) {
                listener.onEnter()
            }.apply {
                tag = "key-enter"
                setTag(MARK_SIDE_KEY, true)
            },
            sideKeyParams(102),
        )
        container.addView(side, adaptiveColumnParams(1f))
        keyboardBody.addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(210),
        ))
    }

'''
view = view[:nine_start] + nine_block + view[nine_end:]

grid_marker = "    /** 3x3 white grid with letter labels; contentDescription/tag key-9:<digit>. */\n"
grid_start = view.index(grid_marker)
grid_end = view.index("    private fun renderDigits()", grid_start)
grid_block = '''    /** 3x3 white grid with letter labels; contentDescription/tag key-9:<digit>. */
    private fun nineGrid(): LinearLayout {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            listOf("1" to "@#", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        ).forEachIndexed { rowIndex, rowDef ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowDef.forEach { (num, sub) ->
                val display = if (num == "1") "分词" else sub
                val secondary = if (num == "1") "@#/" else null
                row.addView(
                    key(display, false, secondary, 1f, if (num == "1") 12f else 17f) {
                        if (num == "1") onPinyinSegment() else onNineKey(num)
                    }.apply {
                        tag = "key-9:$num"
                        contentDescription = if (num == "1") "1，分词" else num
                        setTag(MARK_WHITE_KEY, true)
                        if (num == "1") {
                            setOnLongClickListener {
                                showChoicePopup(this, listOf("@", "#", "/"))
                                true
                            }
                        } else if (ImeData.keypad9Map[num].orEmpty().any {
                                it.length == 1 && it[0] in 'a'..'z'
                            }) {
                            setOnLongClickListener {
                                commitKeyboardCharacter(num)
                                true
                            }
                        }
                    },
                    flexKeyParams(gapDp = 2),
                )
            }
            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { if (rowIndex < 2) bottomMargin = dp(6) })
        }
        return grid
    }

'''
view = view[:grid_start] + grid_block + view[grid_end:]
view_path.write_text(view)
