# openIME 架构

## 运行时边界

```text
LocalVoiceImeService (InputMethodService)
        │
        ├── ImeKeyboardView / ImeKeyboardViewV2
        │       └── 动态几何、按键、候选栏、工具面板和浮动布局
        │
        ├── ImeState / CompositionController
        │       └── 模式、预编辑、光标、删除、主题和窗口状态
        │
        ├── CandidateEngine / PinyinLexicon
        │       └── 高频候选、前缀索引和首次词典部署回退
        │
        ├── RimeEngine → RimeNative (JNI) → librime/OpenCC
        │       └── 全拼、简拼、显式分词、完整候选、用户学习和简繁转换
        │
        ├── InputConnectionGateway
        │       └── setComposingText / commitText / deleteSurroundingText
        │
        └── VoiceModelLifecycleManager → VoiceModelRepository
                ├── 异步校验/预热 → 10 秒热驻留 → 异步释放
                ├── VoiceAudioRouteManager → AudioRecord → PCM ring
                ├── sherpa-onnx → 动态 hotwords → 流式识别
                └── VoiceCorrectionRepository → composing / commit
```

## 输入提交原则

1. 普通按键只改变输入法自己的预编辑状态，不直接把半成品字符写入目标应用。
2. Rime 返回的 preedit 通过 `InputConnection.setComposingText()` 更新。
3. 用户选择候选、按空格或执行明确提交动作时，使用 `commitText()` 写入目标编辑器。
4. 删除键先处理预编辑和候选，再处理目标编辑器中的已提交文本。
5. 切换编辑器、隐藏输入法、切换模式或结束语音时清理对应的 composing 状态。
6. 首选提交直接向当前 Rime session 请求最终结果，避免最后一次异步候选刷新造成旧首选上屏。
7. 候选上屏后统一结束 Rime composition、清空拼音和候选；用户学习结果优先于通用词频。

## 词典部署与回退

- 完整 Rime Ice 词典在 APK 首次运行时由 librime 部署，部署完成后成为中文候选的权威来源。
- `pinyin_phrases.tsv` 是构建时从同一词典筛选生成的高频子集，完整词典尚未就绪时仍能即时打字。
- 高频候选使用有序拼音键和二分前缀查找，避免每次按键遍历整个词典。
- 空格、回车及明确提交动作走同步首选提交；候选栏刷新仍可异步执行，保证按键反馈连续。

## 布局适配原则

- 横向列宽、列间距由当前 IME 窗口的实际可用宽度计算。
- 普通按键的纵向高度保持稳定，只在 Compact / Normal / Wide 断点做小范围调整。
- 宽屏限制键盘内容最大宽度并居中，避免平板上无限拉伸。
- WindowInsets 提供底部系统栏占用，不能写死手势条或导航栏高度。
- 窗口尺寸、方向或浮动状态改变后重新测量并重排，不复用旧屏幕的绝对像素。

## 测试边界

debug 变体提供语义化 E2E Receiver，脚本通过 `tap:key:q`、`tap:候选`、`state` 和
`bounds` 驱动测试。Receiver 和测试 Activity 受 `android.permission.DUMP` 保护，
仅 adb shell/系统测试可调用；生产变体不包含这套入口，也不依赖固定屏幕坐标。
