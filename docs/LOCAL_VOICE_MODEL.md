# Voice input

openIME recognizes speech on the device.
The voice path does not use a network, and it does not depend on any other app.

## How it works

1. A short press on space types a space or selects the first candidate.
2. A long press on space starts recording. The long-press time follows the Android touch-and-hold timeout.
3. `AudioRecord` uses 16,000 Hz, mono, PCM16. Each model block is 20 ms: 320 samples or 640 bytes.
4. Separate threads capture audio and run the model.
   PCM data goes only into a bounded in-memory ring buffer for the current session.
5. Partial results update the field through `setComposingText()`. The final result ends the composition.
6. After the session ends, the punctuation model adds punctuation. If it fails, openIME keeps the raw text.
7. When the session ends, is canceled or fails, openIME clears the PCM buffer.
   It writes no audio or text to disk. It keeps no recording history and uploads nothing.
8. `onStartInputView()` starts a background check and warm-up of the model.
   After the keyboard hides, the recognizer stays loaded for 10 seconds.
   If the keyboard opens again in that time, openIME reuses it. After that, it calls `OnlineRecognizer.release()`.
9. If the user holds space while the model warms up, openIME starts `AudioRecord` first.
   PCM waits in a 30-second bounded buffer, and the model reads it from the start when ready. No first syllable is lost.

## Release behavior

When the user releases space, openIME stops the microphone at once.
Stop does not cancel recognition.
openIME waits for the capture thread to end, drains the PCM queue, and then runs the model tail, the punctuation and the commit.
Only a cancel drops the audio.

The Streaming Paraformer in sherpa-onnx 1.13.6 needs two steps to decode the last frames.
`inputFinished()` ends feature extraction.
`stream.setOption("is_final", "1")` lets the last partial block enter the decoder.
openIME also appends 300 ms of zero samples as a tail pad for the model.
It does not read the microphone during this pad.

If the PCM ring buffer overflows, openIME reports an error and removes the composition.
It never commits a truncated result.
After capture ends, openIME restores the media volume at once. Queued model work does not keep media muted.

## Text processing

- The punctuation model (CT-Transformer INT8) restores sentence breaks, commas and question marks.
  It keeps punctuation that the user says aloud.
- Structured fields keep their existing protection. openIME adds no sentence punctuation there.
- Filler words such as "嗯" and "呃" can be removed.
  Words such as "额度" and "金额" stay unchanged. A result that is only "嗯" stays as it is.
- The option 标点用空格代替 ("Replace punctuation with spaces") acts on voice results only.
  Commas, periods and question marks become one space. Final punctuation is removed.
  Parentheses and values such as `3.5` or `a.b` stay unchanged. The default is off.
- If the user deletes and corrects a voice result right away, openIME stores the pair in the private `VoiceCorrectionRepository`.
  The next identical raw result uses the corrected text. All data stays on the device.

## Password fields

Voice input works in password fields.
openIME shows no partial result and commits the final text once.
It does not learn words or corrections from these fields.
Logs never contain PCM data, transcripts or corrections.

## Built-in models

The models are in `app/src/main/assets/models/voice/`:

- `bilingual-paraformer/`: encoder, decoder and tokens of `sherpa-onnx-streaming-paraformer-bilingual-zh-en` (INT8)
- `punctuation/`: the CT-Transformer INT8 punctuation model
- `manifest.json`: lists each file with its SHA-256 hash

`manifest.json` contains these fields:

```json
{
  "modelId": "paraformer-zh-en-int8",
  "modelVersion": "...",
  "language": "zh-CN,en-US",
  "modelType": "paraformer",
  "engineVersion": "...",
  "fileHash": "sha256",
  "supportsPunctuation": true,
  "requiredMemory": 500000000,
  "files": ["...", "..."]
}
```

`VoiceModelRepository` checks the fields and the SHA-256 hashes in the background before it loads a model.
On first install, or when the version or manifest changes, it hashes every file.
It then saves a read-only marker.
Later starts of the same version only do a quick manifest check.
This avoids a repeated read of about 199 MB on the keyboard creation path.

A built-in model is an APK resource. Users cannot delete it.
A downloaded model must pass the same checks and load in the background before it can replace the built-in model.
If a downloaded model fails, is corrupt, times out or crashes, openIME falls back to the built-in model.
The model never changes during a recording.

## Runtime

`StreamingEmbeddedVoiceModelRuntime` connects the sherpa-onnx arm64 runtime:

- `start()` creates a new `OnlineStream`. It does not reload the model.
- `preload()` creates and maps the `OnlineRecognizer` on a dedicated thread.
- `release()` frees the native recognizer after the 10-second cooldown.
- `acceptWaveform()` accepts only new float PCM data.
- `inputFinished()` ends the stream and returns the raw text.
- `punctuate()` runs once at the end of a voice segment.
- Model loading and inference never block the main thread.

The runtime uses `OnlineParaformerModelConfig`, `modelType="paraformer"` and `greedy_search`.
Because Paraformer does not use transducer hotwords, openIME sends no hotwords to the stream.

The keyboard uses the service-level `VoiceModelLifecycleManager` for all voice work.
The keyboard view only shows state. It does not build model providers.
`VoiceRecognitionBackendFactory` never falls back to an Android or online speech service.
If the local model is not ready, openIME says so.
It does not present online recognition as offline recognition.

## Diagnostics and audio routes

- `VoicePerformanceTrace` records the timing of each step and the count of dropped samples.
  If `droppedPcmSamples > 0`, it marks the session as degraded.
- `VoiceAudioRouteManager` manages BLE, SCO, wired and USB communication devices on Android 12 and later.
  It restores the route after the session.
  Older systems keep the system route, to avoid the first-syllable delay of a forced SCO link.
