# 参考输入法 UI 基线（用户提供截图）

来源：用户提供的参考输入法 UI 原型。

## 截图内容

| 参考文件 | 识别内容 |
| -- | -- |
| `ref-qwerty-send.png` | 英文 QWERTY，底部 `123 / space / return`，右上 `发送`，候选/输入区 |
| `ref-qwerty-send-2.png` | 英文 QWERTY，长输入文本 + `发送` |
| `ref-qwerty-send-3.png` | 英文 QWERTY，`发送` 操作 |
| `ref-pinyin-candidates.png` | 拼音输入，候选含“哪个那个女警 / 灰姑娘 / 韩国那个女警”，底部 `123 / 中英 / 确定` |
| `ref-pinyin-google-suggest.png` | 拼音输入，候选 + Google 建议/联想 |
| `ref-voice-click.png` | 语音输入面板，`点击说话`，右上关闭 |
| `ref-toolbar-functions.png` | 工具栏/功能页：语音输入、键盘选择、常用语、表情、剪切板、定制工具栏、手写找字、繁体输入 |
| `ref-settings-tools.png` | 设置页：键盘调节、更多设置、问题反馈、单手模式 |

## 值得学习的地方

1. 键盘上方保留一条 **action/toolbar**，把用户高频能力放进一屏：
   - 语音输入
   - 键盘选择
   - 常用语 / 快捷短语
   - 表情
   - 剪切板
   - 手写找字
   - 繁体输入
   - 定制工具栏
2. 拼音候选栏与输入内容保持同一行，候选不会盖住正文。
3. 底部 action 明确：`123 / space / 发送`、`123 / 中英 / 确认`，与编辑器动作一致。
4. 语音面板有明确 `点击说话` 状态，关闭后可视化状态恢复，不给用户“麦克风还在跑”的错觉。
5. 设置入口提供键盘调节、单手模式、问题反馈，兼顾可用性和可发现性。

## 本项目的落地建议

- 在现有候选栏上方增加一个可滚动的 **功能工具栏**，复用 `Panel` 模型。
- 把常用语、表情、剪切板、手写、语音入口集中展示。
- 为 `发送 / 前往 / 确认` 提供真实 `EditorInfo` action 映射。
- 语音面板打开/关闭时维护 `voiceActive`，关闭即停。
- 所有布局继续遵循 `COORDINATE_SYSTEM.md`：不写死绝对像素，使用 Row/Weight/归一化坐标。

## 设备上的 openIME 与 Minis for Android

| 包名 | 服务 | 标签 |
| -- | -- | -- |
| `llc.slacker.openime` | `LocalVoiceImeService` | `openIME`（本原型 APK） |
| `dev.openminispet.android` | `com.openminis.app.inputmethod.LocalVoiceInputMethodService` | `Minis 本地语音输入法`（主项目中的 IME） |

两个 ID 是独立应用，所以“输入法管理”里会看到两个。若要只保留主项目版本，可以停用/卸载 `llc.slacker.openime`；若要只保留本原型，则停用 `dev.openminispet.android` 中的 IME 服务。
