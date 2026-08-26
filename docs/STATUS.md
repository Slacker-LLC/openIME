# 实现状态

## 状态定义

```text
Core IME: PASS
Functional Input: PASS
Build: PASS
Unit / Lint: PASS
Real IME Core + Extended Regression: PASS
Security Password / Voice Permission: PASS
Lifecycle: PASS
Performance: PARTIAL
Stress: PARTIAL
Upgrade: PASS
```

第二阶段的 UI 视觉纠偏已进入验收状态；历史成果和 README 均保留。

## 已验收的 Core / Functional

- Android 系统 IME 注册、enable、set：通过。
- 真实 InputConnection 输入：通过。
- 26 键 `nihao → 你好`：通过。
- 九键 `64426 → 你好`：通过（真实小米 24129PN74C）。
- T9 / 数字输入：真实回归通过（真实小米 24129PN74C）。
- Build / Unit / Lint / Instrumented：通过（Unit 28/28，Lint 0 error，API 34 4/4）。

## 已完成并验证的 UI 纠偏

- Root 层级：MainDock / Panel / CandidateOverlay 已重构。
- Toolbar：两行错误已改为单行紧凑布局。
- 独立“中文26键”标题：已移除。
- Candidate Bar：单行、42dp、小展开按钮。
- 26 键：44dp、副提示 right-top、第二行居中、Enter primary。
- Nine Key / T9 / Digits：侧栏与右栏已恢复。
- SubPanel：已替换 Main Keyboard，不再叠加。

## 本轮完成项

- Real IME after 截图：`docs/visual/01_real_*_after.png` ~ `21_real_*_after.png`。
- 实机 after 截图：`docs/visual/real-device/`。
- Root 层级、Toolbar/Candidate/Panel/Overlay：已按原型修正。
- 26/九键/T9/数字：真实 IME 回归通过。
- 五套主题主键盘：真实 IME 截图通过。
- Multi-width 360/393/412dp：截图通过。
- `docs/VISUAL_ACCEPTANCE.md`：已生成，逐项 PASS。

## 保留边界

- 手写识别引擎：未配置，界面明确提示。
- 四川话/粤语：取决于系统 Provider，不支持时真实报错。
- Text Editor 上/下/Undo：受编辑器限制，明确保留。
- AI Writer：不接入。
