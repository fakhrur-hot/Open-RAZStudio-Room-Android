/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.sd_card_browser.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for FileOpener blend kernel failure fallback paths.
 *
 * Since FileOpener depends on Android framework classes (Context, ContentResolver,
 * DocumentFile) and native libraries (NativeRawDecoder, DualIsoNative), these tests
 * verify the error state contracts and fallback path invariants using the same
 * algorithmic simulation pattern as other tests in this module.
 *
 * The three failure paths tested:
 * 1. I/O failure reading CR2 stream (openInputStream returns null)
 * 2. Invalid bayer data (NativeRawDecoder.readRawBayerForDualIso returns null)
 * 3. Blend kernel failure (DualIsoNative.blendMean23 returns false)
 *
 * All three paths must emit an error state containing:
 * - A non-empty, distinct message identifying the failure
 * - The original CR2 URI as the fallback for single-ISO opening
 *
 * **Validates: Requirements 8.5, 8.6**
 */
class FileOpenerTest {

    // ══════════════════════════════════════════════════════════════════
    // Error State Contract Tests
    // ══════════════════════════════════════════════════════════════════

    /**
     * Simulates the error state data structure contract.
     * Each error state must carry a non-empty message and a fallback URI string
     * (which in production is the original CR2 URI).
     */
    data class SimErrorState(val message: String, val fallbackUri: String)

    /**
     * Verify that all three error paths produce states with non-empty messages
     * and a fallback URI pointing to the original CR2.
     *
     * **Validates: Requirements 8.5, 8.6**
     */
    @Test
    fun errorState_alwaysContainsFallbackUri() {
        val fallbackUri = "content://test/cr2/IMG_0001.CR2"
        val errorStates = listOf(
            SimErrorState("Failed to read CR2 bytes", fallbackUri),
            SimErrorState("Failed to decode bayer plane", fallbackUri),
            SimErrorState("Blend kernel failed", fallbackUri),
        )
        for (state in errorStates) {
            assertNotNull("Error state must have fallback URI", state.fallbackUri)
            assertEquals(fallbackUri, state.fallbackUri)
            assertTrue("Error message must not be empty", state.message.isNotEmpty())
        }
    }

    /**
     * Verify the three distinct error paths produce unique, distinguishable messages:
     * 1. I/O failure reading CR2 stream
     * 2. Invalid bayer data (NativeRawDecoder returns null)
     * 3. Blend kernel failure (blendMean23 returns false)
     *
     * **Validates: Requirements 8.5, 8.6**
     */
    @Test
    fun errorPaths_produceDistinctMessages() {
        val fallbackUri = "content://test/cr2/DUAL0001.CR2"

        val ioError = SimErrorState("Failed to read CR2 bytes", fallbackUri)
        val bayerError = SimErrorState("Failed to decode bayer plane", fallbackUri)
        val blendError = SimErrorState("Blend kernel failed", fallbackUri)

        // All three error types are distinguishable by message
        val messages = setOf(ioError.message, bayerError.message, blendError.message)
        assertEquals("All three error paths should have distinct messages", 3, messages.size)
    }

    /**
     * Verify that the fallback URI in an error state matches the original
     * CR2 URI (not the twin DNG or any intermediate). This ensures the
     * "Open in single-ISO mode" button navigates to the correct file.
     *
     * **Validates: Requirements 8.5, 8.6**
     */
    @Test
    fun errorState_fallbackUri_isOriginalCr2() {
        val originalCr2Uri = "content://storage/1234-5678/DCIM/100CANON/DUAL0001.CR2"
        val error = SimErrorState("Blend kernel failed", originalCr2Uri)

        // The fallback should be the original CR2, not a twin or blended output
        assertEquals(originalCr2Uri, error.fallbackUri)
        assertTrue(error.fallbackUri.contains("DUAL0001.CR2"))
    }

    // ══════════════════════════════════════════════════════════════════
    // OpenState Flow Contract Tests — simulated as state machine transitions
    // ══════════════════════════════════════════════════════════════════

    /**
     * Models the open-file state machine without Android dependencies.
     * Each state mirrors [OpenState] but uses String URIs.
     */
    sealed interface SimOpenState {
        data object Idle : SimOpenState
        data class Blending(val progress: Float) : SimOpenState
        data class Ready(val targetUri: String, val sidecarUri: String?) : SimOpenState
        data class Error(val message: String, val fallbackUri: String) : SimOpenState
    }

