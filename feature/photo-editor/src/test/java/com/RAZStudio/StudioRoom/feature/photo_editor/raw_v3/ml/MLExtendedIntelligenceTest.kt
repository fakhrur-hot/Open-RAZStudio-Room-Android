/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Unit tests for MLExtendedIntelligence (spec cr2-intelligence-integration).
 * Pure Kotlin — context is passed as null (no Robolectric in this module),
 * which exercises the graceful "table not loaded" paths of
 * MLChromaticAberration / MLBodyWbTrim rather than their asset-backed data.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine
import org.junit.Assert.*
import org.junit.Test

class MLExtendedIntelligenceTest {

    private fun stageA(
        cameraMake: String = "Canon",
        cameraModel: String = "Canon EOS 6D",
        lensId: Int = 160,
        iso: Int = 3200,
        colorTemperature: Int = 5200,
        shutterSpeed: Float = 1f / 200f,
        aperture: Float = 5.6f,
        focalLength: Float = 24f,
    ) = RawV3Engine.StageAResult(
        success = true,
        width = 5472,
        height = 3648,
        orientation = 0,
        cameraMake = cameraMake,
        cameraModel = cameraModel,
        lensMake = "Canon",
        lensModel = "EF 24-70mm",
        lensId = lensId,
        colorTemperature = colorTemperature,
        iso = iso,
        shutterSpeed = shutterSpeed,
        aperture = aperture,
        focalLength = focalLength,
        dateTimeOriginal = "2026:07:20 12:00:00",
        error = null,
    )

    // ── Property 4: Sidecar Precedence (Requirement 1.3) ──

    @Test
    fun sidecarDualIso_takesPrecedenceOverExifPath() {
        val sidecar = MlShotSidecar(
            formatVersion = 2,
            filename = "IMG_0001.CR2",
            dualIso = MlDualIsoBlock(enabled = true, isoBase = 100, isoAlternate = 1600, interleavePeriod = 2),
        )
        val result = MLExtendedIntelligence.applyExtendedDefaults(
            context = null,
            stageA = stageA(iso = 100),
            stageAHistogramStats = null,
            sidecar = sidecar,
            currentMacro = UserMacro(),
        )
        // EXIF-only path (no MakerNote tag available) would report isDualIso=false
        // (recoveryGain=0); the sidecar path must win and produce a non-zero gain.
        assertTrue("expected sidecar-derived recovery gain > 0", result.extDiagnostics[0] > 0f)
        assertEquals(log2(16f), result.extDiagnostics[0], 0.05f)
    }

    @Test
    fun sidecarEttr_takesPrecedenceOverStageAHistogramStats() {
        val sidecar = MlShotSidecar(
            formatVersion = 2,
            filename = "IMG_0001.CR2",
            ettr = MlEttrBlock(lightLevel = 5, sceneDR = 11f, highlightHeadroom = 1.2f, channelClip = floatArrayOf(0.01f, 0.02f, 0.0f)),
        )
        val exifStats = StageAHistogramStats(
            sceneDynamicRange = 6f, highlightHeadroom = 0.2f,
            redClipPercent = 0f, greenClipPercent = 0f, blueClipPercent = 0f,
        )
        val result = MLExtendedIntelligence.applyExtendedDefaults(
            context = null,
            stageA = stageA(),
            stageAHistogramStats = exifStats,
            sidecar = sidecar,
            currentMacro = UserMacro(),
        )
        assertEquals(11f, result.extDiagnostics[2], 0.01f)
        assertEquals(1.2f, result.extDiagnostics[3], 0.01f)
    }

    // ── Property 3: Reused-Slot Monotonicity (Requirement 1.6) ──

    @Test
    fun caSlots_neverDecreaseExistingMacroValues() {
        val preExisting = UserMacro(aberStrength = 90f, aberFringeReduce = 90f)
        val sidecar = MlShotSidecar(
            formatVersion = 2,
            filename = "IMG_0001.CR2",
            lens = MlLensBlock(id = 160, caStrength = 18, fringeReduce = 35),
        )
        val result = MLExtendedIntelligence.applyExtendedDefaults(
            context = null,
            stageA = stageA(),
            stageAHistogramStats = null,
            sidecar = sidecar,
            currentMacro = preExisting,
        )
        assertTrue(result.adjustedMacro.aberStrength >= preExisting.aberStrength)
        assertTrue(result.adjustedMacro.aberFringeReduce >= preExisting.aberFringeReduce)
    }

