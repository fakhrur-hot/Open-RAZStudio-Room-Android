/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Canon Picture Style Detection and Bias Scaling.
 *
 * Reads Canon Picture Style from MakerNote tag 0x000A and computes a bias
 * scale factor that modulates all ML adjustment magnitudes:
 *
 *   • NEUTRAL / FAITHFUL → scale 0.5 (subtle adjustments)
 *   • MONOCHROME → scale 1.0, but skip color trims entirely
 *   • STANDARD / PORTRAIT / LANDSCAPE / AUTO / USER_DEF → scale 1.0 (full)
 *
 * Non-Canon cameras default to STANDARD (full biases, scale 1.0).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

/**
 * Detects Canon Picture Style from MakerNote and computes bias scaling.
 *
 * The orchestrator uses [biasScale] to multiply all macro bias fields and
 * [skipColorTrims] to suppress color processing for monochrome styles.
 */
object MLPictureStyle {

    // Canon MakerNote Picture Style tag 0x000A values
    private const val TAG_STANDARD = 0x81
    private const val TAG_PORTRAIT = 0x82
    private const val TAG_LANDSCAPE = 0x83
    private const val TAG_NEUTRAL = 0x84
    private const val TAG_FAITHFUL = 0x85
    private const val TAG_MONOCHROME = 0x86
    private const val TAG_USER_DEF = 0x21
    private const val TAG_AUTO = 0x00

    /**
     * Detects Canon Picture Style from the MakerNote style tag value.
     *
     * @param cameraMake Camera manufacturer string from EXIF (e.g. "Canon").
     * @param makerNoteStyleTag Raw value from Canon MakerNote tag 0x000A,
     *        or null if absent.
     * @return Detected [CanonPictureStyle]. Returns [CanonPictureStyle.STANDARD]
     *         for non-Canon cameras or missing/unrecognized tag values.
     */
    fun detect(cameraMake: String, makerNoteStyleTag: Int?): CanonPictureStyle {
        // Non-Canon cameras → default to STANDARD
        if (!cameraMake.contains("Canon", ignoreCase = true)) {
            return CanonPictureStyle.STANDARD
        }

        // Missing tag → STANDARD
        if (makerNoteStyleTag == null) {
            return CanonPictureStyle.STANDARD
        }

        return when (makerNoteStyleTag) {
            TAG_STANDARD -> CanonPictureStyle.STANDARD
            TAG_PORTRAIT -> CanonPictureStyle.PORTRAIT
            TAG_LANDSCAPE -> CanonPictureStyle.LANDSCAPE
            TAG_NEUTRAL -> CanonPictureStyle.NEUTRAL
            TAG_FAITHFUL -> CanonPictureStyle.FAITHFUL
            TAG_MONOCHROME -> CanonPictureStyle.MONOCHROME
            TAG_USER_DEF -> CanonPictureStyle.USER_DEF
            TAG_AUTO -> CanonPictureStyle.AUTO
            else -> CanonPictureStyle.AUTO  // Unrecognized tag → AUTO
        }
    }

    /**
     * Returns the bias scale factor for the given Picture Style.
     *
     * Neutral and Faithful styles produce subtle adjustments (0.5),
     * all other styles receive full bias (1.0).
     *
     * @param style The detected [CanonPictureStyle].
     * @return Scale factor in [0.0..1.0] to multiply macro bias fields.
     */
    fun biasScale(style: CanonPictureStyle): Float {
        return when (style) {
            CanonPictureStyle.NEUTRAL,
            CanonPictureStyle.FAITHFUL -> 0.5f
            else -> 1.0f
        }
    }

    /**
     * Returns whether color trims should be skipped for this Picture Style.
     *
     * Only MONOCHROME skips color processing (saturation, tint, WB
     * adjustments from flash compensation and body WB trim).
     *
     * @param style The detected [CanonPictureStyle].
     * @return True if color-related processing should be skipped.
     */
    fun skipColorTrims(style: CanonPictureStyle): Boolean {
        return style == CanonPictureStyle.MONOCHROME
    }
}