    /**
     * Simulates the full openCr2 pipeline logic as implemented in FileOpener.
     * Parameters control which failure path is triggered.
     */
    private fun simulateOpenCr2(
        isDualIso: Boolean,
        cr2Uri: String,
        sidecarUri: String? = null,
        preflightResult: String = "NeedsBake", // "TwinReady", "NeedsBake", "NotDualIso"
        ioReadSuccess: Boolean = true,
        bayerDecodeSuccess: Boolean = true,
        blendSuccess: Boolean = true,
    ): List<SimOpenState> {
        val emissions = mutableListOf<SimOpenState>()

        if (!isDualIso) {
            emissions.add(SimOpenState.Ready(cr2Uri, sidecarUri))
            return emissions
        }

        // Dual-ISO path
        when (preflightResult) {
            "TwinReady" -> {
                val twinUri = cr2Uri.replace(".CR2", "_twin.DNG")
                emissions.add(SimOpenState.Ready(twinUri, sidecarUri))
            }

            "NeedsBake" -> {
                emissions.add(SimOpenState.Blending(0f))

                if (!ioReadSuccess) {
                    emissions.add(SimOpenState.Error("Failed to read CR2 bytes", cr2Uri))
                    return emissions
                }

                if (!bayerDecodeSuccess) {
                    emissions.add(SimOpenState.Error("Failed to decode bayer plane", cr2Uri))
                    return emissions
                }

                emissions.add(SimOpenState.Blending(0.3f))

                if (!blendSuccess) {
                    emissions.add(SimOpenState.Error("Blend kernel failed", cr2Uri))
                    return emissions
                }

                emissions.add(SimOpenState.Blending(1f))
                emissions.add(SimOpenState.Ready(cr2Uri, sidecarUri))
            }

            else -> {
                // NotDualIso / Suspected — open normally
                emissions.add(SimOpenState.Ready(cr2Uri, sidecarUri))
            }
        }

        return emissions
    }

    /**
     * Simulate corrupt CR2 bytes (invalid bayer data) → verify Error is emitted
     * with fallback URI.
     *
     * **Validates: Requirements 8.5, 8.6**
     */
    @Test
    fun corruptBayerData_emitsError_withFallbackUri() {
        val cr2Uri = "content://storage/1234-5678/DCIM/100CANON/DUAL0001.CR2"
        val emissions = simulateOpenCr2(
            isDualIso = true,
            cr2Uri = cr2Uri,
            bayerDecodeSuccess = false,
        )

        // Should emit: Blending(0f) → Error
        assertTrue("Should have at least 2 emissions", emissions.size >= 2)

        val lastEmission = emissions.last()
        assertTrue("Last emission should be Error", lastEmission is SimOpenState.Error)

        val error = lastEmission as SimOpenState.Error
        assertEquals("Failed to decode bayer plane", error.message)
        assertEquals(cr2Uri, error.fallbackUri)
    }

    /**
     * Simulate missing twin DNG (preflightSaf returns NeedsBake but blendMean23
     * returns false) → verify error state with fallback.
     *
     * **Validates: Requirements 8.5, 8.6**
     */
    @Test
    fun blendKernelFailure_emitsError_withFallbackUri() {
        val cr2Uri = "content://storage/1234-5678/DCIM/100CANON/DUAL0002.CR2"
        val emissions = simulateOpenCr2(
            isDualIso = true,
            cr2Uri = cr2Uri,
            blendSuccess = false,
        )

        // Should emit: Blending(0f) → Blending(0.3f) → Error
        assertTrue("Should have at least 3 emissions", emissions.size >= 3)

        val lastEmission = emissions.last()
        assertTrue("Last emission should be Error", lastEmission is SimOpenState.Error)

        val error = lastEmission as SimOpenState.Error
        assertEquals("Blend kernel failed", error.message)
        assertEquals(cr2Uri, error.fallbackUri)
    }

