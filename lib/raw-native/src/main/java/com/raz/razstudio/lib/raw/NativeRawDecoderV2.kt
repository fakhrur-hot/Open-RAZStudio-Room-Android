/*
 * NativeRawDecoderV2.kt
 *
 * v2 native bindings — raw-pipeline-v2 Phase 2.1–2.3.
 *
 * Stage of the rewrite:
 *   ✅ Kotlin types — DecodeResult sealed class, CancelFlag handle.
 *   ✅ JNI bindings — four decode entry points + cancel flag lifecycle.
 *   ⬜ Implementations — native side returns RDV2_ERR_NOT_IMPLEMENTED until Phase 1
 *      tasks 1.1–1.8 land the shared decode_core() body.
 *
 * The legacy [NativeRawDecoder] surface stays untouched so the current preview/full-res
 * paths keep working while the v2 dispatcher is built. The coordinator (Phase 5) is what
 * routes between V1 and V2; this file does not touch the legacy code at all.
 */

package com.raz.razstudio.lib.raw

import java.io.Closeable

/**
 * v2 §A — cancellable native flag handle. Allocate one per decode invocation; pass the
 * pointer to the matching decode method; flip [cancel] to ask LibRaw to return at the
 * next row; always [close] to free the underlying int32.
 *
 * The handle is intentionally _not_ AutoCloseable-via-`use {}` only — pipelines hold it
 * across coroutine suspension points to flip cancellation from another thread.
 */
class CancelFlag : Closeable {
    @Volatile
    private var nativePtr: Long = NativeRawDecoderV2.allocCancelFlag()

    val isOpen: Boolean get() = nativePtr != 0L

    /** Address passed to JNI decode methods. Returns 0 when the flag is already closed. */
    fun ptr(): Long = nativePtr

    /** Signal cancellation. Idempotent and thread-safe. No-op after [close]. */
    fun cancel() {
        val p = nativePtr
        if (p != 0L) NativeRawDecoderV2.cancel(p)
    }

    /** Free the native int32. Subsequent calls are no-ops. */
    override fun close() {
        val p = nativePtr
        if (p == 0L) return
        nativePtr = 0L
        NativeRawDecoderV2.freeCancelFlag(p)
    }
}

/**
 * v2 §1.6 / Phase 2.2 — status protocol shared with raw_decoder_v2.cpp. Integer codes match
 * the `RawDecodeStatus` enum on the native side ordinal-for-ordinal so that the JNI surface
 * stays exception-free (LibRaw's allocation-retry loop would otherwise leak native heap if
 * an exception unwound across the boundary).
 */
enum class DecodeStatus(val code: Int) {
    Ok(0),
    NotImplemented(1),
    DecodeTimeout(2),
    OutOfMemory(3),
    SilentZero(4),
    InsufficientBuffer(5),
    OutputWrite(6),
    Cancelled(7),
    DcpParse(8),

    // ── LibRaw-specific classifications (codes 100+) ───────────────────
    // Mapped from LIBRAW_* error codes returned by open_file / open_buffer
    // / unpack / dcraw_process. Surfaced via [NativeRawDecoder.getLastLibRawError]
    // after any V1 entry point returns false. Lets the UI distinguish
    // "unsupported camera" (actionable: tell user) from "I/O died"
    // (actionable: retry) from "no thumbnail" (silently skip preview).
    LibRawFileUnsupported(100),
    LibRawNoThumbnail(101),
    LibRawIoError(102),
    LibRawOutOfOrder(103),
    LibRawDataError(104),
    LibRawCancelled(105),
    LibRawBadCrop(106),
    LibRawFloatingPointUnsupported(107),  // our own guard
    LibRawOther(199),

    Unknown(99),
    ;

