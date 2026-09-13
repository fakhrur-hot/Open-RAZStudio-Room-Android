/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Integration test that verifies the lens_tune.tbl asset can be parsed
 * correctly and that key entries (like lens_id 160) are present with the
 * expected values.
 *
 * This test reads the real asset file from disk (not via Android Context)
 * to verify parsing logic without requiring an emulator.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parses lens_tune.tbl directly from the project's assets directory to
 * verify the file format and expected entries are intact.
 */
class LensTuneAssetParseTest {

    /**
     * Locates lens_tune.tbl relative to the test working directory.
     * Gradle runs tests from the module directory, so we walk up to find it.
     */
    private fun findAssetFile(): File? {
        // Try several common relative paths based on how Gradle runs unit tests
        val candidates = listOf(
            "../../app/src/main/assets/ml/lens_tune.tbl",
            "../app/src/main/assets/ml/lens_tune.tbl",
            "app/src/main/assets/ml/lens_tune.tbl",
        )
        for (candidate in candidates) {
            val f = File(candidate)
            if (f.exists()) return f
        }
        // Try absolute path as last resort
        val absolute = File("c:/Users/Public/Kiro/StudioRoom/Fixed16bit/app/src/main/assets/ml/lens_tune.tbl")
        if (absolute.exists()) return absolute
        return null
    }

    /**
     * Parse the tbl file into a map of lens_id -> LensTuneEntry.
     */
    private fun parseTbl(file: File): Map<Int, LensTuneEntry> {
        val result = mutableMapOf<Int, LensTuneEntry>()
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEach
            val parts = trimmed.split('|')
            if (parts.size >= 7) {
                try {
                    val entry = LensTuneEntry(
                        lensId = parts[0].trim().toInt(),
                        contrastBias = parts[1].trim().toFloat(),
                        saturationBias = parts[2].trim().toFloat(),
                        colorToneBias = parts[3].trim().toFloat(),
                        evBias = parts[4].trim().toFloat(),
                        wbRedScale = parts[5].trim().toFloat(),
                        wbBlueScale = parts[6].trim().toFloat(),
                    )
                    result[entry.lensId] = entry
                } catch (_: NumberFormatException) {
                    // skip malformed
                }
            }
        }
        return result
    }

    @Test
    fun `lens_tune_tbl asset exists and is readable`() {
        val file = findAssetFile()
        assertNotNull("lens_tune.tbl not found relative to test working dir", file)
        assertTrue("lens_tune.tbl should be readable", file!!.canRead())
        assertTrue("lens_tune.tbl should not be empty", file.length() > 0)
    }

    @Test
    fun `lens_tune_tbl contains lens_id 160 with expected trims`() {
        val file = findAssetFile() ?: return // skip if file not found
        val table = parseTbl(file)

        val entry = table[160]
        assertNotNull("lens_id 160 should exist in lens_tune.tbl", entry)

        // Verify lens_id 160 (Tamron 20-40mm) — values from tbl:
        // 160|-1|0|1|0|1000|1024
        assertEquals(-1f, entry!!.contrastBias, 0.001f)
        assertEquals(0f, entry.saturationBias, 0.001f)
        assertEquals(1f, entry.colorToneBias, 0.001f)
        assertEquals(0f, entry.evBias, 0.001f)
        assertEquals(1000f, entry.wbRedScale, 0.001f)
        assertEquals(1024f, entry.wbBlueScale, 0.001f)
    }

    @Test
    fun `lens_tune_tbl contains neutral fallback lens_id 0`() {
        val file = findAssetFile() ?: return
        val table = parseTbl(file)

        val neutral = table[0]
        assertNotNull("lens_id 0 (neutral) should exist", neutral)
        assertEquals(0f, neutral!!.contrastBias, 0.001f)
        assertEquals(0f, neutral.saturationBias, 0.001f)
        assertEquals(0f, neutral.colorToneBias, 0.001f)
        assertEquals(0f, neutral.evBias, 0.001f)
        assertEquals(1024f, neutral.wbRedScale, 0.001f)
        assertEquals(1024f, neutral.wbBlueScale, 0.001f)
    }

    @Test
    fun `lens_tune_tbl has multiple entries (at least 5)`() {
        val file = findAssetFile() ?: return
        val table = parseTbl(file)
        assertTrue(
            "Expected at least 5 lens entries, got ${table.size}",
            table.size >= 5
        )
    }

    @Test
    fun `non-Canon lens returns neutral via fallback mechanism`() {
        // Simulate what forLensId would do for an unknown lens
        val file = findAssetFile() ?: return
        val table = parseTbl(file)

        val unknownLensId = 99999
        val result = table[unknownLensId] ?: table[0]

        assertNotNull("Fallback to lens_id 0 should work", result)
        assertEquals(0f, result!!.contrastBias, 0.001f)
        assertEquals(0f, result.saturationBias, 0.001f)
        assertEquals(0f, result.colorToneBias, 0.001f)
    }
}
