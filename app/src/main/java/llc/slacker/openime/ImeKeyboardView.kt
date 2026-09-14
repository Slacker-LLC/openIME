package llc.slacker.openime

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors

open class ImeKeyboardView(
    context: Context,
    private val listener: Listener,
    private val standalonePanel: Boolean = false,
) : FrameLayout(context) {

    interface Listener {
        fun onModeChanged(mode: KeyboardMode)
        fun onPanelChanged(panel: Panel)
        fun onCharacter(char: String)
        fun onBackspace()
        fun onClearAll()
        fun onSpace()
        fun onFloatingKeyboardChanged(floating: Boolean)
        fun onFloatingKeyboardDragged(deltaX: Float, deltaY: Float)
        fun onVoiceToggle()
        fun onVoicePressChanged(pressed: Boolean) {
            if (pressed) onVoiceToggle()
        }
        fun onVoiceSessionStarted(autoCommitOnFinal: Boolean) {}
        fun onVoicePartial(text: String) {}
        fun onVoiceFinal(text: String) {}
        fun onVoiceError(message: String) {}
        fun onVoiceCommit() {}
        fun onVoiceCancel() {}
        fun voiceModelState(): VoiceModelLifecycleState = VoiceModelLifecycleState.COLD
        fun startVoiceRecognition(languageTag: String, events: VoiceRecognitionEvents) {
            events.onError("本地语音服务未连接")
        }
        fun stopVoiceRecognition() {}
        fun cancelVoiceRecognition() {}
        fun onEnter()
        fun onCompositionChanged(composition: String, candidates: List<String>)
        fun onNineKeyCompositionChanged(
            composition: String,
            digitBuffer: String,
            pinyinPaths: List<String>,
            candidates: List<String>,
        ) {
            onCompositionChanged(composition, candidates)
        }
        fun onCandidateSelected(candidate: String)
        fun onAssociationSelected(text: String) = Unit
        fun onCompositionBackspace()
        fun onThemeChanged(theme: ImeTheme)
        fun onAppearanceChanged(appearance: ImeAppearance)
        fun onShiftStateChanged(state: ShiftState)
        fun onCandidateExpanded(open: Boolean)
        fun onSymbolSelected(symbol: String)
        fun onEmojiSelected(emoji: String)
        fun onTextEdit(action: String)
        fun onSoundChanged(enabled: Boolean)
        fun onHapticChanged(enabled: Boolean)
        fun onPopupChanged(enabled: Boolean)
        fun onFuzzyChanged(enabled: Boolean)
        fun onSkinChanged(opacity: Int, radius: Int, fontSize: Int, primaryColor: String) {}
    }

    private val MARK_WHITE_KEY = 0x1F000001
    private val MARK_SIDE_KEY = 0x1F000002
    private val MARK_FUNCTION_KEY = 0x1F000003

    companion object {
        private val DIGITS_ONLY = Regex("[0-9]+")
        private const val EMOJI_CACHE_BYTES = 4 * 1024 * 1024
        private const val CANDIDATE_STRIP_LIMIT = 24
        private val emojiBitmaps = object : LruCache<String, Bitmap>(EMOJI_CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
        private val emojiDecodeExecutor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "openime-emoji-decode").apply { isDaemon = true }
        }
        private val emojiMainHandler = Handler(Looper.getMainLooper())
        private val emojiDecodeLock = Any()
        private val emojiDecodeWaiters = mutableMapOf<String, MutableList<(Bitmap) -> Unit>>()

        private fun requestEmojiBitmap(context: Context, assetPath: String, onReady: (Bitmap) -> Unit) {
            emojiBitmaps.get(assetPath)?.let { cached ->
                onReady(cached)
                return
            }
            val shouldDecode = synchronized(emojiDecodeLock) {
                emojiBitmaps.get(assetPath)?.let { cached ->
                    emojiMainHandler.post { onReady(cached) }
                    return@synchronized false
                }
                val waiters = emojiDecodeWaiters[assetPath]
                if (waiters != null) {
                    waiters += onReady
                    false
                } else {
                    emojiDecodeWaiters[assetPath] = mutableListOf(onReady)
                    true
                }
            }
            if (!shouldDecode) return
            val appContext = context.applicationContext
            emojiDecodeExecutor.execute {
                val bitmap = runCatching {
                    appContext.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                val waiters = synchronized(emojiDecodeLock) {
                    if (bitmap != null) emojiBitmaps.put(assetPath, bitmap)
                    emojiDecodeWaiters.remove(assetPath).orEmpty()
                }
                if (bitmap != null && waiters.isNotEmpty()) {
                    emojiMainHandler.post { waiters.forEach { it(bitmap) } }
                }
            }
        }
    }