    companion object {
        fun fromCode(code: Int): DecodeStatus =
            entries.firstOrNull { it.code == code } ?: Unknown

        /**
         * Translate a LibRaw native return code into a [DecodeStatus].
         * Negative values are LibRaw-specific; positive values are
         * system errno. LIBRAW_SUCCESS (0) maps to [Ok].
         *
         * From libraw_const.h:
         *   -1  = LIBRAW_FILE_UNSUPPORTED
         *   -2  = LIBRAW_REQUEST_FOR_NONEXISTENT_IMAGE
         *   -3  = LIBRAW_NO_THUMBNAIL (recoverable — file decodes fine)
         *   -4  = LIBRAW_OUT_OF_ORDER_CALL (caller bug)
         *   -5  = LIBRAW_DATA_ERROR
         *   -6  = LIBRAW_IO_ERROR
         *   -7  = LIBRAW_CANCELLED_BY_CALLBACK
         *   -8  = LIBRAW_BAD_CROP
         *   -100 = our own LIBRAW_FLOATING_POINT_UNSUPPORTED guard
         */
        fun fromLibRawCode(code: Int): DecodeStatus = when (code) {
            0    -> Ok
            -1   -> LibRawFileUnsupported
            -2,
            -3   -> LibRawNoThumbnail
            -4   -> LibRawOutOfOrder
            -5   -> LibRawDataError
            -6   -> LibRawIoError
            -7   -> LibRawCancelled
            -8   -> LibRawBadCrop
            -100 -> LibRawFloatingPointUnsupported
            else -> if (code > 0) LibRawIoError else LibRawOther
        }
    }
}

/**
 * Output dimensions of a decode result, in pixels.
 *
 * Width/height are post-orientation — i.e. they already reflect any 90/180/270 rotation
 * the camera applied. Pipelines compare against the original RAW dimensions on the area
 * (`width * height`) so portrait/landscape swap does not trigger a false "too small" reject.
 */
data class DecodedDimensions(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height.toLong()
}

/**
 * v2 §2.1 — sealed result of a decode call.
 *
 * Pipelines pattern-match: [InMem] for Pipeline 1 (half-res preview ARGB) and [Mapped] for
 * Pipelines 2, 3, 4 (full-res mapped, either ARGB or float16 depending on the entry point).
 * [Failure] carries the matching [DecodeStatus] for the caller's error surface.
 */
sealed interface DecodeResult {
    data class InMem(val argb: IntArray, val dims: DecodedDimensions) : DecodeResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is InMem) return false
            return dims == other.dims && argb.contentEquals(other.argb)
        }
        override fun hashCode(): Int = 31 * argb.contentHashCode() + dims.hashCode()
    }

    /**
     * Pixels were written to the file descriptor passed in by the caller. The caller owns
     * the fd and the mmap-backing file lifecycle; this result just confirms what was
     * written and where the dimensions ended up.
     */
    data class Mapped(val dims: DecodedDimensions) : DecodeResult

    data class Failure(val status: DecodeStatus) : DecodeResult
}

/**
 * v2 §1.3 / Phase 2.1 — parameters common to every v2 decode entry point.
 *
 * Bundled into a single struct so the JNI argument list of each entry point stays readable
 * and so adding a parameter later (e.g. a new highlight-mode flag) doesn't churn four call
 * sites. The struct is value-passed; nothing is held across the JNI boundary except via the
 * explicit `cancelFlag` handle.
 */
data class DecodeParams(
    val filePath: String,
    /** LibRaw user_qual id (0=LINEAR, 1=VNG, 2=PPG, 3=AHD, 4=DCB, 5=AHD_MOD, 11=LMMSE,
     *  12=AMAZE, -1=RAZ_AMAZE/RCD, -2=RAZ_AMAZE_DUAL). */
    val userQual: Int,
    /** 0=sRGB, 1=DCI-P3 — matches the index of RawColorSpace values used today. */
    val gamut: Int,
    /** 0=Off, 1=Clip, 2=Reconstruct — see HighlightRecoveryMode. */
    val highlightMode: Int,
    val nrEnabled: Boolean,
    val nrLuma: Int,
    val nrChroma: Int,
    /** Identifier into DcpRegistry; pass "auto" to let native pick the best match. */
    val dcpProfileId: String,
)

/**
 * v2 §2.1 — bindings to raw_decoder_v2.cpp.
 *
 * Each `decode*` function delegates to the JNI export of the same name. The Kotlin façade
 * wraps the integer status protocol in a typed [DecodeResult] so callers don't deal with
 * raw codes.
 *
 * Mapped-output methods take a (fd, offset, capacity) triple: the caller is expected to
 * `MemoryFile` or `ParcelFileDescriptor` the destination scratch file and pass the resulting
 * fd. The native side does its own `mmap` over that fd — Kotlin doesn't keep a mapping.
 */
