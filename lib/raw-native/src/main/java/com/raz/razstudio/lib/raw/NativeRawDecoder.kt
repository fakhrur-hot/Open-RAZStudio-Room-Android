package com.raz.razstudio.lib.raw

import java.nio.ByteBuffer

object NativeRawDecoder {
    init { System.loadLibrary("raw_decoder") }

    external fun decodeRawPreviewLinearIntoBuffer(
        data: ByteArray,
        outSize: IntArray,
        outBuffer: ByteBuffer,
        colorFringing: Int = 0,
    ): Boolean

    external fun decodeRawLinearIntoBuffer(
        data: ByteArray,
        outSize: IntArray,
        outBuffer: ByteBuffer,
        userQual: Int = 1,
        colorFringing: Int = 0,
    ): Boolean

    external fun decodeRawLinearIntoBufferRcd(
        data: ByteArray,
        outSize: IntArray,
        outBuffer: ByteBuffer,
        colorFringing: Int = 0,
    ): Boolean

    external fun readRawPreviewDimensions(data: ByteArray, outSize: IntArray): Boolean

    /**
     * Read the full raw Bayer plane plus camera metadata, for dual-ISO
     * detection. Allocates a `short[]` of length raw_width * raw_height
     * (~50 MB for a 25 MP CR2) and fills [outMeta] with
     * `[width, height, black, white, ax1, ay1, ax2, ay2]`.
     *
     * Returns null on any LibRaw failure (open / unpack / null raw_image).
     * Caller should drop the reference to the returned array immediately
     * after detection — it's a one-shot allocation, not a cache.
     */
    external fun readRawBayerForDualIso(data: ByteArray, outMeta: IntArray): ShortArray?

    external fun readCamMul(data: ByteArray): FloatArray?

    /** Returns a JSON string: {"camMake":…,"camModel":…,"lensModel":…,…} */
    external fun readLensMetadata(data: ByteArray): String?

    /**
     * Extract the camera's embedded preview JPEG from a RAW file's headers.
     * ~10ms vs ~1-2s for a full decode — used at file-open to show an
     * instant placeholder before the real Stage A pipeline catches up.
     *
     * Returns the JPEG bytes (typically 100-300 KB, sized 1620×1080 or
     * similar) decodable directly via [android.graphics.BitmapFactory.decodeByteArray],
     * or null if the file has no JPEG-format thumbnail (rare — some
     * Foveon cameras use uncompressed RGB thumbs).
     */
    external fun extractEmbeddedThumbnail(data: ByteArray): ByteArray?

    /**
     * Extract the embedded ICC color profile from a RAW file's headers,
     * if present. Some cameras (Canon CR3, some Nikon NEF) embed an ICC
     * profile describing the working space the camera intended for the
     * image. Tagging the exported JPEG with this profile lets color-
     * managed viewers (Safari/iOS Photos/Adobe apps) render correctly.
     *
     * Returns the raw ICC bytes (typically 500 B – 4 KB) ready to embed as
     * an APP2 "ICC_PROFILE" JPEG chunk, or null if the file has no
     * embedded profile (the majority of consumer RAW files).
     */
    external fun extractEmbeddedIccProfile(data: ByteArray): ByteArray?

    /**
     * Extract the camera's baked-in tone curve from a RAW file. Nikon NEF,
     * Sony ARW, and some Pentax PEF files carry a 65536-entry 16-bit LUT
     * representing the camera's intended brightness/contrast mapping
     * ("Picture Style" / "Creative Look" / "Picture Mode").
     *
     * Returns a 65536-length ShortArray (16-bit values; treat as unsigned)
     * mapping raw value [0..65535] → camera-output value [0..65535]. Null
     * when the file doesn't carry a curve (Canon CR3 typically doesn't —
     * Canon's picture-style runs in post, not via a baked curve).
     *
     * Used by Stage A's camera-style finish to match the in-camera JPG
     * tone curve exactly instead of approximating with synthetic ones.
     */
    external fun extractCameraToneCurve(data: ByteArray): ShortArray?

    /**
     * Extract the camera's stored WB preset coefficients from EXIF.
     *
     * Returns an IntArray laid out as repeated `[lightSource, R, G, B]`
     * tuples (one tuple per populated preset). lightSource is the EXIF
     * LightSource enum value:
     *   1   = Daylight
     *   2   = Fluorescent
     *   3   = Tungsten
     *   4   = Flash
     *   9   = Fine weather
     *  10   = Cloudy weather
     *  11   = Shade
     *  12-15 = Fluorescent variants (Day-W / Day-Wh / Cool-Wh / White-Fl)
     *  17-20 = Various tungsten / studio sources
     *  21   = D65
     *  22   = D50
     *  23   = ISO studio tungsten
     * (Full list: EXIF spec 2.32 §4.6.5 / TIFF tag 0x9208.)
     *
     * Empty array if the camera didn't store any presets (rare — most
     * bodies store ~6-12). Caller uses these as authoritative WB
     * multipliers for the corresponding preset names in the UI, instead
     * of synthetic values computed from colour temperature.
     */
    external fun extractWbPresets(data: ByteArray): IntArray

