package llc.slacker.openime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import llc.slacker.openime.theme.ImeTheme
import llc.slacker.openime.voice.VoiceFaceState
import llc.slacker.openime.voice.VoiceFaceView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The character is drawn with `android.graphics`, so only a device can show that
 * it draws at all: this renders every state of a voice session and checks the
 * pixels it left on the canvas.
 *
 * The frames are stepped by hand rather than waited for, because a view that is
 * not attached to a window never starts its own frame loop.
 *
 * The pictures land in the app's files directory under `voice-face/`, which is
 * how the artwork is looked at by eye after a change to the port.
 */
@RunWith(AndroidJUnit4::class)
class VoiceFaceRenderInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun everyStatePaintsTheCharacter() {
        val tokens = ImeTheme.IOS.tokens()
        for (state in VoiceFaceState.entries) {
            var bitmap: Bitmap? = null
            instrumentation.runOnMainSync {
                val view = VoiceFaceView(context, { dp: Int -> dp })
                view.applyTokens(tokens)
                view.setState(state)
                val spec = View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY)
                view.measure(spec, spec)
                view.layout(0, 0, SIZE, SIZE)
                // A mood enters with a spring, so the state is given a second of
                // frames before the picture is taken.
                var now = 0f
                repeat(FRAMES) {
                    now += 16f
                    view.face.botState.step(now)
                }
                val picture = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(picture))
                bitmap = picture
            }

            val picture = requireNotNull(bitmap) { "$state drew nothing" }
            val pixels = IntArray(SIZE * SIZE)
            picture.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            val opaque = pixels.count { (it ushr 24) and 0xFF > 8 }
            val body = pixels.count { it == tokens.primary }
            save(picture, state)
            assertTrue("$state painted $opaque pixels", opaque > 200)
            assertTrue("$state painted no body colour ($body pixels)", body > 50)
        }
    }

    /** Writes the picture out, so a person can look at the artwork itself. */
    private fun save(bitmap: Bitmap, state: VoiceFaceState) {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir,
            "voice-face",
        )
        if (!dir.isDirectory && !dir.mkdirs()) return
        runCatching {
            FileOutputStream(File(dir, "${state.name.lowercase()}.png")).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    private companion object {
        /** Side of the canvas, in pixels; the face fills 64 of them. */
        const val SIZE = 200

        /** Frames the state is given before the picture is taken, about one second. */
        const val FRAMES = 60
    }
}
