/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.sony_sync.data

/**
 * Parsed A7 II PC-Remote state, built from a GetAllDevicePropData (0x9209) dump.
 * Raw values come straight off the wire; the *Label formatters are best-effort
 * (Sony encodings vary) and can be refined once verified on-device. Unknown
 * values fall back to "—" / the raw number, so the UI always renders something.
 */
data class SonyCameraState(
    val iso: Long? = null,
    val fNumber: Long? = null,        // F × 100 (400 = f/4.0)
    val shutter: Long? = null,        // (numerator << 16) | denominator
    val exposureBias: Long? = null,   // signed, 1/1000 EV
    val dro: Long? = null,            // 0x10 | level, 0x1F = Auto
    val exposureProgram: Long? = null,
    val whiteBalance: Long? = null,
    val colorTemp: Long? = null,      // Kelvin (custom WB)
    val wbAB: Long? = null,           // WB fine tune amber/blue (signed)
    val wbGM: Long? = null,           // WB fine tune green/magenta (signed)
    val imageSize: Long? = null,
    val focusMode: Long? = null,      // standard PTP FocusMode (0x500A)
    // Live movie-recording status. Populated ONLY once the real prop code is
    // confirmed from a capture (SonyPtpUsb.DPC_MOVIE_STATUS is null until then),
    // so it stays null and the UI never shows a fake REC badge. See [isRecording].
    val recordingStatus: Long? = null,
) {
    /**
     * True/false when the camera actually reports recording state, null when we
     * don't know yet (prop code unverified). The UI must treat null as "unknown"
     * and NOT show REC — better silent than lying. TODO(capture): once the movie
     * status DPC is identified from a `props changed` diff on movie start/stop,
     * set SonyPtpUsb.DPC_MOVIE_STATUS and refine the `!= 0` test to the real
     * recording sentinel.
     */
    val isRecording: Boolean? get() = recordingStatus?.let { it != 0L }

    /** Best-effort focus-mode label (standard PTP FocusMode enum). */
    val focusModeLabel: String get() = when (focusMode) {
        null -> "—"
        0x0001L -> "MF"
        0x0002L -> "AF-A"
        0x0003L -> "AF-S"
        0x0004L -> "AF-C"
        0x8004L, 0x8005L, 0x8006L -> "AF"   // Sony-specific AF sub-modes (best-effort)
        else -> "0x${focusMode.toString(16)}"
    }

    /** Best-effort WB label (Sony encodings vary; refine on-device). */
    val wbLabel: String get() = when (whiteBalance) {
        null -> "—"
        0x0002L -> "Auto"
        0x0001L -> "Manual"
        0x0004L -> "Fluor"
        0x0011L, 0x8012L -> colorTemp?.let { "${it}K" } ?: "Temp"
        0x8010L -> "Daylight"
        0x8006L -> "Flash"
        0x8020L, 0x8021L, 0x8022L -> "Custom"
        0x8002L -> "Shade"
        0x8003L -> "Cloudy"
        0x8004L -> "Tungsten"
        0x8007L -> "U.water"
        else -> "0x${whiteBalance.toString(16)}"
    }

    val isoLabel: String get() = when (val v = iso) {
        null -> "—"
        0L, 0xFFFFFFL, 0xFFFFFFFFL -> "AUTO"
        else -> v.toString()
    }

    val fLabel: String get() = fNumber?.let {
        val f = it / 100.0
        if (f == f.toLong().toDouble()) "f/${f.toLong()}" else "f/%.1f".format(f)
    } ?: "—"

    val shutterLabel: String get() {
        val v = shutter ?: return "—"
        val num = ((v ushr 16) and 0xFFFF).toInt()
        val den = (v and 0xFFFF).toInt()
        if (num == 0 || den == 0) return "—"
        return if (num >= den) {
            val s = num.toDouble() / den
            if (s == s.toLong().toDouble()) "${s.toLong()}\"" else "%.1f\"".format(s)
        } else "1/${den / num}"
    }

    val evLabel: String get() = exposureBias?.let {
        // EV is a SIGNED 1/1000-EV value; the prop table delivers it as an
        // unsigned 16-bit word, so -0.3EV (raw -333 = 0xFEB3) would misread as
        // +65.2. Re-interpret the low 16 bits as signed.
        val signed = it.toShort().toInt()
        "%+.1f".format(signed / 1000.0)
    } ?: "—"

    val droLabel: String get() = dro?.let {
        val lvl = (it and 0x0F).toInt()
        when {
            it == 0x1FL -> "DRO Auto"
            it == 0x01L -> "DRO Off"
            lvl in 1..5 -> "DRO Lv$lvl"
            else -> "DRO"
        }
    } ?: "—"

    val modeLabel: String get() = when (exposureProgram) {
        null -> "—"
        0x1L -> "M"
        0x2L -> "P"
        0x3L -> "A"
        0x4L -> "S"
        else -> "#${exposureProgram}"
    }

    /** Fold a raw propCode→value map into a typed state. */
    companion object {
        fun from(props: Map<Int, Long>): SonyCameraState = SonyCameraState(
            iso            = props[SonyPtpUsb.DPC_ISO],
            fNumber        = props[SonyPtpUsb.DPC_FNUMBER],
            shutter        = props[SonyPtpUsb.DPC_SHUTTER],
            exposureBias   = props[SonyPtpUsb.DPC_EXPOSURE_BIAS],
            dro            = props[SonyPtpUsb.DPC_DRO],
            exposureProgram= props[SonyPtpUsb.DPC_EXPOSURE_PROGRAM],
            whiteBalance   = props[SonyPtpUsb.DPC_WHITE_BALANCE],
            colorTemp      = props[SonyPtpUsb.DPC_COLOR_TEMP],
            wbAB           = props[SonyPtpUsb.DPC_WB_AB],
            wbGM           = props[SonyPtpUsb.DPC_WB_GM],
            imageSize      = props[SonyPtpUsb.DPC_IMAGE_SIZE],
            focusMode      = props[SonyPtpUsb.DPC_FOCUS_MODE],
            // Only read when the code is verified (null DPC → never populated).
            recordingStatus= SonyPtpUsb.DPC_MOVIE_STATUS?.let { props[it] },
        )
    }
}
