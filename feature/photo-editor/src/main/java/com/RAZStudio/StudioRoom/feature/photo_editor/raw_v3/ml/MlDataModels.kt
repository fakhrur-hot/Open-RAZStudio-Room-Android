/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML_6D Firmware Data Export Models.
 *
 * Defines the data classes for per-session export (ml6d_export.json) and
 * per-shot sidecar ({filename}.ml6d) produced by the ML_6D firmware on the
 * SD card alongside CR2 files.
 *
 * These models are consumed by StudioRoom to enrich CR2 metadata with
 * firmware-provided values (dual-ISO, ETTR, lens CA, picture style).
 * When a sidecar is present, its values supplement or override EXIF-derived
 * metadata. When absent, all modules operate from EXIF data alone (graceful
 * degradation).
 *
 * Format specification: formatVersion = 2 (current schema).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

// ── Per-Session Export Model (ML/DATA/ml6d_export.json) ──────────────────────

/**
 * Sensor characteristics reported by the ML_6D firmware.
 *
 * @property pixelPitch Pixel pitch in micrometers (e.g. 6.54 for Canon 6D).
 * @property cropFactor Crop factor relative to full-frame (1.0 FF, 1.6 APS-C).
 */
data class MlSensorInfo(
    val pixelPitch: Float,
    val cropFactor: Float,
)

/**
 * Session-level configuration active when the export was written.
 *
 * @property dualIsoEnabled Whether dual-ISO interleave is enabled for the session.
 * @property ettrEnabled Whether ETTR mode is active for the session.
 * @property pictureStyle Active Canon Picture Style name (e.g. "STANDARD").
 */
data class MlSessionConfig(
    val dualIsoEnabled: Boolean,
    val ettrEnabled: Boolean,
    val pictureStyle: String,
)

/**
 * Per-session metadata export produced by the ML_6D firmware at
 * `ML/DATA/ml6d_export.json` on the SD card.
 *
 * Created/overwritten when the firmware powers on or a new session begins.
 *
 * @property formatVersion Schema version (must be 2 for current format).
 * @property firmwareVersion ML_6D firmware version string (e.g. "ML_6D_2.1").
 * @property body Camera model string (e.g. "Canon EOS 6D").
 * @property sensor Sensor characteristics (pixel pitch, crop factor).
 * @property session Session-level configuration snapshot.
 */
data class MlExportSession(
    val formatVersion: Int,
    val firmwareVersion: String,
    val body: String,
    val sensor: MlSensorInfo,
    val session: MlSessionConfig,
)

// ── Per-Shot Sidecar Model (ML/DATA/SHOTS/{filename}.ml6d) ───────────────────

/**
 * Dual-ISO metadata block from the per-shot sidecar.
 *
 * @property enabled Whether dual-ISO was active for this shot.
 * @property isoBase Base ISO sensitivity (e.g. 100).
 * @property isoAlternate Alternate ISO sensitivity (e.g. 1600).
 * @property interleavePeriod Alternating row count (2 or 4).
 */
data class MlDualIsoBlock(
    val enabled: Boolean,
    val isoBase: Int,
    val isoAlternate: Int,
    val interleavePeriod: Int,
)

/**
 * ETTR scene analysis block from the per-shot sidecar.
 *
 * @property lightLevel Metered light level 0–255.
 * @property sceneDR Scene dynamic range in stops (clamped [4.0..14.0]).
 * @property highlightHeadroom Highlight headroom in stops (clamped [0.0..3.0]).
 * @property channelClip Per-channel clip fractions [R, G, B], each in [0.0..1.0].
 */
data class MlEttrBlock(
    val lightLevel: Int,
    val sceneDR: Float,
    val highlightHeadroom: Float,
    val channelClip: FloatArray,
) {
    init {
        require(channelClip.size == 3) { "channelClip must have exactly 3 elements (R, G, B)" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MlEttrBlock) return false
        return lightLevel == other.lightLevel &&
            sceneDR == other.sceneDR &&
            highlightHeadroom == other.highlightHeadroom &&
            channelClip.contentEquals(other.channelClip)
    }

    override fun hashCode(): Int {
        var result = lightLevel
        result = 31 * result + sceneDR.hashCode()
        result = 31 * result + highlightHeadroom.hashCode()
        result = 31 * result + channelClip.contentHashCode()
        return result
    }
}

/**
 * Lens CA metadata block from the per-shot sidecar.
 *
 * @property id Canon lens ID.
 * @property caStrength Chromatic aberration correction strength [0..100].
 * @property fringeReduce Purple/green fringe suppression strength [0..100].
 */
data class MlLensBlock(
    val id: Int,
    val caStrength: Int,
    val fringeReduce: Int,
)

/**
 * Per-shot sidecar metadata produced by the ML_6D firmware at
 * `ML/DATA/SHOTS/{filename}.ml6d` alongside the CR2 file.
 *
 * All metadata blocks except formatVersion and filename are optional.
 * When a block is present, its values supplement or override EXIF-derived
 * metadata in the StudioRoom processing pipeline.
 *
 * @property formatVersion Schema version (must be 2 for current format).
 * @property filename CR2 filename without path (e.g. "IMG_4126.CR2").
 * @property dualIso Dual-ISO metadata block, or null if not applicable.
 * @property ettr ETTR scene analysis block, or null if ETTR was inactive.
 * @property lens Lens CA metadata block, or null if not available.
 * @property pictureStyle Canon Picture Style name, or null if not recorded.
 */
data class MlShotSidecar(
    val formatVersion: Int,
    val filename: String,
    val dualIso: MlDualIsoBlock? = null,
    val ettr: MlEttrBlock? = null,
    val lens: MlLensBlock? = null,
    val pictureStyle: String? = null,
)
