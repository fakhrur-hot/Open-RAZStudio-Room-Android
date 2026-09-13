/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Unit tests for lens_tune.tbl parsing logic.
 *
 * These tests bypass the Android Context-dependent ensureLoaded() path and
 * instead parse the .tbl file directly from test resources via classloader,
 * validating the pipe-delimited format parsing and lookup behaviour.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LensTuneParseTest {

    private lateinit var table: Map<Int, LensTuneEntry>

    /**
     * Parses the lens_tune.tbl from test resources using the same logic as
     * [MLLensTuneTable.parseAsset] but without Android Context dependency.
     */
    @Before
    fun setUp() {
        val inputStream = javaClass.classLoader!!.getResourceAsStream("ml/lens_tune.tbl")
            ?: error("Test resource ml/lens_tune.tbl not found")

        val result = HashMap<Int, LensTuneEntry>()
        inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) continue

                val parts = trimmed.split('|')
                val entry = when {
                    parts.size >= 7 -> LensTuneEntry(
                        lensId = parts[0].trim().toInt(),
                        contrastBias = parts[1].trim().toFloat(),
                        saturationBias = parts[2].trim().toFloat(),
                        colorToneBias = parts[3].trim().toFloat(),
                        evBias = parts[4].trim().toFloat(),
                        wbRedScale = parts[5].trim().toFloat(),
                        wbBlueScale = parts[6].trim().toFloat(),
                    )
                    parts.size >= 4 -> LensTuneEntry(
                        lensId = parts[0].trim().toInt(),
                        contrastBias = parts[1].trim().toFloat(),
                        saturationBias = parts[2].trim().toFloat(),
                        colorToneBias = parts[3].trim().toFloat(),
                        evBias = 0f,
                        wbRedScale = 1024f,
                        wbBlueScale = 1024f,
                    )
                    else -> null
                }
                if (entry != null) {
                    result[entry.lensId] = entry
                }
            }
        }
        table = result
    }

    @Test
    fun parsesExpectedNumberOfEntries() {
        // 10 non-comment data lines in the file
        assertEquals(10, table.size)
    }

    @Test
    fun lensId160_tamron20to40mm_hasCorrectTrims() {
        val entry = table[160]
        assertNotNull("Lens ID 160 should exist in table", entry)
        entry!!
        assertEquals(-1f, entry.contrastBias, 0.01f)
        assertEquals(0f, entry.saturationBias, 0.01f)
        assertEquals(1f, entry.colorToneBias, 0.01f)
        assertEquals(0f, entry.evBias, 0.01f)
        assertEquals(1000f, entry.wbRedScale, 0.01f)
        assertEquals(1024f, entry.wbBlueScale, 0.01f)
    }

    @Test
    fun lensId0_neutral_allZeros() {
        val entry = table[0]
        assertNotNull("Lens ID 0 (neutral fallback) should exist", entry)
        entry!!
        assertEquals(0f, entry.contrastBias, 0.01f)
        assertEquals(0f, entry.saturationBias, 0.01f)
        assertEquals(0f, entry.colorToneBias, 0.01f)
        assertEquals(0f, entry.evBias, 0.01f)
        assertEquals(1024f, entry.wbRedScale, 0.01f)
        assertEquals(1024f, entry.wbBlueScale, 0.01f)
    }

    @Test
    fun unknownLensId_fallsBackToNeutral() {
        // Simulate the same fallback logic as MLLensTuneTable.forLensId()
        val unknownId = 9999
        val entry = table[unknownId] ?: table[0]
        assertNotNull("Should fall back to lens_id 0", entry)
        entry!!
        assertEquals(0f, entry.contrastBias, 0.01f)
        assertEquals(0f, entry.saturationBias, 0.01f)
        assertEquals(0f, entry.colorToneBias, 0.01f)
    }

    @Test
    fun lensId160_wbRedScale_indicatesWarmShift() {
        val entry = table[160]!!
        // wbRedScale < 1024 means warmer WB (more red gain relative to neutral)
        assertTrue(
            "Lens 160 wbRedScale (${entry.wbRedScale}) should be < 1024 for warm shift",
            entry.wbRedScale < 1024f
        )
    }

    @Test
    fun commentsAndBlankLinesAreSkipped() {
        // If comments were parsed as data, we'd have way more than 10 entries
        assertTrue("Table should have ≤ 10 entries (only data rows)", table.size <= 10)
    }
}
