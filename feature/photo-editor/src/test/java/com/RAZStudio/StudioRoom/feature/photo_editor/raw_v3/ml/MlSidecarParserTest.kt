/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Unit tests for ML_6D sidecar/export JSON parsing.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import org.junit.Assert.*
import org.junit.Test

class MlSidecarParserTest {

    // ── Per-Shot Sidecar Parsing ─────────────────────────────────────────────

    @Test
    fun parseShotSidecar_fullValidJson_returnsAllBlocks() {
        val json = """
        {
          "formatVersion": 2,
          "filename": "IMG_4126.CR2",
          "dualIso": {
            "enabled": true,
            "isoBase": 100,
            "isoAlternate": 1600,
            "interleavePeriod": 2
          },
          "ettr": {
            "lightLevel": 142,
            "sceneDR": 11.2,
            "highlightHeadroom": 1.8,
            "channelClip": [0.001, 0.0, 0.003]
          },
          "pictureStyle": "STANDARD",
          "lens": {
            "id": 160,
            "caStrength": 15,
            "fringeReduce": 20
          }
        }
        """.trimIndent()

        val result = MlSidecarParser.parseShotSidecar(json)

        assertNotNull(result)
        assertEquals(2, result!!.formatVersion)
        assertEquals("IMG_4126.CR2", result.filename)

        // Dual-ISO block
        assertNotNull(result.dualIso)
        assertTrue(result.dualIso!!.enabled)
        assertEquals(100, result.dualIso!!.isoBase)
        assertEquals(1600, result.dualIso!!.isoAlternate)
        assertEquals(2, result.dualIso!!.interleavePeriod)

        // ETTR block
        assertNotNull(result.ettr)
        assertEquals(142, result.ettr!!.lightLevel)
        assertEquals(11.2f, result.ettr!!.sceneDR, 0.01f)
        assertEquals(1.8f, result.ettr!!.highlightHeadroom, 0.01f)
        assertEquals(0.001f, result.ettr!!.channelClip[0], 0.001f)
        assertEquals(0.0f, result.ettr!!.channelClip[1], 0.001f)
        assertEquals(0.003f, result.ettr!!.channelClip[2], 0.001f)

        // Lens block
        assertNotNull(result.lens)
        assertEquals(160, result.lens!!.id)
        assertEquals(15, result.lens!!.caStrength)
        assertEquals(20, result.lens!!.fringeReduce)

        // Picture Style
        assertEquals("STANDARD", result.pictureStyle)
    }

    @Test
    fun parseShotSidecar_minimalJson_optionalBlocksNull() {
        val json = """
        {
          "formatVersion": 2,
          "filename": "IMG_0001.CR2"
        }
        """.trimIndent()

        val result = MlSidecarParser.parseShotSidecar(json)

        assertNotNull(result)
        assertEquals(2, result!!.formatVersion)
        assertEquals("IMG_0001.CR2", result.filename)
        assertNull(result.dualIso)
        assertNull(result.ettr)
        assertNull(result.lens)
        assertNull(result.pictureStyle)
    }

    @Test
    fun parseShotSidecar_wrongFormatVersion_returnsNull() {
        val json = """
        {
          "formatVersion": 1,
          "filename": "IMG_0001.CR2"
        }
        """.trimIndent()

        val result = MlSidecarParser.parseShotSidecar(json)
        assertNull(result)
    }

    @Test
    fun parseShotSidecar_missingFilename_returnsNull() {
        val json = """
        {
          "formatVersion": 2
        }
        """.trimIndent()

        val result = MlSidecarParser.parseShotSidecar(json)
        assertNull(result)
    }

    @Test
    fun parseShotSidecar_invalidJson_returnsNull() {
        val result = MlSidecarParser.parseShotSidecar("not valid json {{{")
        assertNull(result)
    }

    @Test
    fun parseShotSidecar_emptyString_returnsNull() {
        val result = MlSidecarParser.parseShotSidecar("")
        assertNull(result)
    }

    @Test
    fun parseShotSidecar_filenameMismatch_returnsNull() {
        val json = """
        {
          "formatVersion": 2,
          "filename": "IMG_0001.CR2"
        }
        """.trimIndent()

        val result = MlSidecarParser.parseShotSidecar(json, "IMG_9999.CR2")
        assertNull(result)
    }

