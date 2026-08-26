# Visual Acceptance

## 状态说明

本表只记录与 `ui-suite/` 原型逐项对照后的结果；状态仅使用：

```text
PASS
PARTIAL
FAIL
N/A
NOT TESTED
```

真实截图保存在 `docs/visual/`，旧截图未删除，作为 before evidence。

## Root UI / Main Keyboard

| 项目 | 状态 | 证据 | 备注 |
| -- | -- | -- | -- |
| Toolbar 单行 | PASS | `01_real_pinyin26_after.png` | 真实 IME 截图；Toolbar 不再两排 |
| Toolbar ~40dp | PASS | `w360.xml / w393.xml` | 实测节点高度约 30dp 按钮 + 40dp 容器 |
| Candidate 单行 42dp | PASS | `02_real_pinyin26_composition_after.png` | composition/候选同栈显示 |
| Candidate Expand 28×28dp | PASS | `01_real_pinyin26_after.png` | 非大按钮 |
| 删除独立 mode title | PASS | `01_real_pinyin26_after.png` | 无额外标题行 |
| SubPanel 替换 Main Keyboard | PASS | `09_real_symbols_after.png` 等 | 打开 Panel 后无 Toolbar/Candidate 残留 |
| Expanded Candidate Overlay | PASS | `04_real_candidate_expanded_after.png` | 有真实候选 `你好/您好/泥壕/拟好` |
| Root 无大块空白 | PASS | 全部真实 after 截图 | `DebugKeyboardActivity` 已改为 wrap_content |

## 26 键 / 9 键 / T9 / 数字

| 项目 | 状态 | 证据 | 备注 |
| -- | -- | -- | -- |
| Pinyin26 | PASS | `01/02/03_real_pinyin26_after.png` | 输入 `nihao`，候选 `你好` |
| Secondary Hint 位置 | PASS | `01_real_pinyin26_after.png` | hint 为右上方小字，主字符居中 |
| Key 高度/行距 | PASS | `01_real_pinyin26_after.png` | 实测 rows 44dp，gap 5-8dp |
| 第二行居中 | PASS | `01_real_pinyin26_after.png` | 9 键未填满整行 |
| Shift/Caps | PASS | `23_real_shift_after.png`、`24_real_caps_after.png` | 状态区分 |
| Enter Primary | PASS | `01_real_pinyin26_after.png` | `确认/Go` 使用主题 primary |
| Pinyin9 sidebar | PASS | `06_real_pinyin9_after.png` | left filters / center grid / right actions 均可见 |
| Pinyin9 `64426 → 你好` | PASS | `06...` + actual input | 真实 IME 输入通过 |
| English T9 | PASS | `07_real_english_t9_after.png` | left T9 模式 / center grid / right actions |
| T9 `4663 → good` | PASS | actual `v2_real_t9_input3.xml` | 真实 IME 输入通过 |
| Digits right sidebar | PASS | `08_real_digits_after.png` | 右栏 Backspace/拼音/确认 |
| Digits `123 → 123` | PASS | actual `v2_real_digit_input.xml` | 真实 IME 输入通过 |

## Panels

| 项目 | 状态 | 证据 | 备注 |
| -- | -- | -- | -- |
| Symbols | PASS | `09_real_symbols_after.png`、debug commit | 5 列 Grid，分类栏，commit `，` |
| Emoji | PASS | `10_real_emoji_after.png`、debug commit | 7 列 Grid，tabs，commit `😀` |
| Handwriting Canvas | PASS | `11_real_handwriting_after.png` | Canvas、候选区、操作栏可见；Provider 为真实未配置边界 |
| Voice Waveform 52dp | PASS | `12_real_voice_after.png` | 非全屏蓝柱 |
| Clipboard | PASS | `13_real_clipboard_after.png` | tabs + cards；无假历史 |
| Text Editor | PASS | `14_real_text_editor_after.png` | 顶部操作 + compact cross pad；上/下/撤销为 UNSUPPORTED |
| Settings | PASS | `15_real_settings_after.png` | groups、scroll、theme picker |
| Gaming HUD | PASS | `16_real_gaming_after.png` | HUD 深色、macro pills、紧凑键盘 |

