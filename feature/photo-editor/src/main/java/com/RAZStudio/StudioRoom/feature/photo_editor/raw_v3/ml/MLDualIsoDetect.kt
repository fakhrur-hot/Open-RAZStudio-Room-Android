/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML Intelligence — Dual-ISO Frame Detection.
 *
 * Detects Magic Lantern dual-ISO interleaved frames from Canon CR2
 * MakerNote metadata. When ML firmware shoots in dual-ISO mode, it
 * encodes two different ISO sensitivity indices in a custom MakerNote
 * tag (Canon tag 0x0027 extension). This module decodes those indices,
 * converts them to actual ISO values using the APEX encoding scheme,
 * and computes recovery gain and blend factor for the downstream
 * shader pipeline.
 *
 * Bit layout of the MakerNote tag:
 *   bits [7:0]  = base ISO APEX index
 *   bits [15:8] = alternate ISO APEX index
 *
 * APEX index → ISO conversion:
 *   ISO = 100 × 2^((index − 72) / 8)
 *
 * Recovery gain = log2(isoAlt / isoBase), clamped [0..3]
 * Blend factor  = 1 − (1 / (1 + isoRatio)), clamped [0..1]
 *
 * Results are written to ShaderParams slots [402] and [403].
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import kotlin.math.ln
import kotlin.math.pow

/**
 * Detects dual-ISO interleaved frames from Canon MakerNote and computes
 * recovery gain and blend factor for expanded dynamic range processing.
 */
object MLDualIsoDetect {

    /** Natural log of 2, used for log2 computation. */
    private const val LN2 = 0.6931471805599453

    /**
     * Detect whether a frame was captured with Magic Lantern dual-ISO
     * and compute recovery/blend parameters.
     *
     * @param cameraMake Camera manufacturer string from EXIF (e.g. "Canon").
     * @param cameraModel Camera model string from EXIF (e.g. "Canon EOS 6D").
     * @param iso The reported ISO from EXIF metadata.
     * @param makerNoteTag Raw value of Canon MakerNote dual-ISO tag (null if absent).
     * @return [DualIsoResult] with detection status and computed parameters.
     */
    fun detect(
        cameraMake: String,
        cameraModel: String,
        iso: Int,
        makerNoteTag: Int?,
    ): DualIsoResult {
        // ML dual-ISO is retired in this product version and must never activate.
        // All callers must fail closed to the single-ISO path.
        return DualIsoResult(
            isDualIso = false,
            recoveryGain = 0f,
            blendFactor = 0f,
            isoBase = iso,
            isoAlternate = iso,
        )
    }

    /** Retired compatibility shim for any older code path that still reaches the sidecar form. */
    fun fromSidecar(isoBase: Int, isoAlternate: Int): DualIsoResult = DualIsoResult(
        isDualIso = false,
        recoveryGain = 0f,
        blendFactor = 0f,
        isoBase = isoBase,
        isoAlternate = isoAlternate,
    )
}