    @Test
    fun parseShotSidecar_filenameMatchCaseInsensitive_succeeds() {
        val json = """
        {
          "formatVersion": 2,
          "filename": "img_0001.cr2"
        }
        """.trimIndent()

        val result = MlSidecarParser.parseShotSidecar(json, "IMG_0001.CR2")
        assertNotNull(result)
    }

    @Test
    fun parseShotSidecar_ettrBlockInvalidChannelClipSize_ettrNull() {
        val json = """
        {
          "formatVersion": 2,
          "filename": "IMG_0001.CR2",
          "ettr": {
            "lightLevel": 142,
            "sceneDR": 11.2,
            "highlightHeadroom": 1.8,
            "channelClip": [0.001, 0.0]
          }
        }
        """.trimIndent()

        val result = MlSidecarParser.parseShotSidecar(json)
        assertNotNull(result)
        assertNull(result!!.ettr)
    }

    @Test
    fun parseShotSidecar_dualIsoBlockInvalidIso_dualIsoNull() {
        val json = """
        {
          "formatVersion": 2,
          "filename": "IMG_0001.CR2",
          "dualIso": {
            "enabled": true,
            "isoBase": 0,
            "isoAlternate": 1600,
            "interleavePeriod": 2
          }
        }
        """.trimIndent()

        val result = MlSidecarParser.parseShotSidecar(json)
        assertNotNull(result)
        assertNull(result!!.dualIso)
    }

    @Test
    fun parseShotSidecar_lensBlockNegativeId_lensNull() {
        val json = """
        {
          "formatVersion": 2,
          "filename": "IMG_0001.CR2",
          "lens": {
            "id": -1,
            "caStrength": 15,
            "fringeReduce": 20
          }
        }
        """.trimIndent()

        val result = MlSidecarParser.parseShotSidecar(json)
        assertNotNull(result)
        assertNull(result!!.lens)
    }

    @Test
    fun parseShotSidecar_lensCaValuesClamped() {
        val json = """
        {
          "formatVersion": 2,
          "filename": "IMG_0001.CR2",
          "lens": {
            "id": 160,
            "caStrength": 150,
            "fringeReduce": -10
          }
        }
        """.trimIndent()

        val result = MlSidecarParser.parseShotSidecar(json)
        assertNotNull(result)
        assertNotNull(result!!.lens)
        assertEquals(100, result.lens!!.caStrength)
        assertEquals(0, result.lens!!.fringeReduce)
    }

    @Test
    fun parseShotSidecar_ettrValuesClampedToRange() {
        val json = """
        {
          "formatVersion": 2,
          "filename": "IMG_0001.CR2",
          "ettr": {
            "lightLevel": 100,
            "sceneDR": 20.0,
            "highlightHeadroom": 5.0,
            "channelClip": [1.5, -0.1, 0.5]
          }
        }
        """.trimIndent()

        val result = MlSidecarParser.parseShotSidecar(json)
        assertNotNull(result)
        assertNotNull(result!!.ettr)
        assertEquals(14f, result.ettr!!.sceneDR, 0.01f)
        assertEquals(3f, result.ettr!!.highlightHeadroom, 0.01f)
        assertEquals(1f, result.ettr!!.channelClip[0], 0.01f)
        assertEquals(0f, result.ettr!!.channelClip[1], 0.01f)
        assertEquals(0.5f, result.ettr!!.channelClip[2], 0.01f)
    }

    // ── Per-Session Export Parsing ────────────────────────────────────────────

    @Test
    fun parseExportSession_validJson_returnsSession() {
        val json = """
        {
          "formatVersion": 2,
          "firmwareVersion": "ML_6D_2.1",
          "body": "Canon EOS 6D",
          "sensor": {"pixelPitch": 6.54, "cropFactor": 1.0},
          "session": {
            "dualIsoEnabled": true,
            "ettrEnabled": true,
            "pictureStyle": "STANDARD"
          }
        }
        """.trimIndent()

        val result = MlSidecarParser.parseExportSession(json)

        assertNotNull(result)
        assertEquals(2, result!!.formatVersion)
        assertEquals("ML_6D_2.1", result.firmwareVersion)
        assertEquals("Canon EOS 6D", result.body)
        assertEquals(6.54f, result.sensor.pixelPitch, 0.01f)
        assertEquals(1.0f, result.sensor.cropFactor, 0.01f)
        assertTrue(result.session.dualIsoEnabled)
        assertTrue(result.session.ettrEnabled)
        assertEquals("STANDARD", result.session.pictureStyle)
    }

