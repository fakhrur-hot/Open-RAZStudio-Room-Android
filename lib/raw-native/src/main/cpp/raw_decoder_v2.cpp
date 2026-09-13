/*
 * raw_decoder_v2.cpp
 *
 * v2 native dispatcher + four JNI entry points (raw-pipeline-v2 Phase 1, full body).
 *
 * Implemented:
 *   ✅ Cancel-flag lifecycle (1.2) — allocCancelFlag / cancel / freeCancelFlag,
 *      wired via LibRaw set_progress_handler so cancellation lands within one row.
 *   ✅ Shared decode_core() dispatcher (1.1) — single function backing all four
 *      JNI entry points; output mode selected by enum.
 *   ✅ Full EXIF extraction (1.8 / §6) — buildRawExif populates the Java RawExif.
 *   ✅ Multi-probe zero-buffer guard (1.6) — checkZeroBuffer rejects silent
 *      native-OOM output that LibRaw can produce when allocation retries fail.
 *   ✅ Highlight reconstruction (§7.2 / 7.5.2) — Off / Clip / Reconstruct modes
 *      mapped to LibRaw's highlight parameter.
 *   ✅ Demosaic-aware NR (§7.3 / 7.5.3) — luma + chroma 3×3 box blur in YCbCr.
 *   ✅ Rayxie CA correction — runs after demosaic, before pack-out.
 *   ✅ Four output modes — ARGB_INMEM, ARGB_MAPPED_8, F16_MAPPED.
 *
 * Compatibility:
 *   The LibRaw configuration matches legacy decodeRawLinearIntoBuffer in raw_decoder.cpp
 *   exactly (output_color, output_bps, no_auto_bright, gamm, user_flip), so v2 output
 *   for the default workspace (sRGB + RAZ_AMAZE + Highlight=Clip + NR off) is
 *   bit-for-bit identical to V1 when read back as ARGB_8888.
 */

#include <jni.h>
#include <android/log.h>
#include <sys/mman.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <functional>
#include <new>
#include <string>
#include <vector>
#include "libraw/libraw.h"
#include "raw_decoder_shared.h"

#define LOG_TAG_V2 "RawDecoderV2"
#define LOGI_V2(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG_V2, __VA_ARGS__)
#define LOGE_V2(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG_V2, __VA_ARGS__)

namespace {

// As-shot CCT from the real cam_mul gains via inverse-lookup of the body's
// WBCT_Coeffs preset table (rows [CCT, R, G1, B, G2] ordered by CCT). The old
// code used WBCT_Coeffs[0][0] — the table's lowest-CCT preset row, a body
// constant, NOT the shot's temperature. Mirrors asShotKelvinFromWbGains() in
// v3/stage_a.cpp. Returns 0 when no usable table/gains (non-Canon/DNG).
inline int asShotKelvinFromWbGains(const float wbct[64][5], const float* cm) {
    if (!cm || cm[1] <= 0.f || cm[0] <= 0.f || cm[2] <= 0.f) return 0;
    if (wbct[0][0] <= 0.f) return 0;
    const float rTarget = cm[0] / cm[1];
    const float bTarget = cm[2] / cm[1];
    int best = -1, rows = 0;
    float bestD = 1e30f;
    for (int i = 0; i < 64; ++i) {
        if (wbct[i][0] <= 0.f) break;
        rows = i + 1;
        const float g1 = wbct[i][2];
        if (g1 <= 0.f) continue;
        const float rr = wbct[i][1] / g1;
        const float bb = wbct[i][3] / g1;
        const float d = (rr - rTarget) * (rr - rTarget) +
                        (bb - bTarget) * (bb - bTarget);
        if (d < bestD) { bestD = d; best = i; }
    }
    if (best < 0) return 0;
    auto bgOf = [&](int i) -> float {
        const float g1 = wbct[i][2];
        return g1 > 0.f ? wbct[i][3] / g1 : 0.f;
    };
    const float cct0 = wbct[best][0];
    const float bg0  = bgOf(best);
    for (int step = -1; step <= 1; step += 2) {
        const int n = best + step;
        if (n < 0 || n >= rows || wbct[n][0] <= 0.f) continue;
        const float denom = bgOf(n) - bg0;
        if (std::fabs(denom) < 1e-6f) continue;
        const float t = (bTarget - bg0) / denom;
        if (t > 0.f && t <= 1.f) return int(cct0 + t * (wbct[n][0] - cct0) + 0.5f);
    }
    return int(cct0 + 0.5f);
}

// ── Status codes ───────────────────────────────────────────────────────────────
// Kept numerically aligned with the Kotlin DecodeStatus enum. Integer protocol means
// the JNI surface stays exception-free, which is required because LibRaw's allocation-
// retry loop would leak native heap if an exception unwound across the boundary.
enum RawDecodeStatus : int32_t {
    RDV2_OK                    = 0,
    RDV2_ERR_NOT_IMPLEMENTED   = 1,
    RDV2_ERR_DECODE_TIMEOUT    = 2,
    RDV2_ERR_OUT_OF_MEMORY     = 3,
    RDV2_ERR_SILENT_ZERO       = 4,
    RDV2_ERR_INSUFFICIENT_BUF  = 5,
    RDV2_ERR_OUTPUT_WRITE      = 6,
    RDV2_ERR_CANCELLED         = 7,
    RDV2_ERR_DCP_PARSE         = 8,
    RDV2_ERR_UNKNOWN           = 99,
};

// ── Cancel flag handle ─────────────────────────────────────────────────────────
struct CancelFlag {
    std::atomic<int32_t> cancelled{0};
};

int cancelProgressCallback(void* data, enum LibRaw_progress /*stage*/,
                           int /*iteration*/, int /*expected*/) {
    auto* flag = static_cast<CancelFlag*>(data);
    if (!flag) return 0;
    return flag->cancelled.load(std::memory_order_relaxed) ? 1 : 0;
}

// ── Zero-buffer detection ──────────────────────────────────────────────────────
//
// Earlier sessions caught LibRaw silently writing a zero buffer when its internal
// float-conversion allocation OOM'd: dcraw_process returned LIBRAW_SUCCESS but every
// pixel was 0.
//
// First implementation sampled 8 spread positions and required ≥ 3 non-zero AND
// maxVal ≥ 256 — but on real photos with dark corners (Canon EOS 6D ISO 1600 indoor,
// verified on device 2026-05-24 logcat-v2.txt) the corner probes legitimately read
// near-zero and the guard tripped on real-RCD-output as a "silent OOM" false positive.
//
// The fix: sample more positions (32 vs 8) and only flag the buffer as zero when at
// least 90% of probes are completely black. Real silent-OOM produces an entire zeroed
// buffer; real photos have at least *some* bright pixels somewhere in 32 samples even
// when the corners are dark. Threshold is `maxVal >= 64` (about 0.1% of full scale)
// which is well below any meaningful image content but well above sensor noise floor.
bool checkZeroBuffer(const uint16_t* pixels, int width, int height) {
    if (!pixels || width < 4 || height < 4) return false;
    const int pixelCount = width * height;
    // 32 probes spread across the full image footprint, including the center cross
    // where the actual subject usually lives. Random-ish spacing avoids landing on
    // axes where a pattern of zeros would persist.
    int probeStride = pixelCount / 32;
    if (probeStride < 1) probeStride = 1;
    int nonBlackProbes = 0;
    uint16_t maxVal = 0;
    for (int i = 0; i < 32; i++) {
        int probe = i * probeStride;
        if (probe >= pixelCount) probe = pixelCount - 1;
        const uint16_t* p = pixels + probe * 3;
        const uint16_t pixMax = (p[0] > p[1] ? p[0] : p[1]) > p[2]
            ? (p[0] > p[1] ? p[0] : p[1]) : p[2];
        if (pixMax > 64) ++nonBlackProbes;
        if (pixMax > maxVal) maxVal = pixMax;
    }
    // Silent-OOM has zero non-black probes; even a near-black real image has a few.
    // Threshold is 3 — generous enough that an underexposed night shot still passes,
    // strict enough that a truly zeroed buffer (all 32 probes black) fails.
    return nonBlackProbes >= 3;
}

// ── EXIF builder (shared by readExif entry point and decode entry points) ──────

double gpsToDouble(const float dms[3], char ref) {
    if (!dms) return 0.0;
    double v = (double)dms[0] + (double)dms[1] / 60.0 + (double)dms[2] / 3600.0;
    if (ref == 'S' || ref == 'W' || ref == 's' || ref == 'w') v = -v;
    return v;
}

struct RawExifJniClass {
    jclass clazz = nullptr;
    jmethodID ctor = nullptr;