    /**
     * Whether the most recent decode operated on a pixel-shift / multi-shot
     * composite file (Sony A7R IV ARQ, Olympus E-M1X high-res, Panasonic
     * Lumix multi-shot, Pentax K-3 III pixel-shift). LibRaw 0.22 does not
     * expose the individual sub-exposures, so we decode such files as
     * single-shot and forfeit the resolution boost. Surfaced to the UI as
     * an info banner / Toast so users know to merge with the vendor's
     * software first if they want the full pixel-shift quality.
     *
     * Returns false (a) before any decode has happened, or (b) when the
     * most recent decode was a single-shot source.
     */
    external fun wasLastDecodePixelShift(): Boolean

    /**
     * Read the LibRaw return code from the most recent V1 decode call.
     * Use after any V1 entry point returns false to classify the failure:
     *
     *     val ok = NativeRawDecoder.decodeRawLinearIntoBuffer(...)
     *     if (!ok) {
     *         val status = DecodeStatus.fromLibRawCode(NativeRawDecoder.getLastLibRawError())
     *         when (status) {
     *             DecodeStatus.LibRawFileUnsupported -> showUnsupportedDialog()
     *             DecodeStatus.LibRawIoError -> retryOnce()
     *             ...
     *         }
     *     }
     *
     * Returns 0 (LIBRAW_SUCCESS) if no decode has been attempted yet.
     */
    external fun getLastLibRawError(): Int

    /**
     * File-path variants of the main decode entry points. Use these whenever
     * the source RAW exists on the local filesystem — they skip the ~30 MB
     * JVM ByteArray allocation + ~30 MB JNI byte-region copy, and let LibRaw
     * mmap the file via its bigfile_datastream. Net savings: ~60 MB transient
     * memory per decode, plus faster I/O on flash storage.
     *
     * Fall back to the ByteArray variants when reading from a Uri / SAF
     * source that doesn't expose a stable filesystem path.
     */
    external fun decodeRawLinearIntoBufferFromPath(
        filePath: String,
        outSize: IntArray,
        outBuffer: ByteBuffer,
        userQual: Int = 1,
        colorFringing: Int = 0,
    ): Boolean

    external fun decodeRawLinearIntoBufferRcdFromPath(
        filePath: String,
        outSize: IntArray,
        outBuffer: ByteBuffer,
        colorFringing: Int = 0,
    ): Boolean

    external fun decodeRawPreviewLinearIntoBufferFromPath(
        filePath: String,
        outSize: IntArray,
        outBuffer: ByteBuffer,
        colorFringing: Int = 0,
    ): Boolean

    /**
     * RayXie CA fringing correction — in-place on a uint16 BGR DirectByteBuffer.
     *
     * Port of github.com/RayXie29/Chromatic_aberration_correction (MIT-compatible).
     * Corrects lateral chromatic aberration by clamping R/B–G colour differences
     * at edges, in both horizontal and vertical directions.
     *
     * Call immediately after demosaic (before float16 conversion) on the same
     * DirectByteBuffer filled by decodeRawLinearIntoBufferRcd or decodeRawLinearIntoBuffer.
     *
     * @param bgrBuffer   DirectByteBuffer of uint16_t B,G,R per pixel (LibRaw BGR output).
     * @param width       Image width in pixels.
     * @param height      Image height in pixels.
     * @param threshold16 Green gradient threshold in uint16 scale [0–65535].
     *                    ~2000 (≈3% of range) works well for typical camera CA.
     */
    external fun correctFringing(
        bgrBuffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        threshold16: Int,
    )

    /**
     * Convert uint16 BGR DirectByteBuffer → float16 RGBA byte array.
     * Native fast path: ~10-20× faster than the Kotlin loop equivalent.
     * @param srcBuffer  DirectByteBuffer of uint16_t B,G,R per pixel.
     * @param dstArray   Pre-allocated byte[] of length pixelCount * 8.
     * @param pixelCount Number of pixels (width * height).
     */
    external fun convertUint16BgrToFloat16Rgba(
        srcBuffer: ByteBuffer,
        dstBuffer: ByteBuffer,
        pixelCount: Int,
    )
}
