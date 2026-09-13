/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML_6D Dual-ISO MakerNote APEX index encoder/decoder (spec
 * ml6d-extended-intelligence, Requirement 12; task 9.2).
 *
 * Canon MakerNote tag 0x0027 extension encodes base/alternate dual-ISO as
 * two 8-bit APEX indices (bits [7:0] / [15:8]). This is the standalone
 * StudioRoom-side counterpart to the firmware's `apex_encode`/`apex_decode`
 * in ML_6D's `ai_lut.h` (spec cr2-intelligence-integration, Component 6) —
 * same formula, kept independent since the two run in different languages
 * on different sides of the data contract.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * APEX index <-> ISO conversion for the ML_6D dual-ISO MakerNote tag.
 *
 *   index = 72 + 8 * log2(iso / 100), clamped [56..136]
 *   iso   = 100 * 2^((index - 72) / 8)
 */
object ApexIso {

    private const val LN2 = 0.6931471805599453

    /** Encode a real ISO value to its APEX index, clamped to [56..136]. */
    fun encode(iso: Int): Int {
        if (iso <= 0) return 56
        val idx = 72 + (8.0 * (ln(iso.toDouble()) / LN2 - ln(100.0) / LN2)).roundToInt()
        return idx.coerceIn(56, 136)
    }

    /** Decode an APEX index back to a real ISO value. Inverse of [encode]. */
    fun decode(apexIndex: Int): Int {
        val clamped = apexIndex.coerceIn(56, 136)
        return (100.0 * 2.0.pow((clamped - 72.0) / 8.0)).roundToInt()
    }
}
