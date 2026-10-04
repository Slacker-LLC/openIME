package llc.slacker.openime

import android.content.Context
import android.os.Handler
import android.os.Looper
import llc.slacker.openime.hotword.HotwordRuntime
import llc.slacker.openime.voice.VoiceCorrectionRepository
import llc.slacker.openime.voice.VoiceModelLifecycleManager
import llc.slacker.openime.voice.VoiceRecognitionEvents
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Debug-only real PCM -> native model -> actual lab InputConnection replay.
 * The fixture and hook are absent from release builds. No fake ASR callbacks.
 */
internal object VoiceAudioE2E {
    fun replay(context: Context, punctuationOnly: Boolean, finished: () -> Unit) {
        val service = LocalVoiceImeService.activeInstance
        val reportFile = File(context.getExternalFilesDir(null), "beta4-audio-e2e.json")
        reportFile.delete()
        val handler = Handler(Looper.getMainLooper())
        val editor = service?.currentInputEditorInfo
        if (service == null || editor?.packageName != context.packageName || editor.fieldId != R.id.lab_single) {
            reportFile.writeText(JSONObject().put("error", "live lab editor required").toString())
            finished(); return
        }
        service.onVoiceSessionStarted(true)
        Thread({
            val report = JSONObject().put("passed", false)
            try {
                val manager = service.javaClass.getDeclaredField("voiceLifecycle").apply { isAccessible = true }
                    .get(service) as VoiceModelLifecycleManager
                val runtime = checkNotNull(manager.awaitRuntime())
                var lastPartial = ""
                runtime.openSession("zh-CN", object : VoiceRecognitionEvents {
                    override fun onPartial(text: String) {
                        lastPartial = text
                        handler.post { service.onVoicePartial(text) }
                    }
                    override fun onFinal(text: String) = Unit
                    override fun onRms(rms: Float) = Unit
                    override fun onError(message: String) { error(message) }
                    override fun onReady() = Unit
                }).use { session ->
                    val raw: String
                    if (punctuationOnly) {
                        raw = "今天天气很好我们一起去公园散步吧"
                        report.put("asciiSpace", session.punctuate("你好,今天很好.明天见!"))
                            .put("literalSpace", session.punctuate("价格3.5，访问https://a.b。"))
                    } else {
                        val wav = context.assets.open("voice-beta4.wav").use { it.readBytes() }
                        report.put("fixtureSha256", MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") { "%02x".format(it) })
                        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
                        var offset = 12
                        while (String(wav, offset, 4, Charsets.US_ASCII) != "data") {
                            val size = buffer.getInt(offset + 4); offset += 8 + size + size % 2
                        }
                        val size = buffer.getInt(offset + 4)
                        val pcm = ByteBuffer.wrap(wav, offset + 8, size).order(ByteOrder.LITTLE_ENDIAN)
                        report.put("samplesFed", size / 2)
                        while (pcm.hasRemaining()) {
                            session.acceptWaveform(FloatArray(minOf(320, pcm.remaining() / 2)) { pcm.short / 32768f })
                        }
                        report.put("partialBeforeFinish", lastPartial)
                        raw = session.inputFinished()
                        report.put("question", session.punctuate("你今天有时间吗"))
                            .put("clauses", session.punctuate("今天天气很好我们一起去公园散步吧"))
                    }
                    report.put("rawFinal", raw)
                    val final = HotwordRuntime.apply(VoiceCorrectionRepository.apply(session.punctuate(raw).orEmpty()))
                    report.put("final", final)
                    handler.post {
                        try {
                            check(LocalVoiceImeService.activeInstance === service && service.currentInputEditorInfo?.fieldId == R.id.lab_single)
                            service.onVoiceFinal(final)
                            report.put("passed", true)
                        } catch (error: Throwable) { report.put("error", error.toString()) }
                        finally { reportFile.writeText(report.toString(2)); finished() }
                    }
                }
            } catch (error: Throwable) {
                handler.post {
                    service.onVoiceCaptureStopped()
                    report.put("error", error.toString()); reportFile.writeText(report.toString(2)); finished()
                }
            }
        }, "openime-e2e-pcm").apply { isDaemon = true }.start()
    }
}
