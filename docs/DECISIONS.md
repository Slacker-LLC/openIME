# Design decisions

This log records decisions that are not obvious from the code.
Each entry has a date, the topic, the decision and the reason.
Add new entries at the end.

## 2026-09-29

- **Secondary text contrast (light theme).**
  Keep the keyboard value `keySecondaryText=#6E6E73`. Add a page role `textSecondaryRole=#6D6D72`.
  *Reason:* the old value has a contrast of about 4.44:1 on `surface=#EEF0F3`, below the 4.5:1 rule.
  Only the page role changes, so the keyboard look stays the same.
- **Candidate expand icon.**
  Reuse `ic_chevron_down` and rotate it 180° when expanded.
  *Reason:* one vector asset. No Unicode arrows.
- **Setup XML colors.**
  Keep only the resource colors that Android needs before Kotlin starts. `TokenDriftTest` keeps them equal to the Kotlin tokens.
  *Reason:* XML needs resource colors before Kotlin initializes.
- **Backspace repeat.**
  Keep the constant 60 ms repeat.
  *Reason:* visual language v1 says not to change it in that round. A later interaction project can change it.
- **Voice release tail.**
  After release, keep a 300 ms capture window. Use generation and session ownership so that an old session cannot affect a new one.
  *Reason:* an immediate cut loses the tail of `AudioRecord`. A cancel still takes effect at once.
  *Later change:* see `LOCAL_VOICE_MODEL.md`. The microphone now stops at release, and a 300 ms zero pad feeds the model.
- **Nine-key decoder thread.**
  First record the 20-sample window P50 and P95 of `publishNineKeyDigits → resolveNineKey`.
  Keep the current thread until we have a P95 from a low-end or mid-range phone.
  *Reason:* move the decoder to `CandidateQueryCoordinator` only if P95 is above 8 ms.
- **Nine-key first frame and Rime refresh.**
  While a finger presses or scrolls the candidate list, delay asynchronous Rime results. Record the delay from request to applicable result.
  *Reason:* the list must not reorder under the finger.
  Skip the fallback first frame only if the measured P95 is below 40 ms.
- **Rime user dictionary export.**
  The vendored librime 1.17.0 includes the levers module. `UserDictManager::Export/Import` can export or merge a UTF-8 snapshot after the user database session closes.
  *Reason:* the user data JSON includes learned words when a Rime session is loaded. When export is not possible, the app must say so. It must not skip the words silently.
- **Streaming voice model.**
  Use the INT8 encoder and decoder of `sherpa-onnx-streaming-paraformer-bilingual-zh-en` with `OnlineParaformerModelConfig` and `greedy_search`.
  *Reason:* smaller model and streaming Chinese and English recognition, with the official sherpa-onnx v1.13.6 configuration.
  Do not send transducer-only dynamic hotwords to the Paraformer stream.

## 2026-10-03

- **Voice word lists.** *(Removed on 2026-10-05.)*
  The idea was a built-in word list plus a user import list, both offline, with no `INTERNET` permission.
  Paraformer cannot take hotwords in the decoder, so the lists worked as homophone replacement after recognition.
  See the 2026-10-05 entry.
- **Package split.**
  Split the 99 flat files into packages: theme, core, editor, data, setup, widget, floating, handwriting, rime, candidate, voice, panel and keyboard.
  The root package keeps only the service, activities and JNI classes.
  `ArchitectureLayeringTest` enforces the dependency direction.
  *Reason:* packages change organization and visibility only. They do not change the manifest, native symbols or the names that test scripts use.
  We delay Gradle modules, because `keyboard`, `panel` and `voice` still connect through a large interface.
  First split `ImeKeyboardView.Listener`.

## 2026-10-04

- **Autofill strip.**
  Declare only `supportsInlineSuggestions` and build the androidx.autofill v1 style bundle by hand (a version table and one empty v1 style).
  Do not add the androidx.autofill dependency.
  Render entries at a fixed size of 150 dp × 40 dp in a horizontal scroll.
  If a response arrives before the keyboard view exists, hold it. Never return false, because the system then falls back to the dropdown menu.
  *Reason:* `InlineContentView` is a remote surface with no intrinsic size, so `WRAP_CONTENT` gives 0×0.
  The style bundle is a dozen lines. It is not worth the first androidx runtime dependency.
- **Numbers and symbols on letter keys.**
  Do not add a number row.
  Print the number or symbol at the top right of each letter key, as Sogou, iFlytek and WeChat keyboards do.
  Swipe up (this uses the nine-key "swipe up for digits" setting) or long-press to enter it.
  The first row q to p gives 1 to 0. The other rows give punctuation.
  Chinese mode uses full-width forms (！ ￥ ？ （ ） ： ；). English mode uses half-width forms.
  A separate setting, 数字和符号提示 ("Number and symbol hints", default on), turns the hints off.
  *Reason:* an extra row would squeeze every row (about 43 dp in portrait, 34 dp in landscape) or make the keyboard taller.
  A taller keyboard changes the IME window height, and the panels, the floating keyboard and the nine-key layout would all change.
  Hints plus swipe do not change the layout. Major Chinese keyboards do the same.
- **Space-swipe cursor locks the bottom row.**
  The cursor mode starts when three conditions are true.
  The finger moved 18 dp horizontally.
  The horizontal part is more than 1.25 times the vertical part.
  The long-press voice timeout has not ended.
  Then the other keys in the same row get `touchLocked` (grey, no response).
  With a pinyin preedit, the swipe moves the preedit cursor.
  *Reason:* the finger stays on space, but a second finger or a drifting thumb can hit a nearby key.
  Sending arrow key events to the app during a preedit would break the composition.
- **标点用空格代替 ("Replace punctuation with spaces").**
  This setting acts only on voice results.
  Commas, periods and question marks become one space. Final punctuation is removed. Parentheses, `3.5` and `a.b` stay.
  It belongs to the 语音输入 ("Voice input") group with the filler-word option. The default is off.
  *Reason:* we read the request as a switch for voice text post-processing.

## 2026-10-05

- **Voice word lists removed.**
  From 0.0.6-beta.1, the `hotword` module, the built-in lists, the management page and the typing candidate weighting are gone.
  *Reason:* homophone replacement is not mature. We do not ship it yet.
  The earlier code is on the `archive/voice-word-lists` branch (`f3bb5cc`, the 0.0.5-beta.1 commit). We can merge it back after a fix.

