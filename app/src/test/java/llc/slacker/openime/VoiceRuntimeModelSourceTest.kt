package llc.slacker.openime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRuntimeModelSourceTest {

    private fun manifest(modelId: String = "test-model"): VoiceModelManifest = VoiceModelManifest(
        modelId = modelId,
        modelVersion = "1",
        language = "zh-CN,en-US",
        modelType = "zipformer",
        engineVersion = "sherpa-test",
        fileHash = "0".repeat(64),
        supportsPunctuation = false,
        requiredMemory = 420_000_000L,
        files = listOf(
            SHERPA_DECODER,
            SHERPA_ENCODER,
            SHERPA_JOINER,
            SHERPA_TOKENS,
        ),
    )

    @Test
    fun builtInSelectionUsesAssetPaths() {
        val selection = VoiceModelSelection(
            manifest = manifest(),
            source = VoiceModelSelection.Source.BUILT_IN,
        )
        val source = resolveSherpaRuntimeModelFiles(
            selection,
            Files.createTempDirectory("unused-downloaded-root").toFile(),
        )

        assertNotNull(source)
        assertEquals(SherpaRuntimeStorage.ASSETS, source?.storage)
        assertEquals(SHERPA_ENCODER, source?.encoder)
        assertEquals(SHERPA_DECODER, source?.decoder)
        assertEquals(SHERPA_JOINER, source?.joiner)
        assertEquals(SHERPA_TOKENS, source?.tokens)
    }

    @Test
    fun downloadedSelectionUsesFilesFromItsPrivatePackageRoot() {
        val downloadedRoot = Files.createTempDirectory("openime-downloaded-models").toFile()
        val selection = VoiceModelSelection(
            manifest = manifest("downloaded-model"),
            source = VoiceModelSelection.Source.DOWNLOADED,
        )
        val packageRoot = File(downloadedRoot, "downloaded-model")
        try {
            selection.manifest!!.files.forEach { path ->
                File(packageRoot, path).apply {
                    parentFile?.mkdirs()
                    writeText(path)
                }
            }

            val source = resolveSherpaRuntimeModelFiles(selection, downloadedRoot)
            assertNotNull(source)
            assertEquals(SherpaRuntimeStorage.FILES, source?.storage)
            assertEquals(File(packageRoot, SHERPA_ENCODER).canonicalPath, File(source!!.encoder).canonicalPath)
            assertEquals(File(packageRoot, SHERPA_DECODER).canonicalPath, File(source.decoder).canonicalPath)
            assertEquals(File(packageRoot, SHERPA_JOINER).canonicalPath, File(source.joiner).canonicalPath)
            assertEquals(File(packageRoot, SHERPA_TOKENS).canonicalPath, File(source.tokens).canonicalPath)
            assertTrue(listOf(source.encoder, source.decoder, source.joiner, source.tokens).all(File::isFile))
        } finally {
            downloadedRoot.deleteRecursively()
        }
    }

    @Test
    fun downloadedSelectionFailsClosedWhenPackageFilesAreMissing() {
        val downloadedRoot = Files.createTempDirectory("openime-missing-models").toFile()
        try {
            File(downloadedRoot, "downloaded-model").mkdirs()
            val selection = VoiceModelSelection(
                manifest = manifest("downloaded-model"),
                source = VoiceModelSelection.Source.DOWNLOADED,
            )
            assertNull(resolveSherpaRuntimeModelFiles(selection, downloadedRoot))
        } finally {
            downloadedRoot.deleteRecursively()
        }
    }

    @Test
    fun sourceChangeHasDifferentRuntimeIdentityEvenForSameManifest() {
        val manifest = manifest("same-model")
        val builtIn = VoiceModelSelection(manifest, VoiceModelSelection.Source.BUILT_IN)
        val downloaded = VoiceModelSelection(manifest, VoiceModelSelection.Source.DOWNLOADED)

        assertNotEquals(builtIn.runtimeIdentity(), downloaded.runtimeIdentity())
        assertEquals(builtIn.runtimeIdentity(), builtIn.copy().runtimeIdentity())
    }
}
