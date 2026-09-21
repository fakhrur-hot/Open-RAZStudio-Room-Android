/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.hardware.HardwareBuffer

/**
 * Thin Kotlin facade over `v3_jni.cpp`. One method per JNI entry point listed
 * in Plan.md §5. Methods whose milestone hasn't shipped yet throw
 * [NotImplementedError].
 *
 * The native library is the existing `raw_decoder.so` (one shared library
 * for the whole RAW feature). v3 JNI symbols are added alongside the v1/v2
 * exports — no second .so to load. The `System.loadLibrary("raw_decoder")`
 * happens via the v1 bridge during early app init.
 */
object RawV3Engine {

    init {
        // Belt-and-suspenders: ensure the native lib is loaded before the
        // first v3 call. Idempotent — Android's loader skips duplicates.
        runCatching { System.loadLibrary("raw_decoder") }
    }

    /** Version probe — proves the JNI binding resolves at runtime. */
    fun version(): String =
        runCatching { nativeVersion() }.getOrElse { "unbound: ${it.message}" }

    @JvmStatic
    private external fun nativeVersion(): String

    /**
     * Extract the embedded JPEG thumbnail from a RAW file in ~10–30 ms.
     * Returns null if the camera has no embedded JPEG (rare), or on any error.
     * Decode with BitmapFactory.decodeByteArray on the Kotlin side.
     */
    fun extractEmbeddedThumbnail(filePath: String): ByteArray? =
        runCatching { nativeExtractEmbeddedThumbnail(filePath) }.getOrNull()

    @JvmStatic
    private external fun nativeExtractEmbeddedThumbnail(filePath: String): ByteArray?

    // ─────────────────────────────────────────────────────────────────────────
    //  Stage A (M2) — implemented
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Result of a Stage A decode. Fields mirror the JSON the JNI side returns.
     * On failure, [success] is false and [error] carries the diagnostic.
     */
    data class StageAResult(
        val success: Boolean,
        val width: Int,
        val height: Int,
        val orientation: Int,
        val cameraMake: String,
        val cameraModel: String,
        val lensMake: String,
        val lensModel: String,
        val lensId: Int,
        val colorTemperature: Int,
        val iso: Int,
        val shutterSpeed: Float,
        val aperture: Float,
        val focalLength: Float,
        val dateTimeOriginal: String,
        val error: String?,
        /** Auto-resolved dual-demosaic contrast threshold; -1f if not applicable. */
        val dualContrastThreshold: Float = -1f,
    )

    /**
     * Synchronously run Stage A. Caller is responsible for:
     *   • the absolute path of the source RAW being readable,
     *   • the parent of [outTifPath] existing,
     *   • running on a background dispatcher (LibRaw is CPU-heavy).
     */
    fun stageADecode(
        rawFilePath: String,
        outTifPath: String,
        options: RawV3WorkspaceOptions,
        hdrModelData: ByteArray? = null,
        shadowModelData: ByteArray? = null,
        isLinearRaw: Boolean = false,
        liftMap: FloatArray? = null,
        liftTau: Float = 0.7f,
    ): StageAResult {
        val json = nativeStageADecode(
            rawFilePath,
            outTifPath,
            options.demosaicAlgorithm,
            options.highlightMode,
            options.wbSource.wireValue,
            options.exposureShift,
            options.fbddNoise,
            options.nrEnabled,
            options.nrLuma,
            options.nrChroma,
            options.caCorrectionEnabled,
            options.lensfunCameraId,
            options.lensfunLensId,
            options.blackLevelDelta,
            options.whiteLevelDelta,
            options.clipThreshold,
            options.dualContrastThreshold,
            options.dualAutoContrast,
            hdrModelData,
            shadowModelData,
            options.enhanceEnabled,
            options.enhanceWindowSize,
            options.enhanceNoiseVariance8bit / (255f * 255f),
            options.enhanceUsmRadius,
            options.enhanceUsmAmount,
            options.enhanceUsmThreshold8bit / 255f,
            options.enhanceSkipDenoise,
            options.enhanceSkipSharpen,
            options.enhanceUsmEdgeThreshold,
            options.enhanceGuidedFilter,
            options.claheHighlightsBoost,
            options.adjustMaximumThr,
            isLinearRaw,
            options.lensfunDbDir,
            options.lensfunFocalOverrideMm,
            liftMap,
            liftTau,
        )
        return parseStageAJson(json)
    }

    /**
     * Result of [lensfunProbe]: the RAW's own EXIF identity plus the Lensfun
     * database resolution done with the SAME strict matcher the Stage A
     * correction uses — WYSIWYG for the import screen's Lens Correction card.
     * [matchedCameraModel]/[matchedLensModel] empty = no confident match →
     * the import will run WITHOUT correction.
     */
    data class LensfunProbeResult(
        val exifCameraMaker: String,
        val exifCameraModel: String,
        val exifLens: String,
        val focalMm: Float,
        val aperture: Float,
        val matchedCameraModel: String,
        val matchedCropFactor: Float,
        val matchedLensModel: String,
    ) {
        val fullyResolved: Boolean
            get() = matchedCameraModel.isNotBlank() && matchedLensModel.isNotBlank()
    }

    /** Metadata-only Lensfun probe (fast; no unpack). Null = not a readable RAW. */
    fun lensfunProbe(rawFilePath: String, dbDir: String): LensfunProbeResult? {
        val arr = runCatching { nativeLensfunProbe(rawFilePath, dbDir) }.getOrNull()
            ?: return null
        if (arr.size < 8) return null
        return LensfunProbeResult(
            exifCameraMaker    = arr[0],
            exifCameraModel    = arr[1],
            exifLens           = arr[2],
            focalMm            = arr[3].toFloatOrNull() ?: 0f,
            aperture           = arr[4].toFloatOrNull() ?: 0f,
            matchedCameraModel = arr[5],
            matchedCropFactor  = arr[6].toFloatOrNull() ?: 0f,
            matchedLensModel   = arr[7],
        )
    }

    @JvmStatic
    private external fun nativeLensfunProbe(rawFilePath: String, dbDir: String): Array<String>?

    /**
     * Strings-only Lensfun resolution for callers that already hold the EXIF
     * identity (workspace selector). Returns Triple(matchedCameraModel,
     * cropFactor, matchedLensModel); empty camera/lens = no confident match.
     * Null = database unavailable.
     */
    fun lensfunMatch(
        dbDir: String,
        camMaker: String,
        camModel: String,
        lensMaker: String,
        lensModel: String,
    ): Triple<String, Float, String>? {
        val arr = runCatching {
            nativeLensfunMatch(dbDir, camMaker, camModel, lensMaker, lensModel)
        }.getOrNull() ?: return null
        if (arr.size < 3) return null
        return Triple(arr[0], arr[1].toFloatOrNull() ?: 0f, arr[2])
    }

