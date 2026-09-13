/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Unit tests for ML Intelligence modules (pure Kotlin, no Android deps).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import org.junit.Assert.*
import org.junit.Test

class MLModulesTest {

    // ── MLNoiseCurve tests ──

    @Test
    fun noiseCurve_iso100_returnsZero() {
        val (luma, chroma) = MLNoiseCurve.forIso(100)
        assertEquals(0f, luma, 0.01f)
        assertEquals(0f, chroma, 0.01f)
    }

    @Test
    fun noiseCurve_iso3200_returnsExpected() {
        val (luma, chroma) = MLNoiseCurve.forIso(3200)
        assertEquals(35f, luma, 0.1f)
        assertEquals(25f, chroma, 0.1f)
    }

    @Test
    fun noiseCurve_iso12800Plus_returnsClamped() {
        val (luma, chroma) = MLNoiseCurve.forIso(25600)
        assertEquals(65f, luma, 0.1f)
        assertEquals(50f, chroma, 0.1f)
    }

    // ── MLWbSceneTable tests ──

    @Test
    fun wbScene_kelvin0_returnsNoBias() {
        val (wb, tint) = MLWbSceneTable.forKelvin(0)
        assertEquals(0f, wb, 0.01f)
        assertEquals(0f, tint, 0.01f)
    }

    @Test
    fun wbScene_tungsten3000_returnsWarmBias() {
        val (wb, tint) = MLWbSceneTable.forKelvin(3000)
        assertTrue("Tungsten should return warm bias", wb > 0f)
        assertEquals(0f, tint, 0.01f)
    }

    @Test
    fun wbScene_daylight5500_returnsNeutral() {
        val (wb, tint) = MLWbSceneTable.forKelvin(5500)
        assertEquals(0f, wb, 0.01f)
        assertEquals(0f, tint, 0.01f)
    }

    @Test
    fun wbScene_deepShade8000_returnsCoolBias() {
        val (wb, tint) = MLWbSceneTable.forKelvin(8000)
        assertTrue("Deep shade should return cool bias", wb < 0f)
    }

    // ── MLEttrBias tests ──

    @Test
    fun ettrBias_brightScene_returnsNegative() {
        val bias = MLEttrBias.forLightLevel(220)
        assertEquals(-0.5f, bias, 0.01f)
    }

    @Test
    fun ettrBias_darkScene_returnsPositive() {
        val bias = MLEttrBias.forLightLevel(20)
        assertEquals(0.3f, bias, 0.01f)
    }

    @Test
    fun ettrBias_midScene_returnsZero() {
        val bias = MLEttrBias.forLightLevel(128)
        assertEquals(0f, bias, 0.01f)
    }

    // ── MLEttrBias boundary tests ──

    @Test
    fun ettrBias_boundary200_returnsZero() {
        val bias = MLEttrBias.forLightLevel(200)
        assertEquals(0f, bias, 0.01f)
    }

    @Test
    fun ettrBias_boundary201_returnsNegative() {
        val bias = MLEttrBias.forLightLevel(201)
        assertEquals(-0.5f, bias, 0.01f)
    }

    @Test
    fun ettrBias_boundary30_returnsZero() {
        val bias = MLEttrBias.forLightLevel(30)
        assertEquals(0f, bias, 0.01f)
    }

    @Test
    fun ettrBias_boundary29_returnsPositive() {
        val bias = MLEttrBias.forLightLevel(29)
        assertEquals(0.3f, bias, 0.01f)
    }
}