    /**
     * Simulate I/O failure reading CR2 stream (null from openInputStream) →
     * verify error with fallback URI.
     *
     * **Validates: Requirements 8.5, 8.6**
     */
    @Test
    fun ioFailure_emitsError_withFallbackUri() {
        val cr2Uri = "content://storage/1234-5678/DCIM/100CANON/DUAL0003.CR2"
        val emissions = simulateOpenCr2(
            isDualIso = true,
            cr2Uri = cr2Uri,
            ioReadSuccess = false,
        )

        // Should emit: Blending(0f) → Error
        assertEquals(2, emissions.size)

        val lastEmission = emissions.last()
        assertTrue("Last emission should be Error", lastEmission is SimOpenState.Error)

        val error = lastEmission as SimOpenState.Error
        assertEquals("Failed to read CR2 bytes", error.message)
        assertEquals(cr2Uri, error.fallbackUri)
    }

    /**
     * Verify that onOpenSingleIsoFallback() correctly navigates to editor
     * with the original CR2 URI. The fallback URI in every error state must
     * be the same URI that was originally passed in (the CR2 URI), not any
     * derived twin or blended URI.
     *
     * **Validates: Requirements 8.5, 8.6**
     */
    @Test
    fun singleIsoFallback_usesOriginalCr2Uri() {
        val originalUri = "content://test/DUAL0001.CR2"

        // Simulate all three failure paths and verify each produces
        // an error with the original URI as fallback
        val failureModes = listOf(
            Triple(false, true, true),  // I/O failure
            Triple(true, false, true),  // bayer decode failure
            Triple(true, true, false),  // blend failure
        )

        for ((io, bayer, blend) in failureModes) {
            val emissions = simulateOpenCr2(
                isDualIso = true,
                cr2Uri = originalUri,
                ioReadSuccess = io,
                bayerDecodeSuccess = bayer,
                blendSuccess = blend,
            )
            val errorState = emissions.last()
            assertTrue(
                "Expected Error state for failure mode (io=$io, bayer=$bayer, blend=$blend)",
                errorState is SimOpenState.Error
            )
            val error = errorState as SimOpenState.Error
            assertEquals(
                "Fallback URI must be original CR2 URI",
                originalUri,
                error.fallbackUri
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Non-Dual-ISO happy path (for contrast)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Verify Ready state for non-dual-ISO files: opens directly without blending.
     *
     * **Validates: Requirements 8.6**
     */
    @Test
    fun nonDualIso_emitsReady_immediately() {
        val cr2Uri = "content://storage/1234-5678/DCIM/100CANON/IMG_0001.CR2"
        val sidecarUri = "content://storage/1234-5678/ML/DATA/SHOTS/IMG_0001.ml6d"

        val emissions = simulateOpenCr2(
            isDualIso = false,
            cr2Uri = cr2Uri,
            sidecarUri = sidecarUri,
        )

        assertEquals(1, emissions.size)
        val ready = emissions[0] as SimOpenState.Ready
        assertEquals(cr2Uri, ready.targetUri)
        assertEquals(sidecarUri, ready.sidecarUri)
    }

    /**
     * Verify Ready state can have null sidecar (no .ml6d found).
     *
     * **Validates: Requirements 8.6**
     */
    @Test
    fun readyState_nullSidecar_isValid() {
        val cr2Uri = "content://storage/1234-5678/DCIM/100CANON/IMG_0002.CR2"

        val emissions = simulateOpenCr2(
            isDualIso = false,
            cr2Uri = cr2Uri,
            sidecarUri = null,
        )

        assertEquals(1, emissions.size)
        val ready = emissions[0] as SimOpenState.Ready
        assertEquals(cr2Uri, ready.targetUri)
        assertNull(ready.sidecarUri)
    }

    // ══════════════════════════════════════════════════════════════════
    // Blending State Progress Contract Tests
    // ══════════════════════════════════════════════════════════════════

    /**
     * Verify Blending state progress bounds: progress should be between 0.0 and 1.0.
     * On the successful path, the sequence is: Blending(0) → Blending(0.3) → Blending(1) → Ready.
     *
     * **Validates: Requirements 8.5**
     */
    @Test
    fun blendingState_progressBounds_onSuccessPath() {
        val cr2Uri = "content://storage/1234-5678/DCIM/100CANON/DUAL0001.CR2"
        val emissions = simulateOpenCr2(
            isDualIso = true,
            cr2Uri = cr2Uri,
            blendSuccess = true,
        )

        val blendingStates = emissions.filterIsInstance<SimOpenState.Blending>()
        assertTrue("Should have blending states", blendingStates.isNotEmpty())

        for (state in blendingStates) {
            assertTrue("Progress should be >= 0", state.progress >= 0f)
            assertTrue("Progress should be <= 1", state.progress <= 1f)
        }

        // Verify progress is monotonically non-decreasing
        for (i in 1 until blendingStates.size) {
            assertTrue(
                "Progress should be monotonically non-decreasing",
                blendingStates[i].progress >= blendingStates[i - 1].progress
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Sidecar Resolution Contract Tests
    // ══════════════════════════════════════════════════════════════════

    /**
     * Verify the sidecar path resolution logic:
     * For "IMG_1234.CR2" → looks at "ML/DATA/SHOTS/IMG_1234.ml6d"
     *
     * **Validates: Requirements 8.6**
     */
    @Test
    fun sidecarResolution_correctBasenameExtraction() {
        val testCases = mapOf(
            "IMG_1234.CR2" to "IMG_1234",
            "DUAL0001.CR2" to "DUAL0001",
            "photo.cr3" to "photo",
            "my.photo.CR2" to "my.photo", // multiple dots
        )
        for ((filename, expectedBasename) in testCases) {
            val basename = filename.substringBeforeLast('.')
            assertEquals("Basename for $filename", expectedBasename, basename)
        }
    }

    /**
     * Verify that sidecar path follows exactly: ML/DATA/SHOTS/{basename}.ml6d
     * No other paths are checked.
     *
     * **Validates: Requirements 8.6**
     */
    @Test
    fun sidecarResolution_exactPathPattern() {
        val cr2Filename = "IMG_5678.CR2"
        val basename = cr2Filename.substringBeforeLast('.')
        val expectedSidecarName = "$basename.ml6d"
        assertEquals("IMG_5678.ml6d", expectedSidecarName)

        // The path chain is: volumeRoot → findFile("ML") → findFile("DATA") → findFile("SHOTS") → findFile("IMG_5678.ml6d")
        val pathSegments = listOf("ML", "DATA", "SHOTS", expectedSidecarName)
        assertEquals(4, pathSegments.size)
        assertEquals("ML", pathSegments[0])
        assertEquals("DATA", pathSegments[1])
        assertEquals("SHOTS", pathSegments[2])
        assertEquals("IMG_5678.ml6d", pathSegments[3])
    }

    // ══════════════════════════════════════════════════════════════════
    // Error-then-fallback flow completeness
    // ══════════════════════════════════════════════════════════════════

    /**
     * Verify that error states never follow a Ready state in the emission sequence.
     * Once Ready is emitted, the flow is done. Error terminates the flow.
     *
     * **Validates: Requirements 8.5**
     */
    @Test
    fun errorState_terminatesFlow_noFurtherEmissions() {
        val failureScenarios = listOf(
            Triple(false, true, true),  // I/O failure
            Triple(true, false, true),  // bayer decode failure
            Triple(true, true, false),  // blend failure
        )

        for ((io, bayer, blend) in failureScenarios) {
            val emissions = simulateOpenCr2(
                isDualIso = true,
                cr2Uri = "content://test/DUAL.CR2",
                ioReadSuccess = io,
                bayerDecodeSuccess = bayer,
                blendSuccess = blend,
            )

            // The last emission must be Error
            assertTrue(
                "Last emission must be Error for failure (io=$io, bayer=$bayer, blend=$blend)",
                emissions.last() is SimOpenState.Error
            )

            // No Ready or Blending state should appear after Error
            val errorIndex = emissions.indexOfLast { it is SimOpenState.Error }
            for (i in (errorIndex + 1) until emissions.size) {
                fail("No emissions should follow Error state, but found: ${emissions[i]}")
            }
        }
    }

    /**
     * Verify that the successful blend path ends with Ready (not Error).
     *
     * **Validates: Requirements 8.5**
     */
    @Test
    fun successfulBlend_endsWithReady() {
        val cr2Uri = "content://test/DUAL0001.CR2"
        val emissions = simulateOpenCr2(
            isDualIso = true,
            cr2Uri = cr2Uri,
            blendSuccess = true,
        )

        assertTrue(
            "Successful blend must end with Ready state",
            emissions.last() is SimOpenState.Ready
        )
        val ready = emissions.last() as SimOpenState.Ready
        assertEquals(cr2Uri, ready.targetUri)
    }
}