    /**
     * How much to trust an automatic lens match. Mirrors `LfaConfidence` in
     * lensfun_android.h — the native matcher decides this, because it is the
     * one that Stage A actually applies. Deriving it a second time here would
     * eventually disagree with the pixels.
     */
    enum class LensMatchConfidence {
        /** Below the strict gate. Never offer, never apply. */
        None,
        /** Plausible only. Show the brand/range fallback list. */
        Low,
        /** Likely. Show the shortlist and let the user confirm. */
        Medium,
        /** Safe to apply without asking. */
        High;

        companion object {
            fun fromOrdinal(i: Int): LensMatchConfidence =
                entries.getOrElse(i) { None }
        }
    }

    /**
     * Same as [lensfunMatch] but also reports the confidence, so callers can
     * distinguish "apply this silently" from "ask the user first".
     */
    data class LensfunMatchResult(
        val cameraModel: String,
        val cropFactor: Float,
        val lensModel: String,
        val confidence: LensMatchConfidence,
    ) {
        val autoApplicable: Boolean
            get() = lensModel.isNotBlank() && confidence == LensMatchConfidence.High
    }

    fun lensfunMatchDetailed(
        dbDir: String,
        camMaker: String,
        camModel: String,
        lensMaker: String,
        lensModel: String,
    ): LensfunMatchResult? {
        val arr = runCatching {
            nativeLensfunMatch(dbDir, camMaker, camModel, lensMaker, lensModel)
        }.getOrNull() ?: return null
        if (arr.size < 3) return null
        return LensfunMatchResult(
            cameraModel = arr[0],
            cropFactor  = arr[1].toFloatOrNull() ?: 0f,
            lensModel   = arr[2],
            confidence  = LensMatchConfidence.fromOrdinal(
                arr.getOrNull(3)?.toIntOrNull() ?: 0),
        )
    }

    @JvmStatic
    private external fun nativeLensfunMatch(
        dbDir: String, camMaker: String, camModel: String,
        lensMaker: String, lensModel: String,
    ): Array<String>?

    /**
     * One ranked lens candidate, with the per-criterion breakdown behind it.
     * [scoreBreakdown] is the diagnostics payload: it says WHY this candidate
     * placed where it did, in the matcher's own priority order.
     */
    data class LensfunCandidate(
        val model: String,
        val cropFactor: Float,
        val mount: String,
        val confidence: LensMatchConfidence,
        val scoreBreakdown: LensScoreBreakdown,
    )

    /** Per-criterion scores, in the matcher's priority order (see LfaLensScore). */
    data class LensScoreBreakdown(
        val nameTier: Int = 0,
        val sensorFormat: Int = 0,
        val mount: Int = 0,
        val brand: Int = 0,
        val focalRange: Int = 0,
        val aperture: Int = 0,
        val stabiliser: Int = 0,
        val motor: Int = 0,
    ) {
        /** Human-readable reasons this candidate is imperfect, worst first. */
        fun concerns(): List<String> = buildList {
            when (sensorFormat) {
                0 -> add("calibrated for a smaller sensor than this body")
                1 -> add("calibrated for a larger format (usable)")
            }
            if (nameTier <= 2) add("matched on numbers only, not the lens name")
            else if (nameTier == 3) add("database name is less specific than EXIF")
            if (focalRange == 0) add("focal range differs")
            if (aperture == 0) add("maximum aperture differs")
            if (stabiliser == 0) add("stabiliser designation differs")
            if (motor == 0) add("focus-motor designation differs")
            if (brand == 0) add("brand not corroborated by EXIF")
        }
    }

    /**
     * Ranked lens shortlist from the SAME native scorer Stage A applies. Use
     * this whenever the match is not [LensMatchConfidence.High] — the product
     * rule is that a low-confidence guess must be confirmed, not applied.
     *
     * [adaptedMode] drops the mount requirement for a lens on a dumb adapter
     * (which cannot report a matching mount) while keeping the sensor-format
     * preference.
     */
    fun lensfunRankLenses(
        dbDir: String,
        camMaker: String,
        camModel: String,
        lensMaker: String,
        lensModel: String,
        adaptedMode: Boolean = false,
        maxOut: Int = 3,
    ): List<LensfunCandidate> {
        val rows = runCatching {
            nativeLensfunRankLenses(dbDir, camMaker, camModel, lensMaker,
                                    lensModel, adaptedMode, maxOut)
        }.getOrNull().orEmpty()
        return rows.mapNotNull { row ->
            val p = row.split('\t')
            if (p.size < 4) return@mapNotNull null
            val b = p.getOrNull(4)?.split(',')?.mapNotNull(String::toIntOrNull).orEmpty()
            LensfunCandidate(
                model      = p[0],
                cropFactor = p[1].toFloatOrNull() ?: 0f,
                mount      = p[2],
                confidence = LensMatchConfidence.fromOrdinal(p[3].toIntOrNull() ?: 0),
                scoreBreakdown = LensScoreBreakdown(
                    nameTier     = b.getOrElse(0) { 0 },
                    sensorFormat = b.getOrElse(1) { 0 },
                    mount        = b.getOrElse(2) { 0 },
                    brand        = b.getOrElse(3) { 0 },
                    focalRange   = b.getOrElse(4) { 0 },
                    aperture     = b.getOrElse(5) { 0 },
                    stabiliser   = b.getOrElse(6) { 0 },
                    motor        = b.getOrElse(7) { 0 },
                ),
            )
        }
    }

    @JvmStatic
    private external fun nativeLensfunRankLenses(
        dbDir: String, camMaker: String, camModel: String,
        lensMaker: String, lensModel: String,
        adaptedMode: Boolean, maxOut: Int,
    ): Array<String>?

    /** One camera body from the Lensfun database (manual-override picker). */
    data class LensfunCamera(
        val maker: String,
        val model: String,
        val cropFactor: Float,
        val alias: String = "",   // marketing name, e.g. "Alpha 7 II" for ILCE-7M2
    )

    /**
     * One lens from the Lensfun database (manual-override picker).
     *
     * [cropFactor] and [mount] are carried because 95 models appear in the DB
     * more than once, differing ONLY by calibration format — without them the
     * picker shows the user the same name two or three times with no way to
     * tell which is which.
     */
    data class LensfunLens(
        val maker: String,
        val model: String,
        val cropFactor: Float = 0f,
        val mount: String = "",
    )

    @Volatile private var camerasCache: Pair<String, List<LensfunCamera>>? = null
    @Volatile private var lensesCache: Pair<String, List<LensfunLens>>? = null