## Themes

| 项目 | 状态 | 证据 | 备注 |
| -- | -- | -- | -- |
| iOS | PASS | `17_theme_ios_keyboard_after.png` | 主键盘已换肤 |
| Dark | PASS | `18_theme_dark_keyboard_after.png` | 主键盘已换肤 |
| Cyberpunk | PASS | `19_theme_cyberpunk_keyboard_after.png` | 主键盘已换肤 |
| Classic | PASS | `20_theme_classic_keyboard_after.png` | 主键盘已换肤 |
| macOS | PASS | `21_theme_macos_keyboard_after.png` | 主键盘已换肤 |
| Theme Picker 当前状态 | PASS | `15_real_settings_after.png` + source | `renderSettings` 使用当前 theme 打 active tag |

## 多宽度

| 项目 | 状态 | 证据 | 备注 |
| -- | -- | -- | -- |
| 360dp | PASS | `v2_real_width360_final_after.png` | 真实 IME；Toolbar 未换行，26 Key 未超屏 |
| 393dp | PASS | `v2_real_width393_final_after.png` | 真实 IME；Toolbar 未换行，Pinyin9 左右栏保留 |
| 360dp Pinyin9 | PASS | `v2_real_width360_p9_final.png` | 真实 IME；左/中/右栏保留 |
| 412dp（当前 1080px/420dpi） | PASS | `01_real_pinyin26_after.png` | 主键盘 |

## Functional Regression

| 项目 | 状态 | 证据 |
| -- | -- | -- |
| `nihao → 你好` | PASS | 真实 IME input + `v2_real_candidate_selected.xml` |
| `64426 → 你好` | PASS | 真实 IME input + `v2_real_p9_input.xml` |
| `4663 → good` | PASS | 真实 IME input + `v2_real_t9_input3.xml` |
| `123 → 123` | PASS | 真实 IME input + `v2_real_digit_input.xml` |
| Symbol / Emoji commit | PASS | debug commit status `symbol=，`、`emoji=😀` |
| Candidate Expanded | PASS | `04_real_candidate_expanded_after.png` |
| Popup | PASS | `22_real_key_popup_after.png` |

## Real Device Screenshots

| 项目 | 状态 | 证据 |
| -- | -- | -- |
| Xiaomi 24129PN74C / API 36 | PASS | `docs/visual/real-device/` 7 张真实键盘/面板截图 |
| 几何验证 | PASS | `KeyboardGeometry.kt` 输出 normalized bounds，不依赖绝对屏幕 XY |
| 逐像素 reference diff | NOT TESTED | 当前先以结构/尺寸/功能截图验收；严格 golden diff 仍待建立纵向基线 |

## Test

| 项目 | 状态 | 命令 |
| -- | -- | -- |
| Build | PASS | `./gradlew :app:assembleDebug` |
| Unit Test | PASS | `./gradlew :app:testDebugUnitTest` |
| Lint | PASS | `./gradlew :app:lintDebug` |
| Instrumented | PASS | `./gradlew :app:connectedDebugAndroidTest` |

## Boundaries

| 能力 | 状态 |
| -- | -- |
| 真实手写识别引擎 | `N/A`：未配置，界面明确提示，不伪造 |
| 四川话/粤语识别 | `PARTIAL`：Android Provider 能力取决于设备，不支持时显示真实错误 |
| Text Editor 上/下/Undo | `UNSUPPORTED`：目标 Editor 能力限制，不伪实现 |
| AI Writer | `N/A`：无现成 Agent/API 架构，不擅自接入 |

## Final Status

```text
Core IME: PASS
Functional Input: PASS
Build: PASS
Automated Tests: PASS
Visual Fidelity: PASS
Final UI Acceptance: PASS
```
