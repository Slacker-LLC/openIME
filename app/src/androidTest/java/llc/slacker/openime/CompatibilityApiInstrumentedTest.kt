package llc.slacker.openime

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PersistableBundle
import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Core compatibility smoke coverage against current production APIs on API 29 and 31. */
@RunWith(AndroidJUnit4::class)
class CompatibilityApiInstrumentedTest {

    @Test
    fun pPlusBackspaceUsesCodePointDeletionForUnicodeSafety() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = RecordingInputConnection(View(context))
        val gateway = InputConnectionGateway(context, { connection })

        gateway.deleteBackwards()

        assertTrue("API 29+ must use code-point deletion", connection.codePointDeleteCalled)
        assertFalse("API 29+ must not fall back to UTF-16 unit deletion", connection.utf16DeleteCalled)
    }

    @Test
    fun sensitiveClipboardIsNeverCapturedIntoPersistentHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ClipboardHistoryRepository.clearAll(context)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("secret", "compat-secret")
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipboardSensitivityPolicy.SENSITIVE_KEY, true)
        }
        clipboard.setPrimaryClip(clip)

        assertFalse(ClipboardHistoryRepository.capturePrimary(context))
        assertTrue(ClipboardHistoryRepository.load(context).isEmpty())
    }

    @Test
    fun bundledCandidateEngineWorksOnCompatibilityApi() {
        assertTrue(CandidateEngine().getCandidates("nihao").contains("你好"))
    }

    @Test
    fun freshCompatibilityDeviceReportsVoicePermissionFailureWithoutStartingCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "compatibility image has RECORD_AUDIO pre-granted; permission-path check skipped",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED,
        )
        var error = ""
        LocalAudioVoiceBackend(context, runtime = null).start(
            "zh-CN",
            object : VoiceRecognitionEvents {
                override fun onPartial(text: String) = Unit
                override fun onFinal(text: String) = Unit
                override fun onRms(rms: Float) = Unit
                override fun onError(message: String) {
                    error = message
                }
                override fun onReady() = Unit
            },
        )
        assertTrue(error.contains("麦克风权限"))
    }

    private class RecordingInputConnection(view: View) : BaseInputConnection(view, false) {
        var codePointDeleteCalled = false
        var utf16DeleteCalled = false

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            codePointDeleteCalled = true
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            utf16DeleteCalled = true
            return true
        }
    }
}
