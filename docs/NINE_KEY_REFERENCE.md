# Nine-key input design

This document records how other input methods build a nine-key keyboard and what openIME decided.
Read it before you change the nine-key keyboard, the syllable column or the delete gesture.

## Sources

| Source | Type | What openIME takes from it |
|---|---|---|
| [rime-t9-shiyin](https://github.com/Koishi-Neko/rime-t9-shiyin) | Open source, with an engine report | The left column lists valid first syllables of the digit string. A tap locks the syllable and does not commit. The candidate bar shows only words. Swipe up on delete clears the text. |
| Trime and the Cangshu nine-key guide | Open source | Letters match exactly and digits match fuzzily. Key `1` splits words. Lock, unlock and undo. |
| [Baidu Input nine-key help](https://jingyan.baidu.com/article/19020a0a7ee4ab529c284246.html) | Official help | The left of the keys shows exact pinyin. You can scroll the list to change the pinyin. Key `1` splits words. |
| [Sogou Input help](https://shouji.sogou.com/wap/feedback/faqdetail?id=2004148&click_fr=3&platform=Android) | Official help | Swipe up on delete clears. Repeated clears keep only the last one. |
| iOS nine-grid keyboard | System keyboard | The pinyin code area and the text candidate area are separate. There is no separate split key. |

Other keyboards are closed source, so we used only their public help pages.

## Behavior in openIME

1. **One syllable for one character.**
   Each item in the left column is one syllable for the next character (`ni`, `mi`).
   A tap locks it. Then the list moves to the next character.
   The user does not choose pinyin for a whole sentence.
   The preedit shows the reading of the whole word. To change a character, pick it from the candidates.
   The list never offers a syllable that leaves the remaining digits unreadable.
   A lone `a`, `o` or `e`, and a bare `ng` or `m` without a vowel, are not readings.
   An item with only an initial (for example `m` or `n` after you press `6`) also locks.
   The candidates then filter by that letter (`m` → 没, 么, 们).
2. **Locked syllables go to Rime as letters** (`xiong'486`).
   The luna_pinyin schema accepts letters and the digits 2 to 9.
   Readings such as zhong and xiong that share digits stay separate, and the candidates match the chosen reading.
3. **A locked syllable stays locked while the user types on.**
   A boundary seals it.
   Backspace first unlocks the latest locked syllable or split boundary.
4. **Syllable boundaries in the preedit are always apostrophes** (`ni'hao`).
   The locked prefix comes from the view record.
   Do not infer it from spaces or apostrophes in the text, because the decoder also inserts separators.
5. **Words that match the digits exactly come before predictions.**
   For `9426` (xian), Rime can put 自从 (a prediction of zi'cong) first.
   openIME groups candidates by whether the reading uses up exactly the typed digits.
   The Rime order stays inside each group. No candidate is lost.
6. **The preedit follows the reading of the first candidate.**
   For 我想吃饭, it shows `wo'xiang'chi'fan`.
   If the first candidate covers only the first part, openIME takes characters until the candidate cannot cover more.
   For each character it tries the full pinyin, a cut syllable and the initial. Example: `669` → 模型 → `mo'x`.
   It spells the remaining digits as letters.
   **The preedit never shows digits.**
   If no word helps, openIME builds the preedit locally from the fewest full syllables, with an unfinished syllable at the end (`46` → `go`).
7. **If a selected word covers only part of the input, openIME commits only that word.**
   The remaining input stays as preedit. This is the same for nine-key and 26-key.
   openIME does no native learning for a partial selection,
   because a whole-sentence commit would write phrases that the user never selected into the user dictionary.
8. **Space commits the first candidate.**
   Enter (Confirm) commits the typed pinyin text. Rime, fcitx and Gboard pinyin work this way.

## Delete key gesture

- Swipe up 32 dp or more to clear. The final touch position counts as the last move.
  Clear is final. There is no swipe-down undo. This is a product decision.
- One bubble gives the feedback.
  Before the threshold it is dark and says "swipe up to clear" (上滑清空).
  After the threshold it turns red and says "release to clear" (松手清空).
  It sits beside the key, because the thumb covers the area above the key.
- "Clear all" cannot depend on the select-all action of the editor.
  Custom-drawn, Compose and web fields often have no select-all and no full `ExtractedText`.
  The gateway therefore has a third path.
  It uses only the text before and after the cursor, reads all of it and deletes in a loop until the editor reports empty.
  If a step fails, it puts the deleted text back.
  If the returned length equals the requested length, the result can be only a window. The gateway then refuses to delete.
- The debug `CustomEditorTestActivity` reproduces such editors.
  Before the fix, the gesture fired and the text stayed with no message.

## Verification

```bash
bash scripts/core_regression.sh emulator-5554   # 26-key, nine-key, Enter, partial selection
./gradlew :app:testDebugUnitTest --tests '*CandidatePipeline*' --tests '*InputConnectionGateway*'
```

Density, system gestures and the default keyboard differ between phones and emulators.
Both can change gestures and layout.
On a real device, the debug build writes gesture events to the `OpenIme` log tag
(`bs begin`, `clearArmed`, `finish`, and `touch CANCEL` if the system cancels the touch).