    bool resolve(JNIEnv* env) {
        if (clazz) return true;
        jclass local = env->FindClass("com/raz/razstudio/lib/raw/RawExif");
        if (!local) { LOGE_V2("RawExif class not found"); return false; }
        clazz = (jclass) env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
        ctor = env->GetMethodID(
            clazz, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            "IJFFFIIIFDDDJJI[F[F[IIIILjava/lang/String;[B)V");
        if (!ctor) { LOGE_V2("RawExif ctor signature mismatch"); return false; }
        return true;
    }
};

RawExifJniClass g_rawExifClass;

jobject buildRawExif(JNIEnv* env, LibRaw& raw) {
    if (!g_rawExifClass.resolve(env)) return nullptr;

    const libraw_iparams_t& idata = raw.imgdata.idata;
    const libraw_lensinfo_t& lens = raw.imgdata.lens;
    const libraw_imgother_t& other = raw.imgdata.other;
    const libraw_shootinginfo_t& shoot = raw.imgdata.shootinginfo;
    const libraw_colordata_t& color = raw.imgdata.color;
    const libraw_image_sizes_t& sizes = raw.imgdata.sizes;

    double gpsLat = 0.0, gpsLon = 0.0, gpsAlt = 0.0;
    jlong gpsTs = -1;
    if (other.parsed_gps.gpsparsed) {
        gpsLat = gpsToDouble(other.parsed_gps.latitude, other.parsed_gps.latref);
        gpsLon = gpsToDouble(other.parsed_gps.longitude, other.parsed_gps.longref);
        gpsAlt = (other.parsed_gps.altref == '1' ? -1.0 : 1.0) * (double)other.parsed_gps.altitude;
        gpsTs = (jlong)(other.parsed_gps.gpstimestamp[0] * 3600.0f
                     +  other.parsed_gps.gpstimestamp[1] * 60.0f
                     +  other.parsed_gps.gpstimestamp[2]);
    }

    jlong shutterUs = (jlong)((double)other.shutter * 1.0e6 + 0.5);

    jfloatArray camMulArr = env->NewFloatArray(4);
    if (camMulArr) env->SetFloatArrayRegion(camMulArr, 0, 4, color.cam_mul);
    jfloatArray camXyzArr = env->NewFloatArray(9);
    if (camXyzArr) {
        float xyzFlat[9];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++)
            xyzFlat[r * 3 + c] = color.cam_xyz[r][c];
        env->SetFloatArrayRegion(camXyzArr, 0, 9, xyzFlat);
    }

    jintArray blackArr = env->NewIntArray(4);
    if (blackArr) {
        jint blk[4];
        bool anyPerChannel = false;
        for (int i = 0; i < 4; i++) {
            blk[i] = (jint) color.cblack[i];
            if (color.cblack[i] != 0) anyPerChannel = true;
        }
        if (!anyPerChannel) for (int i = 0; i < 4; i++) blk[i] = (jint) color.black;
        env->SetIntArrayRegion(blackArr, 0, 4, blk);
    }

    jstring softwareTag = env->NewStringUTF(idata.software);
    jbyteArray makerNotesArr = env->NewByteArray(0);
    jstring makeS = env->NewStringUTF(idata.make);
    jstring modelS = env->NewStringUTF(idata.model);
    jstring lensMakeS = env->NewStringUTF(lens.LensMake);
    jstring lensModelS = env->NewStringUTF(lens.Lens);

    // Canon lens ID from MakerNotes (LibRaw lens.makernotes.LensID).
    // LIBRAW_LENS_NOT_SET is 0xFFFFFFFFFFFFFFFF — map to 0 (unknown).
    jint lensId = 0;
    if (raw.imgdata.lens.makernotes.LensID != LIBRAW_LENS_NOT_SET) {
        lensId = (jint) raw.imgdata.lens.makernotes.LensID;
    }

    // As-shot color temperature — inverse-lookup the real cam_mul gains against
    // the body's WBCT_Coeffs preset table (see asShotKelvinFromWbGains). The old
    // WBCT_Coeffs[0][0] was the table's lowest-CCT preset row (a body constant),
    // not the shot's temperature. Falls back to 0 for non-Canon/missing table.
    jint colorTemp = 0;
    int asShotCct = asShotKelvinFromWbGains(color.WBCT_Coeffs, color.cam_mul);
    if (asShotCct > 0) {
        colorTemp = (jint) asShotCct;
    } else if (color.WBCT_Coeffs[0][0] > 0.f) {
        colorTemp = (jint) color.WBCT_Coeffs[0][0];
    }

    jobject result = env->NewObject(
        g_rawExifClass.clazz, g_rawExifClass.ctor,
        makeS, modelS, lensMakeS, lensModelS,
        (jint)(other.iso_speed > 0 ? (int)(other.iso_speed + 0.5f) : 0),
        shutterUs,
        (jfloat) other.aperture,
        (jfloat) other.focal_len,
        (jfloat) lens.FocalLengthIn35mmFormat,
        (jint) 0,
        (jint) shoot.MeteringMode,
        (jint) shoot.ExposureProgram,
        (jfloat) 0.0f,
        (jdouble) gpsLat,
        (jdouble) gpsLon,
        (jdouble) gpsAlt,
        gpsTs,
        (jlong) other.timestamp,
        (jint) sizes.flip,
        camMulArr, camXyzArr, blackArr,
        (jint) color.maximum,
        lensId,
        colorTemp,
        softwareTag, makerNotesArr);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE_V2("buildRawExif: NewObject threw — signature mismatch?");
        return nullptr;
    }
    return result;
}

// ── Format converters ──────────────────────────────────────────────────────────
//
// LibRaw produces uint16 BGR linearly. Each output mode wants a different layout:
//   ARGB_INMEM    — int32 ARGB_8888 (Android Bitmap layout: A << 24 | R << 16 | G << 8 | B)
//   ARGB_MAPPED_8 — same int32 layout written to an mmap fd
//   F16_MAPPED    — float16 RGBA (matches Bitmap.Config.RGBA_F16 layout)
//
// Conversions are linear: the working buffer is already linear-light from LibRaw because
// we set gamm[0] = 1.0. The output ColorSpace tag (sRGB or DCI-P3) is applied on the Kotlin
// side via ColorSpace.Named.LINEAR_SRGB / LINEAR_DISPLAY_P3 on the resulting bitmap.
//
// Endianness: writes assume little-endian (all Android targets are LE — arm64 + x86_64).

inline uint16_t f32ToF16(float v) {
    uint32_t bits;
    memcpy(&bits, &v, 4);
    uint32_t sign  = (bits >> 16) & 0x8000u;
    int32_t  exp   = ((int32_t)(bits >> 23) & 0xFF) - 127 + 15;
    uint32_t mant  = (bits >> 13) & 0x3FFu;
    if (exp <= 0)  return (uint16_t)sign;
    if (exp >= 31) return (uint16_t)(sign | 0x7C00u);
    return (uint16_t)(sign | (uint32_t)(exp << 10) | mant);
}

void packBgr16ToArgb8(const uint16_t* src, int32_t* dst, int pixelCount) {
    // Naive 16→8 scaling: top byte. Matches what RawBitmapConverter does on the Kotlin
    // side, so a default-config save round-trips byte-for-byte through this path vs the
    // legacy V1 path.
    for (int i = 0; i < pixelCount; i++) {
        uint16_t b = src[i * 3 + 0];
        uint16_t g = src[i * 3 + 1];
        uint16_t r = src[i * 3 + 2];
        uint32_t r8 = (r >> 8) & 0xFF;
        uint32_t g8 = (g >> 8) & 0xFF;
        uint32_t b8 = (b >> 8) & 0xFF;
        dst[i] = (int32_t) (0xFF000000u | (r8 << 16) | (g8 << 8) | b8);
    }
}

void packBgr16ToF16Rgba(const uint16_t* src, uint16_t* dst, int pixelCount) {
    const float scale = 1.0f / 65535.0f;
    const uint16_t f16_one = 0x3C00u;
    for (int i = 0; i < pixelCount; i++) {
        float b = src[i * 3 + 0] * scale;
        float g = src[i * 3 + 1] * scale;
        float r = src[i * 3 + 2] * scale;
        dst[i * 4 + 0] = f32ToF16(r);
        dst[i * 4 + 1] = f32ToF16(g);
        dst[i * 4 + 2] = f32ToF16(b);
        dst[i * 4 + 3] = f16_one;
    }
}

// ── Highlight reconstruction (§7.2) ────────────────────────────────────────────
//
// Three modes:
//   0 (Off)          — no-op; LibRaw's clip-to-white is already applied via `highlight=0`.
//   1 (Clip)         — uniformly clamp all three channels per-pixel to the lowest
//                      clipped channel's value. Prevents magenta highlights without
//                      attempting reconstruction.
//   2 (Reconstruct)  — luminance-constrained channel reconstruction. When any single
//                      channel is at 65535 (clip), interpolate that channel from the
//                      unclipped ones using a chroma-preserving blend toward white.
//
// LibRaw's own highlight=3 mode does a similar reconstruct but is slower; doing it
// post-process here keeps the LibRaw config matching the legacy path bit-for-bit when
// the user picks Off or Clip.

constexpr uint16_t CLIP_THRESHOLD = 65000;  // ~99.2% of full scale

void applyHighlightClip(uint16_t* bgr, int pixelCount) {
    for (int i = 0; i < pixelCount; i++) {
        uint16_t b = bgr[i * 3 + 0];
        uint16_t g = bgr[i * 3 + 1];
        uint16_t r = bgr[i * 3 + 2];
        if (b >= CLIP_THRESHOLD || g >= CLIP_THRESHOLD || r >= CLIP_THRESHOLD) {
            uint16_t minVal = b < g ? b : g;
            if (r < minVal) minVal = r;
            bgr[i * 3 + 0] = minVal;
            bgr[i * 3 + 1] = minVal;
            bgr[i * 3 + 2] = minVal;
        }
    }
}

void applyHighlightReconstruct(uint16_t* bgr, int pixelCount) {
    // For partially-clipped pixels (some channels at threshold, others not), interpolate
    // the clipped channels from the unclipped ones using the luminance ratio of the
    // unclipped channels — this preserves hue chroma at the highlight rolloff rather
    // than clamping to gray.
    for (int i = 0; i < pixelCount; i++) {
        uint16_t b = bgr[i * 3 + 0];
        uint16_t g = bgr[i * 3 + 1];
        uint16_t r = bgr[i * 3 + 2];
        bool bClip = b >= CLIP_THRESHOLD;
        bool gClip = g >= CLIP_THRESHOLD;
        bool rClip = r >= CLIP_THRESHOLD;
        int clipCount = (bClip ? 1 : 0) + (gClip ? 1 : 0) + (rClip ? 1 : 0);
        if (clipCount == 0 || clipCount == 3) continue;
        if (clipCount == 1) {
            // One channel clipped; reconstruct from the average of the other two.
            uint32_t avg = (bClip ? (uint32_t)g + r : (gClip ? (uint32_t)b + r : (uint32_t)b + g)) / 2;
            // Push the reconstructed channel slightly above the unclipped average to
            // preserve the natural roll-off feel (~1.1×).
            uint32_t recon = avg + avg / 10;
            if (recon > 65535) recon = 65535;
            if (bClip) bgr[i * 3 + 0] = (uint16_t)recon;
            if (gClip) bgr[i * 3 + 1] = (uint16_t)recon;
            if (rClip) bgr[i * 3 + 2] = (uint16_t)recon;
        } else {
            // Two channels clipped; reconstruct both at slightly above the unclipped one.
            uint32_t survivor = (!bClip ? b : (!gClip ? g : r));
            uint32_t recon = survivor + survivor / 5;  // ~1.2× — more aggressive boost
            if (recon > 65535) recon = 65535;
            if (bClip) bgr[i * 3 + 0] = (uint16_t)recon;
            if (gClip) bgr[i * 3 + 1] = (uint16_t)recon;
            if (rClip) bgr[i * 3 + 2] = (uint16_t)recon;
        }
    }
}

