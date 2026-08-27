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
SOP Evidence Orchestrator
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
| E2E | `scripts/typing_engine_regression.ps1` | RUN |
| E2E | `scripts/field_matrix_regression.ps1` | RUN |
| Security | `scripts/security_regression.ps1` | RUN |
| Orchestrator | `scripts/test_sop.ps1` | L0～L3 |
| Visual | `docs/VISUAL_ACCEPTANCE.md` | PASS |

## 坐标与自动化

- `KeyboardGeometry.kt`：normalized 0..1 坐标模型。
- `E2ETestReceiver`：debug-only，语义化 `tap:` / `type64:` / `state` / `bounds`。
- `ImeTestLabActivity`：debug-only，覆盖主要 EditorInfo 类型、1 万字和选区替换宿主。
- `scripts/core_regression.ps1`：核心四路回归。
- `scripts/extended_regression.ps1`：空格/删除/Emoji/Symbol/Enter/面板/模式。
- `scripts/lifecycle_regression.ps1`：Lifecycle P0 回归。
- `scripts/visual_check.ps1` / `scripts/perf_baseline.ps1` / `scripts/upgrade_regression.ps1`：Visual / Perf / Upgrade 证据。
- `scripts/test_sop.ps1`：分级编排、失败即停、设备元数据及统一证据目录。

`DebugKeyboardActivity`、`ImeTestLabActivity` 和 `E2ETestReceiver` 只存在于 debug 变体；
release APK 不声明这些测试入口。
