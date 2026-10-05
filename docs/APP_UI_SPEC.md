# App UI specification

The app UI text is in Chinese. This document gives the Chinese label with an English note where it is useful.

This specification applies to these screens:

- the home screen (setup state and ready state)
- preferences and fuzzy pinyin settings
- the settings panel and the tool panel in the keyboard

It also applies to the buttons, navigation and switches that these screens share.
The key area of the keyboard keeps its existing reference design.

Before you change a screen, choose values from this specification. Then check the real layout.
Do not add new spacing, font sizes or fixed coordinates.

The product has no key skins (custom accent color, corner radius, opacity, key font size) and no one-hand mode.
Do not add them to the UI.

## Basis and units

The design follows Apple [Color](https://developer.apple.com/design/human-interface-guidelines/color), [Layout](https://developer.apple.com/design/human-interface-guidelines/layout), [Toggles](https://developer.apple.com/design/human-interface-guidelines/toggles) and [Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility), and Material 3 [Color roles](https://m3.material.io/styles/color/roles).

An Apple point is not an Android dp or sp.
The values below are the Android values of this project.
Use dp for size and spacing, and sp for text. Never position the UI with physical pixels.

Reuse the tokens in `ImeDesignTokens.kt` and the resources with the same names in `values/dimens.xml` and `values/colors.xml`.
XML uses resources. Dynamic views use tokens.

## Accent color

The accent color means "you can tap this" or "this is on".
Use it rarely, so that it stays easy to see.

| Use the accent color | Use neutral grey |
| --- | --- |
| Primary buttons and text buttons (such as 允许, "Allow") | Icons of settings rows and tools (dark grey on a grey tile) |
| Switches that are on, the filled part of a slider | Navigation arrows, group titles, description text |
| The current step number and the progress bar in setup | Status labels such as 已允许 ("Allowed") |
| The border and caret of a focused input field | "Ready" (已就绪) and finished steps use a semantic green check mark |
| The on state of quick switches in the keyboard | Selected items in cards and segmented controls (white or light grey surface) |

## Colors

Files: `values/colors.xml` and `values-night/colors.xml`.

| Role | Light | Dark | Note |
| --- | --- | --- | --- |
| Page background `setup_page_bg` | #F2F3F6 | #0F1012 | Status bar and navigation bar use the same color |
| Card `setup_surface` | #FFFFFF | #1B1C20 | Dark theme has no shadow and uses three grey levels |
| Icon tile and track `setup_icon_tile`, `setup_muted` | #EEF0F3 / #ECEEF1 | #2A2C31 | |
| Primary text `setup_title` | #15171C | #F2F3F5 | |
| Description `setup_body` | #5B6270 | #A2A8B3 | Contrast on a card is at least 4.5:1 |
| Divider `setup_hairline` | #E7E9ED | #2C2E33 | |
| Accent `setup_primary` | #1668D0 | #2D6FD6 | White text has a contrast of at least 4.5:1 |
| Accent text `setup_primary_text` | #1668D0 | #8AB4F8 | Blue text on a dark background |
| Light accent tint `setup_primary_tint` | #E3EDFA | #24324A | Background of secondary buttons |
| Success `setup_ready` | #1F8A4C | #1F8A4C | Ready and finished |

Panels in the keyboard use the keyboard tokens (`ImeTheme.IOS`).
Switches and quick switches in the keyboard use the keyboard accent color.

## Layout

| Item | Value | Placement and alignment |
| --- | --- | --- |
| System safe area | Real Android insets | The activity subtracts the status bar, navigation bar and display cutout once |
| Content width | At most 600 dp | Center on wide screens. On narrow screens use the available width. Do not use the 390 dp reference canvas of the keyboard. |
| Page side margin | 16 dp | Cards, input fields and the primary button share the left and right edges |
| Home top space | 28 dp | Starts at the safe area |
| Navigation bar | 56 dp high | Back button 48 × 48 dp. Title 18 sp, medium weight, same background as the page. No separate grey bar. |
| Home brand | 48 dp icon, 20 sp name, 13 sp subtitle | A 44 dp round preferences button appears on the right when setup is done |
| Group title | 13 sp, 16 dp start inset | 28 dp below the previous group, 8 dp above the card |
| Group card | 16 dp corner radius, no shadow | One group of settings in one card, rows separated by a 1 dp divider |
| Divider inset | 60 dp (with icon), 56 dp (step), 14 dp (in keyboard) | Starts at the text start. It does not cross the icon. |
| Settings row | At least 56 dp high, 16 dp padding on both sides | Icon tile 32 dp (9 dp corners, 18 dp icon), 12 dp gap to the text. Switch, value, button and arrow share the 16 dp right edge. The arrow box moves 6 dp right to cancel its built-in margin. A slider track aligns with the text column. |
| Sub-setting row | Same as the settings row | It has no icon and is indented to the text of its parent switch. Examples: sound style, haptic style, haptic strength. |
| Row text | Label 15 sp medium, description 13 sp | 4 dp between label and description. The description can wrap. |
| Text to end control | At least 12 dp | Switch, arrow and value align to the right |
| Segmented control (preferences) | Label above, track below, full row width | 48 dp touch height, 34 dp visible track, 30 dp selected item |
| Slider (preferences) | Label and value in one row, slider below | 40 dp touch area. The value uses one line and tabular digits. |
| Switch | 51 × 31 dp track, 27 dp knob | 2 dp between the knob and each end. The on and off states are symmetric. The touch area is at least 48 dp high. |
| Navigation arrow | 18 dp | The whole row is tappable. The arrow is not a separate button. |
| Primary action button | 52 dp high, pill shape, accent background, white text | Shown only in setup. Fixed 16 dp above the bottom. It runs the current step. |
| Secondary action button | 44 dp high, pill shape, light accent background | Example: the voice 允许 ("Allow") button |
| Input field | At least 52 dp high, 14 dp corners | 1 dp divider-color border. A focused field has a 1.5 dp accent border. |

## Home screen

- **Setup state:**
  Show the text 第 N 步，共 3 步 ("Step N of 3") and a three-segment progress bar. The title is 设置你的新键盘 ("Set up your new keyboard").
  The three steps are in one card.
  The current step has a filled accent circle with its number.
  A finished step has a green check mark.
  A future step has a grey outlined circle.
  The 可选 ("Optional") group contains voice input.
  The bottom primary button changes with the step: 前往系统设置启用 ("Go to system settings to enable") or 切换到 openIME ("Switch to openIME").
- **Ready state:**
  The three steps collapse into one openIME 已就绪 ("openIME is ready") card.
  Below it are, in order: the 试一下 ("Try it") input field with three usage hints, the 语音输入 ("Voice input") status and 常用设置 ("Common settings": 偏好设置 preferences, and 模糊音与智能纠错 fuzzy pinyin and smart correction).
  The bottom primary button is hidden.

## Panels in the keyboard

- The settings panel has the same height as the keyboard.
  The first screen shows these items in order:
  four quick switches, appearance and keyboard height.
  The quick switches are 按键音效 (key sound), 触感震动 (haptics), 按键气泡 (key bubble) and 数字提示 (number hints). Each is 68 dp high.
  The appearance segmented control is on the same row as its label and is 216 dp wide.
  The other settings scroll below. Rows are 52 dp high with no icon and no description.
- The tool panel has seven items in four columns: clipboard, emoji, symbols, text editing, floating keyboard, settings and data management.
  Each item is a 56 dp tile with 16 dp corners and a 12 sp name. Icons are neutral.
  The panel has no voice input (hold space instead) and no keyboard switch (the toolbar has one).
- The last group in preferences and in the keyboard settings is 关于与数据 ("About and data").
  It has two rows: 关于 ("About": version, privacy, diagnostics) and 数据管理 ("Data management": export, import, pre-uninstall note).
- The title row has the same background as the panel. The back button has no background and shows feedback only when pressed.

## Width and font changes

- In preferences, segmented controls and sliders always show the label above and the control below. Narrow screens need no layout switch.
- The three appearance options follow the real text width.
  Example: 320 dp wide with font scale 1.3.
  If the longest label (跟随系统, "Follow system") does not fit, the options stack vertically.
  Each option is still at least 48 dp high.
- Wide screens grow only to 600 dp. Do not stretch switches. Do not stretch the primary button across the screen.
- At font scale 2.0 the page may grow longer and scroll.
  Never cut off a description, reduce the font size or move a button off the screen.

## State and interaction

- Primary buttons, secondary buttons and input fields must show pressed or focused feedback.
  A pressed settings row turns grey over the whole row. The card corner radius clips the pressed state.
- A switch shows its state with the track color and the knob position. The setting saves at once.
  The accessibility node is a Switch with checkable, checked and a state description. Quick switches are ToggleButtons.
- A navigation row is tappable as a whole. Its icon and arrow are decoration and take no focus.
- On the home screen, 模糊音与智能纠错 ("Fuzzy pinyin and smart correction") opens the fuzzy pinyin page. Back returns to preferences.

## Key haptics and sound

- From Android 14 QPR3, `performHapticFeedback(KEYBOARD_TAP)` plays at a fixed amplitude that the device defines.
  On many phones it is weak. Gboard lost its own strength slider for this reason.
  Sogou, Xiaoyi and HeliBoard drive the vibration motor themselves.
- `KeyHaptics` calls `Vibrator` directly.
  The setting 震动手感 ("Haptic style") selects the waveform:
  - **Crisp** (default): `PRIMITIVE_TICK`, `EFFECT_TICK` or an 8 ms pulse.
  - **Strong**: `PRIMITIVE_CLICK`, `EFFECT_CLICK` or a 12 ms pulse.
  - **System**: `KEYBOARD_TAP` (tuned by the vendor, not scaled by strength).
  The usage is touch feedback, so the system touch-feedback strength still applies.
  Long pulses ring on linear motors (such as the Xiaomi X-axis motor), so do not use a pulse longer than 12 ms.
  Without a vibration motor, fall back to `KEYBOARD_TAP`.
- The 震动强度 ("Haptic strength") slider (10% to 100%, default 100%) scales this tap.
  With primitives it scales `PRIMITIVE_CLICK`. With amplitude control it scales the amplitude. Otherwise it shortens the pulse.
  While the user drags the slider, a test vibration plays at most every 80 ms.
- Key press, a delete gesture that reaches its threshold and a space hold that starts voice all use this one tap.
  Do not use `LONG_PRESS`, `CLOCK_TICK` or long vibrations. They ring and feel slow.
  Source: Android [Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles).
- Key sound options:
  - **System:** `AudioManager.playSoundEffect(FX_KEYPRESS_STANDARD, -1)` with the volume parameter.
    The version without a volume parameter is silent when the system touch sounds are off.
  - Five synthetic sounds in `res/raw/key_sound_*.wav` (crisp, mechanical, wooden, typewriter, bubble), played by `SoundPool` as UI sounds.
  A new choice plays once at once. It never plays the old sound or haptic first.
- In preferences, the group 按键与输入 ("Keys and input") shows these rows in order: 按键音效 key sound, 音效 sound style, 触感震动 haptics, 震动手感 haptic style, 震动强度 haptic strength.
  In the keyboard settings, 音效 and 震动手感 are in the group 按键反馈 ("Key feedback").

## Acceptance

Run `scripts/beta4_e2e.py`.
It taps real settings and buttons.
It checks size, left and right edges and Button and Switch roles in the real UI XML.
It covers light and dark themes, 320 dp wide with font scales 1.3 and 2.0, and a wide screen.
Keep the screenshots, XML, JSON and logs.
Then check line wrapping, control position and state by eye.