## 2026-10-14

- **Wubi 26-key.**
  Add a wubi86 keyboard that shares the 26-key surface with pinyin.
  Rime selects the `wubi86` schema from the saved Chinese mode, and the keyboard switcher shows the entry only when the compiled table is in the APK.
  The schema and the import script ship in the repository; the dictionary table does not (local data).
  *Reason:* wubi codes are plain letters, so the existing composition, candidate and commit paths apply unchanged; a separate renderer would duplicate the 26-key layout.
- **Handwriting recognition by stroke table.**
  `StrokeMatchHandwritingProvider` classifies each drawn stroke as one of the five basic strokes (h s p n z) and matches the sequence against a table.
  Keep the `HandwritingProvider` boundary; a learned recognizer can replace the implementation later.
  The tools panel shows the entry only when the table is in the APK.
  *Reason:* an offline, deterministic matcher is honest about its limits and needs no model runtime.
- **Local dictionary import.**
  `scripts/import_doubao_data.py` merges decoded external word data into the Rime tables and builds the wubi, handwriting and English tables.
  The data files are git-ignored; the script is committed, and it reads pristine sources from its own backup so a rerun does not drift.
  Phrase weights take the larger of the existing and imported value (an import can raise a word but never demote a tuned entry); the single-character table only gains missing readings, and they enter below weight 1000, because its weights order candidates and are hand-tuned.
  *Reason:* the external data must not ship in the public repository, and the curated ordering must survive the import.
- **English completions from the ranked list.**
  `EnglishLexicon` serves english-words.tsv (rank 1 = most common; unranked rows dropped) and merges with the small `ImeData.englishDict` before the casing fallback.
  *Reason:* the decoded rank column is the only reliable frequency signal; 118k unranked rows would drown the completion rank.

- **Self-developed pinyin engine (clean room).**
  `engine/` implements the pinyin flow from the reverse-engineered behaviour (full-path syllable DAG, syllable-prefix completion for a trailing fragment, Viterbi sentences with a bigram bonus) over binary tables compiled from the locally decoded lexicon.
  `CandidatePipeline` serves 26-key pinyin from it when the tables exist and fuzzy input is off; Rime stays as the source for fuzzy input, nine-key, stroke and wubi, and remains a supplement behind the engine's list.
  *Reason:* the Rime path could not deliver the expected candidate behaviour, and the engine is our own code against our own binary format (no third-party source copied); the tables are not distributed in the repository.
- **Handwriting in the keyboard switcher.**
  The handwriting surface gets an entry card beside the keyboards, not only inside the tools grid.
  *Reason:* users look for input surfaces where the keyboards are; hiding it in the tools panel made it feel missing.

- **Landscape matches the reference skin.**
  The landscape keyboard copies the measured values from the reference APK's landscape skin (`assets/skin/default/land/style.xml`, archived beside the dictionary handover under `skin-reference/`): keyboard side insets 6.485% (nine-key 9.5%), toolbar 8.2%, expanded candidates 9.5%, and the 26-key space weight 133.6 -> 182.2 (openIME 154 -> 210, the same ratio).
  *Reason:* landscape must feel like the reference product, and the numbers come from its shipped skin file instead of guesswork. The window stays full width and the background still spans it; only the content moves inward.

- **One-hand mode.**
  A 76%-width keyboard anchored to the chosen side (关闭 / 左手 / 右手), offered in the layout settings group next to the landscape options; it applies in both orientations and steps aside when the split layout is active.
  *Reason:* the reference product ships a one-hand mode (`单手模式`), and a wide landscape screen makes single-thumb typing awkward; reusing the existing content-inset path keeps every renderer untouched.
- **Keyboard height drag (键盘调节).**
  Long-press on the docked toolbar and drag vertically to change the keyboard height live (fixed sensitivity: a full screen height equals 200 points, so about 20% of the screen covers 40 points); reaching/keying persistence goes through the existing height path. The gesture only arms inside the toolbar row, so candidate long-presses and the input area keep their own gestures.
  *Reason:* the reference product offers height adjustment (`键盘调节`, `is_change_keyboard_height`); dragging the toolbar needs no new controls and is discoverable.

- **Double pinyin (小鹤 / 自然码).**
  Two committed Rime schemas (`double_pinyin_flypy`, `double_pinyin`) share the luna_pinyin dictionary; the keyboard is the plain 26-key surface and a settings choice picks the scheme.
  The chord tables come from rime-double-pinyin (GPL-3.0, credited in the schema header). The reference product ships the same two named schemes (`shuangpin_xiaohe` / `shuangpin_ziranma`, `oime_engine::ShuangPinScheme`); its mapping is compiled into native code without an extractable table, and 小鹤/自然码 chords are standardized layouts, so the standard tables are the same mapping.
  *Reason:* keyboard-type parity with the reference product without Lua or external dependencies.

- **Letter hints follow the reference skin key by key.**
  The corner marks on the 26-key letters are the reference product's own map (`skin-full/assets/skin/default/layout/input_kbd_pinyin26.xml`, `input_kbd_english26.xml`, attribute `ficon_text`): the same key carries the full-width mark in Chinese and the ASCII mark in English, and the top row carries 1–0. A long press opens only the letter in both cases, upper first (`long_press_bubble_text`); the mark belongs to the swipe-up gesture (`fusiyin_keycode`), and the comma/period key also commits the mark above it (。 / .) on an up swipe.
  *Reason:* the previous marks (a=~, s=!, d=@ …) were an older scheme of our own; these values are read from the shipped skin instead of guessed. The reference lists the lower case first on P and L only; we keep a single rule (upper first) on every key, and the digit row is unchanged.

- **Key weights and the nine-key column follow the reference skin.**
  The 26-key letters A and L carry weight 15 against a letter's 10 (`input_kbd_pinyin26.xml:48,85` against `style.xml:300`), and the same ratio covers 分词 and 退格 in the last row (`cls_button_left_big` / `cls_button_right_big`, `style.xml:907-912`); openIME drew nine equal letters and 1.25 for the function keys. The nine-key and stroke side column is 删除 / 重输 / 发送 with send twice as tall (`input_kbd_pinyin9.xml:104-111`, `height_weight="2"`), and the space bar carries the corner 0 (`ficon_text="0"`, `fusiyin_keycode="0x3030"`) instead of a separate zero key: an up swipe on the space bar types it, behind the same 上滑数字 setting as the letter marks. Candidate items pad 8dp per side (`cls_option_canditem` `textpadding="0.027"` of the 308-unit strip plus `childpadding="4"`).
  *Reason:* these are the reference product's shipped values, read from its skin files; the previous numbers were our own approximations.

