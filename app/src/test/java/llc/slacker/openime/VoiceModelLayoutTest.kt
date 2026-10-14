package llc.slacker.openime

import llc.slacker.openime.voice.SHERPA_DECODER
import llc.slacker.openime.voice.SHERPA_ENCODER
import llc.slacker.openime.voice.SHERPA_TOKENS
import llc.slacker.openime.voice.StreamingModelKind
import llc.slacker.openime.voice.VoiceModelAutoDetector
import llc.slacker.openime.voice.VoiceModelLayoutResolver
import llc.slacker.openime.voice.VoiceModelManifest
import llc.slacker.openime.voice.trustFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoiceModelLayoutTest {
    private fun manifest(
        type: String = "paraformer",
        files: List<String> = listOf(SHERPA_ENCODER, SHERPA_DECODER, SHERPA_TOKENS),
        roles: Map<String, String> = emptyMap(),
    ) = VoiceModelManifest(
        modelId = "m", modelVersion = "1", language = "zh", modelType = type,
        engineVersion = "e", fileHash = "0".repeat(64), supportsPunctuation = false,
        requiredMemory = 1L, files = files, roles = roles,
    )

    @Test
    fun legacyParaformerManifestStillResolvesToTheBuiltInPaths() {
        val layout = VoiceModelLayoutResolver.resolve(manifest())
        assertNotNull(layout)
        assertEquals(StreamingModelKind.PARAFORMER, layout!!.kind)
        assertEquals(SHERPA_ENCODER, layout.path("encoder"))
    }

    @Test
    fun legacyManifestFingerprintIsUnchangedByTheRolesField() {
        // 内置模型在受信目录里的指纹是按"没有 roles"算的，这个字段不能把它改掉。
        assertEquals(manifest().trustFingerprint(), manifest(roles = emptyMap()).trustFingerprint())
    }

    @Test
    fun rolesAreCoveredByTheFingerprint() {
        val files = listOf("a.onnx", "b.onnx", "t.txt")
        val one = manifest(files = files, roles = mapOf("encoder" to "a.onnx", "decoder" to "b.onnx", "tokens" to "t.txt"))
        val swapped = manifest(files = files, roles = mapOf("encoder" to "b.onnx", "decoder" to "a.onnx", "tokens" to "t.txt"))
        assertNotEquals(one.trustFingerprint(), swapped.trustFingerprint())
    }

    @Test
    fun transducerNeedsAJoiner() {
        val files = listOf("e.onnx", "d.onnx", "t.txt")
        val m = manifest("transducer", files, mapOf("encoder" to "e.onnx", "decoder" to "d.onnx", "tokens" to "t.txt"))
        assertNull(VoiceModelLayoutResolver.resolve(m))
        assertTrue(VoiceModelLayoutResolver.explain(m)!!.contains("joiner"))
    }

    @Test
    fun transducerWithAllRolesResolves() {
        val files = listOf("e.onnx", "d.onnx", "j.onnx", "t.txt")
        val m = manifest("transducer", files, mapOf("encoder" to "e.onnx", "decoder" to "d.onnx", "joiner" to "j.onnx", "tokens" to "t.txt"))
        assertEquals(StreamingModelKind.TRANSDUCER, VoiceModelLayoutResolver.resolve(m)!!.kind)
    }

    @Test
    fun nonParaformerWithoutRolesIsRejected() {
        assertNull(VoiceModelLayoutResolver.resolve(manifest("transducer")))
    }

    @Test
    fun unknownTypeNamesTheSupportedOnes() {
        val why = VoiceModelLayoutResolver.explain(manifest("whisper"))!!
        assertTrue(why.contains("paraformer") && why.contains("transducer"))
    }

    @Test
    fun roleMustPointAtAListedFile() {
        val m = manifest("zipformer2-ctc", listOf("m.onnx", "t.txt"), mapOf("model" to "other.onnx", "tokens" to "t.txt"))
        assertNull(VoiceModelLayoutResolver.resolve(m))
    }

    @Test
    fun traversalInARolePathIsRejected() {
        val m = manifest("zipformer2-ctc", listOf("../m.onnx", "t.txt"), mapOf("model" to "../m.onnx", "tokens" to "t.txt"))
        assertNull(VoiceModelLayoutResolver.resolve(m))
    }

    @Test
    fun unknownRoleIsRejected() {
        val m = manifest("zipformer2-ctc", listOf("m.onnx", "t.txt", "x.onnx"),
            mapOf("model" to "m.onnx", "tokens" to "t.txt", "weird" to "x.onnx"))
        assertNull(VoiceModelLayoutResolver.resolve(m))
    }

    private fun dir(vararg names: String): File {
        val root = Files.createTempDirectory("vm-detect").toFile()
        names.forEach { n -> File(root, n).apply { parentFile.mkdirs(); writeText("x:$n") } }
        return root
    }

    @Test
    fun detectsAnOfficialTransducerPackagePreferringInt8() {
        val root = dir(
            "encoder-epoch-99.int8.onnx", "encoder-epoch-99.onnx",
            "decoder-epoch-99.onnx", "joiner-epoch-99.int8.onnx", "joiner-epoch-99.onnx", "tokens.txt",
        )
        val m = VoiceModelAutoDetector.detect(root, "pkg").getOrThrow().manifest
        assertEquals("transducer", m.modelType)
        assertEquals("encoder-epoch-99.int8.onnx", m.roles["encoder"])
        assertEquals("joiner-epoch-99.int8.onnx", m.roles["joiner"])
        assertNull(m.validateShape())
        assertFalse(m.supportsPunctuation)
    }

    @Test
    fun detectsParaformerAndAPunctuationModel() {
        val root = dir("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt", "punct/model.int8.onnx")
        val m = VoiceModelAutoDetector.detect(root, "pkg").getOrThrow().manifest
        assertEquals("paraformer", m.modelType)
        assertTrue(m.supportsPunctuation)
        assertEquals("punct/model.int8.onnx", VoiceModelLayoutResolver.punctuationPath(m))
        assertNull(m.validateShape())
    }

    @Test
    fun refusesToGuessSingleFileCtcFamilyFromItsFilename() {
        for (name in listOf("model.onnx", "nemo-ctc.int8.onnx", "zipformer2-ctc.int8.onnx")) {
            val result = VoiceModelAutoDetector.detect(dir(name, "tokens.txt"), "pkg")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("无法仅凭文件名"))
        }
    }

    @Test
    fun detectionFailsClearlyWithoutTokens() {
        val r = VoiceModelAutoDetector.detect(dir("encoder.onnx", "decoder.onnx"), "pkg")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("tokens"))
    }

    @Test
    fun detectionRefusesAmbiguousPackages() {
        val r = VoiceModelAutoDetector.detect(dir("a.onnx", "b.onnx", "tokens.txt"), "pkg")
        assertTrue(r.isFailure)
    }

    @Test
    fun detectedIdIsSafeForTheFilesystem() {
        assertEquals("a-b-c", VoiceModelAutoDetector.safeId("a b/c"))
        assertEquals("imported-model", VoiceModelAutoDetector.safeId("///"))
    }

    @Test
    fun manifestSurvivesAJsonRoundTrip() {
        val files = listOf("e.onnx", "d.onnx", "t.txt")
        val m = manifest("paraformer", files, mapOf("encoder" to "e.onnx", "decoder" to "d.onnx", "tokens" to "t.txt"))
        val back = VoiceModelManifest.parse(m.toJson())
        assertEquals(m, back)
    }
}
