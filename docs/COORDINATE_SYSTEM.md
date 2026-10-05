# Coordinate system

Keyboard tests and UI calculations never use screen pixels.
A pixel position such as "the Q key is at (123, 1876) on a 1080 × 2400 screen" breaks on other screens.

Instead, all positions are normalized to the content area of the input method:

```text
Origin:  top left corner of the IME content area
Range:   0.0 to 1.0
Runtime: normalized × keyboardWidth / keyboardHeight → real px
```

This way, the same tests work on 720p, 1080p and 2K screens, at different densities, in landscape and on screens with rounded corners.

## Implementation

File: `app/src/main/java/llc/slacker/openime/keyboard/KeyboardGeometry.kt`

- `NormalizedBounds(left, top, right, bottom)` is a normalized rectangle.
- `NormalizedBounds.fromView(view, root)` builds it from the measured size of a real `View`.
- `toPx(rootWidth, rootHeight)` converts it to real pixels at runtime.

`ImeKeyboardView.normalizedBoundsReport()` prints one line for each key:

```text
key|x,y,w,h
```

The values x, y, w and h are relative to the current IME root. They are not relative to the screen.

## Layout rules

- Rows use `Row + Weight + Relative Insets`.
- Key width comes from weight. Never write fixed XY values for keys.
- 26-key layout: 10 keys in row 1, 9 centered keys in row 2, the Shift-to-M row, and the bottom function keys.
- Nine-key and digit layouts: a filter column on the left, a grid in the middle and an action column on the right. There is no English nine-key.
- Height and font: dp and sp have minimum and maximum values, so tablets and foldables stay in range.
- Popups, animations and anchors are all calculated from key bounds.

## Test automation

`scripts/core_regression.ps1` and `scripts/extended_regression.ps1` read no absolute XY values:

- `E2ETestReceiver` runs `tap:<semantic-label>` through the click listener of the real `InputMethodService`.
- The scripts find the focus with a live `uiautomator dump` of the EditText bounds.
- The scripts find the mode with the `state` command, which reads `ImeState`.

So the scripts run on emulators and on real phones.
