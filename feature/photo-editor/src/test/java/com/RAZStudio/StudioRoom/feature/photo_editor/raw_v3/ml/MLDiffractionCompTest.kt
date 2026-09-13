/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Unit tests for MLDiffractionComp (pure Kotlin, no Android deps).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import org.junit.Assert.*
import org.junit.Test

class MLDiffractionCompTest {

    // ── Invalid input guard tests ──

    @Test
    fun compute_zeroAperture_returnsZero() {
        val result = MLDiffractionComp.compute(0f, 50f, 1.0f)
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun compute_negativeAperture_returnsZero() {
        val result = MLDiffractionComp.compute(-2f, 50f, 1.0f)
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun compute_zeroCropFactor_returnsZero() {
        val result = MLDiffractionComp.compute(16f, 50f, 0f)
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun compute_negativeCropFactor_returnsZero() {
        val result = MLDiffractionComp.compute(16f, 50f, -1f)
        assertEquals(0f, result, 0.001f)
    }

    // ── Below diffraction limit tests ──

    @Test
    fun compute_apertureAtLimit_fullFrame_returnsZero() {
        // Limit = 11/1.0 = f/11; aperture at limit → 0
        val result = MLDiffractionComp.compute(11f, 50f, 1.0f)
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun compute_apertureBelowLimit_fullFrame_returnsZero() {
        // f/8 is below f/11 limit for FF
        val result = MLDiffractionComp.compute(8f, 50f, 1.0f)
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun compute_apertureAtLimit_apsc_returnsZero() {
        // Limit = 11/1.6 = 6.875; f/6.875 at limit → 0
        val result = MLDiffractionComp.compute(11f / 1.6f, 50f, 1.6f)
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun compute_apertureBelowLimit_apsc_returnsZero() {
        // f/5.6 is below f/6.875 limit for APS-C
        val result = MLDiffractionComp.compute(5.6f, 50f, 1.6f)
        assertEquals(0f, result, 0.001f)
    }

    // ── Above diffraction limit tests (full-frame, cropFactor=1.0) ──

    @Test
    fun compute_f16_fullFrame_returnsExpected() {
        // limit = 11, compensation = (16 - 11) × 6 = 30
        val result = MLDiffractionComp.compute(16f, 50f, 1.0f)
        assertEquals(30f, result, 0.001f)
    }

    @Test
    fun compute_f13_fullFrame_returnsExpected() {
        // limit = 11, compensation = (13 - 11) × 6 = 12
        val result = MLDiffractionComp.compute(13f, 50f, 1.0f)
        assertEquals(12f, result, 0.001f)
    }

    @Test
    fun compute_f22_fullFrame_cappedAt30() {
        // limit = 11, compensation = (22 - 11) × 6 = 66, capped at 30
        val result = MLDiffractionComp.compute(22f, 50f, 1.0f)
        assertEquals(30f, result, 0.001f)
    }

    @Test
    fun compute_f32_fullFrame_cappedAt30() {
        // limit = 11, compensation = (32 - 11) × 6 = 126, capped at 30
        val result = MLDiffractionComp.compute(32f, 50f, 1.0f)
        assertEquals(30f, result, 0.001f)
    }

    // ── Above diffraction limit tests (APS-C, cropFactor=1.6) ──

    @Test
    fun compute_f8_apsc_returnsExpected() {
        // limit = 11/1.6 = 6.875, compensation = (8 - 6.875) × 6 = 6.75
        val result = MLDiffractionComp.compute(8f, 50f, 1.6f)
        assertEquals(6.75f, result, 0.001f)
    }

    @Test
    fun compute_f11_apsc_returnsExpected() {
        // limit = 6.875, compensation = (11 - 6.875) × 6 = 24.75
        val result = MLDiffractionComp.compute(11f, 50f, 1.6f)
        assertEquals(24.75f, result, 0.001f)
    }

    @Test
    fun compute_f16_apsc_cappedAt30() {
        // limit = 6.875, compensation = (16 - 6.875) × 6 = 54.75, capped at 30
        val result = MLDiffractionComp.compute(16f, 50f, 1.6f)
        assertEquals(30f, result, 0.001f)
    }

    // ── Focal length does not affect result (reserved parameter) ──

    @Test
    fun compute_differentFocalLengths_sameResult() {
        val result24mm = MLDiffractionComp.compute(16f, 24f, 1.0f)
        val result200mm = MLDiffractionComp.compute(16f, 200f, 1.0f)
        assertEquals(result24mm, result200mm, 0.001f)
    }

    // ── Property 9: Diffraction Below Limit is Zero (task 5.4) ──

    @Test
    fun property9_diffractionBelowLimitIsZero() {
        val cropFactors = listOf(1.0f, 1.3f, 1.6f, 2.0f)
        val apertureFractions = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 1.0f)
        for (crop in cropFactors) {
            val limit = 11.0f / crop
            for (frac in apertureFractions) {
                val aperture = limit * frac
                val result = MLDiffractionComp.compute(aperture, 50f, crop)
                assertEquals(
                    "aperture=$aperture <= limit=$limit (crop=$crop) -> 0",
                    0f, result, 0.001f,
                )
            }
        }
    }

    // ── Property 10: Diffraction Monotonicity and Cap (task 5.4) ──

    @Test
    fun property10_diffractionMonotonicityAndCap() {
        val cropFactors = listOf(1.0f, 1.3f, 1.6f)
        for (crop in cropFactors) {
            val limit = 11.0f / crop
            val aboveLimitApertures = (1..40).map { limit + it * 0.5f }
            for (i in aboveLimitApertures.indices) {
                for (j in aboveLimitApertures.indices) {
                    val a = aboveLimitApertures[i]
                    val b = aboveLimitApertures[j]
                    if (a > b) {
                        val compA = MLDiffractionComp.compute(a, 50f, crop)
                        val compB = MLDiffractionComp.compute(b, 50f, crop)
                        assertTrue("comp($a) >= comp($b) for crop=$crop", compA >= compB - 0.0001f)
                    }
                }
            }
            for (aperture in aboveLimitApertures) {
                val comp = MLDiffractionComp.compute(aperture, 50f, crop)
                assertTrue("comp <= 30 for aperture=$aperture crop=$crop", comp <= 30f)
            }
        }
    }
}