- **The nine-key reading rail is the symbol rail's list (the reference filter list).**
  During composition the nine-key side rail lists the leading syllables (pre-syllables) of the open digits: one reading per item, one line, one text size, a hairline between items, no state drawn on any item — the pre-edit shows which reading is current — and the list keeps the user's scroll position unless the open digits changed. Tapping a reading fixes that syllable; 退格 undoes it.
  The reference ships one list for both jobs: the left column of `input_kbd_pinyin9.xml` is a single `FilterListLayout` whose item style is the symbol item's own (`cls_pinyin9_filter_button` inherits `cls_vertical_symbol_list_button`, `style.xml:1023-1032`), drawn at the symbol list's size (`fi_vertical_symbol_list_text` 47, `style.xml:112`) with readings one step heavier than marks (`pinyin9_filter_syllable` 600 against `pinyin9_filter_punc` 500, both 46, `style.xml:121-122`). The engine fills it with pre-syllables (`ConvertUtils::GetShowSyllableFilterList`, libshell.so 0x30a308, written by the flow behind `SyllableLattice::NineKeyPreSyllableFilter`) and a tap calls `ui::ButtonSyllableFilter::OnButtonUp` (libkeyboard.so 0x2cd944) → `BoardController::SelectUserSyllableFilter`; the chosen syllables stack up in `ImeData::InsertUserSyllableFilterSeq` and `PopUserSyllableFilterSeq` takes them back.
  *Reason:* our rail drew the readings as a stack of separate keys, filled the current one with the accent colour and scrolled it to the middle on every keystroke. The list moved under the finger, and the highlighted item was the one a tap could not change.

- **The nine-key rail turns into the URL rail in address and e-mail fields.**
  Outside composition, the rail lists `.` `/` `www.` `.com` `.cn` `http` `wap.` `ftp:` when the editor is an address or e-mail field, and the user's own punctuation marks everywhere else; the ＋ editor cell is absent in a URL field, because that list belongs to the field.
  The reference nine-key layout declares the same eight marks on the same rail (`skin-full/assets/skin/default/layout/input_kbd_pinyin9.xml:11`, `uri_filter_list=". / www. .com .cn http wap. ftp:"`), and only the nine-key layout does: the stroke and number layouts carry the rail without it.
  *Reason:* a URL is typed from those fragments far more often than from punctuation, and the reference product puts them exactly where our rail already is.

- **The first candidate is the accent colour, one weight heavier, with no tile.**
  The first item in the candidate strip is drawn in the theme's accent as text (contrast-corrected by `ImeSurfacePolicy.selectedText`) at the medium weight, and it has no background of its own; tapping it shows the accent-tinted pressed surface like any other item.
  The reference style names the same treatment for its first item: `cls_option_first_canditem` (`skin-full/assets/skin/default/style.xml:551-554`) takes `first_cand_font`, which is the candidate font one weight step up (weight 500 against 400, `style.xml:83,98`), and `first_cand_text_color`, which is the accent `rgba(79,132,255,1)` in the light skin and `rgba(255,255,255,0.95)` in the dark one (`values/colors.xml:4`, `values/dark_colors.xml:4`). No first-item class carries a background.
  *Reason:* openIME drew the first candidate as bold black text on a filled key-coloured tile, which is heavier than the reference and competes with the keys below. The colour follows the user's accent instead of the fixed blue in the skin, and passes the 4.5:1 text contrast rule.

- **The expanded candidate page is the reference's 更多候选 page.**
  The page opens with 全部 / 单字 as one option group over the list (`input_more_cand.xml:9-13`; `cls_filter_button`, `style.xml:773-780`), the list sits in one rounded card (radius 11, 2 edge, `cls_morecand_bk`, `style.xml:1162-1166`), and the backspace floats in that card's bottom-right corner at 25.2% by 20.2% of the list, rounding only that corner with the card's own radius (`bkround="0,0,11,0"`, `input_more_cand.xml:18-26`, key face `cls_button_backspace` over `cls_bk_gray_circle`, `style.xml:828-835`). Every cell is the same flat label with a hairline edge (`cls_morecands_button`, `style.xml:1141-1149`), and no cell carries the accent tile, because the skin gives the expanded list no first-item class. The corner key deletes one letter of the open composition and the page stays open on the shorter list.
  *Reason:* the page used to be the key grid itself — one bordered tile per candidate, the first one filled in the accent — which is not the surface the reference ships. 单字 keeps single characters (`is_single="true"` on that option), and 全部 is the page's own default (`selected="true"`), so the two chips do what their labels say. The page's left syllable list (`FilterListLayout name="more_cand_filter_list"`, `input_more_cand.xml:17-18`, commented 音节过滤) is not in this change: the 26-key path has no syllable-fixing state yet, and the list would have nothing to filter by.

- **The 26-key bottom row is the reference's seven-key row.**
  The row is 123 | ，。 | ， | 空格 | 。 | 中/英 | 确定, with the merged key's corner mark and the two symbol keys as the reference ships them. The merged key is `ButtonPeriodComma`: Chinese draws `text="，"` with the always-visible corner `ficon_text="。"` (`skin-full/assets/skin/default/layout/input_kbd_pinyin26.xml:145-152`) and English draws `text="."` with the corner `","` and the bubble `", ."` (`input_kbd_english26.xml:154-161`) — the wide key leads with the period in English, which our version had reversed. The two symbol keys are the `ButtonSymbol` pair the reference marks `visible="true"` either side of the space bar (`input_kbd_pinyin26.xml:153,156`, again in `skin-reference/input_kbd_pinyin26.xml:144,153`; English `input_kbd_english26.xml:162-164,174-175`): they carry the class's own text — full-width `，` and `。` in Chinese (`cls_keyboard_button_comma` / `cls_keyboard_button_period`, `style.xml:335-356`), `,` and `.` in English — and their `uri_adapter_symbol` replaces the pair with `"/"` and `"."` in an address or e-mail field, the same fields whose rail carries the URL list. Weights are the reference's own: 70.6 / 37.6 / 35.5 / 133.6 / 35.5 / 38.6 / 74.6 (`style.xml:957-978`), which in landscape only widens the space bar to 182.2 (`skin-reference/land/style.xml:115`).
  *Reason:* the reference's row has seven keys, not five: the earlier row folded the two symbol keys away and gave the space bar the room, so the 123 and 中/英 keys sat wider than the reference draws them and no key offered `/` or a second `，`/`。`. The two keys' label is inferred from the element's absent `text` plus `uri_adapter_symbol` on the class text: under that reading every attribute in the layout has a job. A device capture can overrule it — the skin alone cannot say whether the app hides these two keys at runtime, since the same file ships both the merged key and them.

