# GrokBot (vendored)

A copy of [Mr-XiaoLiang/GrokBot](https://github.com/Mr-XiaoLiang/GrokBot), pinned to commit
`2cd2e82f31cddcec7b825877830362b3ec364e33`, used for the character in the voice panel.
The upstream project is a Kotlin translation of the Web engine in
[`blessonism/grok-icon-study`](https://github.com/blessonism/grok-icon-study), which draws the
character as vector art from a generated path table.

## What is here

- `LICENSE` - the upstream MIT text, kept as it is.
- The source lives in `app/src/main/java/com/lollipop/grokbot/`, with the same package as upstream.
- The MIT text also ships in the APK as `assets/licenses/grokbot-MIT.txt`.

## What was changed

The upstream code draws with Compose `Canvas`. This copy draws with `android.graphics.Canvas`
instead, so the IME can use it from its view layer, which is not a Compose tree:

| File | Change |
|---|---|
| `GrokFaceModels.kt` | Colours are ARGB ints, not Compose `Color`. |
| `internal/GrokPathCache.kt` | Holds `android.graphics.Path`; `quadraticTo` is spelled `quadTo`. |
| `internal/GrokRenderer.kt` | Draws onto a `Canvas`: `DrawScope` became a `Canvas` and a size, `withTransform` became `save`/`translate`/`rotate`/`scale`/`restoreToCount`, `Brush.linearGradient` became `LinearGradient`, and a Compose `Matrix` became `Matrix.setValues`. |
| `GrokFace.kt` | `mutableStateOf` became a plain field with an observer, `withFrameNanos` became a `Choreographer` frame callback, and the `Canvas` composable became `GrokBotView`, a `View`. |
| Everything else | Copied as it is. |

`GrokBotState.step`, the engine, the geometry tables and the drawing order are unchanged, so the
frames agree with the upstream reference fixtures.

## How the IME uses it

`llc.slacker.openime.voice.VoiceFaceView` owns one `GrokBotView` and shows a `GrokMood` per step of a
voice session (`VoiceFaceState`). The microphone level drives hops on the onsets of speech
(`VoiceFaceLevelPolicy`). Colours come from the IME theme, through `GrokBotConfig.flatInk`,
`eyeColor` and `badgeColor`.

To remove the character without touching the rest of the voice panel, delete
`app/src/main/java/com/lollipop/grokbot/`, `VoiceFaceView.kt` and `VoiceFaceLevelPolicy.kt`, and take
the face out of `VoicePanelView`.

## License boundary

The MIT license covers the source code only. The character itself, its name and its likeness are fan
art of xAI's Grok and stay the property of xAI. The upstream README says so, and it also asks that
nothing containing the GrokBot likeness be used commercially without permission. openIME ships it as
insignia of a voice session in a free, open-source keyboard; the likeness is not ours to license.
