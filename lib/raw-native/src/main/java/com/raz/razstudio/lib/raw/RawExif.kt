/*
 * RawExif.kt
 *
 * v2 §6 / Phase 2.4 — structured EXIF result returned by every v2 decode entry point
 * (and by the cheap `readExif` probe that runs before any pipeline starts).
 *
 * Two halves:
 *  • Structured fields — consumed by the RAWEditor info sheet and the RAW Export page.
 *  • [rawExifBlob] — opaque TIFF/EXIF IFD byte block reserved for the lossless save
 *    passthrough. Phase 1 returns an empty array here; the lossless copy is added by
 *    ExifWriter on the Kotlin side once the save pipelines come online.
 *
 * **JNI constructor signature contract** — the native side in raw_decoder_v2.cpp's
 * buildRawExif() invokes this primary constructor via `GetMethodID("<init>", ...)`. If
 * the parameter order, count, or types change, update the signature string there too —
 * the JNI helper logs a "signature mismatch" error if the lookup fails.
 */

package com.raz.razstudio.lib.raw

/**
 * Full EXIF + camera-calibration block surfaced from LibRaw during decode.
 *
 * @property make                    Camera manufacturer string (LibRaw imgdata.idata.make).
 * @property model                   Camera body model.
 * @property lensMake                Lens manufacturer string. May be empty for fixed-lens cameras.
 * @property lensModel               Lens model description.
 * @property iso                     Reported ISO speed.
 * @property shutterUs               Shutter time in microseconds (lossless `1 / shutterSec`).
 * @property apertureF               Aperture as f-number (e.g. 2.8).
 * @property focalMm                 Actual lens focal length in mm.
 * @property focalMm35eq             35-mm equivalent focal length, if reported by the body.
 * @property flash                   Raw flash flag from EXIF (0 when not exposed by LibRaw).
 * @property meteringMode            EXIF metering mode (LibRaw shootinginfo.MeteringMode).
 * @property exposureProgram         EXIF exposure program (LibRaw shootinginfo.ExposureProgram).
 * @property exposureCompensationEv  Exposure compensation in EV. 0 when not reported.
 * @property gpsLat                  Latitude in decimal degrees, signed; 0.0 when no GPS.
 * @property gpsLon                  Longitude in decimal degrees, signed; 0.0 when no GPS.
 * @property gpsAlt                  Altitude in metres, signed; 0.0 when no GPS.
 * @property gpsTimestampS           GPS time-of-day in seconds; -1 when not reported.
 * @property timestampEpochS         Unix epoch capture timestamp (LibRaw imgother.timestamp).
 * @property orientationCcwQuarters  LibRaw flip count, 0/1/2/3. UI uses this to render the
 *                                   info sheet preview at the same rotation the editor canvas
 *                                   already applies.
 * @property cameraWhiteBalanceAsShot Per-channel as-shot WB multipliers, length 4.
 * @property colorMatrixCamToXyz     LibRaw cam_xyz, flattened row-major (3×3 = 9 floats).
 * @property blackLevelsPerChannel   Per-channel black levels, length 4.
 * @property whiteLevel              Sensor saturation point (`imgdata.color.maximum`).
 * @property lensId                  Canon lens_id from MakerNotes (LibRaw lens.makernotes.LensID).
 *                                   0 when not reported or non-Canon.
 * @property colorTemperature        As-shot color temperature in Kelvin from Canon MakerNotes
 *                                   (LibRaw color.WBCT_Coeffs[0][0]). 0 when not available.
 * @property softwareTag             EXIF Software tag from the source file. ExifWriter
 *                                   overwrites this on save with our own version string.
 * @property rawExifBlob             Reserved for the lossless TIFF/EXIF IFD passthrough.
 *                                   Empty in Phase 1; ExifWriter will populate it in Phase B
 *                                   when the source file is re-read for byte-perfect copy.
 */
