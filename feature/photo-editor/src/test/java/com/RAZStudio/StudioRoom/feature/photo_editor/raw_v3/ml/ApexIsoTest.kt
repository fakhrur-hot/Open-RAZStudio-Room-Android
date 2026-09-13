/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Unit/property tests for ApexIso (spec ml6d-extended-intelligence, task 9.3).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ApexIsoTest {

    // ── Property 18: Dual-ISO APEX Index Encoding Round-Trip (task 9.3) ──

    @Test
    fun property18_encodeDecodeRoundTrip_wholeIsoRange() {
        for (iso in 100..25600 step 1) {
            val idx = ApexIso.encode(iso)
            assertTrue("index in [56..136] for iso=$iso", idx in 56..136)
            val decoded = ApexIso.decode(idx)
            // Within integer rounding, per Requirement 12.2 — allow small
            // relative tolerance since each APEX step is a 1/8-EV quantization.
            val tolerance = maxOf(1, (iso * 0.03).toInt())
            assertTrue(
                "round-trip iso=$iso -> idx=$idx -> decoded=$decoded within tolerance=$tolerance",
                abs(decoded - iso) <= tolerance,
            )
        }
    }

    @Test
    fun encode_clampsToDocumentedRange() {
        assertEquals(56, ApexIso.encode(1))
        assertEquals(56, ApexIso.encode(0))
        assertEquals(56, ApexIso.encode(-100))
        assertTrue(ApexIso.encode(1_000_000) <= 136)
    }

    @Test
    fun decode_clampsToDocumentedRange() {
        assertEquals(ApexIso.decode(56), ApexIso.decode(-10))
        assertEquals(ApexIso.decode(136), ApexIso.decode(999))
    }

    @Test
    fun encode_iso100_isReferenceIndex72() {
        assertEquals(72, ApexIso.encode(100))
    }
}