    @Test
    fun parseExportSession_wrongFormatVersion_returnsNull() {
        val json = """
        {
          "formatVersion": 3,
          "firmwareVersion": "ML_6D_2.1",
          "body": "Canon EOS 6D",
          "sensor": {"pixelPitch": 6.54, "cropFactor": 1.0},
          "session": {"dualIsoEnabled": false, "ettrEnabled": false, "pictureStyle": "STANDARD"}
        }
        """.trimIndent()

        val result = MlSidecarParser.parseExportSession(json)
        assertNull(result)
    }

    @Test
    fun parseExportSession_missingSensor_returnsNull() {
        val json = """
        {
          "formatVersion": 2,
          "firmwareVersion": "ML_6D_2.1",
          "body": "Canon EOS 6D",
          "session": {"dualIsoEnabled": false, "ettrEnabled": false, "pictureStyle": "STANDARD"}
        }
        """.trimIndent()

        val result = MlSidecarParser.parseExportSession(json)
        assertNull(result)
    }

    @Test
    fun parseExportSession_missingSession_returnsNull() {
        val json = """
        {
          "formatVersion": 2,
          "firmwareVersion": "ML_6D_2.1",
          "body": "Canon EOS 6D",
          "sensor": {"pixelPitch": 6.54, "cropFactor": 1.0}
        }
        """.trimIndent()

        val result = MlSidecarParser.parseExportSession(json)
        assertNull(result)
    }

    @Test
    fun parseExportSession_invalidJson_returnsNull() {
        val result = MlSidecarParser.parseExportSession("{broken json")
        assertNull(result)
    }

    @Test
    fun parseExportSession_missingFirmwareVersion_returnsNull() {
        val json = """
        {
          "formatVersion": 2,
          "body": "Canon EOS 6D",
          "sensor": {"pixelPitch": 6.54, "cropFactor": 1.0},
          "session": {"dualIsoEnabled": false, "ettrEnabled": false, "pictureStyle": "STANDARD"}
        }
        """.trimIndent()

        val result = MlSidecarParser.parseExportSession(json)
        assertNull(result)
    }

    @Test
    fun parseExportSession_missingBody_returnsNull() {
        val json = """
        {
          "formatVersion": 2,
          "firmwareVersion": "ML_6D_2.1",
          "sensor": {"pixelPitch": 6.54, "cropFactor": 1.0},
          "session": {"dualIsoEnabled": false, "ettrEnabled": false, "pictureStyle": "STANDARD"}
        }
        """.trimIndent()

        val result = MlSidecarParser.parseExportSession(json)
        assertNull(result)
    }

    // ── Property 16: Sidecar Malformed Graceful Degradation (task 7.3) ──

    @Test
    fun property16_malformedInput_neverThrows_alwaysFallsBackToNull() {
        val malformedInputs = listOf(
            "",
            "   ",
            "not json at all",
            "{",
            "{}",
            "{\"formatVersion\": 1}",                       // wrong version
            "{\"formatVersion\": 999, \"filename\": \"x\"}", // unsupported version
            "{\"formatVersion\": 2}",                        // missing filename
            "null",
            "[]",
            "{\"formatVersion\": 2, \"filename\": \"IMG_0001.CR2\", \"dualIso\": \"not an object\"}",
            "{\"formatVersion\": 2, \"filename\": \"IMG_0001.CR2\", \"ettr\": {\"channelClip\": \"nope\"}}",
            " garbage",
            "{".repeat(500),
        )
        for (input in malformedInputs) {
            // Must not throw for either parser, and must degrade to null
            // (EXIF-only fallback), never a partially-populated object.
            val shot = try {
                MlSidecarParser.parseShotSidecar(input)
            } catch (e: Exception) {
                fail("parseShotSidecar threw for input=$input: $e"); null
            }
            val session = try {
                MlSidecarParser.parseExportSession(input)
            } catch (e: Exception) {
                fail("parseExportSession threw for input=$input: $e"); null
            }
            // Every input in this list is deliberately invalid/incomplete —
            // both parsers must reject it.
            assertNull("parseShotSidecar should reject: $input", shot)
            assertNull("parseExportSession should reject: $input", session)
            assertFalse(MlSidecarParser.isValidShotSidecar(input))
            assertFalse(MlSidecarParser.isValidExportSession(input))
        }
    }
}
