package llc.slacker.openime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelPathPolicyTest {
    @Test
    fun modelIdsCannotBecomeFilesystemPaths() {
        assertTrue(isSafeVoiceModelId("sherpa-onnx_2026.08"))
        assertFalse(isSafeVoiceModelId("../escape"))
        assertFalse(isSafeVoiceModelId("nested/model"))
        assertFalse(isSafeVoiceModelId("nested\\model"))
        assertFalse(isSafeVoiceModelId(".."))
        assertFalse(isSafeVoiceModelId(""))
    }

    @Test
    fun manifestMembersMustBeStrictRelativePaths() {
        assertTrue(isSafeVoiceModelRelativePath("models/encoder.onnx"))
        assertTrue(isSafeVoiceModelRelativePath("tokens.txt"))
        assertFalse(isSafeVoiceModelRelativePath("../outside.onnx"))
        assertFalse(isSafeVoiceModelRelativePath("models/../outside.onnx"))
        assertFalse(isSafeVoiceModelRelativePath("/absolute/model.onnx"))
        assertFalse(isSafeVoiceModelRelativePath("models\\encoder.onnx"))
        assertFalse(isSafeVoiceModelRelativePath("models//encoder.onnx"))
    }

    @Test
    fun containedResolverKeepsNormalMembersInsideRoot() {
        val root = Files.createTempDirectory("openime-model-root").toFile()
        try {
            val nested = root.resolve("models").apply { mkdirs() }
            val model = nested.resolve("encoder.onnx").apply { writeText("model") }

            assertEquals(model.canonicalFile, resolveContainedVoiceModelFile(root, "models/encoder.onnx"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun containedResolverRejectsSymlinkIndirection() {
        val root = Files.createTempDirectory("openime-model-root").toFile()
        val outside = Files.createTempDirectory("openime-model-outside").toFile()
        try {
            val outsideModel = outside.resolve("encoder.onnx").apply { writeText("model") }
            val link = root.toPath().resolve("encoder.onnx")
            val symlinkCreated = runCatching {
                Files.createSymbolicLink(link, outsideModel.toPath())
            }.isSuccess
            if (!symlinkCreated) return

            assertTrue(
                runCatching { resolveContainedVoiceModelFile(root, "encoder.onnx") }.isFailure,
            )
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