- **The nine-key 1 cell is the reference's @# key, and the board has no 分词 key.**
  The cell shows `@#` with the digit 1 in the corner: a tap types the digit and an up swipe types it too, exactly as the letter cells type their digits, and a long press opens the reference's own symbol list `/ - _ 1 @ # .` in that order. The reference's cell is `ButtonPinyin9Symbol text="@#"` with `ficon_text="1"`, `fusiyin_keycode="0x3031"` (the digit-1 family) and `fusiyin_bubble_text="/ - _ 1 @ # ."` (`skin-full/assets/skin/default/layout/input_kbd_pinyin9.xml:23-28`), and no keycode attribute: the label names the symbols the key offers rather than what it types. The board has no 分词 key because the reference has none — `ButtonSplit text="分词"` appears once in the whole skin, on the 26-key row (`input_kbd_pinyin26.xml:92`).
  *Reason:* our nine-key cell was a 分词 key with an invented `@#/` long-press popup, and the syllable-split command it ran does not exist on the reference's nine-key board (there the user taps a reading in the rail). The reference lists those seven symbols on the key's up-swipe bubble; our up swipe commits a single character, so the same list opens on a long press, which is where our other nine-key keys keep their bubbles.

- **The pre-edit is two colours on one line, as the reference draws it.**
  The open Pinyin string above the candidates is drawn at `comp_font` (`style.xml:79`, size 40, one step below the candidate font's 56) with the syllables the user has fixed in the theme colour and the still-unconverted tail in the key text colour. The reference ships exactly that pair: `cls_comp_half` holds 半选 and the nine-key reading the user chose, right-aligned in `@color/theme_color` (`style.xml:580-583`), `cls_comp_right` holds the unconverted string, left-aligned in `@color/keybutton_text` (`style.xml:585-587`), and the two are sibling labels of one `Composition` (`layout/input_toolbar_typing.xml:5-13`).
  *Reason:* our pre-edit was one uniform accent-coloured run at 14sp, so it could not show which syllables were settled. Only a plain prefix of the shown string counts as fixed — a divider the decoder inserted is not a choice the user made — so the colour follows the reading the user tapped in the nine-key rail instead of every boundary the engine guesses. The 26-key path has no user-fixed prefix yet, so it stays in one colour until it does.

- **The candidate strip carries the reference's own insets, split and expand key.**
  The strip is `[4] 候选列表(308) [1] [3] 更多候选(40) [4]` (`layout/input_toolbar_typing.xml:23-32`): a small inset at each end, a hairline split immediately after the list, a 3-unit gap, then the expand key. The list's own weight is `cls_cand_list_weight_no_speech` 308 (`style.xml:530-534`), the split is filled with `@color/canditem_split` (`cls_candidate_split`, `style.xml:504-506`, the same colour the list's hairlines use), and the expand key is `cls_bk_toolbar_button`: transparent until pressed, pill radius, press tint `canditem_pushedbk` (`style.xml:231-235`, `cls_btn_morecand` `style.xml:482-488`).
  *Reason:* openIME had a 6dp pad on one side and nothing on the other, no split, and a square 8dp-radius press tint on the expand key. The key keeps its 48dp touch target — our own accessibility rule, which the reference's 40-unit box does not meet — so only its shape and spacing follow the skin.

