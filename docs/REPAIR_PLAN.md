# openIME Stabilization, UI and Interaction Repair Plan

Status: active execution baseline
Base: main @ 8c0daf696bf9a5ff7f61096180fdecf591a5753b
Branch: fix/ime-stabilization-ui

## Goal

Stabilize the current product before further feature growth, converge UI and interaction behavior into one coherent system, then resume visual polish and missing feature work. The project should not migrate to Compose or add abstraction layers unless a current problem requires them.

## Execution rules

1. Do not add unrelated features while the stabilization gates are red.
2. Fix root causes and all active callers instead of patching only the failing assertion.
3. Reuse existing helpers, policies, repositories and native Android capabilities before adding new layers.
4. Do not add interface/provider/factory abstractions for single implementations unless a real current boundary needs them.
5. Keep the production interaction contract and tests aligned. Never make a test green by preserving a dead UI state.
6. Preserve current mature interaction work (26-key input, voice-in-space, backspace swipe-to-clear, candidate pipeline) while restructuring around it.
7. Every phase must leave the branch buildable. Structural extractions are mechanical first, behavioral changes second.

## Phase 1 — Restore a trustworthy green baseline

Current blocking failures must be resolved before broad UI refactoring:

- password-field clipboard history policy mismatch;
- password-field paste/control availability mismatch;
- floating keyboard tests targeting the removed legacy gaming panel;
- panel focus test expecting controls that no longer exist;
- clipboard clear-confirmation action mismatch;
- clipboard open/capture lifecycle mismatch.

Required baseline:

- testDebugUnitTest: pass;
- lintDebug: pass;
- assembleDebug: pass;
- API 29 Android tests: pass;
- API 31 Android tests: pass.

## Phase 2 — One capability source of truth

Create one small capability model derived from actual runtime state instead of repeating conditionals across View, Service, Repository and tests.

The capability model must cover at least:

- persistent clipboard history;
- clipboard paste;
- copy/cut/select-all;
- candidates;
- voice;
- handwriting;
- floating mode;
- floating drag;
- clear-all;
- editor action/Enter behavior.

Inputs remain concrete and minimal: EditorInfo/editor kind, password state, IME window mode, provider availability and relevant permission/model state.

## Phase 3 — Floating keyboard becomes a first-class window mode

The current main branch contains a real WindowManager floating IME implementation, but its old Panel.GAMING control surface and tests were not fully retired.

Target model:

- Docked;
- Floating.

Rules:

- floating mode is not a Panel;
- remove Panel.GAMING from the product state machine;
- the Tools panel keeps a “浮动键盘” action;
- tapping it immediately enters the real floating IME window;
- floating mode exposes a visible drag handle and an explicit dock/exit action;
- drag is disabled when docked;
- drag bounds must keep the whole card inside safe screen margins;
- opening another panel must not silently destroy window-mode state;
- hiding/reopening the IME, focus changes and process/session recreation must restore a valid window state;
- accessibility text/actions must match actual availability.

## Phase 4 — Separate orientation from floating state

Current landscape auto-floating mixes a layout strategy with a user window-mode choice.

Target behavior:

- landscape uses compact responsive keyboard geometry;
- landscape does not implicitly mutate the user's Docked/Floating preference;
- manual floating remains manual;
- rotation preserves a valid current window mode;
- remove obsolete landscapeAutoFloating state once behavior is covered.

## Phase 5 — Remove unreachable/dead product states

Remove stale state only after migration behavior is defined.

Primary target:

- KeyboardMode.ENGLISH_T9;
- renderEnglish9 and T9-only UI state;
- t9Filter/lastT9Digits and obsolete accessibility labels;
- stale persisted-value migration/fallback handling;
- other unreachable panels/settings/demo compatibility code identified during extraction.

Product contract remains: Chinese 26-key, Chinese 9-key, English 26-key, numeric/specialized numeric keyboards. No English 9-key.

## Phase 6 — Mechanically split ImeKeyboardView

ImeKeyboardView is currently approximately 6,020 lines and is the dominant UI debt. Do not rewrite the state model and do not migrate to Compose during this phase.

