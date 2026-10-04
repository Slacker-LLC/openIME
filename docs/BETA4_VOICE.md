# Beta4 语音与 App 界面验收

基线：`8576727`，`0.0.3-beta.1`。开发分支 `codex/beta4-voice`，本地版本 `0.0.4-beta.1`。

## 用户要求与实现

- 松开空格立即结束麦克风采集，删除原来的 300ms 实采等待。stop 不取消识别：等采集线程结束、排空 PCM，再处理模型末块、标点与上屏。主动取消才丢弃音频。
- 丢尾字根因：sherpa-onnx v1.13.6 的 Streaming Paraformer `inputFinished()` 只结束特征提取；不足整块的最后帧还需要 `stream.setOption("is_final", "1")` 才会进入解码。已补齐。保留 300ms 全零数组作为模型计算尾垫，完全不继续访问麦克风。
- 新增本地 CT-Transformer INT8 标点模型，最终结果恢复断句、逗号与问号，保留用户明确说出的标点。结构化输入框沿用原来的保护策略，不自动补句子标点。
- 在标点恢复后应用“标点用空格代替”，补齐紧贴中文的 ASCII 标点；小数 `3.5` 和网址 `https://a.b` 保留。真实设置控件保存后立即影响后续结果。
- PCM 使用批量拷贝，推理复用 320 样本块；溢出明确报错，不把已缺字的结果当成功上屏。采集完成即恢复媒体音量，模型排队与标点不继续静音。
- App 首页、偏好设置、键盘内设置和工具面板按新设计重做：首页分“三步引导”和“已就绪”两种状态；设置改为分组卡片列表，行间用分隔线，不再是一张张带底边的按键；强调色只用于可交互元素和开启状态，开关开启为强调色；浅色与暗色各有一套 App 配色。按键皮肤和单手模式已移除。键盘按键本身保持原有绘制效果。

App 的具体配色、间距、尺寸、位置和适配阈值见 [App 界面开发规范](APP_UI_SPEC.md)。

## 编码前列出的失败路径

语音包含 Android 麦克风、native ASR、主线程事件与编辑器，不能靠孤立的文本后处理证明完整链路。需区分：

1. 麦克风未 ready 就松手、权限拒绝、启动失败：不能留下媒体静音或错误聆听状态。
2. 正常松手：最后 PCM 入队并消费，实采立即停止；最后不足 320 样本或不足模型整块都不能漏解码。
3. 取消、切换输入框、隐藏键盘、重开录音：旧回调不能解除新会话静音或提交旧文字。
4. 环形跨界、输入大于容量、最后余样本：不能丢失、重复、越界或错计 droppedSamples。
5. 加载/解码落后引起溢出：必须报错并撤销 composing，不能提交截断结果。
6. 冷启动采集先结束：恢复媒体不等待模型；已录音频仍需保留到推理完成。
7. 空录音、长句、中英混说、密码框：空结果无残留；密码框只最终提交。
8. 标点恢复失败、显式标点、ASCII 标点、数字网址、设置来回切换：文本不丢，设置实际落盘并通过最终上屏体现。
9. App 浅色/深色、320dp 窄屏与大字体、模糊音二级页返回：控件可访问，键盘原有布局不受 App 缩放影响。

## 可重复端到端测试

不新增单元测试。Debug 专属 `VoiceAudioE2E` 接收器运行在真实 IME 进程内，固定 PCM 经过实际 native Paraformer、实际 CT-Transformer 和真实 Android InputConnection；设置通过实际 App 控件修改。麦克风停止由脚本单独驱动真实空格 DOWN/UP 与 Android AudioRecord，不注入假识别回调，也不使用假的编辑器。

```bash
./gradlew --offline :app:assembleDebug :app:lintDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
python3 scripts/beta4_e2e.py --serial emulator-5554 --out .local/test-runs/beta4/layout-final
```

测试采用 API 36 / x86_64 模拟器，临时 4GiB RAM、256MiB Java heap class，关闭宿主音频；`AudioRecord` 仍实际运行并提供静音 PCM。脚本保存并恢复输入法、App 偏好、App 语言、字体大小、显示尺寸与密度、旋转。运行前需让模拟器支持模型内存需求；例如临时启动 `emulator -avd openime-review-api36 -memory 4096 -no-snapshot -no-audio`，其余显示参数依主机配置。

SDK 的 gRPC `injectAudio` 在该主机反复导致模拟器退出，不能算通过。因此语音语料在设备内重放到真实模型，麦克风停止另测，不能宣称验证了“宿主麦克风收音 → 全部语料正确识别”的单条链路。早期失败尝试保留在 `.local/test-runs/beta4/failed-rpc/`；真机收音、口音准确率、Bluetooth 路由与设备性能尚未复测。上述完整失败矩阵也并非全部执行通过。