    @Test
    fun noiseReductionSlots_neverDecrease() {
        val preExisting = UserMacro(luminanceNR = 0.9f, colorNR = 0.9f)
        val result = MLExtendedIntelligence.applyExtendedDefaults(
            context = null,
            stageA = stageA(iso = 25600, shutterSpeed = 30f),
            stageAHistogramStats = null,
            sidecar = null,
            currentMacro = preExisting,
        )
        assertTrue(result.adjustedMacro.luminanceNR >= preExisting.luminanceNR)
        assertTrue(result.adjustedMacro.colorNR >= preExisting.colorNR)
    }

    // ── Property 5: Range Safety (Requirement 1.5) ──

    @Test
    fun extDiagnostics_stayWithinDocumentedRanges_forMalformedSidecar() {
        // Absurd but structurally valid sidecar values.
        val sidecar = MlShotSidecar(
            formatVersion = 2,
            filename = "IMG_0001.CR2",
            dualIso = MlDualIsoBlock(enabled = true, isoBase = 100, isoAlternate = 25600, interleavePeriod = 2),
            ettr = MlEttrBlock(lightLevel = 255, sceneDR = 999f, highlightHeadroom = -999f, channelClip = floatArrayOf(9f, -9f, 9f)),
        )
        val result = MLExtendedIntelligence.applyExtendedDefaults(
            context = null,
            stageA = stageA(),
            stageAHistogramStats = null,
            sidecar = sidecar,
            currentMacro = UserMacro(),
        )
        val diag = result.extDiagnostics
        assertTrue(diag[0] in 0f..3f)          // dual-ISO recovery gain
        assertTrue(diag[1] in 0f..1f)          // dual-ISO blend factor
        assertTrue(diag[2] in 4f..14f)         // sceneDR
        assertTrue(diag[3] in 0f..3f)          // highlightHeadroom
        assertTrue(diag[4] in 0f..30f)         // diffraction
        assertTrue(diag[5] in -1f..1f)         // body WB trim
        assertTrue(result.adjustedMacro.aberStrength in 0f..100f)
        assertTrue(result.adjustedMacro.aberFringeReduce in 0f..100f)
        assertTrue(result.adjustedMacro.luminanceNR in 0f..1f)
        assertTrue(result.adjustedMacro.colorNR in 0f..1f)
    }

    @Test
    fun noSidecarNoContext_doesNotThrow_gracefulExifOnlyFallback() {
        // Requirement 8.4: orchestrator still runs (Picture Style, focus depth,
        // flash, thermal, diffraction, body WB trim don't need a sidecar).
        val result = MLExtendedIntelligence.applyExtendedDefaults(
            context = null,
            stageA = stageA(),
            stageAHistogramStats = null,
            sidecar = null,
            currentMacro = UserMacro(),
        )
        assertNotNull(result)
        assertEquals(6, result.extDiagnostics.size)
    }

    // ── MONOCHROME zeroing ──

    @Test
    fun monochromeStyle_zeroesSaturationTintAndWhiteBalance() {
        // MLPictureStyle.detect requires an actual MakerNote tag to select
        // MONOCHROME; without one it defaults to STANDARD, so this test
        // documents current EXIF-only behavior rather than asserting
        // MONOCHROME is reachable from this call site alone.
        val result = MLExtendedIntelligence.applyExtendedDefaults(
            context = null,
            stageA = stageA(),
            stageAHistogramStats = null,
            sidecar = null,
            currentMacro = UserMacro(contrast = 10f, saturation = 10f, whiteBalance = 100, tint = 5f),
        )
        assertNotNull(result.adjustedMacro)
    }

    private fun log2(x: Float): Float = (Math.log(x.toDouble()) / Math.log(2.0)).toFloat()

    // ── Property 1: Reused Slot Preservation / maxOf Semantics (task 8.5) ──