object NativeRawDecoderV2 {
    init { System.loadLibrary("raw_decoder") }

    // ── Cancel flag lifecycle ─────────────────────────────────────────────────
    @JvmStatic external fun allocCancelFlag(): Long
    @JvmStatic external fun cancel(flagPtr: Long)
    @JvmStatic external fun freeCancelFlag(flagPtr: Long)

    /**
     * v2 §6 / Phase 1.8 — cheap EXIF probe: opens the file via path, runs `open_file +
     * unpack` only (no demosaic, no post-processing), returns the structured fields plus
     * an empty [RawExif.rawExifBlob]. Costs ~50 ms on the supported-floor device, so it
     * can run as part of openRawFile before the Preview pipeline starts.
     *
     * Returns `null` on any LibRaw error or path failure — the UI treats that as
     * "EXIF unavailable" and degrades the info sheet rather than blocking the open.
     */
    @JvmStatic external fun readExif(path: String): RawExif?

    /**
     * Extract the embedded full-size JPEG preview from the RAW container.
     * Used as a last-resort fallback when full-res decode hangs or errors —
     * the user sees the camera's own preview rather than a blank canvas.
     *
     * Returns null when the source has no JPEG-format preview (rare; almost
     * every consumer RAW embeds one) or on any LibRaw error.
     */
    @JvmStatic external fun extractEmbeddedJpeg(path: String): ByteArray?

    // ── Decode entry points (Phase 1 stubs — return NotImplemented) ───────────

    @JvmStatic external fun decodeHalfResToArgb(
        path: String,
        userQual: Int,
        gamut: Int,
        highlightMode: Int,
        nrEnabled: Boolean,
        nrLuma: Int,
        nrChroma: Int,
        dcpProfileId: String,
        cancelFlagPtr: Long,
        outArgb: IntArray,
        outDims: IntArray,
    ): Int

    @JvmStatic external fun decodeFullResToArgbMapped(
        path: String,
        userQual: Int,
        gamut: Int,
        highlightMode: Int,
        nrEnabled: Boolean,
        nrLuma: Int,
        nrChroma: Int,
        dcpProfileId: String,
        cancelFlagPtr: Long,
        outFd: Int,
        outOffset: Long,
        outCapacity: Long,
        outDims: IntArray,
    ): Int

    @JvmStatic external fun decodeFullResToArgbMapped8bit(
        path: String,
        userQual: Int,
        gamut: Int,
        highlightMode: Int,
        nrEnabled: Boolean,
        nrLuma: Int,
        nrChroma: Int,
        dcpProfileId: String,
        cancelFlagPtr: Long,
        outFd: Int,
        outOffset: Long,
        outCapacity: Long,
        outDims: IntArray,
    ): Int

    /**
     * Compatibility wedge — decodes into a uint16 BGR DirectByteBuffer that matches the
     * legacy `decodeRawLinearIntoBuffer*` JNI exports bit-for-bit. The Kotlin caller
     * passes the same allocateDirect-backed buffer it would have given to V1, V2 fills
     * it identically. Used behind BuildConfig.USE_RAW_V2 so production downstream code
     * (Stage A/B/C in LibRawJniBridge) doesn't have to change format-wise to A/B test
     * V2's dispatcher.
     */
    @JvmStatic external fun decodeIntoBgr16Buffer(
        path: String,
        halfSize: Boolean,
        userQual: Int,
        gamut: Int,
        highlightMode: Int,
        nrEnabled: Boolean,
        nrLuma: Int,
        nrChroma: Int,
        /**
         * Rayxie chromatic-aberration correction. true → run on the full-res buffer.
         * false → skip. Ignored on the half-size path (CA is always skipped at half-res
         * since fringes are invisible there). Wired to the workspace selector toggle.
         */
        caCorrectionEnabled: Boolean,
        dcpProfileId: String,
        cancelFlagPtr: Long,
        outBuffer: java.nio.ByteBuffer,
        outDims: IntArray,
    ): Int

    @JvmStatic external fun decodeFullResToFloat16Mapped(
        path: String,
        userQual: Int,
        gamut: Int,
        highlightMode: Int,
        nrEnabled: Boolean,
        nrLuma: Int,
        nrChroma: Int,
        dcpProfileId: String,
        cancelFlagPtr: Long,
        outFd: Int,
        outOffset: Long,
        outCapacity: Long,
        outDims: IntArray,
    ): Int