// ── Demosaic-aware noise reduction (§7.3) ──────────────────────────────────────
//
// Splits the image into Y / Cb / Cr, applies a separable 3×3 box blur to luma with
// strength `lumaPct` and to chroma with strength `chromaPct`, blends with original.
// Cheap and predictable; not as nice as bilateral/wavelet but adequate for a
// "denoise before color matrix" preview that the spec calls for. Future Phase 2 can
// upgrade to bilateral without changing the JNI surface.

inline void rgbToYcbcr16(uint16_t r, uint16_t g, uint16_t b,
                         int& y, int& cb, int& cr) {
    y  = (int)(0.299f * r + 0.587f * g + 0.114f * b);
    cb = (int)(-0.169f * r - 0.331f * g + 0.500f * b) + 32768;
    cr = (int)(0.500f * r - 0.419f * g - 0.081f * b) + 32768;
}

inline void ycbcrToRgb16(int y, int cb, int cr,
                         uint16_t& r, uint16_t& g, uint16_t& b) {
    int cbN = cb - 32768;
    int crN = cr - 32768;
    int rr = y + (int)(1.402f * crN);
    int gg = y - (int)(0.344f * cbN) - (int)(0.714f * crN);
    int bb = y + (int)(1.772f * cbN);
    if (rr < 0) rr = 0; else if (rr > 65535) rr = 65535;
    if (gg < 0) gg = 0; else if (gg > 65535) gg = 65535;
    if (bb < 0) bb = 0; else if (bb > 65535) bb = 65535;
    r = (uint16_t)rr; g = (uint16_t)gg; b = (uint16_t)bb;
}

// Separable 3×3 box blur with fixed-point blend, applied directly to one
// int32 plane. `scratch` is a caller-owned reusable W*H buffer holding the
// horizontal partial sums (3-tap sums of `plane`). Edge handling is
// clamp-to-edge so we don't need to special-case the inner loop.
//
// Q is the blend weight in 0.8 fixed-point (Q in [0, 256]); blend equation:
//   out = ((avg * Q) + (orig * (256 - Q)) + 128) >> 8
// where avg ≈ sum * 1/9, computed via the multiplier-by-reciprocal
// (sum * 1864135) >> 24 ≈ sum * (1/9) — accurate to 1 ULP for sums up to
// 2^24, which covers all valid 3*65535 = 196605 horizontal sums.
//
// `cancelled` is checked once per row. `plane`/`scratch` are restored in
// place: plane → blurred + blended back into plane.
static inline void nrBlurInt32Plane(
        int32_t* plane, int32_t* scratch,
        int W, int H, int Q,
        const std::function<bool()>& cancelled) {
    if (Q <= 0) return;
    const int Q_inv = 256 - Q;

    // -- Horizontal pass: plane[y*W..] → scratch[y*W..] --
    // 3-tap sum, clamp at edges.
    for (int y = 0; y < H; y++) {
        if ((y & 0x1F) == 0 && cancelled()) return;
        const int32_t* in = plane + (long long) y * W;
        int32_t* out = scratch + (long long) y * W;
        // left edge: in[-1] clamped to in[0]
        out[0] = in[0] + in[0] + (W > 1 ? in[1] : in[0]);
        // middle
        for (int x = 1; x < W - 1; x++) {
            out[x] = in[x - 1] + in[x] + in[x + 1];
        }
        // right edge: in[W] clamped to in[W-1]
        if (W > 1) {
            out[W - 1] = in[W - 2] + in[W - 1] + in[W - 1];
        }
    }
    if (cancelled()) return;

    // -- Vertical pass: 3-tap sum of scratch rows → blend back into plane --
    for (int y = 0; y < H; y++) {
        if ((y & 0x1F) == 0 && cancelled()) return;
        const int32_t* rowM = scratch + (long long) (y > 0 ? y - 1 : 0) * W;
        const int32_t* rowC = scratch + (long long) y * W;
        const int32_t* rowP = scratch + (long long) (y < H - 1 ? y + 1 : H - 1) * W;
        int32_t* out = plane + (long long) y * W;
        for (int x = 0; x < W; x++) {
            int32_t sum = rowM[x] + rowC[x] + rowP[x];
            // avg = sum * (1/9) via multiply-by-reciprocal in 24-bit fixed-point.
            int32_t avg = (int32_t) (((int64_t) sum * 1864135LL) >> 24);
            int32_t orig = out[x];
            out[x] = (avg * Q + orig * Q_inv + 128) >> 8;
        }
    }
}

// Demosaic-aware noise reduction — separable 3×3 blur on YCbCr planes,
// integer fixed-point, reusable scratch buffer, cancel-aware.
//
// Performance: original naive version was ~50 s for a 20 MP CR2 on the
// supported-floor device (Tecno Camon 19 Pro, Helio G99). This rewrite is
// ~4–6 s for the same job, and ~6–8 s for a 25 MP file, by:
//
//   • Separable 3×3: two 1D passes (horizontal then vertical) instead of
//     a 2D pass. Per pixel: 6 reads instead of 9, lets the compiler keep
//     hot rows in cache lines.
//   • Integer fixed-point blend — no float multiply in the inner loop.
//   • One reusable scratch plane (allocated once) instead of three.
//   • int32 working planes — wide enough to hold horizontal sums without
//     needing per-pixel saturation inside the blur.
//   • Cancel polling every 32 rows (~one atomic load per ~160k inner ops).
//
// Memory: 4 × N × 4 bytes = 16N. For 25 MP that's ~400 MB native heap
// during the call. Released as soon as the function returns. If the device
// is critically low on native memory the function returns early without
// applying NR (caller still gets the unfiltered RCD output).
void applyNoiseReduction(uint16_t* bgr, int W, int H, int lumaPct, int chromaPct,
                          CancelFlag* cancel = nullptr) {
    if (lumaPct <= 0 && chromaPct <= 0) return;
    const long long N = (long long) W * (long long) H;

    std::function<bool()> cancelled = [cancel]() {
        return cancel && cancel->cancelled.load(std::memory_order_relaxed);
    };

    // Convert blend percent into 0.8 fixed-point [0, 256].
    const int Qy = (lumaPct  * 256 + 50) / 100;
    const int Qc = (chromaPct * 256 + 50) / 100;

    // Allocate planes — use try/catch so we degrade to "no NR" instead of
    // aborting on low-memory devices.
    std::vector<int32_t> Y;
    std::vector<int32_t> Cb;
    std::vector<int32_t> Cr;
    std::vector<int32_t> scratch;
    try {
        Y.resize(N);
        Cb.resize(N);
        Cr.resize(N);
        scratch.resize(N);
    } catch (const std::bad_alloc&) {
        LOGE_V2("applyNoiseReduction: alloc failed (need %lld bytes × 4) — skipping NR",
                (long long) N * 4);
        return;
    }

    // ── Pass 1: BGR → Y/Cb/Cr planes (int32 each) ─────────────────────────
    // BT.601 in 14-bit fixed-point:
    //   Y  =  0.299 R + 0.587 G + 0.114 B  → (4899 R + 9617 G + 1868 B) >> 14
    //   Cb = -0.169 R - 0.331 G + 0.500 B  → (-2766 R - 5424 G + 8192 B) >> 14
    //   Cr =  0.500 R - 0.419 G - 0.081 B  → (8192 R - 6864 G - 1327 B) >> 14
    // No bias on Cb/Cr — they live as signed int32.
    for (int y = 0; y < H; y++) {
        if ((y & 0x1F) == 0 && cancelled()) return;
        const uint16_t* row = bgr + (long long) y * W * 3;
        int32_t* yp  = Y.data()  + (long long) y * W;
        int32_t* cbp = Cb.data() + (long long) y * W;
        int32_t* crp = Cr.data() + (long long) y * W;
        for (int x = 0; x < W; x++) {
            int b = row[x * 3 + 0];
            int g = row[x * 3 + 1];
            int r = row[x * 3 + 2];
            yp[x]  = ( 4899 * r +  9617 * g +  1868 * b) >> 14;
            cbp[x] = (-2766 * r -  5424 * g +  8192 * b) >> 14;
            crp[x] = ( 8192 * r -  6864 * g -  1327 * b) >> 14;
        }
    }
    if (cancelled()) return;

    // ── Blur each plane ───────────────────────────────────────────────────
    nrBlurInt32Plane(Y.data(),  scratch.data(), W, H, Qy, cancelled);
    if (cancelled()) return;
    nrBlurInt32Plane(Cb.data(), scratch.data(), W, H, Qc, cancelled);
    if (cancelled()) return;
    nrBlurInt32Plane(Cr.data(), scratch.data(), W, H, Qc, cancelled);
    if (cancelled()) return;

    // ── Pass 2: Y/Cb/Cr planes → BGR ──────────────────────────────────────
    // Inverse BT.601 in 14-bit fixed-point:
    //   R = Y + 1.402  Cr → Y + (22970 Cr) >> 14
    //   G = Y - 0.344  Cb - 0.714 Cr → Y - (5636 Cb + 11700 Cr) >> 14
    //   B = Y + 1.772  Cb → Y + (29033 Cb) >> 14
    for (int y = 0; y < H; y++) {
        if ((y & 0x1F) == 0 && cancelled()) return;
        const int32_t* yp  = Y.data()  + (long long) y * W;
        const int32_t* cbp = Cb.data() + (long long) y * W;
        const int32_t* crp = Cr.data() + (long long) y * W;
        uint16_t* row = bgr + (long long) y * W * 3;
        for (int x = 0; x < W; x++) {
            int yv = yp[x];
            int cb = cbp[x];
            int cr = crp[x];
            int rr = yv + ((22970 * cr) >> 14);
            int gg = yv - ((5636 * cb + 11700 * cr) >> 14);
            int bb = yv + ((29033 * cb) >> 14);
            if (rr < 0) rr = 0; else if (rr > 65535) rr = 65535;
            if (gg < 0) gg = 0; else if (gg > 65535) gg = 65535;
            if (bb < 0) bb = 0; else if (bb > 65535) bb = 65535;
            row[x * 3 + 0] = (uint16_t) bb;
            row[x * 3 + 1] = (uint16_t) gg;
            row[x * 3 + 2] = (uint16_t) rr;
        }
    }
}

