# Full test procedure (SOP)

This procedure is the fixed gate for each version.
Its goal is not to find and test one bug at a time.
For each version, run the same levels and stop at the first failure.
Save logs, screenshots, UI trees, recordings and performance data, so that others can review them.

## 1. Levels and commands

| Level | When | Target time | Automated scope |
|---|---|---:|---|
| L0 Build gate | Each build | 5 to 10 minutes | Build, unit tests, install, 26-key, nine-key pinyin, digits, basic delete |
| L1 Core regression | Each feature change | 30 to 45 minutes | L0, full pinyin engine, field matrix, candidates, panels, lifecycle, visuals of the current window |
| L2 Full regression | Before you push to a phone or GitHub | 2 to 4 hours | L1, lint, upgrade, performance, and the manual device, app and size matrix |
| L3 Release acceptance | Before a release APK | Half a day or more | L2, privacy, permissions, stress, several devices and 30 minutes of stability |

Single entry point:

```powershell
.\scripts\test_sop.ps1 -Level L0 -Serial <serial>
.\scripts\test_sop.ps1 -Level L1 -Serial <serial>
.\scripts\test_sop.ps1 -Level L2 -Serial <serial> -FreshInstall
.\scripts\test_sop.ps1 -Level L3 -Serial <serial> -FreshInstall
```

To see the steps without a device:

```powershell
.\scripts\test_sop.ps1 -Level L3 -ListOnly
```

`-FreshInstall` uninstalls openIME and deletes its settings, word frequencies, clipboard and quick phrases.
Use it only on a test device that you may reset.
An upgrade-keeps-data test does not replace a fresh-install test. Keep evidence for both.

## 2. Stop at the first failure

The level fails at once, and the next steps do not run, if any of these occurs:

- You cannot type, the nine-key keyboard outputs digits directly, or pinyin or candidates disappear.
- Delete removes the wrong object, a swipe-up clear fires by mistake, or text is lost, repeated or reordered.
- A crash, an ANR, a black screen or a death of the input method process.
- The UI overflows the window, the last column is cut off or a key button cannot be tapped.
- An automatic assertion fails, or evidence collection finds an invalid PNG or a missing test host.

After a fix, run the failed level again from its first step.
Do not run only the failed case and then claim that the whole level passed.

## 3. Evidence folder

The single entry point writes evidence to a folder that Git ignores:

```text
.local/test-runs/<time>-<level>-<device serial hash>/
├── metadata.json
├── summary.json
├── summary.md
├── manual-checklist.md
├── steps/          full output of each script
├── screenshots/    PNG before and after each step
├── ui/             uiautomator XML before and after each step
├── video/          screen recording of each step, at most 180 seconds
└── system/         logcat, errors, meminfo, gfxinfo, input_method
```

The metadata records these items:

- the Git commit and the number of dirty files
- the APK SHA-256, size and time
- the phone model and the Android version
- the display size and density, the font scale, the navigation mode and the theme
- the current input method
The evidence never contains the raw device serial. It keeps only the first 12 characters of its SHA-256.

`screenrecord` records at most 180 seconds in one segment.
Automatic steps record one segment per step.
For a manual test of voice, scrolling or stability that is longer than 3 minutes, record in consecutive segments by hand.
Write the file names and the time points in `manual-checklist.md`.

## 4. Test environment matrix

L2 covers at least the widths 320, 360, 390, 412, 432 and 600 dp.
390 × 296 is only the design reference. It is not a fixed pixel size.
At each width, check:

- dynamic column width and spacing
- the space key is centered
- the maximum content width of 600 dp
- stable key height
- `WindowInsets`
- the last column and the last row are not cut off

The system combinations include at least:

- light, dark and follow-system themes
- gesture navigation and three-button navigation
- font scale 100%, 130% and 150%, with default and large display size
- landscape, portrait, split screen, background and foreground, restore after lock screen, restore after the process is killed

The script checks the display matrix. Do not replace it with manual checks:

```bash
python3 scripts/display_matrix_regression.py --serial <serial>
```

The script switches between these environments:
portrait, landscape, font scale 130% and 200%, dark theme, small screen, narrow screen, tablet (portrait and landscape) and the inner screen of a foldable.

In each environment it asserts two things.
The keyboard is a bottom panel, not fullscreen extract mode.
Every key and toolbar button has a size and is inside the window.

It saves a screenshot of each environment for review.
At the end it restores all system settings.

The debug APK has `ImeTestLabActivity`.
It has these fields:

- normal, multiline and password
- number, phone, email and URL
- search, chat send, Next and Done
- a field with 10,000 characters
- a field with preselected text to replace
The release APK contains and exports no test activity or receiver.