Extract by real UI/interaction boundaries:

- top toolbar and composition zone;
- candidate strip and expanded candidates;
- Pinyin 26 keyboard;
- Pinyin 9 keyboard;
- English 26 behavior;
- numeric/phone/decimal keyboard;
- panel host;
- emoji panel;
- symbol panel;
- clipboard/quick phrase panel;
- text-edit panel;
- settings/fuzzy panel;
- voice presentation;
- key popup;
- backspace gesture;
- space/voice gesture;
- floating drag/window presentation.

Use internal concrete classes/functions first. Avoid new generic frameworks.

Target: ImeKeyboardView becomes orchestration rather than the implementation home for every screen and gesture.

## Phase 7 — Retire ImeKeyboardViewV2

ImeKeyboardViewV2 is a production wrapper over the legacy renderer rather than an independent renderer.

Move its surviving responsibilities to their owning modules:

- insets/responsive geometry -> geometry/presentation;
- Enter label/capability decoration -> keyboard presentation policy;
- 9-key repairs -> Pinyin 9 component;
- accessibility post-processing -> owning controls/components;
- runtime capability filtering -> capability model.

Then make LocalVoiceImeService create the final production ImeKeyboardView directly.

## Phase 8 — Converge design tokens

Centralize geometry, typography and motion currently spread across hundreds of literal dp/sp values.

Geometry must cover:

- outer keyboard inset;
- row/key gaps;
- key heights;
- function-key widths;
- top-zone height;
- candidate height;
- bottom-row geometry;
- panel padding/radius;
- popup bounds;
- floating width/radius/drag-handle bounds;
- safe-edge margins.

Typography should converge to semantic roles instead of local numeric sizes:

- key primary;
- key secondary;
- function key;
- candidate;
- panel title;
- panel body;
- panel caption/status.

Motion should centralize press, mode-switch, panel entrance, candidate expansion, popup and voice transitions.

## Phase 9 — Chinese 26-key is the visual reference surface

Polish this first and reuse its system everywhere else.

Audit and lock:

- total height;
- left/right outer inset;
- key width and row spacing;
- second/third-row optical centering;
- function-key weights;
- space bar actual and optical center;
- glyph baseline/visual center;
- Shift/Delete/Enter icon sizing;
- pressed state;
- long-press popup;
- bottom-row symmetry.

Do not design English 26 as a separate geometry system.

## Phase 10 — English 26, Chinese 9-key and numeric keyboards

English 26 reuses 26-key geometry and only changes language-specific behavior.

Chinese 9-key must retain the intended product contract:

- 2–9 primary digit keys;
- no English 9-key;
- no 0 key in the Chinese 9-key layout;
- left segmentation/filter interactions;
- correct candidate editing/segment repair;
- consistent visual language with 26-key.