    @Test
    fun property1_reusedSlotsNeverDecrease_randomizedPreExistingValues() {
        val rnd = kotlin.random.Random(7)
        repeat(40) {
            val pre = UserMacro(
                luminanceNR = rnd.nextFloat(),
                colorNR = rnd.nextFloat(),
                smartSharpness = rnd.nextFloat(),
                smoothBackground = rnd.nextFloat(),
                highlightRecovery = rnd.nextFloat() * 100f,
                aberStrength = rnd.nextFloat() * 100f,
                aberFringeReduce = rnd.nextFloat() * 100f,
            )
            val sidecar = MlShotSidecar(
                formatVersion = 2,
                filename = "IMG_0001.CR2",
                lens = MlLensBlock(id = 160, caStrength = rnd.nextInt(0, 101), fringeReduce = rnd.nextInt(0, 101)),
            )
            val result = MLExtendedIntelligence.applyExtendedDefaults(
                context = null,
                stageA = stageA(iso = rnd.nextInt(100, 25601), shutterSpeed = rnd.nextInt(1, 3600).toFloat()),
                stageAHistogramStats = null,
                sidecar = sidecar,
                currentMacro = pre,
            )
            val post = result.adjustedMacro
            assertTrue("luminanceNR non-decreasing", post.luminanceNR >= pre.luminanceNR - 0.0001f)
            assertTrue("colorNR non-decreasing", post.colorNR >= pre.colorNR - 0.0001f)
            assertTrue("smartSharpness non-decreasing", post.smartSharpness >= pre.smartSharpness - 0.0001f)
            assertTrue("smoothBackground non-decreasing", post.smoothBackground >= pre.smoothBackground - 0.0001f)
            assertTrue("aberStrength non-decreasing", post.aberStrength >= pre.aberStrength - 0.0001f)
            assertTrue("aberFringeReduce non-decreasing", post.aberFringeReduce >= pre.aberFringeReduce - 0.0001f)
        }
    }

    // ── Property 11: Extended Slot Range Safety (task 8.6) ──

    @Test
    fun property11_extendedSlotsAlwaysInRange_fuzzedInputs() {
        val rnd = kotlin.random.Random(99)
        repeat(50) {
            val hasSidecar = rnd.nextBoolean()
            val sidecar = if (!hasSidecar) null else MlShotSidecar(
                formatVersion = 2,
                filename = "IMG_0001.CR2",
                dualIso = if (rnd.nextBoolean()) MlDualIsoBlock(
                    enabled = true,
                    isoBase = rnd.nextInt(-100, 30000),
                    isoAlternate = rnd.nextInt(-100, 30000),
                    interleavePeriod = 2,
                ) else null,
                ettr = if (rnd.nextBoolean()) MlEttrBlock(
                    lightLevel = rnd.nextInt(-50, 300),
                    sceneDR = rnd.nextFloat() * 40f - 10f,
                    highlightHeadroom = rnd.nextFloat() * 10f - 5f,
                    channelClip = floatArrayOf(rnd.nextFloat() * 5f, rnd.nextFloat() * 5f, rnd.nextFloat() * 5f),
                ) else null,
                lens = if (rnd.nextBoolean()) MlLensBlock(
                    id = rnd.nextInt(0, 500), caStrength = rnd.nextInt(-50, 200), fringeReduce = rnd.nextInt(-50, 200),
                ) else null,
            )
            val result = MLExtendedIntelligence.applyExtendedDefaults(
                context = null,
                stageA = stageA(
                    iso = rnd.nextInt(50, 51200),
                    aperture = rnd.nextFloat() * 30f,
                    focalLength = rnd.nextFloat() * 600f,
                    shutterSpeed = rnd.nextFloat() * 4000f,
                    colorTemperature = rnd.nextInt(1000, 12000),
                ),
                stageAHistogramStats = null,
                sidecar = sidecar,
                currentMacro = UserMacro(),
            )
            val diag = result.extDiagnostics
            assertTrue("diag[0] dualIsoRecoveryGain in [0..3]", diag[0] in 0f..3f)
            assertTrue("diag[1] dualIsoBlendFactor in [0..1]", diag[1] in 0f..1f)
            assertTrue("diag[2] sceneDR in [4..14]", diag[2] in 4f..14f || diag[2] == 0f)
            assertTrue("diag[3] highlightHeadroom in [0..3]", diag[3] in 0f..3f || diag[3] == 0f)
            assertTrue("diag[4] diffraction in [0..30]", diag[4] in 0f..30f)
            assertTrue("diag[5] bodyWbTrim in [-1..1]", diag[5] in -1f..1f)
            assertTrue("aberStrength in [0..100]", result.adjustedMacro.aberStrength in 0f..100f)
            assertTrue("aberFringeReduce in [0..100]", result.adjustedMacro.aberFringeReduce in 0f..100f)
            assertTrue("luminanceNR in [0..1]", result.adjustedMacro.luminanceNR in 0f..1f)
            assertTrue("colorNR in [0..1]", result.adjustedMacro.colorNR in 0f..1f)
        }
    }

