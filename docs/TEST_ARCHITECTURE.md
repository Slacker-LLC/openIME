# Test Architecture

```text
Unit Test
State Test
Candidate Engine Test
InputConnection Integration Test
Smoke Test
UI Instrumented Test
Visual Regression Evidence
Real IME E2E
Core Regression Suite
Security / Privacy Audit
```

## Current Test Files

| Layer | File | Status |
| -- | -- | -- |
| Unit | `app/src/test/.../CandidateEngineTest.kt` | PASS |
| Unit | `app/src/test/.../CompositionControllerTest.kt` | PASS |
| Unit | `app/src/test/.../EditorInfoAdapterTest.kt` | PASS |
| Unit | `app/src/test/.../ImeStateTest.kt` | PASS |
| Unit | `app/src/test/.../InputConnectionGatewayTest.kt` | PASS |
| Instrumented | `app/src/androidTest/.../DebugKeyboardActivityTest.kt` | PASS |
| Instrumented | `app/src/androidTest/.../ImeInstrumentedTest.kt` | PASS |
| E2E | `scripts/core_regression.ps1` | RUN |
| Visual | `docs/VISUAL_ACCEPTANCE.md` | PASS |

## 坐标与自动化

- `KeyboardGeometry.kt`：normalized 0..1 坐标模型。
- `E2ETestReceiver`：debug-only，语义化 `tap:` / `type64:` / `state` / `bounds`。
- `scripts/core_regression.ps1`：核心四路回归。
- `scripts/extended_regression.ps1`：空格/删除/Emoji/Symbol/Enter/面板/模式。
- `scripts/lifecycle_regression.ps1`：Lifecycle P0 回归。
- `scripts/visual_check.ps1` / `scripts/perf_baseline.ps1` / `scripts/upgrade_regression.ps1`：Visual / Perf / Upgrade 证据。
