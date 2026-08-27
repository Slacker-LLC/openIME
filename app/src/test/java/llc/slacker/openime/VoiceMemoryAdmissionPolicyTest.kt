package llc.slacker.openime

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMemoryAdmissionPolicyTest {
    private val requiredMemory = 420_000_000L

    @Test
    fun lowRamDeviceSkipsAutomaticPreload() {
        val decision = VoiceMemoryAdmissionPolicy.evaluate(
            requiredMemory = requiredMemory,
            snapshot = healthySnapshot(lowRamDevice = true),
            automaticPreload = true,
        )

        assertFalse(decision.allowed)
    }

    @Test
    fun lowRamDeviceMayStillTryOnExplicitUserRequestWhenHeadroomIsHealthy() {
        val decision = VoiceMemoryAdmissionPolicy.evaluate(
            requiredMemory = requiredMemory,
            snapshot = healthySnapshot(lowRamDevice = true),
            automaticPreload = false,
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun insufficientLiveHeadroomRejectsRuntimeCreation() {
        val decision = VoiceMemoryAdmissionPolicy.evaluate(
            requiredMemory = requiredMemory,
            snapshot = healthySnapshot(
                availableBytes = 520_000_000L,
                lowMemoryThresholdBytes = 80_000_000L,
            ),
            automaticPreload = false,
        )

        assertFalse(decision.allowed)
    }

    @Test
    fun verySmallMemoryClassRejectsLargeRuntime() {
        val decision = VoiceMemoryAdmissionPolicy.evaluate(
            requiredMemory = requiredMemory,
            snapshot = healthySnapshot(memoryClassBytes = 192L * 1024L * 1024L),
            automaticPreload = false,
        )

        assertFalse(decision.allowed)
    }

    @Test
    fun ordinaryDeviceWithHeadroomAllowsRuntimeCreation() {
        val decision = VoiceMemoryAdmissionPolicy.evaluate(
            requiredMemory = requiredMemory,
            snapshot = healthySnapshot(),
            automaticPreload = true,
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun passwordAndPrivateEditorsDoNotAutoPreload() {
        assertFalse(
            VoiceAutoPreloadPolicy.shouldPreload(
                EditorInfoAdapter.EditorKind.PASSWORD,
                imeOptions = 0,
            ),
        )
        assertFalse(
            VoiceAutoPreloadPolicy.shouldPreload(
                EditorInfoAdapter.EditorKind.TEXT,
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ),
        )
        assertTrue(
            VoiceAutoPreloadPolicy.shouldPreload(
                EditorInfoAdapter.EditorKind.TEXT,
                imeOptions = 0,
            ),
        )
    }

    private fun healthySnapshot(
        lowRamDevice: Boolean = false,
        memoryClassBytes: Long = 256L * 1024L * 1024L,
        availableBytes: Long = 1_500_000_000L,
        lowMemoryThresholdBytes: Long = 128_000_000L,
    ): VoiceMemorySnapshot = VoiceMemorySnapshot(
        lowRamDevice = lowRamDevice,
        memoryClassBytes = memoryClassBytes,
        availableBytes = availableBytes,
        lowMemoryThresholdBytes = lowMemoryThresholdBytes,
    )
}