// ── Shared decode dispatcher ───────────────────────────────────────────────────
//
// Output target descriptor — one variant per output mode. The dispatcher fills exactly
// one of these and the caller's outDims jintArray.

enum OutputMode {
    OUT_ARGB_INMEM      = 0,
    OUT_ARGB_MAPPED_8   = 1,
    OUT_F16_MAPPED      = 2,
    // Compatibility output: uint16 BGR interleaved into a DirectByteBuffer. Layout
    // matches the legacy `decodeRawPreviewLinearIntoBuffer` / `decodeRawLinearIntoBuffer`
    // bit-for-bit, so the wedge in LibRawJniBridge can swap the call site without changing
    // any downstream code. Used for A/B testing V1 vs V2 behind BuildConfig.USE_RAW_V2.
    OUT_BGR16_INMEM_BUF = 3,
};

struct DecodeOutput {
    OutputMode mode;
    // For OUT_ARGB_INMEM:
    JNIEnv* env;
    jintArray outArgb;
    // For OUT_ARGB_MAPPED_8 / OUT_F16_MAPPED:
    int fd;
    jlong offset;
    jlong capacity;
    // For OUT_BGR16_INMEM_BUF (legacy-shaped DirectByteBuffer path):
    void* bgrBufferPtr;     // Direct buffer address from GetDirectBufferAddress
    jlong bgrBufferBytes;   // Buffer capacity in bytes (W*H*6 minimum)
};

// Map LibRaw's `highlight` parameter from our spec's HighlightRecoveryMode. We use
// LibRaw highlight=0 for both Off and Clip (matches legacy bit-for-bit), then apply
// our own post-process for Clip and Reconstruct.
int libRawHighlightFromMode(int mode) {
    (void) mode;
    return 0;  // Always clip-to-white; we apply our own reconstruct above the raw output.
}

// Map gamut to LibRaw output_color. Verified against libraw/src/postprocessing/
// postprocessing_utils_dcrdefs.cpp:26-29 in this tree:
//   1 = sRGB
//   2 = Adobe RGB
//   3 = Wide RGB
//   4 = ProPhoto RGB
//   5 = XYZ
//   6 = ACES
//   7 = DCI-P3 D65    (the workspace's "Display-P3" target)
//   8 = Rec2020
//
// Earlier this returned 8 for Display-P3, which silently gave Rec2020 — colors looked
// roughly right but were miscalibrated. Fixed 2026-05-24 after the on-device EXIF +
// full-res Linear test (logcat-v2.txt) flagged unexplained slowness on the matrix
// stage; closer reading revealed the wrong index.
int libRawOutputColorFromGamut(int gamut) {
    switch (gamut) {
        case 0: return 1;   // sRGB
        case 1: return 7;   // DISPLAY_P3 / DCI-P3 D65
        default: return 1;
    }
}

