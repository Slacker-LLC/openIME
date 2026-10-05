# Design decisions

This log records decisions that are not obvious from the code.
Each entry has a date, the topic, the decision and the reason.
Add new entries at the end.

## 2026-09-29

- **Secondary text contrast (light theme).**
  Keep the keyboard value `keySecondaryText=#6E6E73`. Add a page role `textSecondaryRole=#6D6D72`.
  *Reason:* the old value has a contrast of about 4.44:1 on `surface=#EEF0F3`, below the 4.5:1 rule.
  Only the page role changes, so the keyboard look stays the same.
- **Candidate expand icon.**
  Reuse `ic_chevron_down` and rotate it 180° when expanded.
  *Reason:* one vector asset. No Unicode arrows.
- **Setup XML colors.**
  Keep only the resource colors that Android needs before Kotlin starts. `TokenDriftTest` keeps them equal to the Kotlin tokens.
  *Reason:* XML needs resource colors before Kotlin initializes.
- **Backspace repeat.**
  Keep the constant 60 ms repeat.
  *Reason:* visual language v1 says not to change it in that round. A later interaction project can change it.
- **Voice release tail.**
  After release, keep a 300 ms capture window. Use generation and session ownership so that an old session cannot affect a new one.
  *Reason:* an immediate cut loses the tail of `AudioRecord`. A cancel still takes effect at once.
  *Later change:* see `LOCAL_VOICE_MODEL.md`. The microphone now stops at release, and a 300 ms zero pad feeds the model.
- **Nine-key decoder thread.**
  First record the 20-sample window P50 and P95 of `publishNineKeyDigits → resolveNineKey`.
  Keep the current thread until we have a P95 from a low-end or mid-range phone.
  *Reason:* move the decoder to `CandidateQueryCoordinator` only if P95 is above 8 ms.
- **Nine-key first frame and Rime refresh.**
  While a finger presses or scrolls the candidate list, delay asynchronous Rime results. Record the delay from request to applicable result.
  *Reason:* the list must not reorder under the finger.
  Skip the fallback first frame only if the measured P95 is below 40 ms.
- **Rime user dictionary export.**
  The vendored librime 1.17.0 includes the levers module. `UserDictManager::Export/Import` can export or merge a UTF-8 snapshot after the user database session closes.
  *Reason:* the user data JSON includes learned words when a Rime session is loaded. When export is not possible, the app must say so. It must not skip the words silently.
- **Streaming voice model.**
  Use the INT8 encoder and decoder of `sherpa-onnx-streaming-paraformer-bilingual-zh-en` with `OnlineParaformerModelConfig` and `greedy_search`.
  *Reason:* smaller model and streaming Chinese and English recognition, with the official sherpa-onnx v1.13.6 configuration.
  Do not send transducer-only dynamic hotwords to the Paraformer stream.

## 2026-10-03

- **Voice word lists.** *(Removed on 2026-10-05.)*
  The idea was a built-in word list plus a user import list, both offline, with no `INTERNET` permission.
  Paraformer cannot take hotwords in the decoder, so the lists worked as homophone replacement after recognition.
  See the 2026-10-05 entry.
- **Package split.**
  Split the 99 flat files into packages: theme, core, editor, data, setup, widget, floating, handwriting, rime, candidate, voice, panel and keyboard.
  The root package keeps only the service, activities and JNI classes.
  `ArchitectureLayeringTest` enforces the dependency direction.
  *Reason:* packages change organization and visibility only. They do not change the manifest, native symbols or the names that test scripts use.
  We delay Gradle modules, because `keyboard`, `panel` and `voice` still connect through a large interface.
  First split `ImeKeyboardView.Listener`.

## 2026-10-04

- **Autofill strip.**
  Declare only `supportsInlineSuggestions` and build the androidx.autofill v1 style bundle by hand (a version table and one empty v1 style).
  Do not add the androidx.autofill dependency.
  Render entries at a fixed size of 150 dp × 40 dp in a horizontal scroll.
  If a response arrives before the keyboard view exists, hold it. Never return false, because the system then falls back to the dropdown menu.
  *Reason:* `InlineContentView` is a remote surface with no intrinsic size, so `WRAP_CONTENT` gives 0×0.
  The style bundle is a dozen lines. It is not worth the first androidx runtime dependency.
- **Numbers and symbols on letter keys.**
  Do not add a number row.
  Print the number or symbol at the top right of each letter key, as Sogou, iFlytek and WeChat keyboards do.
  Swipe up (this uses the nine-key "swipe up for digits" setting) or long-press to enter it.
  The first row q to p gives 1 to 0. The other rows give punctuation.
  Chinese mode uses full-width forms (！ ￥ ？ （ ） ： ；). English mode uses half-width forms.
  A separate setting, 数字和符号提示 ("Number and symbol hints", default on), turns the hints off.
  *Reason:* an extra row would squeeze every row (about 43 dp in portrait, 34 dp in landscape) or make the keyboard taller.
  A taller keyboard changes the IME window height, and the panels, the floating keyboard and the nine-key layout would all change.
  Hints plus swipe do not change the layout. Major Chinese keyboards do the same.
- **Space-swipe cursor locks the bottom row.**
  The cursor mode starts when three conditions are true.
  The finger moved 18 dp horizontally.
  The horizontal part is more than 1.25 times the vertical part.
  The long-press voice timeout has not ended.
  Then the other keys in the same row get `touchLocked` (grey, no response).
  With a pinyin preedit, the swipe moves the preedit cursor.
  *Reason:* the finger stays on space, but a second finger or a drifting thumb can hit a nearby key.
  Sending arrow key events to the app during a preedit would break the composition.
- **标点用空格代替 ("Replace punctuation with spaces").**
  This setting acts only on voice results.
  Commas, periods and question marks become one space. Final punctuation is removed. Parentheses, `3.5` and `a.b` stay.
  It belongs to the 语音输入 ("Voice input") group with the filler-word option. The default is off.
  *Reason:* we read the request as a switch for voice text post-processing.

## 2026-10-05

- **Voice word lists removed.**
  From 0.0.6-beta.1, the `hotword` module, the built-in lists, the management page and the typing candidate weighting are gone.
  *Reason:* homophone replacement is not mature. We do not ship it yet.
  The earlier code is on the `archive/voice-word-lists` branch (`f3bb5cc`, the 0.0.5-beta.1 commit). We can merge it back after a fix.
