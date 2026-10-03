package llc.slacker.openime.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import llc.slacker.openime.data.FluentEmojiAssetRepository
import llc.slacker.openime.theme.ImeTypographyTokens
import java.util.concurrent.Executors

/**
 * Builds Fluent emoji cells and owns the process-level bitmap decode cache.
 */
internal class EmojiCellFactory(
    private val context: Context,
    private val toPx: (Int) -> Int = { (it * context.resources.displayMetrics.density).toInt() },
    private val onFeedback: () -> Unit,
    private val onEmojiSelected: (String) -> Unit,
) {
    fun create(emoji: String): View {
        val cell = FrameLayout(context).apply {
            tag = "emoji-cell"
            contentDescription = emoji
            isClickable = true
            isFocusable = true
            background = null
        }
        val fallback = TextView(context).apply {
            text = emoji
            textSize = ImeTypographyTokens.KEY_LETTER_SP
            gravity = Gravity.CENTER
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cell.addView(
            fallback,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        FluentEmojiAssetRepository.pathFor(context, emoji)?.let { assetPath ->
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                tag = assetPath
            }
            cell.addView(
                image,
                FrameLayout.LayoutParams(toPx(28), toPx(28)).apply { gravity = Gravity.CENTER },
            )
            requestBitmap(context, assetPath) { bitmap ->
                if (image.tag == assetPath) {
                    image.setImageBitmap(bitmap)
                    image.visibility = View.VISIBLE
                    fallback.visibility = View.INVISIBLE
                }
            }
        }

        cell.setOnClickListener {
            onFeedback()
            onEmojiSelected(emoji)
        }
        return cell
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        private const val CACHE_BYTES = 4 * 1024 * 1024

        private val bitmaps = object : LruCache<String, Bitmap>(CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                value.byteCount
        }
        private val decodeExecutor =
            Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "openime-emoji-decode").apply {
                    isDaemon = true
                }
            }
        private val mainHandler = Handler(Looper.getMainLooper())
        private val decodeLock = Any()
        private val waiters =
            mutableMapOf<String, MutableList<(Bitmap) -> Unit>>()

        private fun requestBitmap(
            context: Context,
            assetPath: String,
            onReady: (Bitmap) -> Unit,
        ) {
            bitmaps.get(assetPath)?.let { cached ->
                onReady(cached)
                return
            }

            val shouldDecode = synchronized(decodeLock) {
                bitmaps.get(assetPath)?.let { cached ->
                    mainHandler.post { onReady(cached) }
                    return@synchronized false
                }

                val existing = waiters[assetPath]
                if (existing != null) {
                    existing += onReady
                    false
                } else {
                    waiters[assetPath] = mutableListOf(onReady)
                    true
                }
            }
            if (!shouldDecode) return

            val appContext = context.applicationContext
            decodeExecutor.execute {
                val bitmap = runCatching {
                    appContext.assets.open(assetPath).use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()

                val callbacks = synchronized(decodeLock) {
                    if (bitmap != null) {
                        bitmaps.put(assetPath, bitmap)
                    }
                    waiters.remove(assetPath).orEmpty()
                }
                if (bitmap != null && callbacks.isNotEmpty()) {
                    mainHandler.post {
                        callbacks.forEach { it(bitmap) }
                    }
                }
            }
        }
    }
}