    /** All camera bodies in the DB, parsed once per process (for autocomplete). */
    fun lensfunCameras(dbDir: String): List<LensfunCamera> {
        camerasCache?.let { if (it.first == dbDir) return it.second }
        val rows = runCatching { nativeLensfunListCameras(dbDir) }.getOrNull().orEmpty()
        val parsed = rows.mapNotNull { row ->
            val p = row.split('\t')
            if (p.size < 3) null
            else LensfunCamera(p[0], p[1], p[2].toFloatOrNull() ?: 0f, p.getOrNull(3).orEmpty())
        }
        camerasCache = dbDir to parsed
        return parsed
    }

    /** All lenses in the DB, parsed once per process (for autocomplete). */
    fun lensfunLenses(dbDir: String): List<LensfunLens> {
        lensesCache?.let { if (it.first == dbDir) return it.second }
        val rows = runCatching { nativeLensfunListLenses(dbDir) }.getOrNull().orEmpty()
        val parsed = rows.mapNotNull { row ->
            val p = row.split('\t')
            if (p.size < 2) null
            else LensfunLens(
                maker      = p[0],
                model      = p[1],
                cropFactor = p.getOrNull(2)?.toFloatOrNull() ?: 0f,
                mount      = p.getOrNull(3).orEmpty(),
            )
        }
        lensesCache = dbDir to parsed
        return parsed
    }

    @JvmStatic
    private external fun nativeLensfunListCameras(dbDir: String): Array<String>?

    @JvmStatic
    private external fun nativeLensfunListLenses(dbDir: String): Array<String>?

    @JvmStatic
    private external fun nativeStageADecode(
        rawFilePath: String,
        outTifPath: String,
        demosaicAlgorithm: Int,
        highlightMode: Int,
        wbSource: Int,
        exposureShift: Float,
        fbddNoise: Int,
        nrEnabled: Boolean,
        nrLuma: Int,
        nrChroma: Int,
        caCorrectionEnabled: Boolean,
        lensfunCameraId: String,
        lensfunLensId: String,
        blackLevelDelta: Float,
        whiteLevelDelta: Float,
        clipThreshold: Float,
        dualContrastThreshold: Float,
        dualAutoContrast: Boolean,
        hdrModelData: ByteArray?,
        shadowModelData: ByteArray?,
        enhanceEnabled: Boolean,
        enhanceWindowSize: Int,
        enhanceNoiseVar: Float,       // pre-scaled to [0,1]² domain
        enhanceUsmRadius: Float,
        enhanceUsmAmount: Float,
        enhanceUsmThreshold: Float,   // pre-scaled to [0,1] domain
        enhanceSkipDenoise: Boolean,
        enhanceSkipSharpen: Boolean,
        enhanceUsmEdgeThreshold: Float,
        enhanceGuidedFilter: Boolean,
        claheHighlightsBoost: Float,
        adjustMaximumThr: Float,
        isLinearRaw: Boolean,
        lensfunDbDir: String,
        lensfunFocalOverrideMm: Float,
        liftMap: FloatArray?,
        liftTau: Float,
    ): String

    /**
     * Synthetic Stage A for already-decoded sources (JPEG/PNG/WebP/…). The
     * caller decodes the file to an EXIF-oriented ARGB_8888 bitmap, extracts
     * its RGBA bytes, and we write the same Stage A BigTIFF a RAW decode would
     * produce — so the rest of the v3 pipeline (Stage B/C, Detail tab,
     * Auto-Exposure, export) works unchanged. [rgba] is row-major
     * width*height*4 bytes (R,G,B,A).
     */
    fun stageAFromBitmap(
        rgba: ByteArray,
        width: Int,
        height: Int,
        orientation: Int,
        outTifPath: String,
    ): StageAResult = parseStageAJson(
        nativeStageAFromRgba(rgba, width, height, orientation, outTifPath)
    )

    @JvmStatic
    private external fun nativeStageAFromRgba(
        rgba: ByteArray,
        width: Int,
        height: Int,
        orientation: Int,
        outTifPath: String,
    ): String

    /**
     * Bake a per-pixel luma-scale map into an existing Stage A FP16 TIFF in place
     * (preserves chroma + 16-bit depth). [scale] is row-major [scaleW × scaleH]
     * and is bilinearly resampled to the TIFF dims, so it may be lower-res.
     * Used for the "denoised baseline baked at import" path. Returns true on success.
     */
    fun applyLumaScaleToStageA(
        tifPath: String,
        scale: FloatArray,
        scaleW: Int,
        scaleH: Int,
    ): Boolean = nativeApplyLumaScaleToStageA(tifPath, scale, scaleW, scaleH)

    @JvmStatic
    private external fun nativeApplyLumaScaleToStageA(
        tifPath: String,
        scale: FloatArray,
        scaleW: Int,
        scaleH: Int,
    ): Boolean

    /**
     * Bake a per-channel 256×3 tone-curve LUT into the FP16 Stage A TIFF in
     * place. [lut768] is 256 entries × 3 channels interleaved (R,G,B per input
     * index) — the same layout as [CameraColorMatch.matchPerChannel] /
     * RawV3ToneCurve.buildLut. Sampled with bilinear interpolation (matching the
     * Stage C toneCurveLut tap); alpha preserved. Returns true on success.
     *
     * Used for the "Camera Color Profile baked at import" path so the Stage B
     * preview and Stage C export inherit the in-camera colour directly.
     */
    fun applyToneCurveToStageA(tifPath: String, lut768: ByteArray): Boolean {
        require(lut768.size >= 768) { "lut768 must be ≥768 bytes, got ${lut768.size}" }
        return nativeApplyToneCurveToStageA(tifPath, lut768)
    }

    @JvmStatic
    private external fun nativeApplyToneCurveToStageA(
        tifPath: String,
        lut: ByteArray,
    ): Boolean

    /**
     * Apply Lensfun correction in place to a non-RAW (JPEG/PNG/TIFF) Stage-A
     * TIFF — the synthetic path's equivalent of the RAW Lensfun block. Returns
     * true only when a confident camera+lens match was found and a correction
     * was actually applied; false (A.tif untouched) otherwise.
     */
    fun applyLensfunToStageA(
        tifPath: String,
        camMaker: String, camModel: String,
        lensMaker: String, lensModel: String,
        focalMm: Float, aperture: Float,
        lensfunDbDir: String,
    ): Boolean = nativeApplyLensfunToStageA(
        tifPath, camMaker, camModel, lensMaker, lensModel, focalMm, aperture, lensfunDbDir)