External apps include at least a native EditText, a WebView, a browser address bar, a notes app, a chat input and a search field.

## 5. L0 fixed smoke test

For each APK, type these inputs:

```text
nihao
woxiangchifan
OpenIME
1234567890
，。！？@#/
```

Then check these items:

- The candidate `你好` appears.
- Nine-key input `64426` gives `你好`.
- Delete removes one pinyin letter.
- After a commit, delete removes one Chinese character.
- A swipe-up clear can be canceled and can be confirmed.
- The emoji, symbol, clipboard and quick phrase panels open.
- A long press on space starts voice, and a swipe up cancels it.

Add the real gestures that no script covers to the manual checklist of the same run.
Do not mark L2 or L3 as a release pass while an item is unchecked.

## 6. Chinese 26-key

| Category | Input | Required candidate or result |
|---|---|---|
| Full pinyin | `nihao` | 你好 |
| Full pinyin | `woxiangchifan` | 我想吃饭 |
| Full pinyin | `jintiantianqibucuo` | 今天天气不错 |
| Full pinyin | `zhonghuarenmingongheguo` | 中华人民共和国 |
| Full pinyin | `changancheng` | 长安城 |
| Word split | `xian` | 先, and 西安 is reachable |
| Word split | `xi'an` | 西安 |
| Word split | `changan` or `chang'an` | Results for 长安 |
| Abbreviation | `zg`, `bj`, `wms`, `jttq` | The matching words are reachable. Full pinyin stays first. |
| Mixed | `woxhcs` | With no result, the editable pinyin stays |

After each key, the preedit and the candidates must update. They must never show internal digits or numbers.
The pinyin cursor must allow delete and insert in the middle and a move to the start.
Long pinyin scrolls horizontally and must not change the keyboard height.

Type long text at slow, normal and fast speed, with 10, 20 and more than 50 characters.
Include a selection in the middle, delete corrections and app switches.
The requirement is 0 lost keys, 0 repeated keys and 0 reordered keys.
The committed text must match the remaining composition.

## 7. Nine-key pinyin (P0)

- Only the pinyin nine-key exists. There is no English nine-key and no separate `0` key. The space key is centered.
- A tap on a nine-key only updates the pinyin path and the candidates. It never commits a digit to the text. Only a long press enters a digit.
- Fixed sequences:
  - `64426` → 你好
  - `94664486` → 中国
  - `9694264244326` → 我想吃饭
  - `9426` → 先 or 西安
  - `94` + split key + `26` → 西安
- Delete removes the nine-key composition first. After a selection, the input method state is empty.
- Do 10, 20 and 50 fast taps at normal, fast and very fast speed. The tap count must match exactly, and vibrations must not pile up.

## 8. Candidates, delete and clear

The candidate bar must work with 0, 1, 3 to 5, more than one screen, very long, mixed Chinese and English, and fast updating states.
Check these actions:

- a tap on the first item, a middle item and the last item
- horizontal scroll, expand and vertical scroll
- partial selection
- the frequency boost of user words
The last item must not stretch to fill the row.

The delete order is fixed: composition, then candidate state, then text in the target editor.
Emoji must delete as complete grapheme clusters.
A long press stops at once on release.
Delete on an empty state must not crash.

Swipe-up clear must meet these rules:

- It starts only at 32 dp or more of upward move.
  Before that, a dark bubble says 上滑清空 ("swipe up to clear"). After that, a red bubble says 松手清空 ("release to clear").
- It gives light feedback on start.
- A move back or sideways cancels it.
- It runs once on release.
- After the clear, the text, the pinyin and the candidates agree. No undo bar and no "cleared · undo" text appears.
- It must work in fields that have no select-all and no full `ExtractedText` (custom-drawn, Compose and web).
  Use the debug `CustomEditorTestActivity` to reproduce them.

## 9. Space, voice, bubbles and haptics

The only voice entry point is a long press on space of about 150 ms.
A short press types a space or commits the first candidate.
During a long press, recording starts at once and the status, the waveform and the live text appear.
Release stops and commits. A swipe up arms the cancel, and a release discards the audio.

The fixed test phrases include these items:

- a Chinese question and a reminder sentence
- an English sentence and mixed Chinese and English
- 30 seconds of continuous speech
- background noise and silence
- spoken punctuation
Voice must work offline.
A denied permission must be recoverable.
Voice works in password fields, but it commits only the final text once.
It shows no partial text, does no word or correction learning, and keeps no unnecessary PCM or temporary text.

The key bubble must anchor to the current key.
It is about 1 to 1.2 times the key width and about 52 dp high, and it moves inward at the edges.
The vibration fires once at the moment of the press, is light by default and respects the system haptic switch.
Fast typing must not create a heavy continuous vibration.

