# Architecture

This document describes the production runtime as it is today.

## Runtime

```text
Android InputMethodService
        │
        ▼
LocalVoiceImeService
        ├── ImeState
        ├── CandidatePipeline / CandidateSnapshot
        │       ├── CandidateEngine (fallback when Rime is not ready, English suggestions)
        │       ├── NativeCandidatePipeline
        │       └── RimeEngine → RimeNative (JNI) → librime / OpenCC
        ├── InputConnectionGateway
        ├── VoiceModelLifecycleManager
        │       ├── VoiceModelRepository
        │       ├── LocalAudioVoiceBackend / VoiceAudioRouteManager
        │       └── sherpa-onnx
        └── IME window
                └── ImeKeyboardView (orchestration, geometry, editor state)
                        ├── ImeTopZone + CandidateBarController
                        ├── Pinyin26 / Pinyin9 / Stroke / Numeric keyboard renderers
                        ├── ImePanelRenderer
                        │       ├── ClipboardPanelController
                        │       ├── TextEditorPanelController
                        │       └── SettingsPanelController
                        ├── VoicePanelController / VoicePanelView
                        ├── InlineVoicePresenter
                        ├── FloatingKeyboardController
                        ├── BackspaceGestureController / SpaceVoiceGestureController
                        ├── KeyPopupController
                        ├── NineKeySegmentRepairController
                        └── ImeThemeApplier
```

`ImeKeyboardView` is the only top-level keyboard view.
It owns window insets, responsive geometry, editor and composition coordination, and the orchestration of the classes in the diagram.
Each of those classes owns one part of the user interface.

## State ownership

The service and the domain modules own the business state.
Views keep only short-lived display state.

| Owner | State |
|---|---|
| `LocalVoiceImeService`, `ImeState` | Editor, keyboard mode, panel, composition, candidates, shift, settings, privacy state |
| `CandidatePipeline`, `CandidateSnapshot`, `RimeEngine` | Candidate generation, generation numbers, native identity, Rime session |
| `VoiceModelLifecycleManager` | Speech runtime, warm-up, recording, cooldown |
| `InputConnectionGateway` | All side effects on the target editor |
| `ImeKeyboardView` | Editor and composition coordination, measured geometry, panel and window orchestration |
| Controllers | Their own short-lived state. Examples: candidate scroll, panel tab, held-key pointer, popup lifetime, floating drag |

`NineKeyUiState` is a cache of ambiguous paths and explicit choices inside one `CandidatePipeline`.
It does not own candidate order or editor state.
Do not move state like this into global state.

## Commit rules

1. Unfinished pinyin is composing text. Never write it as normal text into the target app.
2. Commit only when the user selects a candidate or presses space, Enter or another explicit commit key.
3. Delete the openIME composition first. Then delete text in the target editor.
4. Commit a candidate with the rendered `CandidateSnapshot` or native identity.
   This stops an old asynchronous result from reaching a new composition.
5. When the input field, the mode or the session changes, invalidate the old generation and session.
6. In password fields, do not compose and do not read the text. Do not learn words or corrections.
   Clipboard history and voice input work. Voice inserts the final text only.
   Editors that ask for no personalized learning, and clipboard items that the source app marks as sensitive, do not enter persistent history.

## Candidates and Rime

- When librime is ready, it is the source of Chinese candidates.
- `CandidateEngine` is a local fallback. It also gives English suggestions and some nine-key help.
  It is not a second Chinese engine.
- `CandidateSnapshot` makes sure that the candidates the user sees and the candidates that are committed come from the same generation.
- A native query cannot be interrupted from Kotlin after it enters JNI.
  Do not change librime internals without latency data from real devices.

## Window and layout

- Docked and floating are IME window states. They are not panels.
- Rotation changes only the responsive geometry. It does not change the docked or floating choice.
- `KeyboardLayoutMetrics` does pure dp calculation. The view applies the result to the layout parameters.
- All layout uses the real size of the IME window and its `WindowInsets`. It uses no fixed screen coordinates.
  See [Coordinate system](COORDINATE_SYSTEM.md).

## Voice

`VoiceModelLifecycleManager` is the only owner of the speech runtime.
Model checks, warm-up, build and release never run on the main thread.
For details, see [Voice input](LOCAL_VOICE_MODEL.md).

## Packages

The code is split into packages. A package may depend only on packages below it.
`ArchitectureLayeringTest` enforces the direction.
To add a dependency, first change the table in that test.