    /**
     * Apply Lensfun geometric correction IN PLACE to an ARGB_8888 [bitmap] — the
     * live workspace-selector preview path. The bitmap MUST be mutable
     * (ARGB_8888) or the native lock fails and this returns false. Reuses the
     * same strict matcher + FP16 correction core as [applyLensfunToStageA], so
     * what the preview shows is exactly what the import bakes. Returns false
     * (bitmap untouched) when there's no confident match / no usable calibration.
     */
    fun applyLensfunToBitmap(
        bitmap: android.graphics.Bitmap,
        camMaker: String, camModel: String,
        lensMaker: String, lensModel: String,
        focalMm: Float, aperture: Float,
        lensfunDbDir: String,
    ): Boolean = runCatching {
        nativeApplyLensfunToBitmap(
            bitmap, camMaker, camModel, lensMaker, lensModel, focalMm, aperture, lensfunDbDir)
    }.getOrDefault(false)

    @JvmStatic
    private external fun nativeApplyLensfunToBitmap(
        bitmap: android.graphics.Bitmap,
        camMaker: String, camModel: String,
        lensMaker: String, lensModel: String,
        focalMm: Float, aperture: Float,
        lensfunDbDir: String,
    ): Boolean

    /**
     * Rayxie chromatic-aberration correction applied in place to a non-RAW
     * (JPEG/PNG/TIFF) Stage-A TIFF. The RayXie29 algorithm is RGB-domain
     * (edge-based R-G / B-G colour-difference clipping), so it works on a
     * decoded JPEG — no CFA data required. [threshold] is the green-gradient
     * magnitude in uint16 units; 2000 (~3% of range) matches the v2 pipeline.
     */
    fun applyRayxieCaToStageA(tifPath: String, threshold: Int = 2000): Boolean =
        runCatching { nativeApplyRayxieCaToStageA(tifPath, threshold) }.getOrDefault(false)

    @JvmStatic
    private external fun nativeApplyRayxieCaToStageA(
        tifPath: String,
        threshold: Int,
    ): Boolean

    @JvmStatic
    private external fun nativeApplyLensfunToStageA(
        tifPath: String,
        camMaker: String, camModel: String,
        lensMaker: String, lensModel: String,
        focalMm: Float, aperture: Float,
        lensfunDbDir: String,
    ): Boolean

    /**
     * Parse a LUT file with the SAME native parser the GL preview uses —
     * handles ASCII `.cube`, 1D `.cube` and the binary smol-cube `.smcube`.
     * Returns `[size, domainMin×3, domainMax×3, rgb…]` (red-fastest) or null.
     * See [RawV3LutStore.parseCubeFile], which prefers this over its own
     * ASCII-only reader so preview and export always load the same table.
     */
    fun parseLutFile(path: String): FloatArray? =
        runCatching { nativeParseLutFile(path) }.getOrNull()

    @JvmStatic
    private external fun nativeParseLutFile(path: String): FloatArray?

    /**
     * One-line diagnostic of the last lens correction run on THIS thread
     * (profile, confidence, format match, dist/tca/vig source, zoom). Call
     * immediately after [applyLensfunToStageA] on the same thread.
     */
    fun lensfunLastReport(): String = runCatching { nativeLensfunLastReport() }.getOrDefault("lens=?")

    @JvmStatic
    private external fun nativeLensfunLastReport(): String