## 10. Modes, floating keyboard and panels

Cycle 50 times: 26-key → nine-key pinyin → digits → symbols → emoji → tools → 26-key.
You must be able to go back during the animation.
There must be no white or black flash and no old layout. The total keyboard height must stay stable.
The tool page has an entry to switch keyboards.
It has no duplicate tool entry and no old "game keyboard" name.

The floating keyboard must move over the whole usable window, including the five corners and the center.
It must not get lost, block system gestures or cause false key presses.
Its position must restore and re-constrain after rotation, app switch and lock screen.

## 11. Emoji, symbols, clipboard and quick phrases

- **Emoji.**
  Emoji use the Microsoft Fluent Emoji set.
  A category must change the real data.
  The last row does not stretch.
  There is no empty box, no duplicate, and no mismatch between image and Unicode.
- **Symbols.**
  Symbols cover Chinese, English, math, serial numbers, units, currency, arrows, brackets and special symbols.
  Categories scroll and really filter.
  The side symbols support tap, long press for the same group, swipe to choose or cancel, groups, custom symbols and persistent sorting.
- **Clipboard.**
  Test short, long, multiline, URL, mixed Chinese and English, emoji, duplicate, blank and sensitive items.
  A tap inserts the text unchanged.
  Pin, delete, clear, scroll and restore after restart must be correct.
  In a password scene, the user may view and paste the clipboard content that the user put there. openIME must not copy the password text out.
- **Quick phrases.**
  Test add, rename and delete of categories, and add, edit and delete of templates, with multiline text and emoji.
  A tap inserts at the cursor.
  Phrases stay after a restart and an upgrade.
  Empty, duplicate and very long values have a clear result.

## 12. Settings, adaptation and visuals

Open all settings level by level.
Appearance has only light, dark and follow system.
There are no key skins and no one-hand mode.
Fuzzy pinyin is on its own page.
Settings scroll, have no dead button, return to the right level and persist.
The removed skin, corner radius, one-hand mode and old theme options must not remain.

At each target width, capture these screens:

- empty keyboard, 1 letter, long pinyin, and candidates beyond one screen
- 26-key, nine-key and digits
- symbols, emoji, clipboard and quick phrases
- tools and settings
- voice, clear, bubble and floating keyboard

Check each screen for these problems:
overflow, covering, overlap, cutting, icon centering, row height, an enlarged middle row, a stretched last row, jumps in keyboard height and theme contrast.

## 13. Performance, stress, failures and privacy

| Metric | Target |
|---|---:|
| Press visual feedback P95 | ≤ 50 ms |
| Pinyin and candidate refresh P95 | ≤ 100 ms |
| Show keyboard on warm start P95 | ≤ 150 ms |
| Page animation | About 120 to 200 ms |
| Voice recording feedback | ≤ 200 ms |
| Voice release to commit | ≤ 800 ms if possible |
| Lost, repeated or reordered fast taps | 0 |
| Crashes and ANRs | 0 |

L3 stress covers at least these items:

- 1,000 taps each on 26-key and nine-key
- 30 seconds of delete
- 500 candidate actions
- 200 mode switches
- 5 minutes of panel scrolling
- 5,000 characters
- 100 background and foreground switches
- 50 rotations

Check CPU, PSS, heat, model loading and recovery from low memory.

Failure tests create these conditions on purpose:

- a revoked permission
- a missing or corrupt model
- a process kill
- empty candidates or a timeout
- a very long clipboard
- lock screen, app switch, rotation, and keyboard or theme switch
- no network and low storage

A failure must never damage the text or carry an old composition into a new app.

Privacy gate:

- Typing and local voice work offline.
- Passwords are not learned and not logged.
- Crash logs do not contain user text.
- A canceled voice session clears its temporary state.
- The clipboard and quick phrases are only in the private directory.
- The app has no unrelated permission.
- The models have an integrity check, and a failed model load does not affect normal typing.

## 14. Release conditions

- P0 blockers and P1 severe problems are 0.
- 100% of core input cases pass.
- Fast taps: 0 lost, 0 repeated, 0 reordered.
- 0 overflow at all target widths.
- 30 minutes of continuous use: 0 crashes, 0 ANRs.
- `manual-checklist.md` of L2 and L3 is complete.
  Mark an item that you did not run as `NOT TESTED`. Never write `PASS` for it.
- Keep only P2 problems that do not affect typing, and list them in the release notes.

Do not use `adb shell input text` to prove that the keyboard works.
You may use real touch or UI automation.
You may also use the debug-only receiver to inject semantic key events into the production key handler.
That method still uses the real `InputMethodService` and `InputConnection`.
Never replace the system path with a fake input field.
