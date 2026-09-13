/*
 * JNI loader for the Magic-Lantern dual-ISO blend kernel.
 *
 * NOTE: The native library this class loads is GPL-2 (see
 * LICENSE-GPL2 in this module's root). This Kotlin shim itself only
 * defines `external` declarations + a load guard — there is no
 * GPL-derived source code in this file. It is therefore licensable
 * under the same terms as the rest of the StudioRoom Apache-2 codebase,
 * with the caveat that anything calling these functions ultimately
 * invokes GPL-2 native code through the JNI boundary, which is the
 * standard "system library" boundary for GPL purposes.
 */

package com.raz.razstudio.lib.dualiso

import android.util.Log

/**
 * Kotlin entry points to the cr2hdr-derived bayer kernel. All inputs
 * are 16-bit bayer planes (jshort buffers) sized [width] * [height].
 * `black` and `white` are the sensor levels in 14-bit units (Canon
 * default: 2048 / ~15000).
 *
 * Both functions tolerate larger-than-needed buffers (extra bytes
 * past `width * height` are ignored). They reject buffers that are
 * smaller, returning false rather than reading out of bounds.
 */
object DualIsoNative {

    private const val TAG = "DualIsoNative"
    private var loaded = false

    /**
     * Lazy-load the shared library. Returns true when the library is
     * usable, false when System.loadLibrary failed (e.g. missing .so
     * for the running ABI). Callers should always check this before
     * calling [hdrCheck] / [blendMean23] — calling into an unloaded
     * library would crash with UnsatisfiedLinkError.
     */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return runCatching {
            System.loadLibrary("dualiso_blend")
            loaded = true
            true
        }.onFailure { Log.w(TAG, "loadLibrary(dualiso_blend) failed", it) }
            .getOrDefault(false)
    }

    /**
     * Authoritative dual-ISO detector. Mirrors cr2hdr.c's hdr_check.
     * Returns true when the bayer plane shows the alternating-row-pair
     * brightness pattern characteristic of an ML dual-ISO capture.
     *
     * Cost: one O(w·h) pass; ~50 ms on a 5472x3648 Canon 6D frame on
     * a recent phone. Safe to call from a background dispatcher.
     */
    fun hdrCheck(
        bayer: ShortArray,
        width: Int,
        height: Int,
        black: Int,
        white: Int,
    ): Boolean {
        if (!ensureLoaded()) return false
        return nativeHdrCheck(bayer, width, height, black, white)
    }

    /**
     * Full detection pass: hdr_check + identify_pattern + identify_fields.
     * Returns a [DetectionResult] containing the three flags + the bright/
     * dark row-group pattern. Returns null when the native library failed
     * to load or the input was invalid.
     *
     * Single read-only pass over the bayer plane; ~100 ms on a 6D frame.
     */
    fun detectFull(
        bayer: ShortArray,
        width: Int,
        height: Int,
        black: Int,
        white: Int,
    ): DetectionResult? {
        if (!ensureLoaded()) return null
        val status = nativeDetectFull(bayer, width, height, black, white)
        if (status < 0) return null
        return DetectionResult(
            isDualIso       = (status and 0x01) != 0,
            isRggb          = (status and 0x02) != 0,
            fieldsConfirmed = (status and 0x04) != 0,
            isBright = booleanArrayOf(
                (status and (1 shl 4)) != 0,
                (status and (1 shl 5)) != 0,
                (status and (1 shl 6)) != 0,
                (status and (1 shl 7)) != 0,
            ),
        )
    }

    /**
     * Result of [detectFull].
     *
     * @property isDualIso       true if the avg_ev>0.5 threshold passed.
     * @property isRggb          true for RGGB bayer, false for GBRG.
     * @property fieldsConfirmed true when the bright/dark row-group
     *   detection succeeded (exactly 2 bright groups, in alternating
     *   pairs). False means the file is ambiguous — likely not actually
     *   dual-ISO even if [isDualIso] was true.
     * @property isBright        which of the four mod-4 row groups are
     *   the bright (high-ISO) pair. Index by `y % 4`.
     */
    data class DetectionResult(
        val isDualIso: Boolean,
        val isRggb: Boolean,
        val fieldsConfirmed: Boolean,
        val isBright: BooleanArray,
    ) {
        // BooleanArray needs custom equals/hashCode for data class semantics.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DetectionResult) return false
            return isDualIso == other.isDualIso &&
                isRggb == other.isRggb &&
                fieldsConfirmed == other.fieldsConfirmed &&
                isBright.contentEquals(other.isBright)
        }
        override fun hashCode(): Int {
            var r = isDualIso.hashCode()
            r = 31 * r + isRggb.hashCode()
            r = 31 * r + fieldsConfirmed.hashCode()
            r = 31 * r + isBright.contentHashCode()
            return r
        }
    }

    /**
     * Run the full mean23 dual-ISO blend pipeline in place on the
     * supplied bayer plane. Faithful port of cr2hdr.c's hdr_interpolate
     * orchestrator (detection → 14→20 promote → match_exposures →
     * interpolate → half-res mix → chroma_smooth → write back).
     *
     * Returns true on success — [bayer] now contains the blended,
     * deinterlaced plane (still 14-bit values stored in 16-bit, top
     * 2 bits clear). Returns false on any failure — the native side
     * restores the buffer to its pre-call contents before returning
     * via an RAII snapshot, so the caller can safely fall back to
     * single-ISO rendering with no visible damage.
     *
     * Cost: ~2–4 s on a 25 MP CR2 on arm64-v8a. Memory peak ~400 MB
     * of intermediates during the blend (all freed before return).
     *
     * Validation status: the maths is a faithful port of cr2hdr's
     * mean23 path but has NOT been bit-exact validated against
     * cr2hdr64's desktop output. The first integration on a real
     * file is the validation event.
     */
    fun blendMean23(
        bayer: ShortArray,
        width: Int,
        height: Int,
        black: Int,
        white: Int,
    ): Boolean {
        if (!ensureLoaded()) return false
        return nativeBlendMean23(bayer, width, height, black, white)
    }

    @JvmStatic
    private external fun nativeHdrCheck(
        bayer: ShortArray, width: Int, height: Int, black: Int, white: Int,
    ): Boolean

    @JvmStatic
    private external fun nativeDetectFull(
        bayer: ShortArray, width: Int, height: Int, black: Int, white: Int,
    ): Int

    @JvmStatic
    private external fun nativeBlendMean23(
        bayer: ShortArray, width: Int, height: Int, black: Int, white: Int,
    ): Boolean
}