Numeric layout must be validated for NUMBER, DECIMAL and PHONE EditorInfo. Phone remapping (*, +, #) must remain explicit and tested.

## Phase 11 — Top zone and candidate system

Define explicit visual states:

- idle toolbar;
- composing;
- candidate expanded;
- voice inline.

Reduce accidental simultaneous controls and layout jumps. Audit candidate width, first-candidate emphasis, long phrase handling, horizontal scrolling, expanded-grid flow, back behavior and emoji shortcut placement.

## Phase 12 — Panels become one UI system

Tools, keyboard selector, emoji, symbols, clipboard/quick phrase, text editor, settings and fuzzy settings share one panel contract:

- header;
- back;
- title;
- tabs/categories;
- content;
- primary action;
- empty state;
- disabled state;
- error/status state;
- focus restoration.

Floating keyboard is explicitly excluded from the Panel model.

## Phase 13 — Clipboard and text-edit correctness

Unify privacy and availability behavior across policy, repository, UI and tests.

Validate:

- opening clipboard capture behavior;
- persistent history eligibility;
- password/editor restrictions;
- pinned items;
- 24-hour retention for unpinned items;
- clear unpinned/all confirmation;
- paste availability;
- copy/cut/select-all availability;
- dynamic selection changes;
- disabled-state accessibility.

Controls that cannot execute must be disabled before the user activates them.

## Phase 14 — Gesture acceptance pass

Normal keys:

- down/move/up/cancel;
- slide out/in;
- multi-touch ownership.

Backspace:

- tap delete;
- hold repeat;
- swipe-up clear;
- hysteresis and cancel;
- one batch clear callback.

Space/voice:

- product long-press threshold: 150 ms;
- <150 ms commits space;
- >=150 ms arms voice;
- release finalizes/commits;
- upward swipe cancels;
- return from cancel zone restores;
- ACTION_CANCEL is safe.

Popup:

- consistent size;
- optical centering;
- edge clamping;
- no screen overflow;
- setting-enabled behavior only.

## Phase 15 — Voice UI and lifecycle

Keep the current local speech architecture and inline keyboard-height-preserving presentation.

Polish and verify:

- preparing;
- listening;
- cancel preview;
- recognizing/finalizing;
- partial transcript;
- final transcript/commit;
- failure;
- model unavailable/permission unavailable;
- session cleanup after release, field switch and IME hide.

## Phase 16 — Emoji and symbols

Symbols are already broad and should be visually/category audited after the panel system converges.

Emoji is currently intentionally limited to Smileys & Emotion. Expand only after the core UI is stable:

- recent;
- smileys/emotion;
- people/body;
- skin tones where applicable;
- animals/nature;
- food/drink;
- activities;
- travel/places;
- objects;
- symbols;
- flags.

Category filtering/navigation must materially change the grid; recent usage must persist.

## Phase 17 — Handwriting

Current handwriting is a UI shell backed by UnavailableHandwritingProvider.

Keep the entry hidden until a real recognizer is wired. Once an engine exists, validate stroke capture, undo, clear, candidates/results, commit, orientation and lifecycle before exposing the feature.

## Phase 18 — App home and settings convergence

Align Activity UI and in-keyboard settings with the same design system:

- color/tokens;
- radius;
- typography;
- row geometry;
- toggles;
- sliders;
- fields/cards;
- disabled/focus states.

Setting changes should update the active IME without requiring a full app restart.

## Phase 19 — Accessibility, large text and responsive layout

Validate every production surface for:

- 48dp minimum touch/focus target where appropriate;
- TalkBack names/states/actions;
- no duplicate accessibility nodes;
- large font scale without clipped keys/labels;
- portrait/landscape;
- small phones;
- tablets/foldables;
- hardware keyboard presence;
- navigation/gesture insets.

## Phase 20 — Final acceptance matrix

Automated:

- unit tests;
- lint;
- debug build;
- API 29;
- API 31;
- current target/API 34–36 coverage where runner support exists.

Product acceptance:

- Chinese 26;
- Chinese 9;
- English 26;
- number;
- decimal;
- phone;
- candidate compose/expand;
- symbols;
- emoji;
- clipboard;
- quick phrases;
- text editing;
- voice;
- floating keyboard;
- tools/keyboard selector;
- settings;
- light/dark/system appearance;
- large text;
- portrait/landscape;
- password;
- Enter actions;
- accessibility;
- IME hide/show;
- focus switch;
- rotation;
- process/session recreation.

Performance sanity:

- first IME frame;
- first key latency;
- continuous typing;
- candidate response;
- panel open;
- memory;
- voice model warm-up;
- rotation/window relayout.

## Required execution order

1. Green CI baseline.
2. Capability truth source.
3. Floating keyboard state/window repair.
4. Orientation/floating separation.
5. Dead state cleanup.
6. ImeKeyboardView mechanical split.
7. Remove ImeKeyboardViewV2.
8. Design-token convergence.
9. Chinese 26 visual lock.
10. English 26 / Chinese 9 / numeric polish.
11. Top zone and candidates.
12. Panel system.
13. Clipboard/text editing.
14. Gesture acceptance.
15. Voice polish.
16. Emoji/symbol completion.
17. Handwriting.
18. Home/settings convergence.
19. Accessibility/responsive pass.
20. Full acceptance and performance pass.