```text
                     app (root package: service, activities, RimeNative)
                                        │
                                    keyboard ──────┐
                  ┌──────────┬──────────┼──────────┤
                panel      voice    candidate   floating
                  │          │          │          │
       widget  handwriting   │         rime        │     setup
                  └──────────┴──────────┴────┬─────┴───────┘
                                             data
                                            editor
                                             core
                                            theme
```

| Package | Purpose | Depends on |
|---|---|---|
| `theme` | Design tokens, themes, drawing helpers | none |
| `core` | `ImeState`, keyboard modes, static data, crash guard | theme |
| `editor` | Target editor boundary: `InputConnectionGateway`, `EditorInfoAdapter`, Enter and selection policy | core |
| `data` | Preference and file repositories, user data import and export | core, editor, theme |
| `setup` | Shared UI helpers for activities | data, theme |
| `widget` | Reusable controls: `ImeKeyView`, `SwipeUpDetector` | theme |
| `floating` | Floating keyboard window, drag, card style | theme |
| `handwriting` | Handwriting pad | data, theme |
| `rime` | librime wrapper, input normalization, native candidate references | core, data |
| `candidate` | Candidate pipeline, snapshots, nine-key decoder, fuzzy pinyin, lexicons | core, rime |
| `voice` | Speech recognition, model lifecycle, voice panel, text post-processing | data, editor, theme |
| `panel` | Tool, clipboard, settings and text-edit panels | core, data, handwriting, setup, theme, widget |
| `keyboard` | `ImeKeyboardView`, keyboard renderers, top zone, gestures, popups | all packages above except app |
| `app` (root) | Service and activities from the manifest, JNI class | all |

Rules:

- A low package must not use a high package.
  Two exceptions exist, and the test lists them.
  `panel` and `keyboard` start some activities by class name.
  `rime` calls the JNI class `RimeNative`. Its name is tied to native symbols, so it cannot move.
- Keep only manifest entries, JNI classes and classes that scripts name in the root package.
- The voice layer talks back to the UI through two narrow interfaces:
  `VoiceSessionHost` and `VoiceEditorContext`.
  It does not use `ImeKeyboardView` or the service directly.
- Put a new feature in its own package.
  Split it into pure logic, Android boundary and one entry object.
  Pure logic files do not import `android.*`.
- Use `internal` visibility by default.
  Only activities that the manifest needs are public.

Two classes are still large: `ImeKeyboardView` (about 3,100 lines) and `LocalVoiceImeService` (about 1,900 lines).
`ImeKeyboardView.Listener` has many methods.
Split them next. Follow the package rules above and add no cross-package dependency.

## Product capabilities

| Capability | Implementation | Status |
|---|---|---|
| 26-key pinyin | `ImeKeyboardView`, `CandidatePipeline`, Rime | Production |
| Nine-key pinyin | `ImeKeyboardView`, `NineKeyLocalDecoder`, Rime | Production. No English nine-key. |
| Stroke | `StrokeKeyboardRenderer`, `StrokeLexicon` | Production |
| English 26-key | `CandidatePipeline`, `CandidateEngine` | Production |
| Digits, phone, decimal | `EditorInfoAdapter`, `NumericKeyboardRenderer` | Production |
| Symbols | `SymbolCatalog`, `CustomSymbolRepository` | Production |
| Emoji | `EmojiCatalog`, `EmojiRecentRepository`, Fluent assets | Production |
| Clipboard history | `ClipboardHistoryRepository`, `InputConnectionGateway` | Production. See the privacy rules above. |
| Quick phrases | `QuickPhraseRepository`, `QuickPhraseEditActivity` | Production |
| Text editing | `InputConnectionGateway` | Production. Available actions depend on the editor. |
| Voice | `VoiceModelLifecycleManager`, sherpa-onnx | Production. Hold space to speak. |
| Handwriting | `HandwritingPadView` | Pad only. No recognition engine. The entry point is hidden. |
| Floating keyboard | `FloatingKeyboardController`, `LocalVoiceImeService` | Production |
| Settings | `ImeSettingsRepository`, settings UI | Production |

openIME has no AI writer and no online model.

## Native and third-party code

The project maintains `app/src/main/cpp/local_rime_jni.cc` and the CMake glue.
Vendored librime, OpenCC and Boost are not refactoring targets.
Change vendored code only for a confirmed native defect that has a test.

## Tests

- JVM unit tests: pure policy, candidates, input connection, state, geometry.
- Android instrumentation tests: real views, IME lifecycle, API compatibility.
- Debug source set: E2E receiver and test activities. Only test APKs use them.
- Scripts: real-device evidence for input, visuals, performance, upgrade and security.

See [Test architecture](TEST_ARCHITECTURE.md).
