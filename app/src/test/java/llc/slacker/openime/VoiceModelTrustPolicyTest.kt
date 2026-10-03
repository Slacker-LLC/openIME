package llc.slacker.openime

import llc.slacker.openime.voice.SHERPA_DECODER
import llc.slacker.openime.voice.SHERPA_ENCODER
import llc.slacker.openime.voice.SHERPA_TOKENS
import llc.slacker.openime.voice.TrustedVoiceModelCatalog
import llc.slacker.openime.voice.VoiceModelManifest
import llc.slacker.openime.voice.downloadedPackageCacheSignature
import llc.slacker.openime.voice.isSafeVoiceModelId
import llc.slacker.openime.voice.isSafeVoiceModelRelativePath
import llc.slacker.openime.voice.resolveContainedVoiceModelFile
import llc.slacker.openime.voice.trustFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoiceModelTrustPolicyTest {

    private fun assetRoot(): File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("missing test asset root")

    private fun officialManifest(): VoiceModelManifest =
        VoiceModelManifest.parse(
            File(assetRoot(), "models/voice/manifest.json").readText(),
        )

    private fun sampleManifest(): VoiceModelManifest = VoiceModelManifest(
        modelId = "test-model",
        modelVersion = "1",
        language = "zh-CN",
        modelType = "zipformer",
        engineVersion = "test-engine",
        fileHash = "0".repeat(64),
        supportsPunctuation = false,
        requiredMemory = 128_000_000L,
        files = listOf("model/a.bin", "model/b.bin"),
    )

    @Test
    fun bundledCatalogTrustsOnlyExactOfficialManifestAndLayout() {
        val manifest = officialManifest()
        assertEquals(
            "sherpa-onnx-streaming-paraformer-bilingual-zh-en-int8",
            manifest.modelId,
        )
        assertEquals("paraformer", manifest.modelType)
        assertEquals(
            listOf(SHERPA_DECODER, SHERPA_ENCODER, SHERPA_TOKENS),
            manifest.files,
        )
        val catalog = TrustedVoiceModelCatalog.parse(
            File(assetRoot(), "models/voice/trusted-downloads.tsv").readText(),
        )
        val entry = catalog.entryFor(manifest)
        assertNotNull(entry)
        assertEquals("Slacker-LLC", entry?.publisher)
        assertTrue(entry?.matchesLayout(manifest, assetRoot()) == true)

        assertEquals(null, catalog.entryFor(manifest.copy(requiredMemory = 1L)))
        assertEquals(null, catalog.entryFor(manifest.copy(language = "en-US")))
        assertEquals(null, catalog.entryFor(manifest.copy(fileHash = "1".repeat(64))))
    }

    @Test
    fun traversalAbsoluteAndUnsafeModelIdsAreRejected() {
        assertTrue(isSafeVoiceModelId("official-model_1.2"))
        assertFalse(isSafeVoiceModelId("../outside"))
        assertFalse(isSafeVoiceModelId("/absolute"))
        assertFalse(isSafeVoiceModelId("model/subdir"))

        assertTrue(isSafeVoiceModelRelativePath("models/voice/model.onnx"))
        assertFalse(isSafeVoiceModelRelativePath("../outside"))
        assertFalse(isSafeVoiceModelRelativePath("models/../outside"))
        assertFalse(isSafeVoiceModelRelativePath("/absolute/model.onnx"))
        assertFalse(isSafeVoiceModelRelativePath("models\\outside.onnx"))
    }

    @Test
    fun symlinkEscapeIsRejected() {
        val root = Files.createTempDirectory("openime-model-root").toFile()
        val outside = Files.createTempDirectory("openime-model-outside").toFile()
        try {
            File(outside, "payload.onnx").writeText("outside")
            val linkCreated = runCatching {
                Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
            }.isSuccess
            assumeTrue("filesystem does not allow test symlinks", linkCreated)

            assertTrue(
                runCatching {
                    resolveContainedVoiceModelFile(root, "escape/payload.onnx")
                }.isFailure,
            )
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun trustedMetadataBindsFileSizes() {
        val manifest = sampleManifest()
        val catalog = TrustedVoiceModelCatalog.parse(
            "TestPublisher\t${manifest.trustFingerprint()}\t3,2\n",
        )
        val root = Files.createTempDirectory("openime-trusted-layout").toFile()
        try {
            File(root, "model").mkdirs()
            File(root, "model/a.bin").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "model/b.bin").writeBytes(byteArrayOf(4, 5))
            val entry = catalog.entryFor(manifest) ?: error("trusted entry missing")
            assertTrue(entry.matchesLayout(manifest, root))

            File(root, "model/b.bin").appendBytes(byteArrayOf(6))
            assertFalse(entry.matchesLayout(manifest, root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verificationCacheSignatureInvalidatesWhenInstalledFilesChange() {
        val manifest = sampleManifest()
        val root = Files.createTempDirectory("openime-cache-signature").toFile()
        try {
            File(root, "manifest.json").writeText("{}")
            File(root, "model").mkdirs()
            File(root, "model/a.bin").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "model/b.bin").writeBytes(byteArrayOf(4, 5))

            val before = downloadedPackageCacheSignature(root, manifest, "TestPublisher")
            File(root, "model/a.bin").appendBytes(byteArrayOf(9))
            val after = downloadedPackageCacheSignature(root, manifest, "TestPublisher")

            assertNotEquals(before, after)
        } finally {
            root.deleteRecursively()
        }
    }
}