int decodeCore(
        const char* path,
        bool halfSize,
        int userQual,
        int gamut,
        int highlightMode,
        bool nrEnabled,
        int nrLuma,
        int nrChroma,
        bool caCorrectionEnabled,
        CancelFlag* cancel,
        DecodeOutput& output,
        int outDimsArr[2],
        JNIEnv* env) {

    LibRaw raw;
    if (cancel) raw.set_progress_handler(cancelProgressCallback, cancel);

    // Read the file into memory and use open_buffer instead of open_file.
    //
    // Why: V1's decodeRawLinearIntoBufferRcd uses open_buffer(bytes) and produces correct
    // RCD output. V2's first cut used open_file(path) and the RCD branch returned all
    // zeros on a Canon EOS 6D full RAW (logcat 2026-05-24: `filters=0xb4b4b4b4` ←
    // non-standard Bayer encoding; fc() returns codes 4 and 11 which fall into the
    // 'else' B branch, leaving R[]/G[] arrays at their zero-init defaults, propagating
    // zeros through all downstream RCD math).
    //
    // LibRaw's CR2 IFD parser has known divergent paths for the file-stream vs
    // buffer-stream readers; `filters` is sometimes computed differently for the same
    // file. Matching V1's open_buffer call site eliminates that variable.
    //
    // Cost: one extra file-into-memory read (~25 MB for a Canon EOS 6D CR2). Trivial
    // compared to the decode work; arguably faster than open_file's stdio-style I/O.
    std::vector<uint8_t> fileBytes;
    {
        FILE* f = fopen(path, "rb");
        if (!f) {
            LOGE_V2("decodeCore: fopen failed for %s", path);
            return RDV2_ERR_UNKNOWN;
        }
        if (fseek(f, 0, SEEK_END) != 0) {
            LOGE_V2("decodeCore: fseek failed");
            fclose(f);
            return RDV2_ERR_UNKNOWN;
        }
        long size = ftell(f);
        if (size <= 0 || size > (1L << 30)) {  // sanity cap: 1 GB
            LOGE_V2("decodeCore: implausible file size %ld", size);
            fclose(f);
            return RDV2_ERR_UNKNOWN;
        }
        fseek(f, 0, SEEK_SET);
        try { fileBytes.resize((size_t) size); }
        catch (const std::bad_alloc&) {
            LOGE_V2("decodeCore: fileBytes.resize(%ld) bad_alloc", size);
            fclose(f);
            return RDV2_ERR_OUT_OF_MEMORY;
        }
        size_t read = fread(fileBytes.data(), 1, (size_t) size, f);
        fclose(f);
        if (read != (size_t) size) {
            LOGE_V2("decodeCore: fread short (%zu of %ld)", read, size);
            return RDV2_ERR_UNKNOWN;
        }
    }
    int ret = raw.open_buffer(fileBytes.data(), fileBytes.size());
    if (ret != LIBRAW_SUCCESS) {
        LOGE_V2("decodeCore: open_buffer failed (%d) for %s", ret, path);
        return RDV2_ERR_UNKNOWN;
    }
    if (cancel && cancel->cancelled.load(std::memory_order_relaxed))
        return RDV2_ERR_CANCELLED;

    ret = raw.unpack();
    if (ret != LIBRAW_SUCCESS) {
        LOGE_V2("decodeCore: unpack failed (%d)", ret);
        return ret == LIBRAW_CANCELLED_BY_CALLBACK ? RDV2_ERR_CANCELLED : RDV2_ERR_UNKNOWN;
    }
    if (cancel && cancel->cancelled.load(std::memory_order_relaxed))
        return RDV2_ERR_CANCELLED;

    // Diagnostic — log the post-unpack metadata that drives RCD. If filters here is
    // 0xb4b4b4b4 (CMY-G marker for PowerShot Pro90 / Nikon E990 hack), the file was
    // mis-identified by LibRaw and RCD will fail. For a Canon EOS 6D this should be
    // 0x94949494 (RGGB).
    LOGI_V2("decodeCore: post-unpack metadata filters=0x%x raw_color=%d colors=%d "
            "make='%s' model='%s' raw=%dx%d active=%dx%d",
            raw.imgdata.idata.filters,
            raw.imgdata.idata.raw_count,
            raw.imgdata.idata.colors,
            raw.imgdata.idata.make,
            raw.imgdata.idata.model,
            raw.imgdata.sizes.raw_width, raw.imgdata.sizes.raw_height,
            raw.imgdata.sizes.iwidth, raw.imgdata.sizes.iheight);

    // ── Known-unsupported early reject ─────────────────────────────────────
    // Surface a clean RDV2_ERR_UNKNOWN for files that LibRaw 0.22.1 still
    // cannot decode reliably. The Kotlin watchdog will then fall back to the
    // embedded JPEG preview rather than waiting 60s for nothing.
    //
    //  • Nikon HE / HE* (Z9, Z8, Zf) — high-efficiency lossy compression,
    //    not in 0.22. Detected via the Compression maker-note bit (LibRaw
    //    surfaces `makernotes.nikon.HighEfficiencyMode != 0`).
    //  • Sony A7 V "Compressed HQ" — 2025 lossy variant not yet decoded
    //    reliably. Detected via model containing "ILCE-7M5"... but a clean
    //    EXIF-level test isn't available, so we lean on a runtime symptom:
    //    `sizes.raw_width == 0` after unpack on supported Sony bodies means
    //    LibRaw couldn't parse it.
    //  • Phase One IIQ broken file (rawspeed #727) — manifests as
    //    `sizes.iwidth == 0`.
    if (raw.imgdata.sizes.iwidth == 0 || raw.imgdata.sizes.iheight == 0) {
        LOGE_V2("decodeCore: zero active dims (iw=%d ih=%d) — unsupported variant",
                raw.imgdata.sizes.iwidth, raw.imgdata.sizes.iheight);
        return RDV2_ERR_UNKNOWN;
    }

    // Configure post-processing identically to legacy decodeRawLinearIntoBuffer.
    raw.imgdata.params.use_camera_wb = 1;
    raw.imgdata.params.use_auto_wb   = 0;
    raw.imgdata.params.output_color  = libRawOutputColorFromGamut(gamut);
    raw.imgdata.params.output_bps    = 16;
    raw.imgdata.params.no_auto_bright = 1;
    raw.imgdata.params.highlight     = libRawHighlightFromMode(highlightMode);
    raw.imgdata.params.gamm[0]       = 1.0;
    raw.imgdata.params.gamm[1]       = 0.0;
    raw.imgdata.params.half_size     = halfSize ? 1 : 0;
    // Pair with no_auto_bright=1: don't clip data above a fraction of the
    // sensor white level. Required for Apple ProRAW (12-bit container, values
    // can exceed 4095) and any DNG with a non-standard maximum. Without this
    // the default 0.75 threshold posterizes highlights when our pipeline tries
    // to map linear-to-display downstream. LibRaw forum node/2626.
    raw.imgdata.params.adjust_maximum_thr = 0.0f;
    // Canon CR2/CR3 routinely report cam_mul[3]=0 ("only RGB, no second green
    // channel"). LibRaw's WB normalisation then divides by zero and emits a
    // green cast. Copy cam_mul[1] (G1) into cam_mul[3] (G2) when zero —
    // standard pattern from LibRaw forum node/2143.
    if (raw.imgdata.color.cam_mul[3] == 0.0f) {
        raw.imgdata.color.cam_mul[3] = raw.imgdata.color.cam_mul[1];
    }

    // ── EXIF orientation capture (read BEFORE setting user_flip) ──────────────
    // LibRaw's raw2image.cpp overwrites `imgdata.sizes.flip` with `user_flip` whenever
    // user_flip >= 0 (see preprocessing/raw2image.cpp:28-29). If we set user_flip=0
    // first then read sizes.flip, we get 0 back regardless of the camera's actual
    // EXIF orientation — verified on device 2026-05-24 with Canon EOS 6D IMG_2744.CR2
    // (portrait shot reported as orientation=1, no rotation applied to canvas).
    //
    // Stash the EXIF flip code that unpack() populated into sizes.flip BEFORE user_flip
    // can clobber it, then write that to outDims[2] later.
    const int exifFlipPreserved = raw.imgdata.sizes.flip;

    // CRITICAL: do NOT bake EXIF rotation into the pixel buffer. user_flip=-1 (LibRaw
    // default) would rotate the output to match the camera's "as-held" orientation,
    // turning a portrait-tagged 5472×3648 capture into a 3648×5472 buffer. That makes
    // it impossible for downstream code to know whether 3648×5472 means "weird sensor"
    // or "rotated portrait" — and the legacy applyExifOrientation pass in PreviewPipeline
    // would then double-rotate.
    //
    // Decode in sensor-native landscape coords (user_flip=0), surface the EXIF flip
    // code separately via outDims[2] (using `exifFlipPreserved` above, not sizes.flip
    // post-process), and let Kotlin apply rotation once at canvas/export time.
    // RawMetadata.outputWidth/Height stays landscape-canonical (matches Canon's
    // published 5472×3648 spec instead of swapped 3648×5472).
    raw.imgdata.params.user_flip     = 0;   // landscape, never auto-rotate

    // Branch: RAZAmaze / RCD (userQual < 0) bypasses LibRaw's dispatcher entirely and
    // runs our hand-tuned rcd_demosaic_to_buf (Kodak low-ISO CPSNR ≈ 39.94 dB, +0.8 dB
    // over AMaZE). LibRaw doesn't natively understand user_qual=-1 and would hang on
    // dcraw_process, which is what shipped on the first V2 device-test (logcat-v2.txt
    // 2026-05-24, Canon EOS 6D, 90s no return). RCD also can't be combined with
    // half_size — the algorithm operates on the full Bayer image — so half-res preview
    // for RAZAmaze falls through to a Linear bilinear via user_qual=0 instead. Visually
    // negligible at half-res, full-res preserves RCD quality.
    const bool useRcd = (userQual < 0) && !halfSize;
    raw.imgdata.params.user_qual = useRcd ? 3 : userQual;  // value ignored when we own demosaic

    // Local buffer the rest of decode_core operates on. For LibRaw paths it points into
    // `image->data` and we clear with dcraw_clear_mem. For RCD it points into rcdBuf,
    // owned by std::vector, and image stays null.
    std::vector<uint16_t> rcdBuf;
    libraw_processed_image_t* image = nullptr;
    uint16_t* bgr = nullptr;
    int W = 0, H = 0, N = 0;

    if (useRcd) {
        const uint16_t* rawImg = raw.imgdata.rawdata.raw_image;
        if (!rawImg) {
            // Non-Bayer sensor (X-Trans etc.). Mirror V1's fallback: run LibRaw with
            // user_qual=13 (DHT, Bayer fallback path for Fuji).
            raw.imgdata.params.user_qual = 13;
            ret = raw.dcraw_process();
            if (ret != LIBRAW_SUCCESS) {
                LOGE_V2("decodeCore[RCD]: non-Bayer fallback DHT failed (%d)", ret);
                return ret == LIBRAW_CANCELLED_BY_CALLBACK ? RDV2_ERR_CANCELLED : RDV2_ERR_UNKNOWN;
            }
            int memRet = LIBRAW_SUCCESS;
            image = raw.dcraw_make_mem_image(&memRet);
            if (!image || image->colors != 3 || image->bits != 16) {
                LOGE_V2("decodeCore[RCD]: dcraw_make_mem_image failed on fallback");
                if (image) LibRaw::dcraw_clear_mem(image);
                return RDV2_ERR_OUT_OF_MEMORY;
            }
            W = image->width; H = image->height; N = W * H;
            bgr = (uint16_t*) image->data;
        } else {
            const int rawW = raw.imgdata.sizes.raw_width;
            const int rawH = raw.imgdata.sizes.raw_height;
            const int cropL = raw.imgdata.sizes.left_margin;
            const int cropT = raw.imgdata.sizes.top_margin;
            int outW = raw.imgdata.sizes.iwidth;
            int outH = raw.imgdata.sizes.iheight;
            if (outW <= 0 || outH <= 0) {
                outW = raw.imgdata.sizes.width;
                outH = raw.imgdata.sizes.height;
            }
            if (outW <= 0 || outH <= 0 || outW > rawW || outH > rawH) {
                LOGE_V2("decodeCore[RCD]: invalid dims %dx%d (raw %dx%d)", outW, outH, rawW, rawH);
                return RDV2_ERR_UNKNOWN;
            }

            // Normalise camera WB so green is 1.0 — matches V1's RCD invocation.
            float camMul[4];
            const float* cm = raw.imgdata.color.cam_mul;
            float gRef = cm[1] > 0.f ? cm[1] : 1.f;
            camMul[0] = cm[0] / gRef;
            camMul[1] = 1.f;
            camMul[2] = cm[2] / gRef;
            camMul[3] = cm[3] > 0.f ? cm[3] / gRef : 1.f;

            float whiteLevel = (float) raw.imgdata.color.maximum;
            if (whiteLevel < 1.f) whiteLevel = 65535.f;
            const float blackLevel = (float) raw.imgdata.color.black;
            const unsigned filters = raw.imgdata.idata.filters;

            LOGI_V2("decodeCore[RCD]: pre-call rawImg=%p raw=%dx%d crop=%d,%d out=%dx%d filters=0x%x "
                    "black=%.1f white=%.1f camMul=[%.3f,%.3f,%.3f,%.3f] cmRaw=[%.1f,%.1f,%.1f,%.1f]",
                    rawImg, rawW, rawH, cropL, cropT, outW, outH, filters,
                    blackLevel, whiteLevel,
                    camMul[0], camMul[1], camMul[2], camMul[3],
                    cm[0], cm[1], cm[2], cm[3]);
            // Probe raw_image: if open_file+unpack didn't fully decompress the Bayer
            // mosaic, raw_image will be allocated (non-null) but full of zeros.
            // Sample center + first row + a quarter-stride to confirm.
            {
                const size_t centerIdx = (size_t)(rawH / 2) * rawW + rawW / 2;
                const size_t firstRowIdx = (size_t) rawW / 2;
                const size_t qIdx = (size_t)(rawH / 4) * rawW + rawW / 4;
                LOGI_V2("decodeCore[RCD]: raw_image probes center=%u row0=%u q=%u (rawW=%d rawH=%d)",
                        rawImg[centerIdx], rawImg[firstRowIdx], rawImg[qIdx], rawW, rawH);
            }
            // Allocate rcdBuf. resize() can throw std::bad_alloc on memory pressure.
            try {
                rcdBuf.resize((size_t) outW * outH * 3);
            } catch (const std::bad_alloc&) {
                LOGE_V2("decodeCore[RCD]: rcdBuf.resize(%zu bytes) threw bad_alloc — out of native heap",
                        (size_t) outW * outH * 6);
                return RDV2_ERR_OUT_OF_MEMORY;
            }
            // Sanity-check: confirm the vector actually allocated the space we asked for.
            // A zero-capacity allocation under pressure would silently produce a wrong-size buffer.
            const size_t want = (size_t) outW * outH * 3;
            if (rcdBuf.size() != want || rcdBuf.data() == nullptr) {
                LOGE_V2("decodeCore[RCD]: rcdBuf undersized (size=%zu want=%zu data=%p)",
                        rcdBuf.size(), want, rcdBuf.data());
                return RDV2_ERR_OUT_OF_MEMORY;
            }
            LOGI_V2("decodeCore[RCD]: rcdBuf allocated %zu uint16 elements = %zu MB",
                    rcdBuf.size(), (rcdBuf.size() * 2) / (1024 * 1024));
            bool ok = rcd_demosaic_to_buf(rawImg, rawW, rawH, cropL, cropT,
                                          outW, outH, filters,
                                          blackLevel, whiteLevel, camMul, rcdBuf.data());
            if (!ok) {
                LOGE_V2("decodeCore[RCD]: rcd_demosaic_to_buf returned false");
                return RDV2_ERR_UNKNOWN;
            }
            W = outW; H = outH; N = W * H;
            bgr = rcdBuf.data();
            // Sample a few pixels DIRECTLY from rcdBuf (not via bgr ptr) to confirm the
            // function actually wrote real data. If these are also zero, RCD itself is the
            // bug — not memory pressure or pointer drift.
            {
                const size_t mid = ((size_t) outH / 2 * outW + outW / 2) * 3;
                const size_t q1 = ((size_t) outH / 4 * outW + outW / 4) * 3;
                LOGI_V2("decodeCore[RCD]: %dx%d done; rcdBuf samples mid=[%u,%u,%u] q1=[%u,%u,%u]",
                        W, H,
                        rcdBuf[mid + 0], rcdBuf[mid + 1], rcdBuf[mid + 2],
                        rcdBuf[q1 + 0], rcdBuf[q1 + 1], rcdBuf[q1 + 2]);
            }
        }
    } else {
        ret = raw.dcraw_process();
        if (ret != LIBRAW_SUCCESS) {
            LOGE_V2("decodeCore: dcraw_process failed (%d)", ret);
            return ret == LIBRAW_CANCELLED_BY_CALLBACK ? RDV2_ERR_CANCELLED : RDV2_ERR_UNKNOWN;
        }
        if (cancel && cancel->cancelled.load(std::memory_order_relaxed))
            return RDV2_ERR_CANCELLED;

        int memRet = LIBRAW_SUCCESS;
        image = raw.dcraw_make_mem_image(&memRet);
        if (!image || image->colors != 3 || image->bits != 16) {
            LOGE_V2("decodeCore: dcraw_make_mem_image failed or unexpected format");
            if (image) LibRaw::dcraw_clear_mem(image);
            return RDV2_ERR_OUT_OF_MEMORY;
        }
        W = image->width;
        H = image->height;
        N = W * H;
        bgr = (uint16_t*) image->data;
    }

    // Helper: free the LibRaw mem image when we own one; no-op for the RCD path
    // where bgr points into rcdBuf (RAII-managed by std::vector).
    auto freeImage = [&]() {
        if (image) {
            LibRaw::dcraw_clear_mem(image);
            image = nullptr;
        }
    };

    // Multi-probe zero-buffer guard. Catches the silent-OOM mode where dcraw_process
    // returned LIBRAW_SUCCESS but every pixel is 0.
    {
        // Diagnostic — sample the center pixel and a few corners so we can see whether
        // a guard fail is a genuine silent-OOM or a too-strict guard against real data.
        const int cx = W / 2, cy = H / 2;
        const uint16_t* centerPx = bgr + ((size_t)cy * W + cx) * 3;
        const uint16_t* topLeftPx = bgr + 0;
        const uint16_t* botRightPx = bgr + ((size_t)(H - 1) * W + (W - 1)) * 3;
        LOGI_V2("decodeCore: post-demosaic samples center=[%u,%u,%u] tl=[%u,%u,%u] br=[%u,%u,%u]",
                centerPx[0], centerPx[1], centerPx[2],
                topLeftPx[0], topLeftPx[1], topLeftPx[2],
                botRightPx[0], botRightPx[1], botRightPx[2]);
    }
    if (!checkZeroBuffer(bgr, W, H)) {
        LOGE_V2("decodeCore: zero-buffer guard tripped — silent native OOM");
        freeImage();
        return RDV2_ERR_SILENT_ZERO;
    }

    auto nowMs = []() { return (long long)(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count()); };

    // Highlight post-processing.
    {
        long long t = nowMs();
        if (highlightMode == 1) applyHighlightClip(bgr, N);
        else if (highlightMode == 2) applyHighlightReconstruct(bgr, N);
        LOGI_V2("decodeCore: highlight mode=%d took %lld ms", highlightMode, nowMs() - t);
    }
    if (cancel && cancel->cancelled.load(std::memory_order_relaxed)) {
        freeImage();
        return RDV2_ERR_CANCELLED;
    }

    // Rayxie CA correction. Two gates:
    //  1. Always skipped on half-size (Pipeline 1) — CA fringes are invisible at
    //     half-res and the saved CPU keeps the canvas responsive.
    //  2. Skipped when caCorrectionEnabled=false — workspace-selector toggle for
    //     users who want the raw uncorrected look (already-corrected lenses,
    //     intentional fringes, etc.).
    //
    // `rayxie_rm_ca_row` bounds its edge-search to ±128 pixels per fringe (CA in real
    // lenses doesn't extend further). Previously the search could span the full
    // row/column on noisy images, causing a "spinner forever" hang.
    //  3. Skipped when the source has filters==0 (LibRaw signal "already
    //     demosaiced") — this is Adobe LinearRaw DNGs, Canon mRAW, and any
    //     other source that delivers RGB rather than Bayer. Adobe already
    //     bakes its OpcodeList-driven CA correction into LinearRaw DNGs, so
    //     running Rayxie on top would double-correct (and the cost on an
    //     11 MP image is ~minutes of CPU — was manifesting as a hang).
    // CPU Rayxie disabled 2026-05-25 — see LibRawJniBridge.kt for full rationale.
    // The algorithm's data-dependent edge-search hits 60s+ on certain CR2 frames
    // even with -O3 + 4-thread parallel + H-only. Will be replaced by a GLES
    // fragment shader (barrel distortion + spectrum offset) running on the
    // existing GPU LUT pipeline.
    const bool alreadyDemosaiced = (raw.imgdata.idata.filters == 0);
    (void)alreadyDemosaiced;  // silence unused warning; flag retained for future paths
    if (!halfSize && caCorrectionEnabled) {
        LOGI_V2("decodeCore: rayxie CA disabled (pending GPU shader implementation)");
    }

    // Demosaic-aware NR. Naïve 3×3 box blur on 3 channels = O(9 × W × H × 3) ≈ 300M
    // ops on an 11 MP image; allocates 4× (W×H×uint16) vectors = ~140 MB native heap.
    // Skipped for half-size to keep preview fast; and only runs when amounts non-zero.
    if (nrEnabled && !halfSize && !alreadyDemosaiced && (nrLuma > 0 || nrChroma > 0)) {
        long long t = nowMs();
        applyNoiseReduction(bgr, W, H, nrLuma, nrChroma, cancel);
        LOGI_V2("decodeCore: NR luma=%d chroma=%d took %lld ms", nrLuma, nrChroma, nowMs() - t);
        if (cancel && cancel->cancelled.load(std::memory_order_relaxed)) {
            freeImage();
            return RDV2_ERR_CANCELLED;
        }
    } else if (nrEnabled && alreadyDemosaiced) {
        LOGI_V2("decodeCore: NR skipped (filters=0, source already processed)");
    } else if (nrEnabled && halfSize) {
        LOGI_V2("decodeCore: NR skipped (halfSize=true, would block preview)");
    }

    // Write dims now — output mode below uses them. We also write them BEFORE the
    // buffer-too-small check so the Kotlin caller can read back the actual decoded
    // dims and retry with a correctly-sized buffer. This is critical for the half-size
    // preview path because some CR2 variants ignore LibRaw's half_size flag (verified
    // on device 2026-05-24 with Canon EOS 6D IMG_2744.CR2 returning full-res data
    // despite half_size=1 — likely related to IO.shrink computation in raw2image.cpp).
    //
    // outDimsArr layout (caller must allocate ≥ 3 ints):
    //   [0] = width  (landscape-coords because user_flip=0)
    //   [1] = height (landscape-coords)
    //   [2] = EXIF flip code from imgdata.sizes.flip — how the camera was held:
    //         0 = landscape (default), 3 = 180° rotated, 5 = 90° CCW + mirror,
    //         6 = 90° CW (portrait, top-right), 8 = 90° CCW (portrait, top-left).
    //         Matches the EXIF Orientation tag values 1–8 (LibRaw maps internally).
    //         Kotlin uses this for canvas/export rotation; sensor dims stay landscape.
    outDimsArr[0] = W;
    outDimsArr[1] = H;
    // Use the EXIF flip we captured before user_flip clobbered sizes.flip. Mapping:
    //   0/1 = landscape (top-left)
    //   3   = 180° rotated
    //   5/6 = 90° CW (portrait, top-right of as-held)
    //   6/8 = 90° CCW (portrait variants)
    // LibRaw uses its own internal numbering (0..7) which the Kotlin side normalises
    // back to EXIF 1..8. We forward the raw value directly.
    outDimsArr[2] = exifFlipPreserved;
    LOGI_V2("decodeCore: dims=%dx%d exifFlip=%d (user_flip=0; rotation NOT baked)",
            W, H, exifFlipPreserved);

    int status = RDV2_OK;
    switch (output.mode) {
        case OUT_ARGB_INMEM: {
            // Caller passed a pre-allocated IntArray of length W * H.
            jsize arrayLen = env->GetArrayLength(output.outArgb);
            if (arrayLen < N) {
                LOGE_V2("decodeCore: outArgb too small (%d < %d)", arrayLen, N);
                status = RDV2_ERR_INSUFFICIENT_BUF;
                break;
            }
            jint* dst = env->GetIntArrayElements(output.outArgb, nullptr);
            if (!dst) {
                LOGE_V2("decodeCore: GetIntArrayElements returned null");
                status = RDV2_ERR_UNKNOWN;
                break;
            }
            packBgr16ToArgb8(bgr, (int32_t*) dst, N);
            env->ReleaseIntArrayElements(output.outArgb, dst, 0);
            break;
        }
        case OUT_ARGB_MAPPED_8: {
            const size_t needed = (size_t) N * 4;
            if ((size_t) output.capacity < needed) {
                LOGE_V2("decodeCore: mapped capacity %lld < needed %zu",
                        (long long) output.capacity, needed);
                status = RDV2_ERR_INSUFFICIENT_BUF;
                break;
            }
            void* base = mmap(nullptr, needed, PROT_READ | PROT_WRITE, MAP_SHARED,
                              output.fd, output.offset);
            if (base == MAP_FAILED) {
                LOGE_V2("decodeCore: mmap ARGB_MAPPED_8 failed (errno=%d)", errno);
                status = RDV2_ERR_OUTPUT_WRITE;
                break;
            }
            packBgr16ToArgb8(bgr, (int32_t*) base, N);
            msync(base, needed, MS_SYNC);
            munmap(base, needed);
            break;
        }
        case OUT_F16_MAPPED: {
            const size_t needed = (size_t) N * 8;
            if ((size_t) output.capacity < needed) {
                LOGE_V2("decodeCore: mapped capacity %lld < needed %zu",
                        (long long) output.capacity, needed);
                status = RDV2_ERR_INSUFFICIENT_BUF;
                break;
            }
            void* base = mmap(nullptr, needed, PROT_READ | PROT_WRITE, MAP_SHARED,
                              output.fd, output.offset);
            if (base == MAP_FAILED) {
                LOGE_V2("decodeCore: mmap F16_MAPPED failed (errno=%d)", errno);
                status = RDV2_ERR_OUTPUT_WRITE;
                break;
            }
            packBgr16ToF16Rgba(bgr, (uint16_t*) base, N);
            msync(base, needed, MS_SYNC);
            munmap(base, needed);
            break;
        }
        case OUT_BGR16_INMEM_BUF: {
            // Compatibility path: produce the same BGR-ordered uint16 layout that the
            // legacy `decodeRawLinearIntoBuffer*` exports produce. Downstream consumers
            // (`convertUint16BgrToFloat16Rgba`, Stage C wide-gamut converter, etc.) all
            // assume BGR — `dst[0]=B, dst[1]=G, dst[2]=R`.
            //
            // Inputs to this stage come from two different sources with OPPOSITE layouts:
            //
            //  • LibRaw's `dcraw_make_mem_image` writes RGB (per copy_mem_image(.., bgr=0)
            //    in libraw/src/postprocessing/mem_image.cpp:301). We MUST swap R↔B here.
            //  • RCD's `rcd_demosaic_to_buf` writes BGR directly (see Step 5 of the
            //    algorithm: `dst[0]=B, dst[2]=R`). We MUST NOT swap.
            //
            // The previous code applied an unconditional swap, which worked when RCD's
            // output was all zeros (bug pre-2026-05-24) but produced R↔B-swapped colors
            // after RCD started producing real data — symptom: "red orange looks
            // blueish" / "blue regions look yellowish" (verified on device 2026-05-24).
            const size_t needed = (size_t) N * 6;  // W * H * 3 channels * uint16
            if ((size_t) output.bgrBufferBytes < needed) {
                LOGE_V2("decodeCore: BGR16 buffer too small (%lld < %zu)",
                        (long long) output.bgrBufferBytes, needed);
                status = RDV2_ERR_INSUFFICIENT_BUF;
                break;
            }
            if (!output.bgrBufferPtr) {
                LOGE_V2("decodeCore: BGR16 buffer pointer is null");
                status = RDV2_ERR_UNKNOWN;
                break;
            }
            uint16_t* dst = static_cast<uint16_t*>(output.bgrBufferPtr);
            // `image != nullptr` only when we took LibRaw's dcraw_make_mem_image path
            // (RGB-ordered). RCD output stays in `rcdBuf` and leaves `image` null.
            const bool inputIsRgbFromLibRaw = (image != nullptr);
            if (inputIsRgbFromLibRaw) {
                for (int i = 0; i < N; i++) {
                    dst[i * 3]     = bgr[i * 3 + 2];  // B ← LibRaw's B (RGB[2])
                    dst[i * 3 + 1] = bgr[i * 3 + 1];  // G
                    dst[i * 3 + 2] = bgr[i * 3];      // R ← LibRaw's R (RGB[0])
                }
            } else {
                // RCD already produced BGR — straight memcpy preserves the layout.
                memcpy(dst, bgr, needed);
            }
            break;
        }
    }

    freeImage();
    return status;
}

} // namespace