    // ── Typed façades ─────────────────────────────────────────────────────────
    //
    // Callers should prefer these over the raw `external fun`s — they handle the integer
    // status protocol and (for in-mem paths) preallocate the destination IntArray to the
    // expected dimensions. The mapped-output façades take an [Allocator] so the pipeline's
    // per-pipeline scratch directory stays in control of the file.

    /**
     * Decode at half resolution into a freshly-allocated ARGB IntArray.
     *
     * Caller passes a pre-allocated [outArgb] sized to the expected number of pixels
     * (LibRaw halves both dimensions on `half_size=1`, so for a 5500×3669 source the
     * caller should pass `IntArray(2750 * 1834)`). When the actual decoded size exceeds
     * the array, the native side returns [DecodeStatus.InsufficientBuffer].
     *
     * Caller can read dimensions ahead of time via [readExif] — the result carries
     * sensor width/height in the standard EXIF fields (after orientation).
     */
    fun decodeHalfRes(
        params: DecodeParams,
        outArgb: IntArray,
        cancelFlag: CancelFlag,
    ): DecodeResult {
        val dims = IntArray(2)
        val code = decodeHalfResToArgb(
            params.filePath, params.userQual, params.gamut, params.highlightMode,
            params.nrEnabled, params.nrLuma, params.nrChroma, params.dcpProfileId,
            cancelFlag.ptr(), outArgb, dims,
        )
        val status = DecodeStatus.fromCode(code)
        return if (status == DecodeStatus.Ok) {
            DecodeResult.InMem(outArgb, DecodedDimensions(dims[0], dims[1]))
        } else {
            DecodeResult.Failure(status)
        }
    }

    /** Decode at full res into a memory-mapped ARGB file (Pipeline 2 — Idle compare). */
    fun decodeFullResArgbMapped(
        params: DecodeParams,
        outFd: Int,
        outOffset: Long,
        outCapacity: Long,
        cancelFlag: CancelFlag,
    ): DecodeResult {
        val dims = IntArray(2)
        val code = decodeFullResToArgbMapped(
            params.filePath, params.userQual, params.gamut, params.highlightMode,
            params.nrEnabled, params.nrLuma, params.nrChroma, params.dcpProfileId,
            cancelFlag.ptr(), outFd, outOffset, outCapacity, dims,
        )
        return mappedResult(code, dims)
    }

    /** Decode at full res into a memory-mapped ARGB file (Pipeline 3 — Save 8-bit). */
    fun decodeFullResArgbMapped8bit(
        params: DecodeParams,
        outFd: Int,
        outOffset: Long,
        outCapacity: Long,
        cancelFlag: CancelFlag,
    ): DecodeResult {
        val dims = IntArray(2)
        val code = decodeFullResToArgbMapped8bit(
            params.filePath, params.userQual, params.gamut, params.highlightMode,
            params.nrEnabled, params.nrLuma, params.nrChroma, params.dcpProfileId,
            cancelFlag.ptr(), outFd, outOffset, outCapacity, dims,
        )
        return mappedResult(code, dims)
    }

    /** Decode at full res into a memory-mapped float16 RGBA file (Pipeline 4 — Save 16-bit). */
    fun decodeFullResFloat16Mapped(
        params: DecodeParams,
        outFd: Int,
        outOffset: Long,
        outCapacity: Long,
        cancelFlag: CancelFlag,
    ): DecodeResult {
        val dims = IntArray(2)
        val code = decodeFullResToFloat16Mapped(
            params.filePath, params.userQual, params.gamut, params.highlightMode,
            params.nrEnabled, params.nrLuma, params.nrChroma, params.dcpProfileId,
            cancelFlag.ptr(), outFd, outOffset, outCapacity, dims,
        )
        return mappedResult(code, dims)
    }

    private fun mappedResult(code: Int, dims: IntArray): DecodeResult {
        val status = DecodeStatus.fromCode(code)
        return if (status == DecodeStatus.Ok) {
            DecodeResult.Mapped(DecodedDimensions(dims[0], dims[1]))
        } else {
            DecodeResult.Failure(status)
        }
    }
}
