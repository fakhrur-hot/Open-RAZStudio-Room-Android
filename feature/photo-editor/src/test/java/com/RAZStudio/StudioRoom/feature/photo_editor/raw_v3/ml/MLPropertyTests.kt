/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Property tests for ml6d-extended-intelligence (spec tasks 2.4/2.5/2.7/4.2/
 * 4.4/4.6/2.2 — Properties 2, 3, 4, 5, 6, 7, 8, 12, 13). Each test iterates a
 * wide range of inputs rather than a handful of fixed examples, per the
 * design document's correctness-property definitions.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import kotlin.random.Random

@Ignore("ML dual-ISO and MLSidecar were retired; this legacy test no longer applies")
class MLPropertyTests {

    // ── Property 2: Dual-ISO Output Bounds (task 2.4) ──

    @Test
    fun property2_dualIsoOutputBounds() {
        val tags = listOf<Int?>(null, 0) + (0..200).map { Random.nextInt(0, 0x10000) }
        for (tag in tags) {
            val r = MLDualIsoDetect.detect("Canon", "Canon EOS 6D", 100, tag)
            assertTrue("recoveryGain in [0..3] for tag=$tag", r.recoveryGain in 0f..3f)
            assertTrue("blendFactor in [0..1] for tag=$tag", r.blendFactor in 0f..1f)
        }
    }

    // ── Property 3: Non-Canon Camera Passthrough (task 2.5) ──

    @Test
    fun property3_nonCanonCameraPassthrough() {
        val nonCanonMakes = listOf("Nikon", "Sony", "Fujifilm", "Panasonic", "Olympus", "PENTAX", "")
        for (make in nonCanonMakes) {
            val dual = MLDualIsoDetect.detect(make, "Some Model", 400, 0x0102)
            assertFalse("isDualIso false for make=$make", dual.isDualIso)
            assertEquals(0f, dual.recoveryGain, 0.0001f)
            assertEquals(0f, dual.blendFactor, 0.0001f)

            val style = MLPictureStyle.detect(make, 0x86 /* would be MONOCHROME on Canon */)
            assertEquals("STANDARD for non-Canon make=$make", CanonPictureStyle.STANDARD, style)

            assertNull("body WB trim null before ensureLoaded, make=$make", MLBodyWbTrim.forModel(make))
        }
    }

    // ── Property 4: Focus Depth Zero-Data Safety (task 2.7) ──

    @Test
    fun property4_focusDepthZeroDataSafety() {
        val cases = listOf(
            Triple(0f, 5.6f, 50f),       // focusDistanceNear = 0
            Triple(2f, 0f, 50f),         // aperture = 0
            Triple(2f, -1f, 50f),        // negative aperture
            Triple(2f, 5.6f, 0f),        // focalLength = 0
            Triple(2f, 5.6f, -10f),      // negative focalLength
        )
        for ((near, aperture, focal) in cases) {
            val r = MLFocusDepth.compute(near, 10f, aperture, focal)
            assertEquals("smartSharpness=0 for near=$near aperture=$aperture focal=$focal", 0f, r.smartSharpness, 0.0001f)
            assertEquals("smoothBackground=0 for near=$near aperture=$aperture focal=$focal", 0f, r.smoothBackground, 0.0001f)
        }
    }

    // ── Property 5/6/7: Thermal Noise (task 4.4) ──

    @Test
    fun property5_thermalNoiseMonotonicity() {
        // For any two shutter speeds a > b > 1s, boost(a) >= boost(b).
        val shutters = listOf(1.1f, 2f, 4f, 8f, 15f, 30f, 60f, 120f)
        for (i in shutters.indices) {
            for (j in shutters.indices) {
                val a = shutters[i]
                val b = shutters[j]
                if (a > b) {
                    val (lumaA, chromaA) = MLThermalNoise.boost(a, 800, 10f, 5f)
                    val (lumaB, chromaB) = MLThermalNoise.boost(b, 800, 10f, 5f)
                    assertTrue("luma($a) >= luma($b)", lumaA >= lumaB - 0.0001f)
                    assertTrue("chroma($a) >= chroma($b)", chromaA >= chromaB - 0.0001f)
                }
            }
        }
    }

    @Test
    fun property6_shortExposureIdentity() {
        val shutters = listOf(0f, 0.001f, 1f / 200f, 1f / 60f, 0.5f, 1.0f)
        val baseLuma = 12.34f
        val baseChroma = 7.89f
        for (s in shutters) {
            val (luma, chroma) = MLThermalNoise.boost(s, 400, baseLuma, baseChroma)
            assertEquals("luma unchanged for shutter=$s", baseLuma, luma, 0.0001f)
            assertEquals("chroma unchanged for shutter=$s", baseChroma, chroma, 0.0001f)
        }
    }

    @Test
    fun property7_thermalNrBoostCap() {
        val shutters = listOf(1.1f, 5f, 30f, 60f, 300f, 3600f, 100000f)
        for (s in shutters) {
            val (luma, chroma) = MLThermalNoise.boost(s, 3200, 0f, 0f)
            assertTrue("luma <= 100 for shutter=$s", luma <= 100f)
            assertTrue("chroma <= 100 for shutter=$s", chroma <= 100f)
            // Boost contribution alone must respect the documented per-slot caps
            // (luma boost <= 20, chroma boost <= 15) when starting from a zero base.
            assertTrue("luma boost <= 20 for shutter=$s", luma <= 20.0001f)
            assertTrue("chroma boost <= 15 for shutter=$s", chroma <= 15.0001f)
        }
    }

    // ── Property 8: Monochrome Skips All Color Adjustments (task 4.6) ──

    @Test
    fun property8_monochromeSkipsColorAdjustments() {
        assertTrue(MLPictureStyle.skipColorTrims(CanonPictureStyle.MONOCHROME))
        for (style in CanonPictureStyle.values()) {
            if (style != CanonPictureStyle.MONOCHROME) {
                assertFalse("skipColorTrims false for $style", MLPictureStyle.skipColorTrims(style))
            }
        }
    }

    // ── Property 12: No Flash Produces Zero Compensation (task 4.2) ──

    @Test
    fun property12_noFlashProducesZeroCompensation() {
        val lightSources = listOf(0, 1, 2, 3, 9, 10, 255)
        val temps = listOf(2000, 3500, 4500, 5000, 6500, 9000)
        for (ls in lightSources) {
            for (t in temps) {
                val r = MLFlashCompensation.compute(false, ls, t)
                assertEquals(0f, r.wbOffset, 0.0001f)
                assertEquals(0f, r.tintOffset, 0.0001f)
                assertEquals(0f, r.shadowLift, 0.0001f)
            }
        }
    }

    // ── Property 13: Lens Table Backward Compatibility (task 2.2) ──

    @Test
    fun property13_lensTableBackwardCompatibility_unknownLensReturnsNullCa() {
        // Any lens_id not present in the (test-resource) table, and lens_id 0,
        // must yield a null CA lookup (zero CA), matching the 7-column /
        // unknown-lens fallback behavior.
        assertNull(MLChromaticAberration.forLensId(0))
        val unknownIds = listOf(999999, -1, 123456789)
        for (id in unknownIds) {
            assertNull("unknown lens_id=$id -> null CA", MLChromaticAberration.forLensId(id))
        }
    }
}