- **Idle toolbar keys are the reference's pill buttons, and the strip keeps its slack.**
  Each idle-toolbar key is 40 units wide with a pill face of its own and a press tint (`cls_toolbar_idle_button`: `width="40"`, `bkcircle="true"`, `bkcolor="@color/toolbar_idle_button_bk"`, `bkround="999"`, `pushedbkcolor`, `style.xml:237-248`; the idle row's keys are `input_toolbar_idle.xml:8-40`, with 收起 last at line 41-43). On our toolbar surface the face is the key cap colour, which is what the reference's own light and dark values do — a white pill at 0.8 alpha on its light toolbar, white at 0.12 in the dark skin.
  *Reason:* our keys were bare icons stretched by weight to fill the row, so they had no face at all and 收起 absorbed the leftover width. The keys now keep the reference's width and press shape, and the slack of the strip sits in a spacer before 收起; a strip or the autofill chips take that slack when they are open. Height stays our 48dp touch target rather than the reference's row height, for the same accessibility reason as the expand key.

- **The number page is the reference's own number layout.**
  The page is `input_kbd_number_from26.xml:5-42`, the layout the reference's 26-key `123` key opens (`input_kbd_pinyin26.xml:134`, `cls_button_123` with `target="9"`): a symbol rail over the return key in the left column, three digit columns each closed by a function cell, and a delete / space / send column on the right. The column weights are the reference's own 66 / 75 / 75 / 75 / 66 (`input_kbd_number_from26.xml:8,14-38`); the left column is the rail's 156 units against the return key's 52 (`input_kbd_number_from26.xml:10-15`), three key rows against one; send is `height_weight="2"` (`input_kbd_number_from26.xml:39`), twice the delete and space keys above it; and the digits read down the columns 1/4/7, 2/5/8, 3/6/9 with `符号`, `0` and `.` as the last cell of each (`input_kbd_number_from26.xml:17-34`). Every digit sits on `cls_keyboard_number9_button`, a white key with `key_number9` (`style.xml:394-395`), and the two function cells follow their own class: the return key is the theme colour (`cls_button_back_26key` over `cls_button_back`, `style.xml:857-858`, `:838-843`, whose `cls_bk_theme_circle` carries `theme_color`, `:282-286`) and send is grey (`cls_button_enter9` over `cls_keyboard9_button_func`, `style.xml:824-826,398-401`), which is why this page's space key is grey where the letter pages draw theirs white.
  *Reason:* our page was a 3x3 grid over a `返回 / 空格 / 0` row with `退格 / . / @ / 发送` down the right — an arrangement the reference does not ship, with the zero parked beside the space bar and an `@` key no number page declares. A phone field in the reference dials from a page our build does not draw, so there the symbol cell carries the dialer's `#` and the period cell its `+` (our own rule, unchanged in effect: `*`, `+` and `#` stay reachable); the reference's other number page keeps the same rail and swaps only those two cells, 符号 at the foot of the left column and the return key in the digit grid (`input_kbd_number.xml:19-20,24-25`), so the slot is the page's, not the key's.

- **The stroke grid is the reference's own nine cells, and the nine-key row its own weights.**
  The stroke board's centre is the reference's 3x3 grid (`input_kbd_stroke.xml:16-66`): 一 丨 丿 on top, 丶 折 通配 next, and @# ：； last, read down its three columns. Every cell carries the skin's corner digit — `ficon_text` 1 to 9 with the matching `fusiyin_keycode` (`input_kbd_stroke.xml:19-22`, 通配 at 57-61) — the two punctuation cells commit themselves (`commitstr="："` and `"；"`, lines 46 and 62), and the @# cell opens the reference's own list `/ - _ @ # . 7` (`long_press_bubble_text`, line 32). The bottom row of both this board and the nine-key board then takes the reference's own 45.5 / 134 / 45.5, which fills its 225-unit centre column exactly (`input_kbd_stroke.xml:69-84`, `input_kbd_pinyin9.xml:87-100`).
  *Reason:* our stroke board drew two rows of six cells and left the reference's last row unwritten, so 通配's digit 6 and the two punctuation marks had no key at all, and the row weights were our own 0.7 against 1.6 — a space bar that took 53% of the row where the reference gives it 60%. The @# cell types its corner digit on a tap, as the nine-key @# cell does, because the skin declares no `keycode` for it; its up swipe types the digit too, like every other cell of the grid.

- **The idle toolbar keeps the reference's own order, inside its own scrolling strip.**
  The icons follow the reference's row — the clipboard, then emoji, then the board selector (`input_toolbar_idle.xml:21-25`) — and they live in a strip that scrolls when they do not all fit, with the strip's slack in a spacer at its own end (`toolbar_left_container` with `sliderenable="true" sliderstopslowdown="true"`, `input_toolbar_idle.xml:14`, and `toolbar_right_spacer`, line 34). 收起 stays outside the strip, its own button on the right (`btn_hide`, line 40).
  *Reason:* our row led with the board selector, which the reference puts after the clipboard and the emoji keys, and our icons were stretched by a row weight: on a narrow screen they shrank instead of scrolling, and the slack spacer sat outside the strip, so it moved 收起's neighbours rather than staying inside the scrolled content. The tools the reference lists that this build does not ship (translate, 繁简, 单手, handwriting lookup) are not in the row; quick phrases keep their own key in the floating toolbar, which is also where the compact set lives.

- **The symbol page keeps its categories in a bottom strip.**
  The reference's symbol page ends with a row of the return key and the category container: the container is weighted 85 against the key's 13.1 (`input_kbd_symbol.xml:44-47`) and scrolls (`cls_symbol_tab_container` with `sliderenable="true"`, `style.xml:707-710`), and each category is a transparent pill whose label darkens when it is the current one (`cls_input_kbd_symbol_option`, `style.xml:715-724`: `symbol_option_text` at 60% to `symbol_option_selectedtext` at 85%, `values/colors.xml:16-17`).
  *Reason:* our categories ran down a 74dp column at the page's left edge, which no reference page has, so the symbol cells lost a fifth of the page's width. The strip is the chip row this app already uses for the emoji page and the expanded candidate page, so the two symbol surfaces now agree. The cells stay five to a row where the reference draws ten (`input_kbd_symbol.xml:6-15`): ten cells across a phone put each of them under our 48dp touch target.

- **The symbol page carries its own 123 and 退格 keys.**
  The two keys close the reference's symbol row (`input_kbd_symbol.xml:31-41`): the 123 key and the backspace key sit at that row's two ends, weighted 15 each against the seven cells' 70, so the page can reach the digits and delete without being left first. Our cells are five to a row, so the two keys take the two ends of the tab strip's row — the row the reference gives to the page's own return key (`input_kbd_symbol.xml:44-47`).
  *Reason:* the page could only type symbols: deleting a character or reaching the digits meant closing it first. Both keys keep our 48dp touch target and use actions the page family already has (switching to the digits layout leaves the panel, exactly as choosing a keyboard from the 切换键盘 page does).

- **The symbol page lists the symbols the user has typed in its toolbar.**
  The reference's symbol board fills its toolbar row with the history of typed symbols (`input_toolbar_quickinput.xml:4-18`, the `toolbar_symbol_recent` row the skin keeps hidden until the board needs it): a 4-unit lead inset, the scrolling history list at weight 308, the same hairline split its candidate strip uses, a 3-unit gap, the 40-unit close key and a 4-unit tail. One cell is 36 units wide with a radius-6 press face and candidate text in the candidate text colour (`cls_history_symbollist_button`, `style.xml:1061-1070`, commented 符号键盘 历史符号列表项), the list keeps 4 units between its cells (`cls_history_symbollist` childpadding, `style.xml:1055-1058`), and the close key borrows the candidate expand key's own 40-unit box (`cls_btn_more_cand_button_adaptive`, `style.xml:468`, over the transparent pill `cls_bk_toolbar_button`, `style.xml:231-235`).
  *Reason:* our board had no history: the symbols a user had just typed were gone when the page closed, so a phone number's `+`, `-` or `#` had to be found again on every visit. The list keeps the reference's row shape, and our own rules cover what the skin leaves open: the close key keeps the 48dp touch target, the cells keep the board's own five-a-row floor where the reference's box is 36 units, a tap commits through the same path the board's cells use (so the list re-reads itself), and the close key ends the strip for that visit while the board stays open — the next visit shows it again. The 收起 key stays at the row's right end, where our toolbar always keeps it. The strip's own empty case hides it: a user who has typed no symbols sees the plain toolbar, not an empty row.

- **The launcher pages draw the reference's own tile grid.**
  The tools page and the keyboard switcher are one surface shape in the reference: a container 88.9% of the page wide with its own paddings (5.6% at each side, 7.2% on top, 10.1% at the foot) and tiles laid out 4 columns by 2 rows with the grid capped at 192 units (`cls_settings_bk_adaptive`, `style.xml:616-619`; `input_settings.xml:13-45`, `input_switch_keyboard.xml:13-29`). Each tile fills its cell with a radius-14 face — `setting_button_bk` under it, `setting_button_pushedbk` while pressed, the theme colour when it is the chosen one — and its name reads in `keybutton_text_3`, white on the chosen tile (`cls_settings_button`, `style.xml:626-647`; `values/colors.xml:15,87-93`). The glyph sits over the name, as `ficon_text` over `text` with `align="top"` draws it.
  *Reason:* our tiles were bare columns: a 56-unit icon chip carried the fill and the name sat outside it on the page background, so the reference's card — the whole cell — was missing, and our names took the plain key text where the reference dims them to `keybutton_text_3`. Our own card colour stays (`toolCardBackground`, opaque #FFFFFF light and #303032 dark) where the reference's is white at 88% and white at 12%, and the glyph's share of the tile is ours (52%) because the skin's `ficon_height="0.7647"` measures its own icon font box. Two of the reference's own rules do not carry over, both stated here: a tile never shrinks under our 48dp touch target — a short keyboard grows the rows back and the page scrolls instead of hiding an entry — and an entry list longer than a page takes a third row rather than the reference's paging, so its page dots (`cls_settings_tab_point_container`, `style.xml:670-677`) are not built.

- **The floating keyboard carries the reference's own tip banner.**
  The reference meets the floating keyboard with a tip row: `input_toolbar_tip.xml:44-49` is one `cls_toolbar_tip_container` pill — the full row's width, `bkcolor="@color/keybutton_bk"`, `bkround="999"` (`style.xml:1273-1275`) — with its own 3.333% horizontal padding (`cls_toolbar_tip_adaptive`, `style.xml:442`), holding a 3.571% lead space (`cls_toolbar_tip_leading_space_adaptive`, `style.xml:1277`), a left-aligned ellipsized label in `keybutton_text` with its own 1.2% inset (`cls_toolbar_tip_label` over `cls_toolbar_tip_label_adaptive`, `style.xml:1265-1270`), and one round close key as wide as it is tall, filled `toolbar_tip_close_button_bk` and pressed `toolbar_tip_close_button_pushedbk` — black 4% and 14% on the light skin, white 7% and 17% on the dark one (`cls_toolbar_tip_close_button`, `style.xml:1307-1313`; `values/colors.xml:163-164`, `values/dark_colors.xml:157-158`). The container's two vertical fractions (0.17857 + 0.10714 of the row, `style.xml:442`) leave 0.71429 of it, the 34dp our painted toolbar controls already use.
  *Reason:* our floating keyboard's only way back is a tap on its handle, and that instruction lived in the handle's accessibility label alone, so a sighted user had no way to learn it. The tip is the reference's own surface for exactly that message, so the row is ported with our own truthful wording (轻点顶部把手可恢复默认键盘) in place of the reference's drag to the bottom edge (`input_toolbar_tip.xml:47`), because our handle docks the keyboard again on a tap. The close key settles the tip for that stay afloat and the next one shows it again, the rule the symbol history uses; 收起 keeps its place at the row's right end, and the tip gives the row back to candidates, associations and the symbol history whenever they have something to say.

- **The pre-edit keeps the reference's own half of the page.**
  The typing toolbar's composition row holds the pinyin block in a wrapper of at most half the page's width (`maxwidth="halfMaxWidth"`, `input_toolbar_typing.xml:6`) with a 5-unit lead of its own (`padding="5,0,0,0"`, line 6) under the row's 3-unit top inset (line 5); the other half goes to an empty control, the room the reference keeps for the surface beside it (line 18). The string scrolls inside that cap, as the reference's own composition container does (`sliderenable="true" sliderstopslowdown="true"`, `cls_comp_container`, `style.xml:575-577`), and the block carries the container's own press tint while it is edited (`pushedbkcolor="@color/comp_pushedbk"` — black 8% on the light skin, white 12% on the dark one, `values/colors.xml:84`, `values/dark_colors.xml:82`, the alphas our pressed surfaces already use).
  *Reason:* our pre-edit ran the full width of the row in portrait, and in landscape it stopped at a 160dp cap of our own making with no source, so the line was twice the reference's and the row it shares had no room reserved beside it; the field also gave no press feedback while it was being edited, although the reference tints it. Our 14dp side insets are replaced by the block's own 5-unit lead, which is what the skin declares. The caret still follows the string, so a pinyin line past the cap scrolls to its newest letters.

- **Our own clipboard and message suggestions wear the reference's quick-input row.**
  The reference answers a fresh copy or a text message with its own quick-input toolbar (`layout/input_toolbar_quickinput.xml`): the clipboard row is `[12] text [glyph] [close] [4]`, the text at `cand_font` in `canditem_text`, left-aligned inside a scrolling container weighted 304 (lines 36-51; the text at lines 41-43, then `quickinput_clipboard_icon1` at `\ue320` in `keybutton_text_2` under `fi_toolbar_tip_superscript`, lines 44-46), and the message row centres its code with the message glyph `\ue363` (lines 20-32). Both end with `cls_history_close_button` — the 40-unit close box the symbol history already uses (`style.xml:1050-1052`), which `btn_quickinput_close` borrows through `cls_btn_more_cand_button_adaptive` (lines 48-49).
  *Reason:* our chips were filled key-coloured pills at the body size behind a back chevron, a shape no reference toolbar draws — the reference's own suggestion is flat text at the candidate size with the glyph that names where it came from, and it closes with its own key rather than with a control borrowed from another strip. The system's autofill chips keep that back control, because they are the platform's own views in the same strip, and every row keeps our 48dp touch target. Our own drawables stand in for the skin's icon font: the message bubble for `\ue363` and the clipboard for `\ue320`.

- **The quick-input row's origin glyph rides above the text as a superscript.**
  The reference's own suggestion row measures both of its parts against the row: the text box carries `margin_decimal="0,0.055,0,0.111"` on `btn_quickinput_clipboard` (`input_toolbar_quickinput.xml:42`), and the glyph that follows it takes `margin_decimal="0,0.08,0,0.111"` with `align="top"` under `fi_toolbar_tip_superscript` (`input_toolbar_quickinput.xml:44-45`) — a font of 30 units where `cand_font` is 56 (`style.xml:138`, `:83`), so the badge is a little over half the line it marks and keeps the row's top edge instead of its centre.
  *Reason:* our first pass drew the glyph as a fixed 18dp icon centred on the row, which reads as a second key beside the text rather than as the mark of where the text came from; the reference's own proportion ties it to the candidate font, so the size is a share of the text rather than a number of its own. The reference's 1% side padding on the glyph is not ported: it is a share of the row's own width in the skin's decimal mode, and a badge sized against the text has no such box to pad.

## 2026-10-15

- **Every key cap takes its own class's margin, radius and face.**
  The skin gives each kind of key its own numbers instead of one shared key style: the 26-key rows keep `margin="2.25,4.75,2.25,4.75"` against the nine-key, stroke and number boards' `2.5,2.75` (`cls_keyboard_button_margin`, `style.xml:289-290`; `cls_keyboard9_button_margin`, `style.xml:373-374`), every cap rounds to `bkround="6"` (`cls_bk_white_circle`, `style.xml:224-227`; `cls_bk_gray_circle`, `style.xml:276-281`), and each family names its own face — `key_letter` 57, `key_letter_english` 60, `key_letter9` 48, `key_letter9_at_symbol` 54, `key_stroke` 60, `key_stroke_wildcard` 44, `key_number9` 54 (`style.xml:4-13`), the stroke grid taking `key_stroke` where we had read it as a symbol cell. The rail list keeps one face for all its cells, `fi_vertical_symbol_list_text` 47 (`style.xml:112`, `:1025-1030`), on a panel with `margin="2.5,2.75,2.5,2.75"` (`style.xml:1035-1036`).
  *Reason:* our caps used 6, 8 and 12dp radii, one uniform margin per board, and sizes borrowed from a neighbouring family — the nine-key letters wore the candidate size and the numeric page wore the symbol size. The reference's own step maps to ours as three units to the sp, the rule `key_letter` 57 → 19 and `comp_font` 40 → 13 already follow.
- **A pressed key takes the other key's fill, and the cap sits on the reference's shadow line.**
  The reference's two key classes swap their fills when pressed: `keybutton_pushed_bk` is `keybutton_func_bk` — a white key turns gray — and `keybutton_func_pushed_bk` is `keybutton_bk` — a gray key turns white (`values/colors.xml:19-23`, and the same pair over the dark skin's translucent fills, `values/dark_colors.xml:18-22`). Every key class also sets `shadow_y_offset="1"` over `keybutton_white_shadow` / `keybutton_gray_shadow` — black at 30% on the light skin and 40% on the dark one (`style.xml:224-227`, `:276-281`).
  *Reason:* we shaded each key a little darker instead, so a pressed gray key read as deeper rather than as the reference's flash of white, and the line under a cap was drawn in that same pressed colour, which the light skin makes nearly invisible. The theme-coloured key keeps its own pressed colour, as `cls_button_enter` does (`style.xml:810-818`).