语料：上游 [Paraformer 测试音频 0.wav](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/test_wavs/0.wav)，固定为 Debug 构建的 `app/src/debug/assets/voice-beta4.wav`，16kHz / mono / PCM16，160,850 个样本，SHA-256 `7d93384ca14702cc584a7a33fe2fed92e89e708549161cb12ea38c916882103b`。上游模型仓库标记 Apache-2.0，测试语料不进入正式 APK。

## 模型来源与代价

- [官方标点模型说明](https://k2-fsa.github.io/sherpa/onnx/punctuation/pretrained_models.html)，固定导出 `sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8`。
- [ModelScope 上游模型卡](https://modelscope.cn/models/iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch/summary) 标记 Apache License 2.0；APK 附许可文本，模型参与内置清单 SHA-256 校验。
- ONNX 大小 75,519,198 字节（约 72MiB），SHA-256 `65a3fb9f5ad7bfb96bf69e0dc4481df97f6ee60513c1d94ce981ba6effd524b1`。标点首次使用才加载，随识别器冷却释放。
- 原来的内存预算 520,000,000 调整为 620,000,000 字节；设备仍按真实可用系统内存与余量准入。Java heap class 的原判据设 256MiB 上限，避免把新增 native 模型大小误当成 Java 堆限制，让之前可用的 256MiB 设备直接失去语音功能。
- 标点模型提高固定例句的断句质量，不能保证每句话都正确；混合语料的 ASR 错词仍能出现，不能把“末块不再漏解码”描述成“识别零错字”。

## 本轮结果

最终构建与验收已通过：

- Debug 构建、`lintDebug`，签名 arm64 Release 构建、`lintRelease` 均成功。发布前补跑全部单元测试（383 个）：首次有 3 个失败，已修复——开关颜色与 App 布局字号改用设计 token，受信模型目录与测试同步标点模型（4 个文件），修复后全部通过。
- 发布前复验（x86_64 模拟器，真实触摸与 AudioRecord）：beta4 端到端 7/7；核心打字回归 19/19；beta3 功能回归 10/10（竖横屏）；显示矩阵 11/11；语音失败路径 50/50（不同按住时长、上滑取消、录音中隐藏键盘与切后台、连按 10 次、静音录音、麦克风权限被撤销后恢复），每项都核对媒体音量恢复、无崩溃、无残留 composing。
- 固定语料在最终处理前的 partial 以“星期”结尾，`inputFinished` 后完整为“星期三”；真实编辑器内容与最终识别结果相同。已录音频的末块得到解码，但中英混说仍有 ASR 错词，原始结果见 `native-audio-editor.json`。
- 通过实际 App 控件打开“标点用空格代替”后，真实上屏为“今天天气很好 我们一起去公园散步吧”；ASCII 标点转换与小数/网址保护通过。
- 实际空格触摸和 AudioRecord：松手至采集结束 33ms；captured=decoded=16,640 样本，缓冲余量=0、丢弃=0，媒体音量恢复。33ms 是这次模拟器实测，不是所有设备的硬保证。
- （以下界面验收针对重新设计之前的版本；新设计需按 `APP_UI_SPEC.md` 重新验收。）首页、设置、皮肤二级页的常规字体验收，以及首页/设置的浅色、深色、320dp / 1.3 倍字体通过；增加 320dp / 2.0 倍字体与 1400dp 宽屏验收。大字体滑杆数值“100%”保持单行，2 倍字体选项纵排；实际 UI XML 核对 16dp 页面边距、8dp 步骤间距、64dp 步骤高度下限、52dp 主按钮高度与 600dp 宽屏内容居中。截图已人工检查。
- 最终截图验证了胶囊形主按钮与绿色/灰色开关、白色滑块；UI XML 进一步核对实际 Button 角色、Switch 的 checkable/checked 开关态。
- 工件在 `.local/test-runs/beta4/layout-final/`：`results.json`、`artifact-index.json`（APK 与工件 SHA-256）、真实编辑器 XML、截图、采集日志与构建日志。重复命令见上文。
- 本地安装包在 `build/beta4-release-files/openIME-v0.0.4-beta.1-arm64-release.apk`，签名与 Beta3 证书一致；正式包不含 Debug 音频和重放入口。APK SHA-256：`a3925715e4109d1445902ae57372763b9708f5418113c2cdb2099fda94d0dd71`。

上述验收在 x86_64 Debug 模拟器执行；arm64 Release 已完成构建、签名、ABI 与内容核对，尚未在真机复测。构建、验收与签名包均为本地操作，没有创建远端 tag 或公开发布。
