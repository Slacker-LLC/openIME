# 本地语音模型接入边界

这个输入法是独立 APK，包名为 `llc.slacker.openime`，语音链路不依赖
`minis-for-android` 的类、进程、网络或数据。

## 已落地的语音链路

1. 空格短按保留原来的空格/候选上屏行为。
2. 空格长按开始录音，松手结束当前语音段。
3. `AudioRecord` 固定使用 `16000 Hz / mono / PCM16`，每个模型块为 20 ms、
   320 samples、640 bytes。
4. 采集线程和推理线程分离，PCM 只进入当前会话的有界内存环形缓冲区。
5. partial 结果通过 `setComposingText()` 更新输入框，最终结果结束 composing；
   语音会话结束后再调用标点模型，标点失败回退原始 ASR 文字。
6. 会话结束、取消或失败时清空 PCM 缓冲区，不写文件、不建立录音历史、不上传
   音频或文字。

## APK 内置模型约定

内置模型必须放在：

```text
app/src/main/assets/models/voice/
```

其中 `manifest.json` 至少包含：

```json
{
  "modelId": "paraformer-zh-en-int8",
  "modelVersion": "...",
  "language": "zh-CN,en-US",
  "modelType": "streaming-paraformer",
  "engineVersion": "...",
  "fileHash": "sha256",
  "supportsPunctuation": true,
  "requiredMemory": 500000000,
  "files": ["...", "..."]
}
```

`VoiceModelRepository` 会在加载前校验字段和 SHA-256。内置模型是 APK 资源，
不可删除；下载模型必须先校验、后台加载成功后才能切换，失败、损坏、超时或
运行异常时回退内置模型。切换不能发生在正在录音的会话中。

## Runtime 接入边界

真正的 sherpa/ONNX arm64 runtime 通过 `StreamingEmbeddedVoiceModelRuntime` 接入：

- `start()` 创建一个新的 OnlineStream，不重新加载模型；
- `acceptWaveform()` 只接收新增的 float PCM；
- `inputFinished()` 结束当前流并返回原始 ASR 文字；
- `punctuate()` 只在语音段结束时运行；
- 模型加载和推理不能阻塞输入法主线程。

`VoiceRecognitionBackendFactory` 不再回退到 Android/联网语音服务；本地模型未
就绪时明确提示未就绪，避免把在线识别伪装成离线识别。

## 当前交付状态

当前 APK 已内置官方 `sherpa-onnx v1.13.6` native runtime 和中英双语流式
Zipformer INT8 模型，模型路径为 `models/voice/bilingual-zipformer/`。模型使用
`OnlineRecognizer` 按 16 kHz PCM 流式解码，识别器首次长按空格时懒加载，后续
中文或英文语音段复用同一个已加载识别器。当前内置的是纯识别模型，`punctuate()` 保留了独立
标点模型的扩展边界；没有标点模型时会安全回退原始识别文字。

模型包的每个文件都在 `manifest.json` 的 SHA-256 清单内，APK 启动时只选择校验
通过的内置包。下载模型仍然必须走 `VoiceModelRepository` 的校验和原子切换，
不能覆盖正在使用的内置模型。
