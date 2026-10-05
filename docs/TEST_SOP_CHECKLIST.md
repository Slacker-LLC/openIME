# L2 and L3 manual acceptance checklist

`scripts/test_sop.ps1` copies this file to `manual-checklist.md` in each `.local/test-runs/...` folder.
Check an item only after you ran it and have evidence.
Write `NOT TESTED` for an item that you did not run. Never check an item in advance.

## Run information

- [ ] I wrote the APK version, build time, Git commit and test apps.
- [ ] The screen recording shows touch positions, so a problem can be found by video time.
- [ ] I recorded the phone model, Android version, width and density, theme, navigation mode and font scale.

## Size and system

- [ ] 320 dp: 26-key, nine-key, digits and panels do not overflow.
- [ ] 360 dp: 26-key, nine-key, digits and panels do not overflow.
- [ ] 390 dp: reference only. Nothing depends on fixed pixels.
- [ ] 412 dp: nothing is cut off, space is centered, keyboard height is stable.
- [ ] 432 dp: nothing is cut off, space is centered, keyboard height is stable.
- [ ] 600 dp: content is 520 to 600 dp wide and centered.
- [ ] Light, dark and follow-system themes are checked.
- [ ] Gesture and three-button navigation do not cover the keyboard.
- [ ] Font scale 100%, 130% and 150%, and large display size, do not cut off anything.
- [ ] Landscape, portrait, split screen, background and foreground, and restore after lock screen pass.

## External input fields

- [ ] Native Android EditText.
- [ ] WebView input.
- [ ] Browser address bar and search box.
- [ ] Notes app with long text.
- [ ] Chat input and "Send".
- [ ] Form Next and Done.
- [ ] Field with 10,000 characters, and selection replacement.

## Core gestures and feel

- [ ] A swipe up on delete below the threshold does not clear.
- [ ] After clear is armed, the bubble is white text on red. A move back or sideways cancels. A release runs once.
- [ ] A long press on the split key slides between `@`, `#` and `/`. Sliding out cancels.
- [ ] A long press of about 150 ms on space starts voice. A release commits. A swipe up cancels and leaves nothing behind.
- [ ] Key bubbles at the left and right edges and in the bottom row are anchored correctly and stay inside the screen.
- [ ] The default haptic is light, fires at press and does not pile up in fast typing.
- [ ] A horizontal swipe on space moves the cursor.
  During the swipe, the other keys in the bottom row turn grey and do not respond. They recover on release.
  A tap on space still types a space.
  While you type pinyin, the swipe moves the pinyin cursor.

## Voice phrases

- [ ] "你好，今天天气怎么样？"
- [ ] "明天下午三点提醒我开会。"
- [ ] "OpenIME is a Chinese and English input method."
- [ ] A mixed Chinese and English sentence, 30 seconds of Chinese, and 30 seconds of mixed Chinese and English.
- [ ] Background noise, silence, and spoken comma, period, question mark and line break.
- [ ] Works without a network. A denied permission is recoverable. Password fields are protected correctly.
- [ ] In "嗯我觉得呃这个不错" the fillers are removed. "额度", "金额" and "呃逆" stay unchanged. A result that is only "嗯" stays unchanged.
- [ ] With 标点用空格代替 ("Replace punctuation with spaces") on, a spoken comma or period becomes a space and the final punctuation disappears. `3.5` and `a.b` stay unchanged.

## Panels and floating keyboard

- [ ] 50 mode cycles show no white flash, height jump, old layout or random state loss.
- [ ] Emoji categories really filter and scroll. The last row does not stretch. No resource is empty.
- [ ] Symbol categories really filter. Side symbols support select, cancel, sort and persistence.
- [ ] Clipboard: short, long, multiline, URL, emoji and sensitive items can be inserted, pinned, deleted and restored.
- [ ] Quick phrases: categories and templates can be added, edited, deleted and inserted. They stay after a restart and an upgrade.
- [ ] Tools and all settings pages scroll, have no dead button, return correctly and show no old theme or corner-radius option.
- [ ] The floating keyboard can move to the five positions and the edges, re-constrains after rotation and can dock again.
- [ ] On Chinese and English 26-key, each letter key shows a number or symbol at the top right.
  A swipe up and a long press enter the hint. A tap still enters the letter.
  Chinese mode enters full-width forms (！ ￥ ？ （ ） ： ；). English mode enters half-width forms.
  Landscape also works.
  When you turn off 数字和符号提示 ("Number and symbol hints"), the hints and the gestures disappear together, and the layout does not change.
- [ ] After you select 开心 ("happy"), the suggestion bar starts with 😊 😄 😁.
  After you turn off 表情联想 ("Emoji suggestions"), they no longer appear. They do not appear in password fields.
- [ ] Autofill (Android 11 and later): in a login field the strip shows the account.
  A tap fills it and the toolbar returns. The "‹" button collapses the strip.
  Normal fields show no strip. Landscape also works.

## Release stability

- [ ] 1,000 taps each on 26-key and nine-key: 0 lost, 0 repeated, 0 reordered.
- [ ] 500 candidate actions, 200 mode switches, 5,000 characters, 100 background and foreground switches and 50 rotations.
- [ ] 30 minutes of continuous use: 0 crashes, 0 ANRs.
- [ ] CPU, PSS, frame rate, heat and the first and second load time of the voice model are recorded.
- [ ] P0 = 0 and P1 = 0. The remaining P2 items are in the release notes.

## Evidence notes

```text
Recording files:
Problem time points:
Screenshot files:
Test apps:
NOT TESTED items and reasons:
```
