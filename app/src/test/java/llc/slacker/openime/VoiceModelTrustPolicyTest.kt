package llc.slacker.openime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelTrustPolicyTest {

    private fun assetRoot(): File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("missing test asset root")

    private fun officialManifest(): VoiceModelManifest = VoiceModelManifest(
        modelId = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20-int8",
        modelVersion = "2023-02-20-int8",
        language = "zh-CN,en-US",
        modelType = "zipformer",
        engineVersion = "sherpa-onnx-v1.13.6",
        fileHash = "f2b835dfe8231bccc08692b4d8751ff50c2360b9b39a7501136a45e1bd97db85",
        supportsPunctuation = false,
        requiredMemory = 420_000_000L,
        files = listOf(
            "models/voice/bilingual-zipformer/decoder-epoch-99-avg-1.onnx",
            "models/voice/bilingual-zipformer/encoder-epoch-99-avg-1.int8.onnx",
            "models/voice/bilingual-zipformer/joiner-epoch-99-avg-1.int8.onnx",
            "models/voice/bilingual-zipformer/tokens.txt",
        ),
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
            "12bf0e2a9d5090bdb935c90941a0b3ef6269346860398fed3e45cef91940a525",
            manifest.trustFingerprint(),
        )
        val catalog = TrustedVoiceModelCatalog.parse(
            File(assetRoot(), "models/voice/trusted-downloads.tsv").readText(),
        )
        val entry = catalog.entryFor(manifest)
        assertNotNull(entry)
        assertEquals("Slacker-LLC", entry?.publisher)
        assertEquals(
            listOf(13_876_452L, 181_895_032L, 3_228_404L, 56_317L),
            entry?.fileSizes,
        )
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
            Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())

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
