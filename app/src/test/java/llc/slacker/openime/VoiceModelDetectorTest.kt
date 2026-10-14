package llc.slacker.openime

import llc.slacker.openime.voice.StreamingModelKind
import llc.slacker.openime.voice.VoiceModelAutoDetector
import llc.slacker.openime.voice.VoiceModelLayoutResolver
import llc.slacker.openime.voice.VoiceModelManifest
import llc.slacker.openime.voice.trustFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoiceModelDetectorTest {

    private fun packageRoot(name: String, vararg files: String): File {
        val dir = Files.createTempDirectory(name).toFile()
        files.forEach { relative ->
            val file = File(dir, relative)
            file.parentFile?.mkdirs()
            file.writeBytes(ByteArray(8) { 1 })
        }
        return dir
    }

    @Test
    fun transducerPackageInfersFamilyAndOptionalFiles() {
        val root = packageRoot(
            "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.onnx",
            "tokens.txt",
            "bpe.vocab",
            "hotwords.txt",
        )
        try {
            val detected = VoiceModelAutoDetector.detect(root, "fallback").getOrThrow().manifest
            assertEquals("transducer", detected.modelType)
            assertEquals("zipformer", detected.family)
            assertEquals("bpe.vocab", detected.roles["bpeVocab"])
            assertEquals("hotwords.txt", detected.roles["hotwords"])
            assertEquals(null, VoiceModelLayoutResolver.explain(detected))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun singleFileCtcAlwaysAsksForAManifest() {
        // 家族用错会让原生层直接失败，所以连名字线索也不猜（原有测试亦是此约定）。
        for (name in listOf(
            "sherpa-onnx-streaming-nemo-ctc-en",
            "sherpa-onnx-streaming-t-one-russian-2025-09-08",
            "mystery-model",
        )) {
            val root = packageRoot(name, "model.onnx", "tokens.txt")
            try {
                val result = VoiceModelAutoDetector.detect(root, "fallback")
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()!!.message!!.contains("manifest.json"))
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun manifestCarriesFamilyAndModelingUnit() {
        val manifest = VoiceModelManifest(
            modelId = "m", modelVersion = "1", language = "zh", modelType = "transducer",
            engineVersion = "e", fileHash = "0".repeat(64), supportsPunctuation = false,
            requiredMemory = 1L,
            files = listOf("e.onnx", "d.onnx", "j.onnx", "t.txt", "bpe.vocab"),
            roles = mapOf(
                "encoder" to "e.onnx", "decoder" to "d.onnx", "joiner" to "j.onnx",
                "tokens" to "t.txt", "bpeVocab" to "bpe.vocab",
            ),
            family = "conformer",
            modelingUnit = "cjkchar+bpe",
        )
        assertEquals(null, manifest.validateShape())
        val parsed = VoiceModelManifest.parse(manifest.toJson())
        assertEquals("conformer", parsed.family)
        assertEquals("cjkchar+bpe", parsed.modelingUnit)
        assertEquals(manifest.trustFingerprint(), parsed.trustFingerprint())
    }

    @Test
    fun familyOnlyFitsTransducers() {
        val paraformer = VoiceModelManifest(
            modelId = "m", modelVersion = "1", language = "zh", modelType = "paraformer",
            engineVersion = "e", fileHash = "0".repeat(64), supportsPunctuation = false,
            requiredMemory = 1L, files = listOf("t.txt"),
            roles = mapOf("tokens" to "t.txt"), family = "zipformer",
        )
        assertTrue(paraformer.validateShape()!!.contains("family"))
    }
}