- **Each pressed surface carries the colour its own class names.**
  The skin names a press colour per surface, and several read the other way round from a derived shade: a candidate cell brightens to `canditem_pushedbk` — white on the light skin, white at 8% on the dark one (`values/colors.xml:81`, `values/dark_colors.xml:79`) — and the first candidate presses like the rest, the expanded page keeps its own `morecands_button_pushedbk` (black 6% / black 4%, `values/colors.xml:117`, `values/dark_colors.xml:111`), a rail row fills with the white key (`cls_vertical_symbol_list_button` → `keybutton_bk`, `style.xml:1025-1030`), a symbol-category tab uses one neutral pill for pressed and chosen with labels at 60% and 85% of the key text colour (`cls_input_kbd_symbol_option`, `style.xml:715-724`, `values/colors.xml:16-17`), a bubble cell fills with the accent and whitens its glyph (`cls_fusiyin_bubble_button`, `style.xml:1135-1139`), and the action key turns white with a dark label (`action_button_pushedbk` / `action_button_pushedtext`, `values/colors.xml:35,38`, `values/dark_colors.xml:34,36`). The corner marks take `keybutton_text_2` (black 40% / white 40%, `values/colors.xml:14`, `values/dark_colors.xml:13`), the idle toolbar's glyphs `toolbar_idle_button_text` (rgb 127,127,127 / white 50%, `values/colors.xml:30`, `values/dark_colors.xml:28`), and the line between the candidate list and the expand key `canditem_split` (black 12% / white 16%, `values/colors.xml:83`, `values/dark_colors.xml:81`). The first candidate's own colour is the accent on the light skin and the plain candidate colour on the dark one, where `first_cand_text_color` is white at 95% (`values/colors.xml:4`, `values/dark_colors.xml:4`).
  *Reason:* we derived each pressed state by shading whatever neutral surrounded it, so a candidate dimmed where the reference brightens it and a function key pressed to a white key in the reference pressed to a deeper gray here. The reference's translucent pairs also scale with the theme, which a single derived shade cannot.
