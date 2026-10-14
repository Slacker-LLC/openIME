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
