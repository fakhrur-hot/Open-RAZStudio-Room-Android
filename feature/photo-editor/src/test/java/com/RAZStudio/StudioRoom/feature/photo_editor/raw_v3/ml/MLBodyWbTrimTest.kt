/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Unit/property tests for MLBodyWbTrim (pure Kotlin — no Android Context
 * available in this module's unit tests, so these exercise the "table not
 * loaded" graceful-null path rather than real asset-backed entries).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import org.junit.Assert.*
import org.junit.Test

class MLBodyWbTrimTest {

    // ── Property 15: Body WB Trim Null Safety (task 5.6) ──

    @Test
    fun property15_unknownOrUnloadedModel_returnsNullTrim() {
        val models = listOf(
            "Canon EOS 6D", "Canon EOS 5D Mark III", "Nikon D850",
            "", "Unknown Camera Model 12345", "Canon EOS R5",
        )
        for (model in models) {
            assertNull(
                "forModel($model) must be null when the table isn't loaded (or model unknown)",
                MLBodyWbTrim.forModel(model),
            )
        }
    }

    @Test
    fun property15_blankModel_returnsNull() {
        assertNull(MLBodyWbTrim.forModel(""))
        assertNull(MLBodyWbTrim.forModel("   "))
    }

    @Test
    fun property15_orchestrator_leavesBodyWbTrimSlotZero_whenModelUnknown() {
        // Integration check: when MLBodyWbTrim.forModel returns null (as it
        // always does here without a loaded table), the orchestrator's slot
        // [407] diagnostic must stay at its default (0f) rather than being
        // fabricated from a missing entry.
        val result = MLExtendedIntelligence.applyExtendedDefaults(
            context = null,
            stageA = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine.StageAResult(
                success = true, width = 1, height = 1, orientation = 0,
                cameraMake = "Canon", cameraModel = "Canon EOS 6D",
                lensMake = "Canon", lensModel = "EF 50mm", lensId = 0,
                colorTemperature = 5500, iso = 100, shutterSpeed = 1f / 200f,
                aperture = 5.6f, focalLength = 50f, dateTimeOriginal = "", error = null,
            ),
            stageAHistogramStats = null,
            sidecar = null,
            currentMacro = com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro(),
        )
        assertEquals(0f, result.extDiagnostics[5], 0.0001f)
    }
}
