# Test Matrix

| ID | Type | Scenario | Auto/Manual | Status | Evidence |
| -- | -- | -- | -- | -- | -- |
| UNIT-001 | Candidate | `nihao → 你好` | AUTO | PASS | `CandidateEngineTest` |
| UNIT-002 | Candidate | `zhongguo → 中国` | AUTO | PASS | `CandidateEngineTest` |
| UNIT-003 | Candidate | `shurufa → 输入法` | AUTO | PASS | `CandidateEngineTest` |
| UNIT-004 | Candidate | `64426 → 你好` | AUTO | PASS | `CandidateEngineTest` |
| UNIT-005 | Candidate | `4663 → good` | AUTO | PASS | `CandidateEngineTest` |
| UNIT-006 | Candidate | fuzzy variant | AUTO | PASS | `CandidateEngineTest` |
| UNIT-007 | Composition | n→ni→nih→niha→nihao | AUTO | PASS | `CompositionControllerTest` |
| UNIT-008 | Composition | backspace | AUTO | PASS | `CompositionControllerTest` |
| UNIT-009 | Composition | candidate select clears | AUTO | PASS | `CompositionControllerTest` |
| UNIT-010 | State | mode switch / previous mode | AUTO | PASS | `ImeStateTest` |
| UNIT-011 | State | composition clear on mode switch | AUTO | PASS | `ImeStateTest` |
| UNIT-012 | State | shift / caps lock | AUTO | PASS | `ImeStateTest` |
| UNIT-013 | EditorInfo | text / number / decimal / phone / email / url / password / multiline | AUTO | PASS | `EditorInfoAdapterTest` |
| UNIT-014 | InputConnection | setComposingText | AUTO | PASS | `InputConnectionGatewayTest` |
| UNIT-015 | InputConnection | commitText | AUTO | PASS | `InputConnectionGatewayTest` |
| UNIT-016 | InputConnection | backspace | AUTO | PASS | `InputConnectionGatewayTest` |
| UNIT-017 | InputConnection | editor action | AUTO | PASS | `InputConnectionGatewayTest` |
| UNIT-018 | Unicode | emoji commit | AUTO | PASS | `InputConnectionGatewayTest` |
| UNIT-019 | Security | password blocks composition / clipboard | AUTO | PASS | `InputConnectionGatewayTest` |
| UI-001 | Instrumented | keyboard renders | AUTO | PASS | `TEST...GlassTest2...xml`, 4/4 |
| UI-002 | Instrumented | interactive controls | AUTO | PASS | 4/4 |
| UI-003 | Instrumented | root / tags | AUTO | PASS | 4/4 |
| UI-004 | Instrumented | engine on real API 36 | AUTO | PASS | `am instrument ... ImeInstrumentedTest`, OK |
| UI-005 | Instrumented | ActivityScenario / startActivitySync on real MIUI | AUTO | BLOCKED | main thread not idle after 45s; emulator evidence kept |
| SMOKE-001 | Real IME | install / enable / set | AUTO | PASS | `core_regression.ps1 -Serial f0e2ff6f` |
| E2E-001 | Real IME | `nihao → 你好` | AUTO | PASS | `CR 020` real device |
| E2E-002 | Real IME | `64426 → 你好` | AUTO | PASS | `CR 030` real device |
| E2E-003 | Real IME | `4663 → good` | AUTO | PASS | `CR 040` real device |
| E2E-004 | Real IME | `123 → 123` | AUTO | PASS | `CR 050` real device |
| E2E-005 | Real IME | space first candidate | AUTO | PASS | `EXT 021` |
| E2E-006 | Real IME | composition backspace | AUTO | PASS | `EXT 023` |
| E2E-007 | Real IME | committed backspace | AUTO | PASS | `EXT 080` |
| E2E-008 | Real IME | emoji `😀` | AUTO | PASS | `EXT 060` |
| E2E-009 | Real IME | symbol `，` | AUTO | PASS | `EXT 070` |
| E2E-010 | Real IME | panel back | AUTO | PASS | `EXT 110` |
| E2E-011 | Real IME | multiline Enter | AUTO | PASS | `security_multiline` |
| E2E-012 | Real IME | mode cycle | AUTO | PASS | `EXT 100` |
| VIS-001 | Visual | Pinyin26 / English26 / P9 / T9 / Digits | MANUAL | PASS | `docs/visual/real-device/` |
| VIS-002 | Visual | Symbols / Emoji | MANUAL | PASS | `docs/visual/real-device/` |
| VIS-003 | Visual | structural layout check / normalized tags | AUTO | PASS | `scripts/visual_check.ps1` |
| LIFECYCLE-001 | Lifecycle | editor A/B switch + composition cleanup | AUTO | PASS | `lifecycle_regression.ps1` |
| LIFECYCLE-002 | Lifecycle | IME hide/show | AUTO | PASS | `lifecycle_regression.ps1` |
| LIFECYCLE-003 | Lifecycle | app restart composition cleanup | AUTO | PASS | `lifecycle_regression.ps1` |
| LIFECYCLE-004 | Lifecycle | Voice stop on panel close | AUTO | PASS | `state voice=false`, `lifecycle_regression.ps1` |
| LIFECYCLE-005 | Lifecycle | Handwriting stroke cleanup on app switch | MANUAL | PARTIAL | source-only audit; no real provider strokes uploaded |
| SEC-001 | Security | password string in logcat | AUTO | PASS | `SECRET_IME_TEST_739251` absent from logcat |
| SEC-002 | Security | password string in app files | AUTO | PASS | `run-as ... find ... grep` clean |
| SEC-003 | Security | password in clipboard/DataStore | AUTO | PASS | no clipboard hit; data dir only `ime_settings.xml` |
| SEC-004 | Security | value-level log audit | AUTO | PASS | no `Log` of composition/candidate/text in main source |
| SEC-005 | Security | voice permission denied + granted no crash | MANUAL | PASS | mic tap while denied/granted, process alive |
| COMPAT-001 | Compatibility | API 34 emulator | AUTO | PASS | `GlassTest2` 4/4 + core |
| COMPAT-002 | Compatibility | API 36 real Xiaomi | AUTO | PASS | `f0e2ff6f` core + extended |
| COMPAT-003 | Compatibility | Android 10 / 12 | MANUAL | NOT TESTED | no matching emulator available |
| PERF-001 | Performance | ADB command baseline | MANUAL | PARTIAL | P50 mode 63.7ms / state 57.5ms; not frame/Jank |
| STRESS-001 | Stress | 30 cycles × mode/panel open-close | MANUAL | PARTIAL | PID alive, no FATAL/ANR, PSS ~29.7 MB |
| STRESS-002 | Stress | 10000 key presses / long soak | MANUAL | NOT TESTED | not yet executed |
| UPGRADE-001 | Upgrade | theme DARK retention after reinstall | AUTO | PASS | `upgrade_regression.ps1` |

<!-- 2026-08-25 重跑会话追加 -->
| UI-006 | UI | 关键尺寸 toolbar 40 / candidate 42 / key 44 / expand 28dp | AUTO | PASS | `bounds` 归一化报告 × 309.2dp 窗口 |
| NEG-001 | Negative | 空候选点击 expand 不崩溃 | AUTO | PASS | `tap:candidate-expand` 进程存活 |
| E2E-013 | EditorAction | `actionNext` 真实焦点移动 | AUTO | PASS | A=abc + Enter → B focused |
| E2E-014 | EditorAction | `actionDone` 真实提交并隐藏 IME | AUTO | PASS | B=def + Enter → input view false |
| STRESS-003 | Stress | 500 轮 × 3 命令 panel/mode | AUTO | PASS | 无 FATAL/ANR，PSS 177→119MB |
| PERF-002 | Performance | host→adb 命令基线 | AUTO | PARTIAL | P50 65.4 / 58.0ms（往返主导，帧/Jank 待测） |