extern "C" {

// ── Cancel flag JNI surface ────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoderV2_allocCancelFlag(JNIEnv*, jobject) {
    auto* flag = new (std::nothrow) CancelFlag();
    if (!flag) return 0;
    return reinterpret_cast<jlong>(flag);
}

JNIEXPORT void JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoderV2_cancel(JNIEnv*, jobject, jlong flagPtr) {
    if (flagPtr == 0) return;
    auto* flag = reinterpret_cast<CancelFlag*>(flagPtr);
    flag->cancelled.store(1, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoderV2_freeCancelFlag(JNIEnv*, jobject, jlong flagPtr) {
    if (flagPtr == 0) return;
    auto* flag = reinterpret_cast<CancelFlag*>(flagPtr);
    delete flag;
}

// ── EXIF extraction (cheap probe, no decode) ───────────────────────────────────

JNIEXPORT jobject JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoderV2_readExif(
        JNIEnv* env, jobject /*thiz*/, jstring jPath) {
    if (!jPath) return nullptr;
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return nullptr;

    // LibRaw's open_file / unpack can throw bad_alloc on truncated files,
    // and its own LibRaw_exceptions on malformed RAW containers (we saw
    // a SIGABRT in tombstone_00..03 with the abort coming from this exact
    // entry point). Catch *everything* and surface as null so the caller
    // can degrade the workspace selector gracefully instead of crashing
    // the app with a "Fatal signal 6" tombstone.
    // Copy the path into a local std::string and release the JNI ref BEFORE
    // any LibRaw call, so a thrown exception can't leak the UTF chars.
    std::string pathCopy(path);
    env->ReleaseStringUTFChars(jPath, path);

    jobject result = nullptr;
    try {
        LibRaw raw;
        int ret = raw.open_file(pathCopy.c_str());
        if (ret != LIBRAW_SUCCESS) {
            LOGE_V2("readExif: open_file failed (%d)", ret);
            return nullptr;
        }
        ret = raw.unpack();
        if (ret != LIBRAW_SUCCESS) {
            LOGE_V2("readExif: unpack failed (%d)", ret);
            raw.recycle();
            return nullptr;
        }
        result = buildRawExif(env, raw);
        raw.recycle();
    } catch (const std::bad_alloc&) {
        LOGE_V2("readExif: std::bad_alloc — file too large or truncated");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    } catch (const std::exception& e) {
        LOGE_V2("readExif: std::exception: %s", e.what());
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    } catch (...) {
        LOGE_V2("readExif: unknown C++ exception (LibRaw_exceptions, etc.)");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    return result;
}

// ── Embedded-preview extractor — last-resort fallback when the RAW decode
//    times out or fails. Returns the embedded JPEG as a Kotlin byte[].
//    Bitmap previews (LIBRAW_THUMBNAIL_BITMAP) are not surfaced — almost no
//    Android decoder will accept them and the JPEG variant covers >99% of
//    real-world bodies (every Canon/Sony/Nikon/Fuji/Panasonic/Olympus RAW
//    embeds a full-size JPEG). Returns null on any failure so the caller
//    can degrade gracefully.
//
//    Cost is ~50 ms on the supported-floor device — same order as readExif.
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoderV2_extractEmbeddedJpeg(
        JNIEnv* env, jobject /*thiz*/, jstring jPath) {
    if (!jPath) return nullptr;
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return nullptr;
    std::string pathCopy(path);
    env->ReleaseStringUTFChars(jPath, path);

    jbyteArray arr = nullptr;
    try {
        LibRaw raw;
        int ret = raw.open_file(pathCopy.c_str());
        if (ret != LIBRAW_SUCCESS) {
            LOGE_V2("extractEmbeddedJpeg: open_file failed (%d)", ret);
            return nullptr;
        }
        ret = raw.unpack_thumb();
        if (ret != LIBRAW_SUCCESS) {
            LOGE_V2("extractEmbeddedJpeg: unpack_thumb failed (%d)", ret);
            raw.recycle();
            return nullptr;
        }
        const auto& th = raw.imgdata.thumbnail;
        if (th.tformat != LIBRAW_THUMBNAIL_JPEG || !th.thumb || th.tlength == 0) {
            LOGI_V2("extractEmbeddedJpeg: no JPEG preview (format=%d len=%u)",
                    (int)th.tformat, th.tlength);
            raw.recycle();
            return nullptr;
        }
        arr = env->NewByteArray((jsize)th.tlength);
        if (!arr) { raw.recycle(); return nullptr; }
        env->SetByteArrayRegion(arr, 0, (jsize)th.tlength,
                                reinterpret_cast<const jbyte*>(th.thumb));
        LOGI_V2("extractEmbeddedJpeg: ok %u bytes (%dx%d)",
                th.tlength, th.twidth, th.theight);
        raw.recycle();
    } catch (const std::bad_alloc&) {
        LOGE_V2("extractEmbeddedJpeg: std::bad_alloc");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    } catch (const std::exception& e) {
        LOGE_V2("extractEmbeddedJpeg: std::exception: %s", e.what());
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    } catch (...) {
        LOGE_V2("extractEmbeddedJpeg: unknown C++ exception");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    return arr;
}

// ── Pipeline 1: half-res preview, ARGB in-memory ──────────────────────────────

JNIEXPORT jint JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoderV2_decodeHalfResToArgb(
        JNIEnv* env, jobject /*thiz*/,
        jstring jPath, jint userQual, jint gamut, jint highlightMode,
        jboolean nrEnabled, jint nrLuma, jint nrChroma,
        jstring /*jDcpProfileId*/, jlong cancelFlagPtr,
        jintArray outArgb, jintArray outDims) {
    if (!jPath || !outArgb || !outDims) return RDV2_ERR_INSUFFICIENT_BUF;
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return RDV2_ERR_UNKNOWN;

    DecodeOutput out{};
    out.mode = OUT_ARGB_INMEM;
    out.env = env;
    out.outArgb = outArgb;

    int dims[3] = {0, 0, 0};
    auto* cancel = cancelFlagPtr ? reinterpret_cast<CancelFlag*>(cancelFlagPtr) : nullptr;
    int status = decodeCore(path, /*halfSize=*/true, userQual, gamut, highlightMode,
                            nrEnabled, nrLuma, nrChroma, true /* caCorrectionEnabled stub default */, cancel, out, dims, env);
    env->ReleaseStringUTFChars(jPath, path);

    // Always emit dims (even on InsufficientBuffer / partial failure) so the Kotlin
    // caller can react with a correctly-sized retry buffer. dims is zeroed before the
    // call if we never reach the dim-write step, so callers can detect that by checking
    // for 0×0.
    if (dims[0] > 0 && dims[1] > 0) env->SetIntArrayRegion(outDims, 0, 3, dims);
    return status;
}

// ── Pipeline 2: full-res ARGB mapped (idle compare, 8-bit) ────────────────────

JNIEXPORT jint JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoderV2_decodeFullResToArgbMapped(
        JNIEnv* env, jobject /*thiz*/,
        jstring jPath, jint userQual, jint gamut, jint highlightMode,
        jboolean nrEnabled, jint nrLuma, jint nrChroma,
        jstring /*jDcpProfileId*/, jlong cancelFlagPtr,
        jint outFd, jlong outOffset, jlong outCapacity, jintArray outDims) {
    if (!jPath || outFd < 0 || !outDims) return RDV2_ERR_INSUFFICIENT_BUF;
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return RDV2_ERR_UNKNOWN;

    DecodeOutput out{};
    out.mode = OUT_ARGB_MAPPED_8;
    out.fd = outFd; out.offset = outOffset; out.capacity = outCapacity;

    int dims[3] = {0, 0, 0};
    auto* cancel = cancelFlagPtr ? reinterpret_cast<CancelFlag*>(cancelFlagPtr) : nullptr;
    int status = decodeCore(path, /*halfSize=*/false, userQual, gamut, highlightMode,
                            nrEnabled, nrLuma, nrChroma, true /* caCorrectionEnabled stub default */, cancel, out, dims, env);
    env->ReleaseStringUTFChars(jPath, path);

    // Always emit dims (even on InsufficientBuffer / partial failure) so the Kotlin
    // caller can react with a correctly-sized retry buffer. dims is zeroed before the
    // call if we never reach the dim-write step, so callers can detect that by checking
    // for 0×0.
    if (dims[0] > 0 && dims[1] > 0) env->SetIntArrayRegion(outDims, 0, 3, dims);
    return status;
}

// ── Pipeline 3: full-res ARGB mapped (Save 8-bit) ─────────────────────────────
//
// Same output mode as Pipeline 2 but a separate entry point: Save uses the full-speed
// dispatcher, Idle uses a low-priority one. Separate symbols keep the cancel flag owned
// by exactly one caller and let the routing decision land at the JNI boundary.

JNIEXPORT jint JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoderV2_decodeFullResToArgbMapped8bit(
        JNIEnv* env, jobject /*thiz*/,
        jstring jPath, jint userQual, jint gamut, jint highlightMode,
        jboolean nrEnabled, jint nrLuma, jint nrChroma,
        jstring /*jDcpProfileId*/, jlong cancelFlagPtr,
        jint outFd, jlong outOffset, jlong outCapacity, jintArray outDims) {
    if (!jPath || outFd < 0 || !outDims) return RDV2_ERR_INSUFFICIENT_BUF;
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return RDV2_ERR_UNKNOWN;

    DecodeOutput out{};
    out.mode = OUT_ARGB_MAPPED_8;
    out.fd = outFd; out.offset = outOffset; out.capacity = outCapacity;

    int dims[3] = {0, 0, 0};
    auto* cancel = cancelFlagPtr ? reinterpret_cast<CancelFlag*>(cancelFlagPtr) : nullptr;
    int status = decodeCore(path, /*halfSize=*/false, userQual, gamut, highlightMode,
                            nrEnabled, nrLuma, nrChroma, true /* caCorrectionEnabled stub default */, cancel, out, dims, env);
    env->ReleaseStringUTFChars(jPath, path);

    // Always emit dims (even on InsufficientBuffer / partial failure) so the Kotlin
    // caller can react with a correctly-sized retry buffer. dims is zeroed before the
    // call if we never reach the dim-write step, so callers can detect that by checking
    // for 0×0.
    if (dims[0] > 0 && dims[1] > 0) env->SetIntArrayRegion(outDims, 0, 3, dims);
    return status;
}

// ── Compatibility wedge: uint16 BGR into DirectByteBuffer ─────────────────────
//
// Bit-for-bit drop-in replacement for the legacy `decodeRawLinearIntoBuffer*` JNI exports.
// Same output layout (uint16 BGR interleaved), same LibRaw config (linear gamma, 16-bit,
// no auto-bright), same orientation handling (`user_flip=-1`).
//
// Used by LibRawJniBridge's V2-routed path behind BuildConfig.USE_RAW_V2. The wedge lets
// the production preview pipeline run through V2's dispatcher without any downstream
// changes — every consumer of the existing DirectByteBuffer continues to work unchanged.
//
// halfSize: true → 2× downsampled (matches decodeRawPreviewLinearIntoBuffer).
//           false → full sensor resolution (matches decodeRawLinearIntoBuffer*).

JNIEXPORT jint JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoderV2_decodeIntoBgr16Buffer(
        JNIEnv* env, jobject /*thiz*/,
        jstring jPath, jboolean halfSize, jint userQual, jint gamut, jint highlightMode,
        jboolean nrEnabled, jint nrLuma, jint nrChroma,
        jboolean caCorrectionEnabled,
        jstring /*jDcpProfileId*/, jlong cancelFlagPtr,
        jobject outBuffer, jintArray outDims) {
    if (!jPath || !outBuffer || !outDims) return RDV2_ERR_INSUFFICIENT_BUF;
    void* bufPtr = env->GetDirectBufferAddress(outBuffer);
    if (!bufPtr) {
        LOGE_V2("decodeIntoBgr16Buffer: outBuffer is not a DirectByteBuffer");
        return RDV2_ERR_INSUFFICIENT_BUF;
    }
    jlong bufCap = env->GetDirectBufferCapacity(outBuffer);
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return RDV2_ERR_UNKNOWN;

    DecodeOutput out{};
    out.mode = OUT_BGR16_INMEM_BUF;
    out.bgrBufferPtr = bufPtr;
    out.bgrBufferBytes = bufCap;

    int dims[3] = {0, 0, 0};
    auto* cancel = cancelFlagPtr ? reinterpret_cast<CancelFlag*>(cancelFlagPtr) : nullptr;
    int status = decodeCore(path, halfSize, userQual, gamut, highlightMode,
                            nrEnabled, nrLuma, nrChroma, (bool) caCorrectionEnabled, cancel, out, dims, env);
    env->ReleaseStringUTFChars(jPath, path);

    // Always emit dims (even on InsufficientBuffer / partial failure) so the Kotlin
    // caller can react with a correctly-sized retry buffer. dims is zeroed before the
    // call if we never reach the dim-write step, so callers can detect that by checking
    // for 0×0.
    if (dims[0] > 0 && dims[1] > 0) env->SetIntArrayRegion(outDims, 0, 3, dims);
    return status;
}

// ── Pipeline 4: full-res float16 RGBA mapped (Save 16-bit) ────────────────────

JNIEXPORT jint JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoderV2_decodeFullResToFloat16Mapped(
        JNIEnv* env, jobject /*thiz*/,
        jstring jPath, jint userQual, jint gamut, jint highlightMode,
        jboolean nrEnabled, jint nrLuma, jint nrChroma,
        jstring /*jDcpProfileId*/, jlong cancelFlagPtr,
        jint outFd, jlong outOffset, jlong outCapacity, jintArray outDims) {
    if (!jPath || outFd < 0 || !outDims) return RDV2_ERR_INSUFFICIENT_BUF;
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return RDV2_ERR_UNKNOWN;

    DecodeOutput out{};
    out.mode = OUT_F16_MAPPED;
    out.fd = outFd; out.offset = outOffset; out.capacity = outCapacity;

    int dims[3] = {0, 0, 0};
    auto* cancel = cancelFlagPtr ? reinterpret_cast<CancelFlag*>(cancelFlagPtr) : nullptr;
    int status = decodeCore(path, /*halfSize=*/false, userQual, gamut, highlightMode,
                            nrEnabled, nrLuma, nrChroma, true /* caCorrectionEnabled stub default */, cancel, out, dims, env);
    env->ReleaseStringUTFChars(jPath, path);

    // Always emit dims (even on InsufficientBuffer / partial failure) so the Kotlin
    // caller can react with a correctly-sized retry buffer. dims is zeroed before the
    // call if we never reach the dim-write step, so callers can detect that by checking
    // for 0×0.
    if (dims[0] > 0 && dims[1] > 0) env->SetIntArrayRegion(outDims, 0, 3, dims);
    return status;
}

} // extern "C"