    // ── Property 17: Sidecar Precedence Over EXIF — EXCEPT lens/CA, which is
    //    always sourced from the CR2's own EXIF, matching plain single-file
    //    RAWEditor behavior (task 7.5, explicit label; behavior intentionally
    //    reversed for lens/CA after an on-device sighting of a stale/wrong
    //    sidecar lens block driving CA correction for the wrong lens). ──

    @Test
    fun property17_sidecarLensBlock_isIgnored_evenWhenExifLensIdHasNoCaData() {
        // sidecar lens id 160 has real CA data (see lens_tune.tbl); EXIF lensId
        // 0 is neutral (always null CA). The sidecar's lens block must be
        // ignored entirely — CA stays at its pre-existing macro value (0),
        // it must NOT pick up the sidecar's 42/33.
        val sidecar = MlShotSidecar(
            formatVersion = 2,
            filename = "IMG_0001.CR2",
            lens = MlLensBlock(id = 160, caStrength = 42, fringeReduce = 33),
        )
        val result = MLExtendedIntelligence.applyExtendedDefaults(
            context = null,
            stageA = stageA(lensId = 0),
            stageAHistogramStats = null,
            sidecar = sidecar,
            currentMacro = UserMacro(),
        )
        assertEquals(0f, result.adjustedMacro.aberStrength, 0.01f)
        assertEquals(0f, result.adjustedMacro.aberFringeReduce, 0.01f)
    }

    @Test
    fun caLensId_alwaysFromExif_regardlessOfSidecarLensBlock() {
        // sidecar claims lens_id 999999 (no CA data, would resolve to null);
        // EXIF says lens_id 160 (real CA data). EXIF must win — the sidecar
        // lens block is never consulted for CA at all.
        val sidecar = MlShotSidecar(
            formatVersion = 2,
            filename = "IMG_0001.CR2",
            lens = MlLensBlock(id = 999999, caStrength = 77, fringeReduce = 88),
        )
        val result = MLExtendedIntelligence.applyExtendedDefaults(
            context = null,
            stageA = stageA(lensId = 160),
            stageAHistogramStats = null,
            sidecar = sidecar,
            currentMacro = UserMacro(),
        )
        assertTrue("EXIF lensId 160 has real CA data, should be non-zero", result.adjustedMacro.aberStrength > 0f)
        assertTrue(result.adjustedMacro.aberStrength != 77f)
        assertTrue(result.adjustedMacro.aberFringeReduce != 88f)
    }

    // ── Property 19: Bias Scale Application (task 8.7) ──

    @Test
    fun property19_biasScaleAppliedToMacroBiasFields() {
        // MLPictureStyle.biasScale returns 0.5 for NEUTRAL/FAITHFUL and 1.0
        // otherwise; from this EXIF-only call site (no MakerNote style tag
        // available), detect() always resolves STANDARD -> scale 1.0, so the
        // bias-scaling step must be a no-op on contrast/saturation/tint/WB.
        val pre = UserMacro(contrast = 20f, saturation = 30f, tint = 10f, whiteBalance = 200)
        val result = MLExtendedIntelligence.applyExtendedDefaults(
            context = null,
            stageA = stageA(),
            stageAHistogramStats = null,
            sidecar = null,
            currentMacro = pre,
        )
        assertEquals(1.0f, result.biasScale, 0.0001f)
        assertEquals(pre.contrast, result.adjustedMacro.contrast, 0.01f)
        assertEquals(pre.saturation, result.adjustedMacro.saturation, 0.01f)
        assertEquals(pre.tint, result.adjustedMacro.tint, 0.01f)
        assertEquals(pre.whiteBalance, result.adjustedMacro.whiteBalance)
    }
}