- **A surface presses onto the page it lies on.**
  Every press colour the skin names for a tile or a toolbar key is the same recipe: black at 6% on the light skin and white at 4% on the dark one, laid over the ground the face sits on (`toolbar_idle_button_pushedbk`, `values/colors.xml:45`, `values/dark_colors.xml:43`; `setting_button_pushedbk`, `values/colors.xml:89`, `values/dark_colors.xml:87`). The tool and switcher tiles, the settings rows and the idle-toolbar keys use that recipe over their own page ground, and the association bar's cells press to the candidate fill, the family their class belongs to (`cls_option_associate_first_canditem`, `style.xml:562-566`).
  *Reason:* our pressed states were derived by blending the key text colour into the resting face, which brightens a surface on the dark skin where the reference dims it — a pressed tile glowed. The reference's own recipe sinks the face toward the page on both skins, so it carries over unchanged.
- **The composing strip splits 20 : 36, the reference's own two rows.**
  `input_toolbar_typing.xml:2-34` gives the pinyin line 20 height units (line 5) and the candidate row 36 (line 23), with a 2-unit gap under both (`<Control height_weight="2" />`, line 33).
  *Reason:* our 60dp strip divided those rows as 18 and 42, which cramped the pinyin line and gave the candidate row more room than the reference's proportion. 21 and 37 hold the reference's ratio and keep our zone's 2 units of gap inside the strip, so the keyboard below does not move.
