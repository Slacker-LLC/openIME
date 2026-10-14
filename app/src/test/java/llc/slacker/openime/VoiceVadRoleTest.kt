package llc.slacker.openime

import llc.slacker.openime.voice.SHERPA_DECODER
import llc.slacker.openime.voice.SHERPA_ENCODER
import llc.slacker.openime.voice.SHERPA_TOKENS
import llc.slacker.openime.voice.StreamingModelKind
import llc.slacker.openime.voice.VoiceModelAutoDetector
import llc.slacker.openime.voice.VoiceModelLayoutResolver
import llc.slacker.openime.voice.VoiceModelManifest
import llc.slacker.openime.voice.VoiceModelSelection
import llc.slacker.openime.voice.resolveSherpaRuntimeModelFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoiceVadRoleTest {

    private fun transducerManifest(
        files: List<String> = listOf("e.onnx", "d.onnx", "j.onnx", "t.txt"),
        roles: Map<String, String> = mapOf(
            "encoder" to "e.onnx",
            "decoder" to "d.onnx",
            "joiner" to "j.onnx",
            "tokens" to "t.txt",
        ),
    ) = VoiceModelManifest(
        modelId = "vad-model", modelVersion = "1", language = "zh", modelType = "transducer",
        engineVersion = "e", fileHash = "0".repeat(64), supportsPunctuation = false,
        requiredMemory = 1L, files = files, roles = roles,
    )

    private fun dir(vararg names: String): File {
        val root = Files.createTempDirectory("vm-vad").toFile()
        names.forEach { n -> File(root, n).apply { parentFile.mkdirs(); writeText("x:$n") } }
        return root
    }

    @Test
    fun vadIsAnOptionalRoleThatTheLayoutKeeps() {
        assertTrue("vad" in StreamingModelKind.OPTIONAL_ROLES)
        val m = transducerManifest(
            files = listOf("e.onnx", "d.onnx", "j.onnx", "t.txt", "silero_vad.onnx"),
            roles = mapOf(
                "encoder" to "e.onnx", "decoder" to "d.onnx", "joiner" to "j.onnx",
                "tokens" to "t.txt", "vad" to "silero_vad.onnx",
            ),
        )
        assertNull(m.validateShape())
        val layout = VoiceModelLayoutResolver.resolve(m)
        assertNotNull(layout)
        assertEquals("silero_vad.onnx", layout!!.path("vad"))
    }

    @Test
    fun detectorFindsSileroVadWithoutBreakingSingleFileCtcRules() {
        val root = dir("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "silero_vad.onnx")
        try {
            val detected = VoiceModelAutoDetector.detect(root, "pkg").getOrThrow()
            assertEquals("silero_vad.onnx", detected.manifest.roles["vad"])
            assertTrue(detected.manifest.files.contains("silero_vad.onnx"))
            assertTrue(detected.note.contains("VAD"))
            assertNull(VoiceModelLayoutResolver.explain(detected.manifest))
        } finally {
            root.deleteRecursively()
        }

        // silero_vad.onnx 不能把单文件 CTC 包伪装成"多文件"；家族猜测仍然必须拒绝。
        val ctcRoot = dir("model.onnx", "tokens.txt", "silero_vad.onnx")
        try {
            val failure = VoiceModelAutoDetector.detect(ctcRoot, "pkg")
            assertTrue(failure.isFailure)
            assertTrue(failure.exceptionOrNull()!!.message!!.contains("无法仅凭文件名"))
        } finally {
            ctcRoot.deleteRecursively()
        }
    }

    @Test
    fun downloadedPackageResolvesTheVadFile() {
        val downloadedRoot = Files.createTempDirectory("openime-vad-models").toFile()
        val selection = VoiceModelSelection(
            manifest = transducerManifest(
                files = listOf("e.onnx", "d.onnx", "j.onnx", "t.txt", "silero_vad.onnx"),
                roles = mapOf(
                    "encoder" to "e.onnx", "decoder" to "d.onnx", "joiner" to "j.onnx",
                    "tokens" to "t.txt", "vad" to "silero_vad.onnx",
                ),
            ),
            source = VoiceModelSelection.Source.DOWNLOADED,
        )
        val packageRoot = File(downloadedRoot, "vad-model")
        try {
            selection.manifest!!.files.forEach { path ->
                File(packageRoot, path).apply { parentFile?.mkdirs(); writeText(path) }
            }
            val resolved = resolveSherpaRuntimeModelFiles(selection, downloadedRoot)
            assertNotNull(resolved)
            assertEquals(
                File(packageRoot, "silero_vad.onnx").canonicalPath,
                File(resolved!!.vad!!).canonicalPath,
            )
        } finally {
            downloadedRoot.deleteRecursively()
        }
    }

    @Test
    fun declaredVadFileMissingFailsClosed() {
        val downloadedRoot = Files.createTempDirectory("openime-vad-missing").toFile()
        val selection = VoiceModelSelection(
            manifest = transducerManifest(
                files = listOf("e.onnx", "d.onnx", "j.onnx", "t.txt", "silero_vad.onnx"),
                roles = mapOf(
                    "encoder" to "e.onnx", "decoder" to "d.onnx", "joiner" to "j.onnx",
                    "tokens" to "t.txt", "vad" to "silero_vad.onnx",
                ),
            ),
            source = VoiceModelSelection.Source.DOWNLOADED,
        )
        val packageRoot = File(downloadedRoot, "vad-model")
        try {
            // 声明了 vad 角色却缺文件：整包校验失败，不允许静默降级。
            listOf("e.onnx", "d.onnx", "j.onnx", "t.txt").forEach { path ->
                File(packageRoot, path).apply { parentFile?.mkdirs(); writeText(path) }
            }
            assertNull(resolveSherpaRuntimeModelFiles(selection, downloadedRoot))
        } finally {
            downloadedRoot.deleteRecursively()
        }
    }

    @Test
    fun legacyBuiltInManifestHasNoVad() {
        val legacy = VoiceModelManifest(
            modelId = "built-in", modelVersion = "1", language = "zh-CN,en-US", modelType = "paraformer",
            engineVersion = "e", fileHash = "0".repeat(64), supportsPunctuation = true,
            requiredMemory = 1L, files = listOf(SHERPA_ENCODER, SHERPA_DECODER, SHERPA_TOKENS),
        )
        val selection = VoiceModelSelection(legacy, VoiceModelSelection.Source.BUILT_IN)
        val resolved = resolveSherpaRuntimeModelFiles(selection, File("/unused"))
        assertNotNull(resolved)
        assertNull(resolved!!.vad)
    }
}