    // ─────────────────────────────────────────────────────────────────────────
    //  Tiny single-purpose JSON parser. Stage A's reply has a fixed schema
    //  (see v3_jni.cpp). We extract by string scan rather than pulling in a
    //  full JSON dependency.
    // ─────────────────────────────────────────────────────────────────────────
    private fun parseStageAJson(json: String): StageAResult {
        fun bool(key: String): Boolean? =
            Regex("\"$key\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
        fun int(key: String): Int? =
            Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
        fun flt(key: String): Float? =
            Regex("\"$key\"\\s*:\\s*(-?\\d+\\.?\\d*)").find(json)?.groupValues?.get(1)?.toFloatOrNull()
        fun str(key: String): String? =
            Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")?.replace("\\\\", "\\")

        return StageAResult(
            success         = bool("success") ?: false,
            width           = int("width") ?: 0,
            height          = int("height") ?: 0,
            orientation     = int("orientation") ?: 1,
            cameraMake      = str("cameraMake") ?: "",
            cameraModel     = str("cameraModel") ?: "",
            lensMake        = str("lensMake") ?: "",
            lensModel       = str("lensModel") ?: "",
            lensId          = int("lensId") ?: 0,
            colorTemperature = int("colorTemperature") ?: 0,
            iso             = int("iso") ?: 0,
            shutterSpeed    = flt("shutterSpeed") ?: 0f,
            aperture        = flt("aperture") ?: 0f,
            focalLength     = flt("focalLength") ?: 0f,
            dateTimeOriginal = str("dateTimeOriginal") ?: "",
            error           = str("error"),
            dualContrastThreshold = flt("dualContrastThreshold") ?: -1f,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Adobe XMP sidecar parser (M5.5)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse an Adobe Camera Raw / Lightroom sidecar `.xmp` (or in-memory XMP
     * string) and return a 25-float blob ready to splat into
     * [ShaderParams.withXmp]. Returns a zero-filled array if parsing failed
     * or no recognised CRS tags were found (in that case `blob[0] == 0`,
     * meaning the overlay is OFF).
     */
    fun parseAdobeXmp(pathOrString: String, isPath: Boolean): FloatArray {
        return runCatching { nativeParseAdobeXmp(pathOrString, isPath) }
            .getOrDefault(FloatArray(ShaderParams.XMP_BLOB_FLOAT_COUNT))
    }

    @JvmStatic
    private external fun nativeParseAdobeXmp(pathOrString: String, isPath: Boolean): FloatArray

    // ─────────────────────────────────────────────────────────────────────────
    //  Stage B / C / Renderer — placeholders until their milestones
    // ─────────────────────────────────────────────────────────────────────────

    data class StageBResult(
        val success: Boolean,
        val cancelled: Boolean = false,
        val width: Int,
        val height: Int,
        val error: String?,
    )

    /**
     * Lanczos-3 downsample of the Stage A TIFF at [stageATifPath] into [ahb].
     * Caller must allocate [ahb] with format `R16G16B16A16_FLOAT`, usage
     * `CPU_WRITE_RARELY | GPU_SAMPLED_IMAGE`, width ≥ [targetW], height ≥
     * [targetH]. Synchronous; call from Dispatchers.IO.
     *
     * Stage B produces an ungraded preview AHB. The GL renderer applies the
     * full grade live; this keeps preview and export on a single code path
     * and avoids double-grading.
     */
    fun stageBDownsample(
        stageATifPath: String,
        ahb: HardwareBuffer,
        targetW: Int,
        targetH: Int,
        params: FloatArray? = null,
        subjectMask: FloatArray? = null,
        subjectMaskSize: Int = 0,
        // Mask height; 0 = square (legacy 320²). Pass the refined mask's height
        // so preview spatial passes sample the same mask the GL grade does.
        subjectMaskH: Int = 0,
        // Cancellation token: byte[1] shared with the native downsample loop.
        // Set cancelFlag[0] = 1 from any thread to abort early.
        cancelFlag: ByteArray? = null,
    ): StageBResult {
        val json = nativeStageBDownsample(
            stageATifPath, ahb, targetW, targetH, params, subjectMask, subjectMaskSize, subjectMaskH, cancelFlag,
        )
        return parseStageBJson(json)
    }

    @JvmStatic
    private external fun nativeStageBDownsample(
        stageATifPath: String,
        ahb: HardwareBuffer,
        targetW: Int,
        targetH: Int,
        params: FloatArray?,
        subjectMask: FloatArray?,
        subjectMaskSize: Int,
        subjectMaskH: Int,
        cancelFlag: ByteArray?,
    ): String

    /**
     * Option A fast path: copy the pristine (downsample-only) FP16 from [srcAhb]
     * into [dstAhb] and re-apply ONLY the spatial pre-pass (CLAHE/NR/Detail) in
     * place. Skips the disk decode + Lanczos downsample — used when only spatial
     * sliders change. Both AHBs must be R16G16B16A16_FLOAT, same dims.
     * Synchronous; call from Dispatchers.IO/Default.
     */
    fun stageBApplySpatialToAhb(
        srcAhb: HardwareBuffer,
        dstAhb: HardwareBuffer,
        params: FloatArray? = null,
        subjectMask: FloatArray? = null,
        subjectMaskSize: Int = 0,
        subjectMaskH: Int = 0,
    ): StageBResult {
        val json = nativeStageBApplySpatialToAhb(srcAhb, dstAhb, params, subjectMask, subjectMaskSize, subjectMaskH)
        return parseStageBJson(json)
    }

    @JvmStatic
    private external fun nativeStageBApplySpatialToAhb(
        srcAhb: HardwareBuffer,
        dstAhb: HardwareBuffer,
        params: FloatArray?,
        subjectMask: FloatArray?,
        subjectMaskSize: Int,
        subjectMaskH: Int,
    ): String

    private fun parseStageBJson(json: String): StageBResult {
        fun bool(key: String): Boolean? =
            Regex("\"$key\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
        fun int(key: String): Int? =
            Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
        fun str(key: String): String? =
            Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)
        return StageBResult(
            success   = bool("success")   ?: false,
            cancelled = bool("cancelled") ?: false,
            width     = int("width")  ?: 0,
            height    = int("height") ?: 0,
            error     = str("error"),
        )
    }

    /**
     * Snapshot [ahb]'s pixels to [outputPath] (see `stage_b_serialize.h` for
     * the on-disk format). Called by the Apply button so the editor can free
     * its 64 MB GPU AHardwareBuffer before navigating to the RAW Export
     * screen. Synchronous; call from Dispatchers.IO.
     */
    fun stageBSerialize(ahb: HardwareBuffer, outputPath: String): Boolean {
        return runCatching { nativeStageBSerialize(ahb, outputPath) }
            .getOrDefault(false)
    }

    @JvmStatic
    private external fun nativeStageBSerialize(ahb: HardwareBuffer, outputPath: String): Boolean

    /**
     * Refine a soft saliency mask via fast-guided-filter against a linear-light
     * luma guide. Both inputs are float planes of [width]×[height] in [0..1].
     * [radius] is the box-filter half-window; [eps] the variance regulariser;
     * [scale] selects fast-guided-filter downsampling (>=1; 4-8 is typical).
     * Returns a fresh refined matte the same size as the inputs, or null.
     */
    fun guidedFilterRefine(
        guideLinearLuma: FloatArray,
        mask: FloatArray,
        width: Int,
        height: Int,
        radius: Int = 32,
        eps: Float = 1e-3f,
        scale: Int = 4,
    ): FloatArray? = runCatching {
        nativeGuidedFilterRefine(guideLinearLuma, mask, width, height, radius, eps, scale)
    }.getOrNull()

    @JvmStatic
    private external fun nativeGuidedFilterRefine(
        guide: FloatArray,
        mask: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        eps: Float,
        scale: Int,
    ): FloatArray?

    /**
     * Read a previously-serialized Stage B snapshot from [path] and fill
     * [ahb] (caller-allocated, RGBA_F16, ≥ source dims) with its pixels.
     * Returns `(width, height)` on success or null on failure.
     */
    fun stageBLoadSerialized(path: String, ahb: HardwareBuffer): Pair<Int, Int>? {
        val json = runCatching { nativeStageBLoadSerialized(path, ahb) }
            .getOrNull() ?: return null
        val w = Regex("\"width\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
        val h = Regex("\"height\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
        return if (w != null && h != null && Regex("\"success\"\\s*:\\s*true").containsMatchIn(json))
            w to h else null
    }

    @JvmStatic
    private external fun nativeStageBLoadSerialized(path: String, ahb: HardwareBuffer): String

    /**
     * Read the width/height of a serialized Stage B snapshot without
     * allocating an AHardwareBuffer.
     */
    fun stageBGetDims(path: String): Pair<Int, Int>? {
        val json = runCatching { nativeStageBGetDims(path) }.getOrNull() ?: return null
        val w = Regex("\"width\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
        val h = Regex("\"height\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
        return if (w != null && h != null && Regex("\"success\"\\s*:\\s*true").containsMatchIn(json))
            w to h else null
    }

    @JvmStatic
    private external fun nativeStageBGetDims(path: String): String

    /**
     * Render a serialized Stage B snapshot into [bitmap] (must be ARGB_8888
     * and match the snapshot's dimensions). Returns true on success.
     */
    fun stageBLoadToBitmap(path: String, bitmap: android.graphics.Bitmap): Boolean {
        return runCatching { nativeStageBLoadToBitmap(path, bitmap) }.getOrDefault(false)
    }

    @JvmStatic
    private external fun nativeStageBLoadToBitmap(path: String, bitmap: android.graphics.Bitmap): Boolean

    enum class StageCFormat(val nativeValue: Int) {
        Tiff16(0),
        // M9: 8-bit hybrid path. The NDK fills an RGBA_8888 Android Bitmap
        // and Kotlin compresses via Bitmap.compress / HeifWriter. PNG-16
        // + AVIF (NDK libpng / libavif) remain a follow-up.
        Bitmap8888(1),
    }

    data class StageCResult(
        val success: Boolean,
        val karisBloomFallback: Boolean = false,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val outputPath: String?,
        val error: String?,
    )

    /**
     * Read the Stage A TIFF at [stageATifPath], apply [actionParams] +
     * optional [lutData] per pixel via the shared apply_macro kernel, and
     * write the result to [outputPath]. Synchronous; call from Dispatchers.IO.
     *
     * M8: format must be `Tiff16`. M9 adds the lossy codecs.
     *
     * @param actionParams 57-float ShaderParams blob (from `ShaderParams.toFloatArray()`).
     * @param lutData      Optional 3D LUT: size³ × 3 floats, RGB. Pass null /
     *                     [lutSize] = 0 to skip LUT lookup even when
     *                     `actionParams[29]` (lutEnabled) is on.
     */
    fun stageCExport(
        stageATifPath: String,
        outputPath: String,
        format: StageCFormat,
        targetW: Int,
        targetH: Int,
        actionParams: FloatArray,
        lutData: FloatArray? = null,
        lutSize: Int = 0,
        lutDomainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
        lutDomainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
        subjectMask: FloatArray? = null,
        subjectMaskSize: Int = 0,
        // Mask height; 0 = square. The editor passes the 1024px refined
        // (image-aspect) mask — the same texture the GL preview samples.
        subjectMaskH: Int = 0,
        subjectMaskRectU0: Float = 0f,
        subjectMaskRectV0: Float = 0f,
        subjectMaskRectU1: Float = 1f,
        subjectMaskRectV1: Float = 1f,
        // Orton/bokeh sky-attenuation — Cityscapes max(sky, terrain), same
        // letterbox rect as subjectMask. Null = gate off (matches GL).
        attenMask: FloatArray? = null,
        attenMaskSize: Int = 0,
        attenMaskH: Int = 0,
        // Depth→CoC (DA-V2) — same plane GL unit-10 .g uses. Null = depth-off disc.
        depthMap: FloatArray? = null,
        depthMapW: Int = 0,
        depthMapH: Int = 0,
        focusDepth: Float = 0.5f,
        // Up to 4 brush-mask layer alphas ([0,1]), each maskLayerW×maskLayerH,
        // concatenated (layer i at offset i·W·H). Null = no brush masks.
        maskLayers: FloatArray? = null,
        maskLayerW: Int = 0,
        maskLayerH: Int = 0,
        maskLayerCount: Int = 0,
        // Tone Curve LUT: 256 RGB8 texels (768 bytes). Null = identity.
        toneCurveLut: ByteArray? = null,
        // Optional ICC profile bytes to embed as TIFF tag 34675. Null = no ICC.
        iccProfile: ByteArray? = null,
    ): StageCResult {
        val json = nativeStageCExport(
            stageATifPath, outputPath,
            format.nativeValue,
            targetW, targetH,
            actionParams,
            lutData, lutSize, lutDomainMin, lutDomainMax,
            subjectMask, subjectMaskSize, subjectMaskH,
            subjectMaskRectU0, subjectMaskRectV0, subjectMaskRectU1, subjectMaskRectV1,
            attenMask, attenMaskSize, attenMaskH,
            depthMap, depthMapW, depthMapH, focusDepth,
            maskLayers, maskLayerW, maskLayerH, maskLayerCount,
            toneCurveLut,
            iccProfile,
        )
        return parseStageCJson(json)
    }

    private fun parseStageCJson(json: String): StageCResult {
        fun bool(key: String): Boolean? =
            Regex("\"$key\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
        fun int(key: String): Int? =
            Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
        fun lng(key: String): Long? =
            Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
        fun str(key: String): String? =
            Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)
        return StageCResult(
            success            = bool("success") ?: false,
            karisBloomFallback = bool("karisBloomFallback") ?: false,
            width              = int("width")  ?: 0,
            height             = int("height") ?: 0,
            durationMs         = lng("durationMs") ?: 0L,
            outputPath         = str("outputPath"),
            error              = str("error"),
        )
    }

    @JvmStatic
    private external fun nativeStageCExport(
        stageATifPath: String,
        outputPath: String,
        format: Int,
        targetW: Int,
        targetH: Int,
        actionParams: FloatArray,
        lutData: FloatArray?,
        lutSize: Int,
        lutDomainMin: FloatArray,
        lutDomainMax: FloatArray,
        subjectMask: FloatArray?,
        subjectMaskSize: Int,
        subjectMaskH: Int,
        subjectMaskRectU0: Float,
        subjectMaskRectV0: Float,
        subjectMaskRectU1: Float,
        subjectMaskRectV1: Float,
        attenMask: FloatArray?,
        attenMaskSize: Int,
        attenMaskH: Int,
        depthMap: FloatArray?,
        depthMapW: Int,
        depthMapH: Int,
        focusDepth: Float,
        maskLayers: FloatArray?,
        maskLayerW: Int,
        maskLayerH: Int,
        maskLayerCount: Int,
        toneCurveLut: ByteArray?,
        iccProfile: ByteArray?,
    ): String

    /**
     * M9 — render Stage A through the apply_macro kernel into [bitmap]
     * (must be RGBA_8888 at exactly the source's full-res dims). Caller
     * compresses the filled Bitmap via Bitmap.compress / HeifWriter to
     * produce JPEG / WebP / PNG-8 / HEIC. Synchronous; call from
     * Dispatchers.IO.
     */
    fun stageCToBitmap(
        stageATifPath: String,
        bitmap: android.graphics.Bitmap,
        actionParams: FloatArray,
        lutData: FloatArray? = null,
        lutSize: Int = 0,
        lutDomainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
        lutDomainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
    ): StageCResult {
        val json = nativeStageCToBitmap(
            stageATifPath, bitmap, actionParams, lutData, lutSize, lutDomainMin, lutDomainMax,
        )
        return parseStageCJson(json)
    }

    @JvmStatic
    private external fun nativeStageCToBitmap(
        stageATifPath: String,
        bitmap: android.graphics.Bitmap,
        actionParams: FloatArray,
        lutData: FloatArray?,
        lutSize: Int,
        lutDomainMin: FloatArray,
        lutDomainMax: FloatArray,
    ): String

    /**
     * Read the Stage C RGB16 TIFF intermediate and write a 16-bit lossless
     * output file (PNG-16 or TIFF-16), applying crop, area-filter resize,
     * Laplacian post-sharpen, and optional watermark composite.
     *
     * @param rgb16TifPath   Stage C intermediate TIFF path.
     * @param outputPath     Destination file.
     * @param outputFormat   0 = PNG-16, 1 = TIFF-16.
     * @param cropX/Y/W/H    Crop rect in source pixels (0,0,0,0 = no crop).
     * @param dstW/dstH      Resize target (0,0 = keep post-crop dims).
     * @param sharpenAmount  Laplacian strength [0,1] (ShaderParams slot 379).
     * @param watermarkBitmap Optional ARGB_8888 Bitmap to composite; must be
     *                        dstW×dstH.  Pass null for no watermark.
     */
    fun encode16Bit(
        rgb16TifPath: String,
        outputPath: String,
        outputFormat: Int,
        cropX: Int, cropY: Int, cropW: Int, cropH: Int,
        dstW: Int, dstH: Int,
        sharpenAmount: Float,
        watermarkBitmap: android.graphics.Bitmap?,
        iccProfile: ByteArray? = null,
    ): StageCResult {
        val json = nativeEncode16Bit(
            rgb16TifPath, outputPath, outputFormat,
            cropX, cropY, cropW, cropH,
            dstW, dstH, sharpenAmount, watermarkBitmap,
            iccProfile,
        )
        return parseStageCJson(json)
    }

    @JvmStatic
    private external fun nativeEncode16Bit(
        rgb16TifPath: String,
        outputPath: String,
        outputFormat: Int,
        cropX: Int, cropY: Int, cropW: Int, cropH: Int,
        dstW: Int, dstH: Int,
        sharpenAmount: Float,
        watermarkBitmap: android.graphics.Bitmap?,
        iccProfile: ByteArray?,
    ): String

    /**
     * Same 16-bit crop/resize/sharpen/watermark pass as [encode16Bit], but
     * renders the result into an [outputBitmap] configured as [RGBA_F16]
     * instead of writing a file. Used for 16-bit HEIC/AVIF export where the
     * downstream encoder (`HeifCoder`) accepts high-bit-depth Bitmaps.
     *
     * @param outputBitmap must be RGBA_F16 and dstW×dstH (or post-crop dims
     *                     when dstW/dstH are 0).
     */
    fun encode16BitToBitmap(
        rgb16TifPath: String,
        outputBitmap: android.graphics.Bitmap,
        cropX: Int, cropY: Int, cropW: Int, cropH: Int,
        dstW: Int, dstH: Int,
        sharpenAmount: Float,
        watermarkBitmap: android.graphics.Bitmap?,
    ): StageCResult {
        val json = nativeEncode16BitToBitmap(
            rgb16TifPath, outputBitmap,
            cropX, cropY, cropW, cropH,
            dstW, dstH, sharpenAmount, watermarkBitmap,
        )
        return parseStageCJson(json)
    }

    @JvmStatic
    private external fun nativeEncode16BitToBitmap(
        rgb16TifPath: String,
        outputBitmap: android.graphics.Bitmap,
        cropX: Int, cropY: Int, cropW: Int, cropH: Int,
        dstW: Int, dstH: Int,
        sharpenAmount: Float,
        watermarkBitmap: android.graphics.Bitmap?,
    ): String

    /**
     * Phase 3 Checkpoint 1 — headless GL smoke test. Fills [bitmap]
     * (must be ARGB_8888) with opaque red via a pbuffer EGL context,
     * proving the offscreen GL pipeline reaches the save buffer. Returns
     * true on success, false on any EGL/GL failure. Debug-only.
     */
    fun offscreenClearTest(bitmap: android.graphics.Bitmap): Boolean =
        nativeOffscreenClearTest(bitmap)

    @JvmStatic
    private external fun nativeOffscreenClearTest(
        bitmap: android.graphics.Bitmap,
    ): Boolean

    /**
     * Apply PREQ-Port highlight recovery in-place to an AHB using the cached
     * FP16 Stage A pixel buffer.
     */
    fun recoverHighlights(fp16Bytes: ByteArray, ahb: android.hardware.HardwareBuffer, width: Int, height: Int, recovery: Float) =
        nativeRecoverHighlights(fp16Bytes, ahb, width, height, recovery)

    private external fun nativeRecoverHighlights(fp16Bytes: ByteArray, ahb: android.hardware.HardwareBuffer, width: Int, height: Int, recovery: Float)

    /**
     * Phase 3 Checkpoint 2 — render a UV gradient via a minimal shader
     * in the pbuffer context. Validates shader compile / VAO /
     * drawArrays before plugging the full per-pixel kernel.
     */
    fun offscreenUvTest(bitmap: android.graphics.Bitmap): Boolean =
        nativeOffscreenUvTest(bitmap)

    @JvmStatic
    private external fun nativeOffscreenUvTest(
        bitmap: android.graphics.Bitmap,
    ): Boolean

    /**
     * Canvas-matched GPU export. Grades Stage A through the same uber-shader
     * + Karis bloom stack as the Export preview canvas, writing RGBA8888 into
     * [outputBitmap]. Bitmap may be Stage A size OR a smaller working size
     * (JPG/WebP at [targetLongSide] after crop accounting) — native
     * bilinear-subsamples Stage A from the mmap when smaller. Returns false
     * → caller falls back to CPU Stage C.
     */
    fun renderGradedOffscreen(
        stageATifPath: String,
        actionParams: FloatArray,
        outputBitmap: android.graphics.Bitmap,
        lutData: FloatArray? = null,
        lutSize: Int = 0,
        lutDomainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
        lutDomainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
        toneCurveLut: ByteArray? = null,
        subjectMask: FloatArray? = null,
        subjectMaskW: Int = 0,
        subjectMaskH: Int = 0,
        subjectMaskRect: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
        // Brush-mask layers (same packing as stageCExport): up to 4 planes of
        // [0,1] floats, each maskLayerW×maskLayerH, concatenated. Required for
        // Mask-tab preview=export on the preferred JPG/WebP GPU path.
        maskLayers: FloatArray? = null,
        maskLayerW: Int = 0,
        maskLayerH: Int = 0,
        maskLayerCount: Int = 0,
        // Unit-10 RG8: sky/terrain atten (.r) + relative depth (.g) for CoC bokeh.
        attenMask: FloatArray? = null,
        attenMaskW: Int = 0,
        attenMaskH: Int = 0,
        depthMap: FloatArray? = null,
        depthMapW: Int = 0,
        depthMapH: Int = 0,
        focusDepth: Float = 0.5f,
    ): Boolean = runCatching {
        nativeRenderGradedOffscreen(
            stageATifPath, actionParams,
            lutData, lutSize, lutDomainMin, lutDomainMax,
            toneCurveLut,
            subjectMask, subjectMaskW, subjectMaskH, subjectMaskRect,
            maskLayers, maskLayerW, maskLayerH, maskLayerCount,
            attenMask, attenMaskW, attenMaskH,
            depthMap, depthMapW, depthMapH, focusDepth,
            outputBitmap,
        )
    }.onFailure { e ->
        android.util.Log.e(
            "RawV3Engine",
            "renderGradedOffscreen threw: ${e::class.simpleName}: ${e.message}",
            e,
        )
    }.getOrDefault(false)

    @JvmStatic
    private external fun nativeRenderGradedOffscreen(
        stageATifPath: String,
        actionParams: FloatArray,
        lutData: FloatArray?,
        lutSize: Int,
        lutDomainMin: FloatArray?,
        lutDomainMax: FloatArray?,
        toneCurveLut: ByteArray?,
        subjectMask: FloatArray?,
        subjectMaskW: Int,
        subjectMaskH: Int,
        subjectMaskRect: FloatArray?,
        brushMasks: FloatArray?,
        brushMaskW: Int,
        brushMaskH: Int,
        brushMaskCount: Int,
        attenMask: FloatArray?,
        attenMaskW: Int,
        attenMaskH: Int,
        depthMap: FloatArray?,
        depthMapW: Int,
        depthMapH: Int,
        focusDepth: Float,
        outputBitmap: android.graphics.Bitmap,
    ): Boolean

    /**
     * Write a Stage A BigTIFF resized to [destW]×[destH] (never upscales).
     * Used by the CPU Stage C fallback so JPG/WebP grades at export size.
     */
    fun downsampleStageATiff(
        srcPath: String,
        dstPath: String,
        destW: Int,
        destH: Int,
    ): Boolean = runCatching {
        nativeDownsampleStageATiff(srcPath, dstPath, destW, destH)
    }.getOrDefault(false)

    fun bakeVintageOverlay(film: Boolean, rgba: ByteArray, width: Int, height: Int) {
        nativeBakeVintageOverlay(film, rgba, width, height)
    }

    @JvmStatic
    private external fun nativeBakeVintageOverlay(
        film: Boolean,
        rgba: ByteArray,
        width: Int,
        height: Int,
    )

    @JvmStatic
    private external fun nativeDownsampleStageATiff(
        srcPath: String,
        dstPath: String,
        destW: Int,
        destH: Int,
    ): Boolean

    // ─────────────────────────────────────────────────────────────────────────
    //  VNG dual-decode self-tests (Item 2, Pass 1 harness)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Test 1 — Liveness: demosaic a synthetic 256×256 CFA gradient and verify
     * all output pixels are finite and in [0,1]. Returns "PASS" or a "FAIL:…"
     * description. Runs synchronously (fast — no file I/O).
     */
    fun vngSelfTestLiveness(): String =
        runCatching { nativeVngSelfTestLiveness() }.getOrElse { "FAIL: ${it.message}" }

    @JvmStatic private external fun nativeVngSelfTestLiveness(): String

    /**
     * Test 2 — Strip consistency: runs VNG twice on the same synthetic input
     * and checks RMS between the two outputs is below 1e-4 (proves determinism
     * and no ring-buffer seam). Returns "PASS: RMS=…" or "FAIL:…".
     */
    fun vngSelfTestStrips(): String =
        runCatching { nativeVngSelfTestStrips() }.getOrElse { "FAIL: ${it.message}" }

    @JvmStatic private external fun nativeVngSelfTestStrips(): String

    /**
     * Test 3 — Dual-decode pixel stats: run Stage A with `RAZ_AMAZE_VNG`
     * (`demosaicAlgorithm = -3`) on [rawPath] and return a JSON blob with the
     * output dimensions and auto-resolved contrast threshold. Used for offline
     * regression comparison against an AMaZE-only baseline.
     */
    fun vngSelfTestDualStats(rawPath: String, outTifPath: String): String =
        runCatching { nativeVngSelfTestDualStats(rawPath, outTifPath) }
            .getOrElse { "{\"error\":\"${it.message}\"}" }

    @JvmStatic private external fun nativeVngSelfTestDualStats(
        rawPath: String,
        outTifPath: String,
    ): String

    // ─────────────────────────────────────────────────────────────────────────
    //  Film Simulation (Tasks 7.1–7.3)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Apply a film simulation profile to a linear Stage A BigTIFF.
     *
     * Reads [inputPath] (pristine linear TIFF, read-only), applies the
     * selected profile's color matrix + gamma + grain, and writes the result
     * to [outputPath] (newly created). Uses LibRaw native injection for
     * profiles 2,3,5,6,7 and a post-decode pixel loop for 1,4.
     *
     * @param inputPath    Pristine linear Stage A BigTIFF (read-only).
     * @param outputPath   Destination for the film-sim baked TIFF (created).
     * @param profileIndex C++ ProfileIndex enum value [0..15]; 0 = pass-through.
     * @param grainAmount  Grain amplitude [0.0, 0.07]; 0.0 = grain pass skipped.
     * @return true on success; false if [outputPath] was not created.
     */
    fun applyFilmSim(
        inputPath: String,
        outputPath: String,
        profileIndex: Int,
        grainAmount: Float,
    ): Boolean {
        require(profileIndex in 0..15) { "profileIndex must be 0..15, got $profileIndex" }
        require(grainAmount in 0f..0.10f) { "grainAmount must be in [0, 0.10], got $grainAmount" }
        return nativeApplyFilmSim(inputPath, outputPath, profileIndex, grainAmount)
    }

    @JvmStatic
    private external fun nativeApplyFilmSim(
        inputPath: String,
        outputPath: String,
        profileIndex: Int,
        grainAmount: Float,
    ): Boolean

    @JvmStatic
    fun resetAdjustmentDebugLog() = nativeResetAdjustmentDebugLog()

    @JvmStatic
    private external fun nativeResetAdjustmentDebugLog()

    // ─── Debug Exports (dual-demosaic maps) ──────────────────────────────────
    fun exportDebugMap(type: Int, path: String): Boolean =
        runCatching { nativeExportDebugMap(type, path) }.getOrDefault(false)

    @JvmStatic
    private external fun nativeExportDebugMap(type: Int, path: String): Boolean

    // ─── AhbMaskLoader (zero-copy NPU bridge) ────────────────────────────────
    fun createAhbMaskLoader(): Long = nativeCreateAhbMaskLoader()

    fun destroyAhbMaskLoader(ptr: Long) = nativeDestroyAhbMaskLoader(ptr)

    fun importAhbMask(ptr: Long, buffer: HardwareBuffer, eglDisplay: Long, generation: Long): Int =
        nativeImportAhbMask(ptr, buffer, eglDisplay, generation)

    @JvmStatic
    private external fun nativeCreateAhbMaskLoader(): Long

    @JvmStatic
    private external fun nativeDestroyAhbMaskLoader(ptr: Long)

    @JvmStatic
    private external fun nativeImportAhbMask(
        ptr: Long, buffer: HardwareBuffer, eglDisplay: Long, generation: Long
    ): Int
}