- **The number page's return key wears the theme colour.**
  The key that leaves the number page for the letter board is `cls_button_back_26key` (`input_kbd_number_from26.xml:13`), which takes `cls_button_back`: an icon key whose `cls_bk_theme_circle` fills with `theme_color` and presses to the white key's own fill (`style.xml:857-858`, `:838-843`, `:282-286`). Its glyph is a private-use character of the skin's icon font, so the label stays our own `返回`.
  *Reason:* the entry above recorded this key as grey, read from the class name `cls_button_back_26key` rather than from what it inherits — the skin's "back" class is a theme-colour circle, not a neutral one. Our key now matches the skin's colour; the letter on it is our substitution, as the icon font is not ours to ship.
- **The keyboard-switch page's chosen tile keeps its card and answers in the accent.**
  `input_switch_keyboard.xml:16-24` builds its four tiles from `cls_settings_option_mode_switch_button` (`style.xml:649-668`), which names `selectedtextcolor` and `ficon_textcolor_selected` as `theme_color` (`values/colors.xml:3`) and declares no `selectedbkcolor` at all — against the settings page's `cls_settings_button`, which fills the chosen tile with `theme_color` and whitens its label (`style.xml:626-647`, `setting_button_selectedtext`, `values/colors.xml:93`).
  *Reason:* our switch page borrowed the settings page's selected treatment, so the keyboard in use glowed as a solid accent card. The two pages name different classes for the same tile shape and we had read only one of them; the chosen tile now keeps the card its neighbours wear and colours its name and glyph with the accent, shaded to the 4.5:1 our text roles are held to.
- **The space bar's name stays full-contrast.**
  The reference's 26-key space is a wordmark glyph (`cls_button_space`, whose own text is a wordmark glyph in `fi_space_text1`) painted in `keybutton_text_2` — black 40% / white 40% (`style.xml:860-868`, `values/colors.xml:14`, `values/dark_colors.xml:13`) — while the nine-key and number pages paint their own 空格 in the full key text (`cls_keyboard9_button_space_func`, `style.xml:876-879`, and the white-key `cls_button_space_number9`, `:871-874`, both `keybutton_text`).
  *Reason:* we draw the word 空格 where the reference draws an icon-font wordmark. 40% is the weight of a drawing there; as text it falls under the 4.5:1 our labels are held to, so our 26-key space keeps the key text colour, and our number page's space already wears the grey key the class names for it.
- **The English board's 中/英 key answers in the accent.**
  The key carries the direction it points in the reference: on the English board it is `cls_button_en2cn`, whose own wordmark takes `keyboard_current_english` — the theme colour at 95%, declared the same on both skins (`style.xml:897-904`, `values/colors.xml:18`, `values/dark_colors.xml:17`) — while the Chinese boards' `cls_button_cn2en` draws its wordmark in the plain key text and dims only the corner mark to `cnen_button_light` (`style.xml:882-890`, `values/colors.xml:41`). Our English 26-key board's 中/英 key now takes the accent (shaded to the 4.5:1 floor over its grey face) and keeps the tag that says which way it points.
  *Reason:* one key wore one colour on both boards, where the reference uses the colour to say that this key is the way back to Chinese. The label stays our own text: the reference's is an icon-font wordmark.
- **The nine-key and stroke boards' bottom row is white, not grey, either side of the space bar.**
  In both layouts the 123 key is `cls_keyboard9_button_func_white_circle` (`input_kbd_pinyin9.xml:88`, `input_kbd_stroke.xml:70`) — the white circle with the full key text (`style.xml:404-407`) — and the 中/英 key is the same white circle under `cls_button_cn2en`'s or `cls_button_stroke2en`'s own colours (`input_kbd_pinyin9.xml:98`, `input_kbd_stroke.xml:82`). The space bar between them is `cls_button_space`, itself a white-key subclass (`style.xml:860-868`), while the 符号 key above the row keeps the grey function face.
  *Reason:* we drew both outer keys with the grey function face, the one the 26-key board's 123 wears, so the row read as three different key kinds where the reference draws one white row. Their labels are full-contrast in the reference too (unlike the space's own wordmark), which is what our white key role already paints.
- **The panel header's close key is the reference's own circle.**
  Both pages the reference gives a header open with `cld_toolbar_close_button` (`input_settings.xml:5-8`, `input_switch_keyboard.xml:5-8`): a circle whose only face is the idle fill, `bkcolor="@color/toolbar_idle_button_bk"` — white at 80% on the light skin and white at 12% on the dark one — pressing to `toolbar_idle_button_pushedbk` (black 6% / white 4%), with its glyph in `action_button_pushedtext`, the button-glyph colour rather than the idle toolbar's own icon grey (`style.xml:266-273`, `:237-248`, `values/colors.xml:38,43,45`, `values/dark_colors.xml:36,41,43`). Its width is `match_height`, the circle as wide as its row is high, and the toolbar's own circular keys are 40 units wide (`cls_toolbar_idle_button`, `style.xml:240-243`).
  *Reason:* our panel headers drew a bare arrow on the page ground, which is neither the shape nor the colour the two pages name; the arrow also fell into the tools page's tile-icon rule and took the accent, where the reference keeps this key out of the tiles' colour family. The circle paints 40dp inside the 48dp touch target our controls hold to, and the glyph stays our own arrow: the reference's is a private-use character of its icon font.
- **Three reference classes stay unported, with the evidence.** `cls_quickinput_button` declares a press fill (`quickinput_button_pushedbk`, black 8% / `#FFe8ebf0`) for "the tap-to-commit button of quick input" (`style.xml:1254-1262`, `values/colors.xml:149`, `values/dark_colors.xml:143`), but no layout in the skin tree instantiates it — the quick-input row itself is built from `cls_toolbar_idle_button` keys and `canditem_text` labels (`input_toolbar_quickinput.xml`), which we ported. `cls_morecands_scroll` (`style.xml:1153`) is likewise declared and referenced nowhere in the shipped layouts. `cls_period_comma_fusiyin` does reach the layouts — it is composed into the two ，。 key classes (`style.xml:304`, `:312`) — but it only gives that key's corner mark its geometry and the 40% text colour (`ficon_textcolor="@color/keybutton_text_2"`, `style.xml:215-221`, `values/colors.xml:14`), which our corner hint already carries through `ImeSurfacePolicy.keyHint`.
  *Reason:* a class the layouts never instantiate names no behaviour to port, and guessing at its trigger would invent one; the corner mark's colour was already the reference's.