data class RawExif(
    val make: String,
    val model: String,
    val lensMake: String,
    val lensModel: String,
    val iso: Int,
    val shutterUs: Long,
    val apertureF: Float,
    val focalMm: Float,
    val focalMm35eq: Float,
    val flash: Int,
    val meteringMode: Int,
    val exposureProgram: Int,
    val exposureCompensationEv: Float,
    val gpsLat: Double,
    val gpsLon: Double,
    val gpsAlt: Double,
    val gpsTimestampS: Long,
    val timestampEpochS: Long,
    val orientationCcwQuarters: Int,
    val cameraWhiteBalanceAsShot: FloatArray,
    val colorMatrixCamToXyz: FloatArray,
    val blackLevelsPerChannel: IntArray,
    val whiteLevel: Int,
    val lensId: Int,
    val colorTemperature: Int,
    val softwareTag: String,
    val rawExifBlob: ByteArray,
) {
    /** True when LibRaw parsed a GPS block from the source file. */
    val hasGps: Boolean
        get() = gpsLat != 0.0 || gpsLon != 0.0 || gpsAlt != 0.0

    /** Convenience: shutter as fractional-seconds string e.g. "1/250 s" or "2.5 s". */
    val shutterDisplay: String
        get() {
            if (shutterUs <= 0L) return ""
            val seconds = shutterUs / 1_000_000.0
            return if (seconds < 1.0) {
                "1/${(1.0 / seconds).toInt()} s"
            } else {
                "%.1f s".format(seconds)
            }
        }

    // Manual equals/hashCode because data class default trips up on the arrays.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawExif) return false
        return make == other.make &&
            model == other.model &&
            lensMake == other.lensMake &&
            lensModel == other.lensModel &&
            iso == other.iso &&
            shutterUs == other.shutterUs &&
            apertureF == other.apertureF &&
            focalMm == other.focalMm &&
            focalMm35eq == other.focalMm35eq &&
            flash == other.flash &&
            meteringMode == other.meteringMode &&
            exposureProgram == other.exposureProgram &&
            exposureCompensationEv == other.exposureCompensationEv &&
            gpsLat == other.gpsLat &&
            gpsLon == other.gpsLon &&
            gpsAlt == other.gpsAlt &&
            gpsTimestampS == other.gpsTimestampS &&
            timestampEpochS == other.timestampEpochS &&
            orientationCcwQuarters == other.orientationCcwQuarters &&
            cameraWhiteBalanceAsShot.contentEquals(other.cameraWhiteBalanceAsShot) &&
            colorMatrixCamToXyz.contentEquals(other.colorMatrixCamToXyz) &&
            blackLevelsPerChannel.contentEquals(other.blackLevelsPerChannel) &&
            whiteLevel == other.whiteLevel &&
            lensId == other.lensId &&
            colorTemperature == other.colorTemperature &&
            softwareTag == other.softwareTag &&
            rawExifBlob.contentEquals(other.rawExifBlob)
    }

    override fun hashCode(): Int {
        var r = make.hashCode()
        r = 31 * r + model.hashCode()
        r = 31 * r + lensMake.hashCode()
        r = 31 * r + lensModel.hashCode()
        r = 31 * r + iso
        r = 31 * r + shutterUs.hashCode()
        r = 31 * r + apertureF.hashCode()
        r = 31 * r + focalMm.hashCode()
        r = 31 * r + focalMm35eq.hashCode()
        r = 31 * r + flash
        r = 31 * r + meteringMode
        r = 31 * r + exposureProgram
        r = 31 * r + exposureCompensationEv.hashCode()
        r = 31 * r + gpsLat.hashCode()
        r = 31 * r + gpsLon.hashCode()
        r = 31 * r + gpsAlt.hashCode()
        r = 31 * r + gpsTimestampS.hashCode()
        r = 31 * r + timestampEpochS.hashCode()
        r = 31 * r + orientationCcwQuarters
        r = 31 * r + cameraWhiteBalanceAsShot.contentHashCode()
        r = 31 * r + colorMatrixCamToXyz.contentHashCode()
        r = 31 * r + blackLevelsPerChannel.contentHashCode()
        r = 31 * r + whiteLevel
        r = 31 * r + lensId
        r = 31 * r + colorTemperature
        r = 31 * r + softwareTag.hashCode()
        r = 31 * r + rawExifBlob.contentHashCode()
        return r
    }
}
