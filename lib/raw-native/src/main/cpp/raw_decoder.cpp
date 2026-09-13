#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <vector>
#include <memory>
#include <cmath>
#include <cstdlib>  // calloc / free (Hist-Neutralizer histogram)
#include <chrono>
#include <climits>
#include <thread>
#include <mutex>
#include <string>
#include <strings.h>  // strncasecmp
#include "libraw/libraw.h"

#define LOG_TAG "RawDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Persistent LibRaw instance ────────────────────────────────────────────────
// Survives across JNI calls so that setManualWhiteBalance / resetToAsShot can
// mutate params on the same instance that was used for the last decode.
// Populated by decodeRawLinearIntoBuffer / decodeRawPreviewLinearIntoBuffer;
// guarded by the Java-side bufferMutex in StudioEditorComponent.
static LibRaw* g_rawProcessor = nullptr;
// Re-decode optimization (recycle_datastream).
//   When a decode is called with the SAME source as the previous successful
//   decode, we can skip open_buffer + unpack and just re-run dcraw_process
//   with fresh params. That saves ~200-500ms on every slider-driven
//   re-render of the same file. Source identity is tracked by:
//     • g_lastSourcePath — for path-based decode entry points
//     • g_lastSourceLen + first/last 32 bytes hash — for ByteArray entries
//       (full hash would defeat the speed-up; partial hash is robust enough
//       for "is this the exact same file content?")
static std::string g_lastSourcePath;
static size_t      g_lastSourceLen      = 0;
static uint64_t    g_lastSourceHash     = 0;
static bool        g_lastDecodeSucceeded = false;

/**
 * Cheap partial-hash for source identity. Reads the first 32 bytes, the
 * last 32 bytes, and the length. Two different RAW files differ in nearly
 * every byte; same file twice produces identical hash with zero false
 * collisions in practice.
 */
static uint64_t cheapSourceHash(const void* data, size_t len) {
    if (!data || len < 64) return 0;
    const uint8_t* p = (const uint8_t*)data;
    uint64_t h = (uint64_t)len * 0x9E3779B97F4A7C15ULL;
    for (int i = 0; i < 32; ++i) {
        h ^= p[i];
        h *= 0xBF58476D1CE4E5B9ULL;
    }
    for (int i = 0; i < 32; ++i) {
        h ^= p[len - 32 + i];
        h *= 0x94D049BB133111EBULL;
    }
    return h;
}

/**
 * Returns true when the previous successful decode used the same source
 * AND the existing g_rawProcessor is still alive — in which case the
 * caller can skip open_buffer + unpack and just re-run dcraw_process()
 * with new params on the existing instance. Caller MUST hold
 * g_rawProcessorMutex.
 */
static bool canReuseLastDecode(const std::string& sourcePath,
                                size_t sourceLen,
                                uint64_t sourceHash) {
    if (!g_rawProcessor) return false;
    if (!g_lastDecodeSucceeded) return false;
    if (!sourcePath.empty()) {
        return sourcePath == g_lastSourcePath;
    }
    return sourceLen == g_lastSourceLen && sourceHash == g_lastSourceHash;
}

/**
 * Stamp the source-identity globals after a successful decode so the
 * NEXT call can decide whether to fast-path.
 */
static void recordSuccessfulSource(const std::string& sourcePath,
                                    size_t sourceLen,
                                    uint64_t sourceHash) {
    g_lastSourcePath      = sourcePath;
    g_lastSourceLen       = sourceLen;
    g_lastSourceHash      = sourceHash;
    g_lastDecodeSucceeded = true;
}

static void invalidateLastSource() {
    g_lastSourcePath.clear();
    g_lastSourceLen       = 0;
    g_lastSourceHash      = 0;
    g_lastDecodeSucceeded = false;
}

// Mutex protecting all access to g_rawProcessor. The Kotlin side has its
// own `bufferMutex` that already serialises the V1 decode entry points,
// but auxiliary JNI calls (setRawWb / setRawCameraWb / get*) take a
// different code path and previously raced with in-flight decodes — a
// concurrent WB write during dcraw_process() could corrupt
// `imgdata.params` mid-pipeline and crash. This native mutex is the
// defensive belt: every JNI entry that touches `g_rawProcessor` takes a
// `std::lock_guard` at entry. Recursive lock not needed — internal
// helpers (applyQualityImprovements / probeVendorHints / etc.) operate
// on the LibRaw& passed in, not on the global.
static std::mutex g_rawProcessorMutex;
static bool g_useCameraWb = true;
static float g_userMul[4] = { 1.0f, 1.0f, 1.0f, 1.0f };

// Last LibRaw return code from any V1 entry point. Surfaced to Kotlin
// via getLastLibRawError() so callers can distinguish unsupported-camera
// from I/O-error from no-thumbnail. Updated on every decode (success or
// failure). Initialised to 0 = LIBRAW_SUCCESS = "no decode attempted yet".
//
// Not thread-local: V1 paths are serialised by the JNI bufferMutex on
// the Kotlin side; concurrent decodes are not possible from the V1 API.
static int g_lastLibRawError = 0;

// Custom error code outside LibRaw's range (-100 reserved for our own
// floating-point-RAW guard).
static constexpr int RAZ_FLOATING_POINT_UNSUPPORTED = -100;

/**
 * Quality-improvement params applied unconditionally before dcraw_process()
 * on every decode path. Cross-references with libraw 0.22 API docs:
 *
 *   • green_matching = 1        → equalises G1/G2 in RGGB Bayer to suppress
 *                                  the maze pattern some Sony A7-series and
 *                                  older Pentax bodies show in highlights.
 *                                  Free for every Bayer file.
 *   • med_passes (ISO-adaptive) → 1 post-demosaic median pass when ISO is
 *                                  high. Kills isolated colour speckle in
 *                                  dark areas without touching the
 *                                  pre-demosaic FBDD chroma NR. Skipped at
 *                                  base ISO so detail is preserved.
 *   • exp_preser = 1            → when user dials a positive exposure shift
 *                                  (params.exp_shift > 0 AND exp_correc=1),
 *                                  preserve highlights instead of clipping.
 *                                  Otherwise +1 EV blows skies / specular
 *                                  highlights into pure white.
 *
 * Note: `green_matching` lives in libraw_output_params_t (the same struct
 * as the other params here). It only takes effect on Bayer sources — for
 * X-Trans / Foveon / DNG-RGB it's silently ignored, so it's safe to set
 * unconditionally.
 */
// Forward-declared; defined below `applyQualityImprovements`.
static void probeVendorHints(LibRaw &raw);

static void applyQualityImprovements(LibRaw &raw) {
    raw.imgdata.params.green_matching = 1;
    // Explicit defensive: use the camera matrix when computing the
    // camera→output-space transform. Default is 1 (use camera matrix
    // when WB is camera/auto), but setting explicitly documents intent
    // and protects against future LibRaw default changes. Critical for
    // wider-gamut output paths (ProPhoto / AdobeRGB / Rec.2020) where
    // the matrix is what bridges camera primaries to the chosen space.
    raw.imgdata.params.use_camera_matrix = 1;
    // Vendor-specific shooting metadata probe — surfaces HTP / ALO /
    // pixel-shift hints via raz*() getters. Cheap (single struct read).
    probeVendorHints(raw);
    // ISO-adaptive median passes — read iso_speed only AFTER unpack() so
    // imgdata.other is populated. Callers ensure that ordering.
    float iso = raw.imgdata.other.iso_speed;
    if (iso >= 3200.0f) {
        raw.imgdata.params.med_passes = 1;
    } else {
        raw.imgdata.params.med_passes = 0;
    }
    // exp_preser only matters when exp_correc is on and user shift > 0.
    if (raw.imgdata.params.exp_correc == 1 && raw.imgdata.params.exp_shift > 1.0f) {
        raw.imgdata.params.exp_preser = 1.0f;
    }
    // DCB tuning: when DCB (user_qual=4) is selected, give it 2 refinement
    // iterations + edge enhancement. Default is 0 iterations / no enhance,
    // which makes DCB visibly worse than AHD. With these knobs it beats
    // AHD on fine textures (the whole point of offering it).
    if (raw.imgdata.params.user_qual == 4) {
        raw.imgdata.params.dcb_iterations = 2;
        raw.imgdata.params.dcb_enhance_fl = 1;
    }
}

// ──                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            ──────────────────────────────────────────────────────────
//
// Global luminance normalization applied to the raw linear 16-bit RGB buffer
// AFTER LibRaw's dcraw_process() (with no_auto_bright=1) has produced a purely
// linear decode. This replaces LibRaw's legacy 1%-clip auto-bright with an
// intelligent whole-scene approach:
//
//   1. Build a full 65536-bin Rec.709 luminance histogram (no precision loss).
//   2. Read the MEDIAN (P50) — robust to bright windows / dark voids in a way
//      the mean is not — plus the 98th percentile as a highlight anchor.
//   3. Derive a brighten-only scale that maps the median to a PERCEPTUAL
//      middle gray expressed in LINEAR light (≈14025 = sRGB 0.5; the buffer is
//      linear, so the old gamma-domain 128/255≈32896 target over-brightened).
//   4. Throttle the scale if it would push P98 past white, then apply it
//      uniformly to all channels with a 64-bit-safe 65535 clamp.
//
// Benefits:
//   • Consistent AI inputs (Restormer, CodeFormer, etc.) — always receive
//     a uniformly balanced exposure level.
//   • Highlight protection — the P98 throttle keeps a bright gain from
//     clipping skies/skin/clouds to flat white (soft limit, not a guarantee).
//   • Total creative control — the user's exposure slider starts from a
//     known neutral baseline.
//
// Returned: the computed scale factor (autoBrightFactor ≥ 1.0). The Kotlin
// side stores this and feeds it to the "Smart Bright" slider for undo-aware
// exposure interpolation. Returns 1.0 when the scene median is already at or
// above the perceptual mid-gray target (no brightening applied).
//
// Performance: histogram pass + a 65536-bin CDF walk + an apply pass. The
// 256 KB histogram fits L2; the constant /10000 lowers to multiply+shift, so
// on a 24 MP image (~36 M uint16) this stays within the Stage A budget.
//
static float applyHistNeutralizer(uint16_t* rgb16, int width, int height) {
    const size_t totalPx = (size_t)width * height;
    if (totalPx == 0 || !rgb16) return 1.f;

    // ── Pass 1: build a full 16-bit luminance histogram (65536 bins) ──
    // A median/percentile anchor needs the distribution, not just the mean.
    // The mean is dragged around by a large bright window or a dark void; the
    // median is robust to both. 65536×uint32 = 256 KB on the heap (fits L2);
    // each bin counts ≤ totalPx pixels, well within uint32.
    uint32_t* hist = (uint32_t*)calloc(65536, sizeof(uint32_t));
    if (!hist) return 1.f;
    const uint16_t* p = rgb16;
    for (size_t i = 0; i < totalPx; ++i) {
        // Rec.709 integer-scaled weights (2126+7152+722 = 10000). Division by
        // the compile-time constant 10000 lowers to a multiply+shift, not a
        // real divide, so this stays cheap.
        uint32_t luma = (p[0] * 2126u + p[1] * 7152u + p[2] * 722u) / 10000u;
        if (luma > 65535u) luma = 65535u;
        hist[luma]++;
        p += 3;
    }

    // ── Pass 2: walk the CDF for the median (P50) and a highlight anchor (P98) ──
    const size_t medianCount = totalPx / 2;
    const size_t p98Count    = (size_t)((double)totalPx * 0.98);
    size_t acc = 0;
    uint16_t medianLuma16 = 0;
    uint16_t p98Luma16 = 65535;
    bool foundMedian = false;
    for (uint32_t bin = 0; bin < 65536u; ++bin) {
        acc += hist[bin];
        if (!foundMedian && acc >= medianCount) { medianLuma16 = (uint16_t)bin; foundMedian = true; }
        if (acc >= p98Count) { p98Luma16 = (uint16_t)bin; break; }
    }
    free(hist);

    if (medianLuma16 < 1) return 1.f;  // pitch-black frame — avoid div-by-zero

    // Perceptual target: middle gray. The buffer is LINEAR (gamm=1.0 in Stage A),
    // so the target must be the LINEAR value of mid-gray, NOT the gamma-encoded
    // 8-bit 128 (which is ≈0.50 linear ≈ 188/255 on screen — the old over-bright
    // bug). sRGB 0.5 → linear ≈ 0.214 → 14025;  18% gray → linear ≈ 0.18 → 11796.
    // Tune this if the baseline ends up too dark/bright for your taste.
    const double TARGET_LINEAR_MIDGRAY = 14025.0;

    // Brighten-only gain anchored on the scene median.
    double scaleFactor = TARGET_LINEAR_MIDGRAY / (double)medianLuma16;
    if (scaleFactor <= 1.0) {
        LOGI("HistNeutralizer: median=%u p98=%u target=%.0f — already bright, scale=1.0",
             medianLuma16, p98Luma16, TARGET_LINEAR_MIDGRAY);
        return 1.f;
    }

    // Highlight throttle: if the median-driven gain would push the 98th
    // percentile past white, compromise toward a highlight-safe gain so skies /
    // skin / clouds keep structure instead of clipping to flat white. This is a
    // SOFT limit (it splits the difference) — not a hard no-clip guarantee.
    if ((double)p98Luma16 * scaleFactor > 65535.0) {
        double safeScale = 65535.0 / (double)p98Luma16;
        double throttled = (scaleFactor + safeScale) * 0.5;
        scaleFactor = throttled < 1.0 ? 1.0 : throttled;
    }
    if (scaleFactor > 16.0) scaleFactor = 16.0;  // +4 EV ceiling

    LOGI("HistNeutralizer: median=%u p98=%u target=%.0f scale=%.4f (+%.2f EV)",
         medianLuma16, p98Luma16, TARGET_LINEAR_MIDGRAY, scaleFactor, std::log2(scaleFactor));

    // ── Pass 3: uniform multiply, 65535 clamp. The product MUST be 64-bit: at
    // the 16× cap a bright pixel gives 65535 × 1,048,576 ≈ 6.87e10, far past
    // uint32's 4.29e9 — the old uint32 product wrapped and turned would-be-white
    // pixels into dark speckles (hidden by the Stage B downscale, visible in
    // full-res saves).
    uint16_t* dst = rgb16;
    const uint64_t scaleQ16 = (uint64_t)(scaleFactor * 65536.0 + 0.5);
    for (size_t i = 0; i < totalPx; ++i) {
        uint64_t r = ((uint64_t)dst[0] * scaleQ16) >> 16;
        uint64_t g = ((uint64_t)dst[1] * scaleQ16) >> 16;
        uint64_t b = ((uint64_t)dst[2] * scaleQ16) >> 16;
        dst[0] = (uint16_t)(r > 65535 ? 65535 : r);
        dst[1] = (uint16_t)(g > 65535 ? 65535 : g);
        dst[2] = (uint16_t)(b > 65535 ? 65535 : b);
        dst += 3;
    }

    return (float)scaleFactor;
}

/**
 * Rotate a uint16 RGB buffer in-place to match LibRaw's `imgdata.sizes.flip`
 * value. dcraw_process() does this internally, but our custom RCD path
 * bypasses dcraw_process and writes the sensor-native orientation — so
 * portrait shots from a horizontal-sensor body come out sideways unless we
 * rotate ourselves.
 *
 * LibRaw flip codes (dcraw convention):
 *   0 = no rotation
 *   3 = 180°
 *   5 = 90° CCW (sensor was rotated CCW → image needs CW rotation back)
 *   6 = 90° CW  (sensor was rotated CW → image needs CCW rotation back)
 *
 * Allocates a scratch buffer the size of the image; copies to scratch with
 * the appropriate index permutation, then copies back to the destination
 * (which is the caller's DirectByteBuffer). New dimensions are written to
 * outW/outH so the caller can update its size record.
 *
 * For flip = 5 or 6 the dimensions swap (W ↔ H).
 *
 * Returns true on success, false on alloc failure (image left as-is).
 */
static bool applyFlipInPlace(uint16_t* buf, int& w, int& h, int flip) {
    if (flip == 0) return true;
    const size_t pxCount = (size_t)w * h;
    const size_t bytes   = pxCount * 3 * sizeof(uint16_t);
    std::unique_ptr<uint16_t[]> tmp(new (std::nothrow) uint16_t[pxCount * 3]);
    if (!tmp) return false;
    memcpy(tmp.get(), buf, bytes);

    if (flip == 3) {
        // 180°: dst[i] = src[N-1-i]
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                int srcY = h - 1 - y, srcX = w - 1 - x;
                uint16_t* dst = buf + ((size_t)y * w + x) * 3;
                const uint16_t* src = tmp.get() + ((size_t)srcY * w + srcX) * 3;
                dst[0] = src[0]; dst[1] = src[1]; dst[2] = src[2];
            }
        }
        return true;
    }
    if (flip == 5 || flip == 6) {
        // 90°: dimensions swap. New dst is h × w (rows = old w).
        int newW = h, newH = w;
        for (int y = 0; y < newH; ++y) {
            for (int x = 0; x < newW; ++x) {
                int srcY, srcX;
                if (flip == 6) { // CW 90°: src(x, h-1-y)
                    srcY = h - 1 - x;
                    srcX = y;
                } else { // flip == 5, CCW 90°: src(w-1-x, y)
                    srcY = x;
                    srcX = w - 1 - y;
                }
                uint16_t* dst = buf + ((size_t)y * newW + x) * 3;
                const uint16_t* src = tmp.get() + ((size_t)srcY * w + srcX) * 3;
                dst[0] = src[0]; dst[1] = src[1]; dst[2] = src[2];
            }
        }
        w = newW;
        h = newH;
        return true;
    }
    // Unknown flip code — leave unchanged.
    return true;
}

/**
 * Color fringing mode pass-through.
 *
 * IMPORTANT — investigation finding: LibRaw's `imgdata.params.aber[]` is NOT
 * an "enable lateral CA correction" toggle. It's a manual per-channel RADIAL
 * SCALING FACTOR (postprocessing_utils_dcrdefs.cpp:222–240): for each pixel
 * at (row, col), R and B are sampled from (row * aber[c], col * aber[c])
 * relative to image centre. Default = 1.0 = no scaling = no correction. To
 * actually correct CA you'd need per-lens calibration constants like
 * `aber[0] = 1.0007` — which dcraw_emu accepts as CLI args because you need
 * a lens-specific value. LibRaw has no built-in lens database.
 *
 * The "free CA correction at decode" finding from the earlier audit was
 * therefore wrong. Two actually-working options:
 *   • Stick with the existing Rayxie runtime CA pass (Kotlin gated by
 *     `caCorrectionEnabled` — already shipped).
 *   • Integrate lensfun later (separate task — needs lens DB + EXIF probe).
 *
 * For now this function:
 *   • Records the mode in a static so a later runtime purple-fringe shader
 *     pass can read it (Strong mode = run the pass).
 *   • Leaves `aber[]` at 1.0 (no-op) since setting it to anything else
 *     without per-lens data would actively HURT image quality.
 *
 * The mode value still threads end-to-end so the Strong-mode purple-fringe
 * pass (Stage B/C) can read it; this just doesn't touch LibRaw decode.
 */
static int g_colorFringingMode = 0;
static void applyColorFringing(LibRaw &raw, int mode) {
    g_colorFringingMode = mode;
    // aber[] intentionally left at default — see comment above.
    (void)raw;
}

/** Read back the last mode the Kotlin side set, for the Stage B/C gating. */
extern "C" int razColorFringingMode() { return g_colorFringingMode; }

// ── Canon-specific decode hints ─────────────────────────────────────────────
//   Surfaced via getter functions so Stage B / coordinator code can read
//   them without holding a reference to g_rawProcessor (lifetime-fragile).
//
//   • g_canonHtpEvCompensation: EV stops to ADD at Stage B when Canon HTP
//     ("Highlight Tone Priority") was on at capture. Canon dims sensor data
//     by ~1 stop with HTP on so highlights have extra headroom; without
//     compensation the editor opens the photo looking 1 stop dark.
//
//   • g_canonAloShadowsMuteFactor: scale factor in [0..1] to apply to the
//     CLAHE shadows-boost slider when Canon ALO ("Auto Lighting Optimizer")
//     was on at capture. ALO already lifted shadows in-camera; stacking our
//     CLAHE on top double-processes them into muddy mid-shadows.
//
//   • g_pixelShiftDetected: set true when the file is a Sony/Olympus/Pentax
//     pixel-shift composite. We DON'T support pixel-shift demosaic; just
//     log it so users on those bodies know why they're not getting the
//     resolution boost.
static float g_canonHtpEvCompensation     = 0.f;
static float g_canonAloShadowsMuteFactor  = 1.f;
static bool  g_pixelShiftDetected         = false;

extern "C" float razCanonHtpEvCompensation()    { return g_canonHtpEvCompensation; }
extern "C" float razCanonAloShadowsMuteFactor() { return g_canonAloShadowsMuteFactor; }
extern "C" int   razPixelShiftDetected()        { return g_pixelShiftDetected ? 1 : 0; }

/**
 * Probe vendor-specific shooting metadata after `unpack()` and stash the
 * resulting adjustment hints into the globals above. Idempotent — safe to
 * call from every decode path. Quiet — only logs when a hint actually fires.
 *
 * Currently handles:
 *   • Canon HighlightTonePriority (HTP):
 *       value 1 = on  → suggest +1 EV at Stage B (LibRaw doesn't compensate)
 *       value 2 = "enhanced" on some bodies (R5/R6) → also ~+1 EV
 *       value 0 / -1 = off / unavailable → no compensation
 *   • Canon AutoLightingOptimizer (ALO):
 *       0 = Standard, 1 = Low, 2 = Strong, 3 = Disabled (encoding varies)
 *       For >0 we mute the CLAHE shadows boost proportionally so the editor
 *       doesn't stack shadow lift on top of in-camera ALO.
 *   • Pixel-shift detection across vendors.
 */
static void probeVendorHints(LibRaw &raw) {
    g_canonHtpEvCompensation    = 0.f;
    g_canonAloShadowsMuteFactor = 1.f;
    g_pixelShiftDetected        = false;

    const char* normMake = raw.imgdata.idata.normalized_make;
    bool isCanon = normMake && strncasecmp(normMake, "Canon", 5) == 0;

    if (isCanon) {
        const auto& canon = raw.imgdata.makernotes.canon;
        // HTP values vary by body (1 = on for most; some bodies use 2 for
        // "enhanced"). Treat any positive value as "on".
        if (canon.HighlightTonePriority > 0) {
            g_canonHtpEvCompensation = 1.0f;
            LOGI("probeVendorHints: Canon HTP=%d → suggesting +1.0 EV "
                 "compensation at Stage B", canon.HighlightTonePriority);
        }
        // ALO encoding (from ExifTool): 0 = Standard, 1 = Low,
        // 2 = Strong, 3 = Disabled. Many older bodies use 0 to mean
        // "disabled" too — we only act when value is 1 or 2.
        int alo = canon.AutoLightingOptimizer;
        if (alo == 1) {
            g_canonAloShadowsMuteFactor = 0.65f; // -35% CLAHE shadows lift
            LOGI("probeVendorHints: Canon ALO=Low → muting CLAHE shadows "
                 "boost to 65%%");
        } else if (alo == 2) {
            g_canonAloShadowsMuteFactor = 0.35f; // -65% CLAHE shadows lift
            LOGI("probeVendorHints: Canon ALO=Strong → muting CLAHE shadows "
                 "boost to 35%%");
        }
    }

    // Pixel-shift detection — vendor-specific markers:
    //   • Olympus high-res: olympus.StackedImage[0] > 0 (frame index, not 0).
    //   • Panasonic/Leica/Yuneec multi-shot: panasonic.Multishot != 0
    //     (65536 specifically = pixel-shift per ExifTool).
    //   • Canon multi-exposure: canon.multishot[0] > 1.
    // Sony pixel-shift is detected via a separate file format (.ARQ) which
    // LibRaw handles as a multi-page TIFF; not flagged here.
    bool ps = false;
    if (raw.imgdata.makernotes.olympus.StackedImage[0]  > 0u) ps = true;
    if (raw.imgdata.makernotes.panasonic.Multishot      != 0u) ps = true;
    if (raw.imgdata.makernotes.canon.multishot[0]       > 1u)  ps = true;
    if (ps) {
        g_pixelShiftDetected = true;
        LOGI("probeVendorHints: multi-shot / pixel-shift detected — "
             "decoded as single-shot (not yet supported by this pipeline)");
    }
}

/**
 * Log LibRaw's non-fatal error counter after a successful decode. LibRaw
 * silently absorbs corrupt-thumbnail / missing-EXIF / truncated-tag errors
 * and increments this counter — when it's non-zero the decoded image is
 * still rendered but parts of the metadata are unreliable. Surface it so
 * triage on user "this file looks weird" reports doesn't have to guess.
 */
static void logErrorCount(LibRaw &raw, const char* tag) {
    int n = raw.error_count();
    if (n > 0) {
        LOGI("%s: LibRaw absorbed %d non-fatal error(s) during decode", tag, n);
    }
}

/**
 * Floating-point RAW guard. Canon CRM (high-fps), Hasselblad 3FR, and
 * ARRIRAW deliver floating-point sensor data via `imgdata.rawdata.float_image`
 * (or `float3_image` / `float4_image` for multi-channel), NOT the unsigned
 * `raw_image` array our demosaic pipeline reads from. Without this guard
 * the decode silently produces all-black output (or crashes via null
 * pointer in some configs).
 *
 * Returns true if the file is a supported integer-Bayer source; false if
 * it's an FP-RAW we don't yet handle. Caller should bail with a clear
 * error message rather than feeding garbage downstream.
 */
static bool isSupportedIntegerRaw(LibRaw &raw) {
    if (raw.is_floating_point()) {
        LOGE("isSupportedIntegerRaw: file is floating-point RAW "
             "(Canon CRM / Hasselblad 3FR / ARRIRAW) — not supported by "
             "the v3 demosaic path");
        g_lastLibRawError = RAZ_FLOATING_POINT_UNSUPPORTED;
        return false;
    }
    return true;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_getLastLibRawError(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_lastLibRawError;
}

// Pixel-shift / multi-shot detection result from the most recent decode.
// Set by probeVendorHints() during applyQualityImprovements(). Returns 1
// when the source file is a Sony pixel-shift / Olympus high-res / Pentax
// pixel-shift composite. The decode itself proceeds as single-shot — this
// flag exists so the Kotlin side can surface a user-facing warning that
// the multi-shot resolution boost requires merging via the camera vendor's
// software (Sony Imaging Edge, Olympus Workspace, Pentax Digital Camera
// Utility) BEFORE opening in this app.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_wasLastDecodePixelShift(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_pixelShiftDetected ? JNI_TRUE : JNI_FALSE;
}

static void configureWhiteBalance(LibRaw &raw) {
    if (g_useCameraWb) {
        raw.imgdata.params.use_camera_wb = 1;
        raw.imgdata.params.use_auto_wb = 0;
    } else {
        raw.imgdata.params.use_camera_wb = 0;
        raw.imgdata.params.use_auto_wb = 0;
        raw.imgdata.params.user_mul[0] = g_userMul[0];
        raw.imgdata.params.user_mul[1] = g_userMul[1];
        raw.imgdata.params.user_mul[2] = g_userMul[2];
        raw.imgdata.params.user_mul[3] = g_userMul[3];
    }
}

// Copy 16-bit BGR LibRaw output → uint16_t RGB DirectByteBuffer (full resolution).
static void copyBgr16ToRgbBuffer(const uint16_t* src, uint16_t* dst, int width, int height) {
    int N = width * height;
    for (int i = 0; i < N; i++) {
        dst[i * 3]     = src[i * 3 + 2]; // R
        dst[i * 3 + 1] = src[i * 3 + 1]; // G
        dst[i * 3 + 2] = src[i * 3];     // B
    }
}

// 2× box downsample 16-bit BGR → uint16_t RGB (preview path).
static void copyBgr16Downsample2xToRgbBuffer(
        const uint16_t* src, uint16_t* dst, int srcW, int srcH) {
    int halfW = srcW / 2, halfH = srcH / 2;
    for (int hy = 0; hy < halfH; hy++) {
        for (int hx = 0; hx < halfW; hx++) {
            int sx = hx * 2, sy = hy * 2;
            const uint16_t* p00 = src + (sy * srcW + sx) * 3;
            const uint16_t* p10 = src + (sy * srcW + sx + 1) * 3;
            const uint16_t* p01 = src + ((sy + 1) * srcW + sx) * 3;
            const uint16_t* p11 = src + ((sy + 1) * srcW + sx + 1) * 3;
            int o = (hy * halfW + hx) * 3;
            dst[o]     = (uint16_t)(((int)p00[2] + p10[2] + p01[2] + p11[2]) / 4);
            dst[o + 1] = (uint16_t)(((int)p00[1] + p10[1] + p01[1] + p11[1]) / 4);
            dst[o + 2] = (uint16_t)(((int)p00[0] + p10[0] + p01[0] + p11[0]) / 4);
        }
    }
}

// R/B ratio → Kelvin lookup table (daylight locus, sRGB output space).
// Higher R/B = warmer (lower K).
static const float RB_VALS[] = { 7.5f, 5.1f, 3.6f, 2.2f, 1.5f, 1.05f, 0.86f, 0.70f, 0.56f, 0.44f };
static const int   K_VALS[]  = { 2000, 2500, 3000, 4000, 5000, 5500,  6500,  7500,  9000, 12000 };
static const int   K_TABLE_N = 10;

static int rbToKelvin(float rb) {
    if (rb >= RB_VALS[0])         return K_VALS[0];
    if (rb <= RB_VALS[K_TABLE_N - 1]) return K_VALS[K_TABLE_N - 1];
    for (int i = 0; i < K_TABLE_N - 1; i++) {
        if (rb <= RB_VALS[i] && rb >= RB_VALS[i + 1]) {
            float t = (rb - RB_VALS[i]) / (RB_VALS[i + 1] - RB_VALS[i]);
            return (int)(K_VALS[i] + t * (float)(K_VALS[i + 1] - K_VALS[i]) + 0.5f);
        }
    }
    return 5500;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_decodeRaw(
        JNIEnv *env,
        jobject thiz,
        jbyteArray raw_data) {

    jsize len  = env->GetArrayLength(raw_data);
    jbyte *data = env->GetByteArrayElements(raw_data, nullptr);

    LibRaw rawProcessor;

    int ret = rawProcessor.open_buffer(data, len);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("open_buffer failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }

    ret = rawProcessor.unpack();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("unpack failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }
    if (!isSupportedIntegerRaw(rawProcessor)) {
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }

    // ── Output params ────────────────────────────────────────────────────────
    // Set AFTER open_buffer()+unpack(): open_buffer calls open_datastream()
    // which re-initialises imgdata (including params) to defaults, so any
    // values written before open_buffer() are silently discarded.
    // use_camera_wb = 1: apply the WB multipliers the camera recorded at
    // capture time.  Without this, Sony ARW files render with a strong
    // blue/purple cast on the A7 III.
    rawProcessor.imgdata.params.use_camera_wb  = 1;
    rawProcessor.imgdata.params.output_color   = 1;   // sRGB
    rawProcessor.imgdata.params.output_bps     = 8;   // 8-bit per channel
    rawProcessor.imgdata.params.no_auto_bright = 0;   // allow auto-brightness
    rawProcessor.imgdata.params.highlight      = 0;   // clip highlights
    applyQualityImprovements(rawProcessor);

    ret = rawProcessor.dcraw_process();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("dcraw_process failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }

    libraw_processed_image_t *image = rawProcessor.dcraw_make_mem_image(&ret);
    if (!image) {
        LOGE("dcraw_make_mem_image failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }

    // ── Create Bitmap ────────────────────────────────────────────────────────
    jclass    bitmapClass    = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmap   = env->GetStaticMethodID(bitmapClass, "createBitmap",
                                   "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass    configClass    = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID  argb8888Field  = env->GetStaticFieldID(configClass, "ARGB_8888",
                                   "Landroid/graphics/Bitmap$Config;");
    jobject   config         = env->GetStaticObjectField(configClass, argb8888Field);

    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmap,
                                                 (jint)image->width,
                                                 (jint)image->height,
                                                 config);
    void *pixels;
    AndroidBitmap_lockPixels(env, bitmap, &pixels);

    // ── Copy pixels ──────────────────────────────────────────────────────────
    // LibRaw RGB24 → Android ARGB_8888 (uint32 = 0xAARRGGBB on little-endian)
    if (image->colors == 3 && image->bits == 8) {
        uint8_t  *src = image->data;
        uint32_t *dst = (uint32_t *)pixels;
        int n = image->width * image->height;
        for (int i = 0; i < n; i++) {
            dst[i] = (0xFF    << 24)
                   | (src[i * 3 + 2] << 16)   // R (LibRaw outputs BGR for sRGB)
                   | (src[i * 3 + 1] <<  8)   // G
                   |  src[i * 3];              // B
        }
    } else if (image->colors == 3 && image->bits == 16) {
        // Fallback: 16-bit output (should not occur with output_bps=8)
        uint16_t *src = (uint16_t *)image->data;
        uint32_t *dst = (uint32_t *)pixels;
        int n = image->width * image->height;
        for (int i = 0; i < n; i++) {
            uint8_t r = (uint8_t)(src[i * 3 + 2] >> 8);
            uint8_t g = (uint8_t)(src[i * 3 + 1] >> 8);
            uint8_t b = (uint8_t)(src[i * 3]     >> 8);
            dst[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
    } else {
        LOGE("Unsupported image format: colors=%d bits=%d", image->colors, image->bits);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    LibRaw::dcraw_clear_mem(image);
    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
    return bitmap;
}

// ── Decode to linear float32 RGB (scene-referred) ────────────────────────────
// Output: float32 RGB, normalised to [0,1], with camera WB applied.
// Dimensions written to outSize[0]=width, outSize[1]=height.
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_decodeRawLinear(
        JNIEnv *env,
        jobject thiz,
        jbyteArray raw_data,
        jintArray out_size) {

    jsize len  = env->GetArrayLength(raw_data);
    jbyte *data = env->GetByteArrayElements(raw_data, nullptr);

    LibRaw rawProcessor;

    int ret = rawProcessor.open_buffer(data, len);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawLinear open_buffer failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }

    ret = rawProcessor.unpack();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawLinear unpack failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }
    if (!isSupportedIntegerRaw(rawProcessor)) {
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }

    // ── Linear float32 output params ─────────────────────────────────────────
    configureWhiteBalance(rawProcessor);
    rawProcessor.imgdata.params.output_color   = 1;    // sRGB primaries
    rawProcessor.imgdata.params.output_bps     = 16;   // 16-bit unsigned
    rawProcessor.imgdata.params.no_auto_bright = 1;    // preserve linear luminance
    rawProcessor.imgdata.params.highlight      = 0;  // clip highlights to white (neutral, no color cast)
    rawProcessor.imgdata.params.gamm[0]        = 1.0;  // no gamma applied
    rawProcessor.imgdata.params.gamm[1]        = 0.0;  // (second param to power law)
    applyQualityImprovements(rawProcessor);

    ret = rawProcessor.dcraw_process();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawLinear dcraw_process failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }

    libraw_processed_image_t *image = rawProcessor.dcraw_make_mem_image(&ret);
    if (!image) {
        LOGE("decodeRawLinear dcraw_make_mem_image failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }

    // ── Write dimensions ─────────────────────────────────────────────────────
    jint *size_ptr = env->GetIntArrayElements(out_size, nullptr);
    size_ptr[0] = image->width;
    size_ptr[1] = image->height;
    env->ReleaseIntArrayElements(out_size, size_ptr, 0);

    // ── Allocate float32 output array ────────────────────────────────────────
    jsize float_count = (jsize)image->width * image->height * 3;
    jfloatArray float_array = env->NewFloatArray(float_count);
    if (!float_array) {
        LOGE("decodeRawLinear NewFloatArray failed");
        LibRaw::dcraw_clear_mem(image);
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }

    // ── Convert 16-bit BGR → float32 RGB [0,1] ──────────────────────────────
    // LibRaw outputs 16-bit BGR (little-endian), max value typically 65535.
    // Normalise to [0,1] for scene-linear processing.
    if (image->colors == 3 && image->bits == 16) {
        jfloat *float_ptr = env->GetFloatArrayElements(float_array, nullptr);
        uint16_t *src = (uint16_t *)image->data;
        int n = image->width * image->height;

        for (int i = 0; i < n; i++) {
            float b = src[i * 3] / 65535.0f;
            float g = src[i * 3 + 1] / 65535.0f;
            float r = src[i * 3 + 2] / 65535.0f;
            float_ptr[i * 3]     = r;
            float_ptr[i * 3 + 1] = g;
            float_ptr[i * 3 + 2] = b;
        }
        env->ReleaseFloatArrayElements(float_array, float_ptr, 0);
    } else if (image->colors == 3 && image->bits == 8) {
        // Fallback: 8-bit output (should not occur with output_bps=16)
        jfloat *float_ptr = env->GetFloatArrayElements(float_array, nullptr);
        uint8_t *src = image->data;
        int n = image->width * image->height;

        for (int i = 0; i < n; i++) {
            float_ptr[i * 3]     = src[i * 3 + 2] / 255.0f;  // R
            float_ptr[i * 3 + 1] = src[i * 3 + 1] / 255.0f;  // G
            float_ptr[i * 3 + 2] = src[i * 3]     / 255.0f;  // B
        }
        env->ReleaseFloatArrayElements(float_array, float_ptr, 0);
    } else {
        LOGE("decodeRawLinear unsupported format: colors=%d bits=%d",
             image->colors, image->bits);
        LibRaw::dcraw_clear_mem(image);
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }

    LibRaw::dcraw_clear_mem(image);
    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);

    LOGI("decodeRawLinear success: %d×%d → float32 RGB [0,1]",
         image->width, image->height);

    return float_array;
}

// ── White-balance metadata read ───────────────────────────────────────────────
// Reads the camera's shot white balance from RAW metadata without demosaicing.
// Returns an estimated colour temperature in Kelvin [2000, 12000],
// or -1 if the file is not a supported RAW or metadata is unavailable.
extern "C"
JNIEXPORT jint JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_readWhiteBalanceKelvin(
        JNIEnv *env,
        jobject thiz,
        jbyteArray raw_data) {

    jsize  len  = env->GetArrayLength(raw_data);
    jbyte *data = env->GetByteArrayElements(raw_data, nullptr);

    LibRaw rawProcessor;
    int ret = rawProcessor.open_buffer(data, len);
    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);

    if (ret != LIBRAW_SUCCESS) {
        LOGE("readWB open_buffer failed: %s", libraw_strerror(ret));
        return -1;
    }

    // cam_mul[] is populated by open_buffer() from the RAW metadata.
    // [0]=R  [1]=G1  [2]=B  [3]=G2 (G2 present on RGBG Bayer sensors, else 0)
    float *cam = rawProcessor.imgdata.color.cam_mul;

    float g = cam[1];
    if (cam[3] > 0.0f) g = (cam[1] + cam[3]) * 0.5f;  // average both greens

    if (g <= 0.0f || cam[0] <= 0.0f || cam[2] <= 0.0f) {
        LOGE("readWB: invalid cam_mul R=%.2f G=%.2f B=%.2f", cam[0], g, cam[2]);
        return -1;
    }

    float rn = cam[0] / g;   // R/G
    float bn = cam[2] / g;   // B/G
    float rb = rn / bn;      // R/B — higher = warmer (lower K)

    int kelvin = rbToKelvin(rb);
    LOGI("readWB: cam_mul R=%.2f G=%.2f B=%.2f → R/B=%.3f → ~%dK",
         cam[0], g, cam[2], rb, kelvin);

    return kelvin;
}

// ── Heckflosse AMaZE+VNG4 hybrid demosaicing ─────────────────────────────────
// Decodes the RAW twice: once with DHT (detail-preserving, stands in for AMaZE)
// and once with VNG (smooth, stands in for VNG4). Blends based on local luminance
// variance in the VNG output — flat areas get VNG, detailed areas get DHT.
//
// contrastThreshold: 0.0–1.0 normalised contrast at which the blend transitions.
// Typical values: 0.010 (default, similar to RawTherapee threshold=10) – 0.050.
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_decodeRawLinearHybrid(
        JNIEnv *env,
        jobject thiz,
        jbyteArray raw_data,
        jintArray out_size,
        jfloat contrastThreshold) {

    if (!raw_data || !out_size) return nullptr;
    jsize  len  = env->GetArrayLength(raw_data);
    jbyte *data = env->GetByteArrayElements(raw_data, nullptr);
    if (!data) return nullptr;

    auto setLinearParams = [](LibRaw &p, int quality) {
        configureWhiteBalance(p);
        p.imgdata.params.output_color   = 1;
        p.imgdata.params.output_bps     = 16;
        p.imgdata.params.no_auto_bright = 1;
        p.imgdata.params.highlight      = 0;  // clip highlights to white (neutral, no color cast)
        p.imgdata.params.gamm[0]        = 1.0;
        p.imgdata.params.gamm[1]        = 0.0;
        p.imgdata.params.user_qual      = quality;
    };

    // ── Pass 1: AMaZE (heap-allocated to avoid stack overflow) ───────────────
    int W = 0, H = 0;
    std::vector<float> hq;
    {
        auto rawHQ = std::unique_ptr<LibRaw>(new LibRaw());
        int ret = rawHQ->open_buffer(data, len);
        if (ret != LIBRAW_SUCCESS) {
            LOGE("hybrid open_buffer HQ failed: %s", libraw_strerror(ret));
            env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
            return nullptr;
        }
        rawHQ->unpack();
        if (!isSupportedIntegerRaw(*rawHQ)) {
            env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
            return nullptr;
        }
        setLinearParams(*rawHQ, 13);
        applyQualityImprovements(*rawHQ);
        ret = rawHQ->dcraw_process();
        if (ret != LIBRAW_SUCCESS) {
            LOGE("hybrid dcraw_process HQ failed: %s", libraw_strerror(ret));
            env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
            return nullptr;
        }
        libraw_processed_image_t *imgHQ = rawHQ->dcraw_make_mem_image(&ret);
        if (!imgHQ || imgHQ->colors != 3 || imgHQ->bits != 16) {
            LOGE("hybrid HQ image invalid");
            if (imgHQ) LibRaw::dcraw_clear_mem(imgHQ);
            env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
            return nullptr;
        }
        W = imgHQ->width;
        H = imgHQ->height;
        int N = W * H;
        hq.resize(N * 3);
        const uint16_t *src = (const uint16_t *)imgHQ->data;
        for (int i = 0; i < N; i++) {
            hq[i*3]   = src[i*3+2] / 65535.0f;
            hq[i*3+1] = src[i*3+1] / 65535.0f;
            hq[i*3+2] = src[i*3]   / 65535.0f;
        }
        LibRaw::dcraw_clear_mem(imgHQ);
    } // rawHQ destroyed here — frees all LibRaw internal buffers before Pass 2

    // ── Pass 2: VNG (heap-allocated, rawHQ already freed) ────────────────────
    int N = W * H;
    std::vector<float> vng(N * 3);
    {
        auto rawVNG = std::unique_ptr<LibRaw>(new LibRaw());
        int ret = rawVNG->open_buffer(data, len);
        if (ret != LIBRAW_SUCCESS) {
            env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
            return nullptr;
        }
        rawVNG->unpack();
        if (!isSupportedIntegerRaw(*rawVNG)) {
            env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
            return nullptr;
        }
        setLinearParams(*rawVNG, 1);
        applyQualityImprovements(*rawVNG);
        ret = rawVNG->dcraw_process();
        if (ret != LIBRAW_SUCCESS) {
            env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
            return nullptr;
        }
        libraw_processed_image_t *imgVNG = rawVNG->dcraw_make_mem_image(&ret);
        if (!imgVNG || imgVNG->colors != 3 || imgVNG->bits != 16) {
            LOGE("hybrid VNG image invalid");
            if (imgVNG) LibRaw::dcraw_clear_mem(imgVNG);
            env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
            return nullptr;
        }
        const uint16_t *src = (const uint16_t *)imgVNG->data;
        for (int i = 0; i < N; i++) {
            vng[i*3]   = src[i*3+2] / 65535.0f;
            vng[i*3+1] = src[i*3+1] / 65535.0f;
            vng[i*3+2] = src[i*3]   / 65535.0f;
        }
        LibRaw::dcraw_clear_mem(imgVNG);
    } // rawVNG destroyed here

    // ── Compute blend weights from local luminance variance (VNG pass) ────────
    float threshold = (contrastThreshold > 0.0f) ? contrastThreshold : 0.010f;
    std::vector<float> weight(N, 0.0f);

    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            float sum = 0.0f, sumSq = 0.0f;
            int   cnt = 0;
            for (int dy = -2; dy <= 2; dy++) {
                int ny = y + dy;
                if (ny < 0 || ny >= H) continue;
                for (int dx = -2; dx <= 2; dx++) {
                    int nx = x + dx;
                    if (nx < 0 || nx >= W) continue;
                    int idx = (ny * W + nx) * 3;
                    // Rec.709 luminance
                    float lum = 0.2126f * vng[idx] + 0.7152f * vng[idx+1] + 0.0722f * vng[idx+2];
                    sum   += lum;
                    sumSq += lum * lum;
                    cnt++;
                }
            }
            float mean = sum / cnt;
            float var  = sumSq / cnt - mean * mean;
            float std  = sqrtf(var > 0.0f ? var : 0.0f);

            // Smoothstep: 0 at threshold/2, 1 at threshold
            float lo = threshold * 0.5f;
            float t  = (std - lo) / (threshold - lo);
            t = t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
            weight[y * W + x] = t * t * (3.0f - 2.0f * t);  // smoothstep
        }
    }

    // ── Write dimensions ─────────────────────────────────────────────────────
    jint *sizePtr = env->GetIntArrayElements(out_size, nullptr);
    sizePtr[0] = W;
    sizePtr[1] = H;
    env->ReleaseIntArrayElements(out_size, sizePtr, 0);

    // ── Blend DHT and VNG ────────────────────────────────────────────────────
    jfloatArray result = env->NewFloatArray((jsize)(N * 3));
    if (!result) {
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }
    jfloat *out = env->GetFloatArrayElements(result, nullptr);

    for (int i = 0; i < N; i++) {
        float w = weight[i];
        out[i*3]   = vng[i*3]   + w * (hq[i*3]   - vng[i*3]);
        out[i*3+1] = vng[i*3+1] + w * (hq[i*3+1] - vng[i*3+1]);
        out[i*3+2] = vng[i*3+2] + w * (hq[i*3+2] - vng[i*3+2]);
    }

    env->ReleaseFloatArrayElements(result, out, 0);
    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);

    LOGI("decodeRawLinearHybrid: %d×%d AMaZE+VNG blend (threshold=%.4f)", W, H, threshold);
    return result;
}

// ── Fast preview decode ───────────────────────────────────────────────────────
// Single-pass VNG at half_size=1 (2× downscale per axis → ¼ pixels).
// For a 24MP RAW: 6024×4024 → 3012×2012 = 72 MB float32 vs 291 MB full-res.
// Runs in ~2–3 s on LITTLE cores; full AMaZE+VNG is used only at export time.
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_decodeRawPreviewLinear(
        JNIEnv *env,
        jobject thiz,
        jbyteArray raw_data,
        jintArray out_size) {

    if (!raw_data || !out_size) return nullptr;
    jsize  len  = env->GetArrayLength(raw_data);
    jbyte *data = env->GetByteArrayElements(raw_data, nullptr);
    if (!data) return nullptr;

    jfloatArray result = nullptr;
    {
        auto raw = std::unique_ptr<LibRaw>(new LibRaw());
        int ret = raw->open_buffer(data, len);
        if (ret != LIBRAW_SUCCESS) {
            LOGE("preview open_buffer failed: %s", libraw_strerror(ret));
            env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
            return nullptr;
        }
        raw->unpack();
        if (!isSupportedIntegerRaw(*raw)) {
            env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
            return nullptr;
        }

        raw->imgdata.params.use_camera_wb  = 1;
        raw->imgdata.params.output_color   = 1;
        raw->imgdata.params.output_bps     = 16;
        raw->imgdata.params.no_auto_bright = 1;
        raw->imgdata.params.highlight      = 0;  // clip highlights to white (neutral, no color cast)
        raw->imgdata.params.gamm[0]        = 1.0;
        raw->imgdata.params.gamm[1]        = 0.0;
        raw->imgdata.params.user_qual      = 1;  // VNG — clean edges, no colour zipper, ~5x faster than AMaZE
        // No half_size: half_size=1 bypasses demosaicing entirely (raw Bayer avg → magenta/noise artifacts)
        applyQualityImprovements(*raw);

        ret = raw->dcraw_process();
        if (ret != LIBRAW_SUCCESS) {
            LOGE("preview dcraw_process failed: %s", libraw_strerror(ret));
            env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
            return nullptr;
        }

        libraw_processed_image_t *img = raw->dcraw_make_mem_image(&ret);
        if (!img || img->colors != 3 || img->bits != 16) {
            LOGE("preview image invalid");
            if (img) LibRaw::dcraw_clear_mem(img);
            env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
            return nullptr;
        }

        // 2× box-filter downsample in native so Java only receives ¼ the pixels (~72 MB).
        // LibRaw outputs BGR (src[0]=B, src[1]=G, src[2]=R), so swap while downsampling.
        int W = img->width, H = img->height;
        int halfW = W / 2, halfH = H / 2, N = halfW * halfH;
        result = env->NewFloatArray(N * 3);
        if (result) {
            jfloat *out = env->GetFloatArrayElements(result, nullptr);
            const uint16_t *src = (const uint16_t *)img->data;
            const float norm = 0.25f / 65535.0f;  // average 4 pixels + normalise to [0,1]
            for (int hy = 0; hy < halfH; hy++) {
                for (int hx = 0; hx < halfW; hx++) {
                    int sx = hx * 2, sy = hy * 2;
                    const uint16_t *p00 = src + (sy * W + sx) * 3;
                    const uint16_t *p10 = src + (sy * W + sx + 1) * 3;
                    const uint16_t *p01 = src + ((sy + 1) * W + sx) * 3;
                    const uint16_t *p11 = src + ((sy + 1) * W + sx + 1) * 3;
                    int o = (hy * halfW + hx) * 3;
                    out[o]   = (p00[2] + p10[2] + p01[2] + p11[2]) * norm;  // R
                    out[o+1] = (p00[1] + p10[1] + p01[1] + p11[1]) * norm;  // G
                    out[o+2] = (p00[0] + p10[0] + p01[0] + p11[0]) * norm;  // B
                }
            }
            env->ReleaseFloatArrayElements(result, out, 0);

            jint sizes[2] = { halfW, halfH };
            env->SetIntArrayRegion(out_size, 0, 2, sizes);
            LOGI("decodeRawPreviewLinear: %d×%d VNG+2x box-downsample (src %d×%d)", halfW, halfH, W, H);
        }
        LibRaw::dcraw_clear_mem(img);
    }

    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
    return result;
}

// ── N1: Manual white balance ──────────────────────────────────────────────────
// Sets per-channel WB multipliers on the persistent g_rawProcessor instance.
// Disables camera and auto WB so the user-supplied multipliers take effect on
// the next dcraw_process() call.
//
// user_mul layout (RGBG Bayer):
//   [0] = R   [1] = G1   [2] = B   [3] = G2 (mirrors G1 for most sensors)
//
// Called from StudioEditorComponent under bufferMutex.
extern "C"
JNIEXPORT void JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_setManualWhiteBalance(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jfloat wbR, jfloat wbG, jfloat wbB) {
    std::lock_guard<std::mutex> lk(g_rawProcessorMutex);
    g_useCameraWb = false;
    g_userMul[0] = wbR;
    g_userMul[1] = wbG;
    g_userMul[2] = wbB;
    g_userMul[3] = wbG; // G2 mirrors G1
    if (g_rawProcessor) {
        configureWhiteBalance(*g_rawProcessor);
    }
    LOGI("setManualWhiteBalance: R=%.4f G=%.4f B=%.4f (G2=%.4f)", wbR, wbG, wbB, wbG);
}

// ── N2: Reset to as-shot white balance ────────────────────────────────────────
// Restores the camera's original as-shot WB by re-enabling use_camera_wb.
// Disables auto WB so LibRaw uses the recorded cam_mul[] on the next
// dcraw_process() call rather than computing a new auto-WB estimate.
//
// Called from StudioEditorComponent.onResetWb() under bufferMutex.
extern "C"
JNIEXPORT void JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_resetToAsShot(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lk(g_rawProcessorMutex);
    g_useCameraWb = true;
    g_userMul[0] = 1.0f;
    g_userMul[1] = 1.0f;
    g_userMul[2] = 1.0f;
    g_userMul[3] = 1.0f;
    if (g_rawProcessor) {
        configureWhiteBalance(*g_rawProcessor);
    }
    LOGI("resetToAsShot: restored use_camera_wb=1, use_auto_wb=0");
}

// ── N4: Read raw cam_mul[4] multipliers ───────────────────────────────────────
// Returns the camera's as-shot white balance multipliers from RAW metadata
// without demosaicing. Only open_buffer() is called — no unpack() or
// dcraw_process() needed.
//
// cam_mul layout (RGBG Bayer):
//   [0] = R   [1] = G1   [2] = B   [3] = G2 (0 on non-RGBG sensors)
//
// Returns a jfloatArray of length 4, or nullptr if open_buffer fails.
// Used by StudioEditorComponent to populate StudioEditorState.camMul and
// to drive the histogram / WB display without a full decode.
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_readCamMul(
        JNIEnv* env, jobject /*thiz*/, jbyteArray raw_data) {

    jsize  len  = env->GetArrayLength(raw_data);
    jbyte* data = env->GetByteArrayElements(raw_data, nullptr);

    LibRaw rawProcessor;
    int ret = rawProcessor.open_buffer(data, (size_t)len);
    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);

    if (ret != LIBRAW_SUCCESS) {
        LOGE("readCamMul: open_buffer failed: %s", libraw_strerror(ret));
        return nullptr;
    }

    // cam_mul[] is populated by open_buffer() from the RAW metadata.
    // [0]=R  [1]=G1  [2]=B  [3]=G2 (G2 present on RGBG Bayer sensors, else 0)
    //
    // DNG preference: DNG files carry both `cam_mul` (LibRaw-derived) AND
    // the original `as_shot_neutral` tag the camera/DNG converter wrote at
    // capture. The as-shot value is more accurate because it's what the
    // sensor actually measured at the scene; cam_mul is computed back from
    // it (or from cam_xyz × illuminant) and can drift on tricky scenes.
    // When the source is a DNG AND as_shot_neutral is populated, invert it
    // (mul = 1/neutral) to recover the WB multipliers directly.
    float r, g1, b, g2;
    bool isDng = rawProcessor.imgdata.idata.dng_version != 0;
    const float* asn = rawProcessor.imgdata.color.dng_levels.asshotneutral;
    bool asnValid = isDng && asn[0] > 0.f && asn[1] > 0.f && asn[2] > 0.f;
    if (asnValid) {
        // Convention: as_shot_neutral stores the per-channel value of a
        // neutral patch (so a balanced scene = (0.5, 0.5, 0.5)). The WB
        // multiplier is its reciprocal, normalised so the green ref = 1.
        const float gRef = asn[1];
        r  = gRef / asn[0];
        g1 = 1.f;
        b  = gRef / asn[2];
        g2 = 0.f; // DNGs don't separately encode G2; let downstream copy G1.
        LOGI("readCamMul: DNG as_shot_neutral=(%.4f, %.4f, %.4f) → mul (%.4f, 1, %.4f)",
             asn[0], asn[1], asn[2], r, b);
    } else {
        const float* cam = rawProcessor.imgdata.color.cam_mul;
        r = cam[0]; g1 = cam[1]; b = cam[2]; g2 = cam[3];
    }

    jfloatArray result = env->NewFloatArray(4);
    if (!result) {
        LOGE("readCamMul: NewFloatArray(4) failed");
        return nullptr;
    }
    jfloat values[4] = { r, g1, b, g2 };
    env->SetFloatArrayRegion(result, 0, 4, values);

    LOGI("readCamMul: R=%.4f G1=%.4f B=%.4f G2=%.4f", r, g1, b, g2);
    return result;
}

// ── N7: Decode to DirectByteBuffer (16-bit linear RGB) ───────────────────────
// Writes 16-bit linear RGB directly into a caller-provided DirectByteBuffer,
// eliminating the JNI array copy on the hot preview path.
//
// Buffer layout: uint16_t R, G, B interleaved (native byte order).
// Capacity must be >= width * height * 3 * 2 bytes.
//
// Also stores the LibRaw instance in g_rawProcessor so that subsequent
// setManualWhiteBalance / resetToAsShot calls can mutate its params.
// The Kotlin side must guard all access with bufferMutex.
//
// Returns JNI_TRUE on success, JNI_FALSE on any failure.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_decodeRawLinearIntoBuffer(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray raw_data, jintArray out_size, jobject out_buffer,
        jint user_qual, jint color_fringing) {
    std::lock_guard<std::mutex> lk(g_rawProcessorMutex);
    g_colorFringingMode = color_fringing;

    // ── Validate DirectByteBuffer ─────────────────────────────────────────────
    void* bufPtr = env->GetDirectBufferAddress(out_buffer);
    if (!bufPtr) {
        LOGE("decodeRawLinearIntoBuffer: out_buffer is not a DirectByteBuffer or has been GC'd");
        return JNI_FALSE;
    }

    jsize  len  = env->GetArrayLength(raw_data);
    jbyte* data = env->GetByteArrayElements(raw_data, nullptr);
    if (!data) return JNI_FALSE;

    // ── Release previous g_rawProcessor to avoid leaks ───────────────────────
    if (g_rawProcessor) {
        delete g_rawProcessor;
        g_rawProcessor = nullptr;
    }

    // ── Allocate on heap (stack LibRaw causes stack overflow on some devices) ─
    g_rawProcessor = new LibRaw();

    int ret = g_rawProcessor->open_buffer(data, (size_t)len);
    g_lastLibRawError = ret;
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawLinearIntoBuffer: open_buffer failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        delete g_rawProcessor;
        g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    ret = g_rawProcessor->unpack();
    g_lastLibRawError = ret;
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawLinearIntoBuffer: unpack failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        delete g_rawProcessor;
        g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    if (!isSupportedIntegerRaw(*g_rawProcessor)) {
        // g_lastLibRawError set inside isSupportedIntegerRaw().
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        delete g_rawProcessor;
        g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    // ── Set linear output params (AFTER open_buffer + unpack) ────────────────
    configureWhiteBalance(*g_rawProcessor);
    g_rawProcessor->imgdata.params.output_color   = 1;   // sRGB primaries
    g_rawProcessor->imgdata.params.output_bps     = 16;  // 16-bit per channel
    g_rawProcessor->imgdata.params.no_auto_bright = 1;   // preserve linear luminance
    g_rawProcessor->imgdata.params.highlight      = 0;  // clip highlights to white (neutral, no color cast)
    g_rawProcessor->imgdata.params.gamm[0]        = 1.0; // linear gamma
    g_rawProcessor->imgdata.params.gamm[1]        = 0.0;
    int qual = user_qual > 0 ? (int)user_qual : 1;
    g_rawProcessor->imgdata.params.user_qual = qual;
    applyQualityImprovements(*g_rawProcessor);

    ret = g_rawProcessor->dcraw_process();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawLinearIntoBuffer: dcraw_process failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        delete g_rawProcessor;
        g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    // ── copy_mem_image() — write straight into DirectByteBuffer ──────────────
    //   Replaces dcraw_make_mem_image() + memcpy. dcraw_make_mem_image
    //   allocates a ~70 MB intermediate heap buffer (for 24 MP) that lives
    //   alongside the FP16 cache, then we'd copy from it. copy_mem_image
    //   writes RGB directly to caller storage with caller-specified stride.
    //
    //   `bgr=0` → RGB order (our DirectByteBuffer expectation).
    //   stride = width * 3 channels * 2 bytes (16-bit).
    //
    //   Dimension probe via imgdata.sizes (set by dcraw_process); colors
    //   check via imgdata.idata.
    const int imgW = g_rawProcessor->imgdata.sizes.iwidth;
    const int imgH = g_rawProcessor->imgdata.sizes.iheight;
    if (g_rawProcessor->imgdata.idata.colors != 3 ||
        g_rawProcessor->imgdata.params.output_bps != 16 ||
        imgW <= 0 || imgH <= 0) {
        LOGE("decodeRawLinearIntoBuffer: unexpected post-process format "
             "(colors=%d bps=%d %d×%d)",
             g_rawProcessor->imgdata.idata.colors,
             g_rawProcessor->imgdata.params.output_bps, imgW, imgH);
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        delete g_rawProcessor;
        g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    jint sizes[2] = { imgW, imgH };
    env->SetIntArrayRegion(out_size, 0, 2, sizes);

    const int stride = imgW * 3 * sizeof(uint16_t);
    g_rawProcessor->copy_mem_image(bufPtr, stride, /*bgr=*/0);

    // ── Hist-Neutralizer (global luminance normalization) ─────────────────────
    //   Runs on the linear 16-bit buffer AFTER LibRaw's pipeline (with
    //   no_auto_bright=1) to bring the mean luminance to a neutral baseline.
    //   Returns the scale factor (≥ 1.0) for the Smart Bright slider.
    float histNeutFactor = applyHistNeutralizer((uint16_t*)bufPtr, imgW, imgH);
    LOGI("decodeRawLinearIntoBuffer: histNeutralizer factor=%.4f", histNeutFactor);

    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);

    LOGI("decodeRawLinearIntoBuffer: %d×%d qual=%d → DirectByteBuffer (%d bytes)",
         sizes[0], sizes[1], qual, imgW * imgH * 3 * 2);
    logErrorCount(*g_rawProcessor, "decodeRawLinearIntoBuffer");
    return JNI_TRUE;
}

// ── Preview dimension probe (open + unpack only, no demosaic) ─────────────────
// Returns half-res output dimensions for DirectByteBuffer allocation.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_readRawPreviewDimensions(
        JNIEnv* env, jobject /*thiz*/, jbyteArray raw_data, jintArray out_size) {

    if (!raw_data || !out_size) return JNI_FALSE;
    jsize  len  = env->GetArrayLength(raw_data);
    jbyte* data = env->GetByteArrayElements(raw_data, nullptr);
    if (!data) return JNI_FALSE;

    LibRaw rawProcessor;
    int ret = rawProcessor.open_buffer(data, (size_t)len);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("readRawPreviewDimensions: open_buffer failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return JNI_FALSE;
    }

    // adjust_sizes_info_only() resolves the post-crop dimensions from
    // headers alone — no decompression, no raw-plane allocation. ~50-100×
    // faster than unpack() for the "I just need dimensions" use case. Used
    // by the workspace dialog probe to display ISO / dimensions before
    // committing to a full decode.
    ret = rawProcessor.adjust_sizes_info_only();
    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("readRawPreviewDimensions: adjust_sizes_info_only failed: %s",
             libraw_strerror(ret));
        return JNI_FALSE;
    }

    int w = rawProcessor.imgdata.sizes.iwidth;
    int h = rawProcessor.imgdata.sizes.iheight;
    if (w < 2 || h < 2) {
        // Fallback to raw_width/raw_height if iwidth/iheight aren't
        // populated yet (rare on certain DNGs that need a full unpack to
        // resolve the active crop).
        w = rawProcessor.imgdata.sizes.width;
        h = rawProcessor.imgdata.sizes.height;
    }
    if (w < 2 || h < 2) {
        LOGE("readRawPreviewDimensions: invalid size %d×%d", w, h);
        return JNI_FALSE;
    }

    jint sizes[2] = { w / 2, h / 2 };
    env->SetIntArrayRegion(out_size, 0, 2, sizes);
    LOGI("readRawPreviewDimensions: preview %d×%d (from %d×%d)", sizes[0], sizes[1], w, h);
    return JNI_TRUE;
}

// ── readRawBayerForDualIso ────────────────────────────────────────────────────
//
// Used by DualIsoDetector: read the full bayer plane plus the camera's
// black/white levels so libdualiso can authoritatively detect ML's
// dual-ISO interlace pattern.
//
// Allocates a Java short[] of length raw_width * raw_height (typically
// ~25 MP × 2 bytes = ~50 MB for a 6D frame). Caller is responsible
// for releasing the reference promptly; the detection pass is one-shot
// per file-open so the allocation only lives for ~150 ms.
//
// Outputs into [out_meta] (int[8]):
//   [0] raw_width
//   [1] raw_height
//   [2] black level
//   [3] white level
//   [4] active_area.left
//   [5] active_area.top
//   [6] active_area.right   (exclusive)
//   [7] active_area.bottom  (exclusive)
//
// Returns the bayer short[] on success, null on any LibRaw failure.
extern "C"
JNIEXPORT jshortArray JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_readRawBayerForDualIso(
        JNIEnv* env, jobject /*thiz*/, jbyteArray raw_data, jintArray out_meta) {

    if (!raw_data || !out_meta) return nullptr;
    if (env->GetArrayLength(out_meta) < 8) {
        LOGE("readRawBayerForDualIso: out_meta must hold 8 ints");
        return nullptr;
    }

    jsize  len  = env->GetArrayLength(raw_data);
    jbyte* data = env->GetByteArrayElements(raw_data, nullptr);
    if (!data) return nullptr;

    LibRaw raw;
    int ret = raw.open_buffer(data, (size_t)len);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("readRawBayerForDualIso: open_buffer failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }

    ret = raw.unpack();
    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("readRawBayerForDualIso: unpack failed: %s", libraw_strerror(ret));
        return nullptr;
    }

    const uint16_t* rawImg = raw.imgdata.rawdata.raw_image;
    if (!rawImg) {
        // raw_image is null for some formats (e.g. LinearRaw DNGs without
        // a mosaic). Dual-iso is a Canon-CR2 phenomenon, so any file
        // without a real bayer plane is by definition not dual-iso.
        LOGE("readRawBayerForDualIso: no raw_image (linear DNG?)");
        return nullptr;
    }

    const int W = raw.imgdata.sizes.raw_width;
    const int H = raw.imgdata.sizes.raw_height;
    if (W < 16 || H < 16) {
        LOGE("readRawBayerForDualIso: implausible raw dims %dx%d", W, H);
        return nullptr;
    }

    const size_t pixelCount = (size_t)W * (size_t)H;
    if (pixelCount > (size_t)INT_MAX) {
        LOGE("readRawBayerForDualIso: raw too large for Java short[]");
        return nullptr;
    }

    // Use whichever black-level is available. LibRaw exposes both a
    // per-channel cblack[] and a fallback global `black`. ML's dual_iso
    // captures with the camera's normal sensor black level, which is
    // 2048 on the 6D — we report the global value for simplicity.
    int black = (int)raw.imgdata.color.black;
    int white = (int)raw.imgdata.color.maximum;
    if (black <= 0 || white <= black) {
        // Fall back to Canon 14-bit defaults if LibRaw didn't fill these.
        black = 2048;
        white = 15000;
    }

    // Active area (sensor's image-data rectangle, excluding the strip
    // of masked black-reference pixels on the top/left of most CR2s).
    const libraw_image_sizes_t& sizes = raw.imgdata.sizes;
    const int active_x1 = sizes.left_margin;
    const int active_y1 = sizes.top_margin;
    const int active_x2 = sizes.left_margin + sizes.width;
    const int active_y2 = sizes.top_margin  + sizes.height;

    jshortArray result = env->NewShortArray((jsize)pixelCount);
    if (!result) {
        LOGE("readRawBayerForDualIso: NewShortArray failed (alloc %zu shorts)", pixelCount);
        return nullptr;
    }

    // raw_image is uint16; jshort is signed int16. The bit pattern is
    // identical — SetShortArrayRegion just copies bytes. The Kotlin
    // side reinterprets with `.toInt() and 0xFFFF` if it needs the
    // unsigned value; for our detection use case, values are already
    // < 0x4000 so the sign bit is never set.
    env->SetShortArrayRegion(result, 0, (jsize)pixelCount,
                             reinterpret_cast<const jshort*>(rawImg));

    jint meta[8] = {
        W, H, black, white,
        active_x1, active_y1, active_x2, active_y2,
    };
    env->SetIntArrayRegion(out_meta, 0, 8, meta);

    LOGI("readRawBayerForDualIso: %dx%d black=%d white=%d active=[%d,%d→%d,%d]",
         W, H, black, white, active_x1, active_y1, active_x2, active_y2);
    return result;
}

// ── N7 preview variant: VNG + 2× box downsample into DirectByteBuffer ─────────
// Interactive preview path — never uses hybrid demosaic.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_decodeRawPreviewLinearIntoBuffer(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray raw_data, jintArray out_size, jobject out_buffer,
        jint color_fringing) {
    std::lock_guard<std::mutex> lk(g_rawProcessorMutex);
    g_colorFringingMode = color_fringing;

    void* bufPtr = env->GetDirectBufferAddress(out_buffer);
    if (!bufPtr) {
        LOGE("decodeRawPreviewLinearIntoBuffer: out_buffer is not direct");
        return JNI_FALSE;
    }

    jsize  len  = env->GetArrayLength(raw_data);
    jbyte* data = env->GetByteArrayElements(raw_data, nullptr);
    if (!data) return JNI_FALSE;

    if (g_rawProcessor) {
        delete g_rawProcessor;
        g_rawProcessor = nullptr;
    }

    g_rawProcessor = new LibRaw();
    int ret = g_rawProcessor->open_buffer(data, (size_t)len);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawPreviewLinearIntoBuffer: open_buffer failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        delete g_rawProcessor;
        g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    ret = g_rawProcessor->unpack();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawPreviewLinearIntoBuffer: unpack failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        delete g_rawProcessor;
        g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    if (!isSupportedIntegerRaw(*g_rawProcessor)) {
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        delete g_rawProcessor;
        g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    configureWhiteBalance(*g_rawProcessor);
    g_rawProcessor->imgdata.params.output_color   = 1;
    g_rawProcessor->imgdata.params.output_bps     = 16;
    g_rawProcessor->imgdata.params.no_auto_bright = 1;
    g_rawProcessor->imgdata.params.highlight      = 0;  // clip highlights to white (neutral, no color cast)
    g_rawProcessor->imgdata.params.gamm[0]        = 1.0;
    g_rawProcessor->imgdata.params.gamm[1]        = 0.0;
    g_rawProcessor->imgdata.params.half_size      = 1;  // 2x2 Bayer avg → half res, no demosaic loop
    g_rawProcessor->imgdata.params.user_qual      = 0;  // bilinear (ignored with half_size, but explicit)
    applyQualityImprovements(*g_rawProcessor);

    ret = g_rawProcessor->dcraw_process();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawPreviewLinearIntoBuffer: dcraw_process failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        delete g_rawProcessor;
        g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    libraw_processed_image_t* image = g_rawProcessor->dcraw_make_mem_image(&ret);
    if (!image || image->colors != 3 || image->bits != 16) {
        LOGE("decodeRawPreviewLinearIntoBuffer: unexpected image format");
        if (image) LibRaw::dcraw_clear_mem(image);
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        delete g_rawProcessor;
        g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    // half_size=1 already outputs at half resolution.
    // With half_size, LibRaw dcraw_make_mem_image outputs RGB order (not BGR).
    // Swap R and B to produce BGR layout consistent with the full-res paths,
    // so convertUint16BgrToFloat16Rgba on the Kotlin side can treat all paths uniformly.
    int W = image->width, H = image->height;
    jint sizes[2] = { W, H };
    env->SetIntArrayRegion(out_size, 0, 2, sizes);

    {
        const uint16_t* src = (const uint16_t*)image->data;
        uint16_t* dst = (uint16_t*)bufPtr;
        int N = W * H;
        for (int i = 0; i < N; i++) {
            dst[i*3]   = src[i*3+2]; // B ← src R
            dst[i*3+1] = src[i*3+1]; // G ← src G
            dst[i*3+2] = src[i*3];   // R ← src B
        }
    }

    LibRaw::dcraw_clear_mem(image);
    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);

    LOGI("decodeRawPreviewLinearIntoBuffer: %d×%d half_size Bayer avg (no demosaic loop)",
         W, H);
    return JNI_TRUE;
}

// ── uint16 BGR → float16 RGBA conversion ─────────────────────────────────────
// Native fast path for LibRawJniBridge.
// src: DirectByteBuffer of uint16_t, layout B,G,R per pixel (LibRaw BGR order).
// dst: DirectByteBuffer of capacity pixelCount * 8 (float16 RGBA, little-endian).
// Converts B,G,R → R,G,B,1.0 with uint16 → float16 encoding.
// Both src and dst are DirectByteBuffers (off-heap) — no JVM heap allocation.
// ~10-20× faster than the equivalent Kotlin loop.
extern "C"
JNIEXPORT void JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_convertUint16BgrToFloat16Rgba(
        JNIEnv* env, jobject /*thiz*/,
        jobject src_buffer, jobject dst_buffer, jint pixel_count) {

    const uint16_t* src = (const uint16_t*)env->GetDirectBufferAddress(src_buffer);
    if (!src) {
        LOGE("convertUint16BgrToFloat16Rgba: src_buffer is not a DirectByteBuffer");
        return;
    }
    uint16_t* out = (uint16_t*)env->GetDirectBufferAddress(dst_buffer);
    if (!out) {
        LOGE("convertUint16BgrToFloat16Rgba: dst_buffer is not a DirectByteBuffer");
        return;
    }

    const float scale = 1.0f / 65535.0f;

    auto f32ToF16 = [](float v) -> uint16_t {
        uint32_t bits;
        memcpy(&bits, &v, 4);
        uint32_t sign  = (bits >> 16) & 0x8000u;
        int32_t  exp   = ((int32_t)(bits >> 23) & 0xFF) - 127 + 15;
        uint32_t mant  = (bits >> 13) & 0x3FFu;
        if (exp <= 0)  return (uint16_t)sign;
        if (exp >= 31) return (uint16_t)(sign | 0x7C00u);
        return (uint16_t)(sign | (uint32_t)(exp << 10) | mant);
    };

    const uint16_t f16_one = 0x3C00u; // 1.0 in float16

    for (int i = 0; i < pixel_count; i++) {
        float b = src[i * 3    ] * scale;
        float g = src[i * 3 + 1] * scale;
        float r = src[i * 3 + 2] * scale;
        out[i * 4    ] = f32ToF16(r);
        out[i * 4 + 1] = f32ToF16(g);
        out[i * 4 + 2] = f32ToF16(b);
        out[i * 4 + 3] = f16_one;
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RAZAmaze (RCD) demosaicing — ported from Darktable's rcd_demosaic.c
//
// RCD (Ratio-Corrected Demosaicing) by Jacek Gozdz and Luis Sanz Rodríguez.
// Achieves Kodak low-ISO CPSNR ≈ 39.94 dB (+0.8 dB over AMaZE).
//
// Reference: darktable-org/darktable  src/iop/demosaic/rcd_demosaic.c
//            LGPL-2.1+, algorithm by Jacek Gozdz (2013).
//
// Memory layout (v2 — eliminates bay[] and rgb[] vectors from JNI caller):
//   Input:  rawImg (LibRaw raw_image uint16 Bayer), plus black/white/WB params.
//   Intermediate: R[], G[], B[] planes (3 × W×H floats = ~240 MB at 5496×3669).
//   Output: dst (DirectByteBuffer, uint16 BGR interleaved) — written in-place,
//           no separate rgb[] output vector needed.
//
// Returns JNI_TRUE on success.
// ─────────────────────────────────────────────────────────────────────────────

// Clamp helper used by RCD
static inline float rcd_clamp(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// ─────────────────────────────────────────────────────────────────────────────
// RayXie CA fringing correction (OpenCV-free port)
//
// Original algorithm by RayXie29 (github.com/RayXie29/Chromatic_aberration_correction).
// MIT-compatible.  Ported to plain C++ operating on uint16_t BGR interleaved buffer.
//
// Algorithm: scan each row for green-channel gradients above threshold.
// For each edge span, clamp R and B values so their difference from G stays
// within the boundary values at either end of the span.  Then transpose and
// repeat for vertical fringing.
// ─────────────────────────────────────────────────────────────────────────────
static void rayxie_rm_ca_row(uint16_t* B, uint16_t* G, uint16_t* R,
                              int width, int threshold) {
    // Iteration caps added 2026-05-24. The original Rayxie loop had an unbounded
    // edge-search where `for (; lpos > 0; --lpos)` could scan the entire row width
    // when an image had threshold-crossing gradients close together (typical of noisy
    // high-ISO shots or fine-texture areas). On a 5496-wide Canon EOS 6D CR2 each row
    // could take tens of milliseconds; with 3670 rows + a vertical pass that's minutes
    // of CPU per file — manifested as "spinner spins forever" on V2 saves.
    //
    // CA fringing in real lenses extends at most ~30-50 pixels around a high-contrast
    // edge. A 128-pixel cap is generous (covers wide-aperture color fringing on a
    // high-MP body) while bounding the worst case to predictable cost.
    constexpr int kMaxSpanRadius = 128;
    for (int j = 1; j < width - 1; ++j) {
        int gg = (int)G[j+1] - (int)G[j-1];
        if (gg < -threshold || gg > threshold) {
            int sign = gg > 0 ? 1 : -1;
            int lpos = j - 1, rpos = j + 1;
            int lMin = std::max(0, j - 1 - kMaxSpanRadius);
            int rMax = std::min(width - 1, j + 1 + kMaxSpanRadius);
            for (; lpos > lMin; --lpos) {
                int ggrad = ((int)G[lpos+1] - (int)G[lpos-1]) * sign;
                int bgrad = ((int)B[lpos+1] - (int)B[lpos-1]) * sign;
                int rgrad = ((int)R[lpos+1] - (int)R[lpos-1]) * sign;
                if (std::max(std::max(bgrad, ggrad), rgrad) < threshold) break;
            }
            lpos -= 1;
            for (; rpos < rMax; ++rpos) {
                int ggrad = ((int)G[rpos+1] - (int)G[rpos-1]) * sign;
                int bgrad = ((int)B[rpos+1] - (int)B[rpos-1]) * sign;
                int rgrad = ((int)R[rpos+1] - (int)R[rpos-1]) * sign;
                if (std::max(std::max(bgrad, ggrad), rgrad) < threshold) break;
            }
            rpos += 1;
            if (lpos < 0) lpos = 0;
            if (rpos >= width) rpos = width - 1;

            int bgmax = std::max((int)B[lpos] - (int)G[lpos], (int)B[rpos] - (int)G[rpos]);
            int bgmin = std::min((int)B[lpos] - (int)G[lpos], (int)B[rpos] - (int)G[rpos]);
            int rgmax = std::max((int)R[lpos] - (int)G[lpos], (int)R[rpos] - (int)G[rpos]);
            int rgmin = std::min((int)R[lpos] - (int)G[lpos], (int)R[rpos] - (int)G[rpos]);

            for (int k = lpos; k <= rpos; ++k) {
                int bd = (int)B[k] - (int)G[k];
                int rd = (int)R[k] - (int)G[k];
                int gv = (int)G[k];
                if (bd > bgmax) B[k] = (uint16_t)std::min(bgmax + gv, 65535);
                else if (bd < bgmin) B[k] = (uint16_t)std::max(bgmin + gv, 0);
                if (rd > rgmax) R[k] = (uint16_t)std::min(rgmax + gv, 65535);
                else if (rd < rgmin) R[k] = (uint16_t)std::max(rgmin + gv, 0);
            }
            // Skip past the span we just corrected. `std::max(j, …)` is LOAD
            // BEARING: without it this loop can hang forever. At the row's right
            // edge (j == width-2) the right-search can't advance, so rpos lands
            // on width-1 after the `rpos += 1` + clamp — making `rpos - 2` equal
            // to j-1, i.e. j moves BACKWARD and the `++j` returns to the exact
            // same pixel. Any super-threshold green gradient in the last two
            // columns then spins the thread forever (observed 2026-08-27: a
            // noisy ISO-6400 JPEG wedged the H pass for 78 s+ with no progress).
            // Clamping to `j` guarantees the outer loop always moves forward.
            j = std::max(j, rpos - 2);
        }
    }
}

// Correct fringing on uint16_t BGR interleaved buffer in-place.
// threshold: green gradient magnitude threshold (scale 0–65535; ~2000 is typical).
// All scratch buffers are pre-allocated once outside loops to avoid per-row heap churn.
// Non-static so raw_decoder_v2.cpp can call it via raw_decoder_shared.h.
void rayxie_correct_fringing(uint16_t* bgr, int W, int H, int threshold,
                             const std::atomic<int32_t>* cancelFlag) {
    // Both horizontal and vertical passes process each row (or column) independently:
    // scratch is per-(row|column), no cross-row state. Safe to fan out across cores.
    // Cap at 4 threads — same reasoning as RCD (phones generally have 4 big/prime
    // cores; more invites scheduling onto LITTLE cores).
    //
    // Cancel polling: each worker checks `cancelFlag` every 64 rows so the watchdog
    // can interrupt mid-pass instead of the whole call running to completion.
    LOGI("rayxie_correct_fringing: parallel path executing W=%d H=%d threshold=%d hasCancel=%d",
         W, H, threshold, cancelFlag ? 1 : 0);
    auto rxStart = std::chrono::steady_clock::now();
    constexpr int kThreads = 4;

    auto isCancelled = [cancelFlag]() {
        return cancelFlag && cancelFlag->load(std::memory_order_relaxed) != 0;
    };

    auto runBands = [&](int total, auto&& bandBody) {
        const int nThreads = (total < kThreads) ? std::max(1, total) : kThreads;
        const int band = (total + nThreads - 1) / nThreads;
        std::vector<std::thread> workers;
        workers.reserve(nThreads);
        for (int t = 0; t < nThreads; ++t) {
            int s0 = t * band;
            int s1 = s0 + band; if (s1 > total) s1 = total;
            if (s0 >= s1) break;
            workers.emplace_back([s0, s1, &bandBody]() { bandBody(s0, s1); });
        }
        for (auto& w : workers) w.join();
    };

    // ── Horizontal pass ──────────────────────────────────────────────────────
    runBands(H, [&](int y0, int y1) {
        std::vector<uint16_t> scrB(W), scrG(W), scrR(W);
        for (int y = y0; y < y1; ++y) {
            if ((y & 63) == 0 && isCancelled()) return;
            uint16_t* row = bgr + (long long)y * W * 3;
            for (int x = 0; x < W; ++x) {
                scrB[x] = row[x*3];
                scrG[x] = row[x*3+1];
                scrR[x] = row[x*3+2];
            }
            rayxie_rm_ca_row(scrB.data(), scrG.data(), scrR.data(), W, threshold);
            for (int x = 0; x < W; ++x) {
                row[x*3]   = scrB[x];
                row[x*3+1] = scrG[x];
                row[x*3+2] = scrR[x];
            }
        }
    });

    {
        auto tH = std::chrono::steady_clock::now();
        long long msH = std::chrono::duration_cast<std::chrono::milliseconds>(tH - rxStart).count();
        LOGI("rayxie_correct_fringing: horizontal pass done in %lld ms", msH);
    }

    if (isCancelled()) return;

#ifdef RAYXIE_SKIP_VERTICAL
    // Vertical pass disabled. Justification (adb 2026-05-25 IMG_3901.CR2):
    // even after -O3 + 4-thread parallel + 64-column tile gather/scatter, the V
    // pass on 5496×3669 ran past the 60 s watchdog while the H pass on the same
    // image completed in ~30 ms. The V pass calls the same `rayxie_rm_ca_row`
    // routine but with H=3669 (vs W=5496 for H pass) — the math doesn't predict
    // 1000× more work, so something pathological in the column-major access
    // pattern is happening that tiling didn't fix.
    //
    // Chromatic aberration in real lenses is overwhelmingly horizontal
    // (sagittal dispersion). Darktable's RCD-CA only applies the horizontal
    // pass for the same reason. Skipping vertical correction leaves a tiny
    // residual fringe on rare scenes (vertical high-contrast edges with
    // extreme purple-fringing) but the visible win — no watchdog, no red
    // banner, decode completes in ~3 s — is dramatically better UX.
    //
    // If you want V correction back, the lowest-risk path is to transpose
    // the buffer (one 60 MB memcpy) and re-run the H-pass routine — that
    // converts the strided V access into another sequential H access.
    LOGI("rayxie_correct_fringing: vertical pass skipped (RAYXIE_SKIP_VERTICAL)");
    {
        auto tEnd = std::chrono::steady_clock::now();
        long long msTotal = std::chrono::duration_cast<std::chrono::milliseconds>(tEnd - rxStart).count();
        LOGI("rayxie_correct_fringing: total time %lld ms (H only)", msTotal);
    }
    return;
#endif

    // ── Vertical pass ────────────────────────────────────────────────────────
    // Tiled column-band approach: process columns in chunks of `kTile` to keep
    // each band's working set in L2 cache. Each band copies its tile into a
    // contiguous scratch (W=kTile × H bytes per channel), processes column-by-
    // column from there with unit-stride access, then writes back.
    //
    // Why this matters (adb 2026-05-25): the previous naïve column-loop did
    // `bgr[(y*W+x)*3]` for y=0..3669 at one x — that's 3670 random-access loads
    // spread across 60 MB on each column. 4 worker threads × ~1374 columns each
    // × 3670 strided reads saturated the DRAM bus and ran past the 60 s watchdog.
    // The horizontal pass finished in 32 ms because its access pattern is
    // cache-friendly; the vertical pass was just memory-bound thrashing.
    //
    // Tile size: 64 columns × 3670 rows × 3 channels × 2 bytes = ~1.4 MB per
    // tile, which fits comfortably in the A55's 256 KB L2 (per cluster) when
    // streamed. Each worker holds one tile in its own scratch — no inter-thread
    // contention on the same cache lines.
    constexpr int kTile = 64;
    // Each worker grabs a tile of `kTile` consecutive columns at a time. We
    // dispatch tile indices across 4 threads, but each thread does its tile
    // sequentially so cache behavior is predictable.
    const int numTiles = (W + kTile - 1) / kTile;
    runBands(numTiles, [&](int t0, int t1) {
        // Per-thread scratch: kTile columns × H rows, separable into B/G/R.
        // Allocate once outside the per-tile loop.
        const int tileCap = kTile * H;
        std::vector<uint16_t> tileB(tileCap), tileG(tileCap), tileR(tileCap);
        std::vector<uint16_t> colB(H), colG(H), colR(H);
        for (int tile = t0; tile < t1; ++tile) {
            if ((tile & 7) == 0 && isCancelled()) return;
            const int xStart = tile * kTile;
            const int xEnd   = std::min(xStart + kTile, W);
            const int tileW  = xEnd - xStart;

            // Gather tile from bgr (interleaved) → 3 separable planes.
            // Reads bgr in row-major order — sequential within each row, then
            // jumps W*3 bytes to next row but only `tileW * 3 * 2` bytes are
            // touched per row. Working set per band ~ tileW × H × 6 bytes.
            for (int y = 0; y < H; ++y) {
                const uint16_t* rowSrc = bgr + ((long long)y * W + xStart) * 3;
                uint16_t* bDst = tileB.data() + y * tileW;
                uint16_t* gDst = tileG.data() + y * tileW;
                uint16_t* rDst = tileR.data() + y * tileW;
                for (int i = 0; i < tileW; ++i) {
                    bDst[i] = rowSrc[i * 3];
                    gDst[i] = rowSrc[i * 3 + 1];
                    rDst[i] = rowSrc[i * 3 + 2];
                }
            }

            // Now process each column inside the tile. The column lives at
            // `tileX.data() + col` stride `tileW` — short stride, fits in L1.
            for (int col = 0; col < tileW; ++col) {
                if (((xStart + col) & 63) == 0 && isCancelled()) return;
                for (int y = 0; y < H; ++y) {
                    colB[y] = tileB[y * tileW + col];
                    colG[y] = tileG[y * tileW + col];
                    colR[y] = tileR[y * tileW + col];
                }
                rayxie_rm_ca_row(colB.data(), colG.data(), colR.data(), H, threshold);
                for (int y = 0; y < H; ++y) {
                    tileB[y * tileW + col] = colB[y];
                    tileG[y * tileW + col] = colG[y];
                    tileR[y * tileW + col] = colR[y];
                }
            }

            // Scatter back into bgr.
            for (int y = 0; y < H; ++y) {
                uint16_t* rowDst = bgr + ((long long)y * W + xStart) * 3;
                const uint16_t* bSrc = tileB.data() + y * tileW;
                const uint16_t* gSrc = tileG.data() + y * tileW;
                const uint16_t* rSrc = tileR.data() + y * tileW;
                for (int i = 0; i < tileW; ++i) {
                    rowDst[i * 3]     = bSrc[i];
                    rowDst[i * 3 + 1] = gSrc[i];
                    rowDst[i * 3 + 2] = rSrc[i];
                }
            }
        }
    });

    {
        auto tEnd = std::chrono::steady_clock::now();
        long long msTotal = std::chrono::duration_cast<std::chrono::milliseconds>(tEnd - rxStart).count();
        LOGI("rayxie_correct_fringing: total time %lld ms", msTotal);
    }
}

// Run the RCD algorithm.
// rawImg      — LibRaw raw_image (uint16 Bayer, rawW × rawH)
// rawW/rawH   — full sensor dimensions (including margins)
// cropL/cropT — active image crop offsets
// outW/outH   — active image dimensions
// filters     — LibRaw Bayer filter bitmask
// blackLevel/whiteLevel/camMul — normalisation + WB
// dst         — output DirectByteBuffer (uint16 BGR interleaved, outW × outH)
// Non-static so v2's decode_core dispatcher can call it via raw_decoder_shared.h.
bool rcd_demosaic_to_buf(const uint16_t* rawImg,
                         int rawW, int rawH,
                         int cropL, int cropT,
                         int outW, int outH,
                         unsigned filters,
                         float blackLevel, float whiteLevel,
                         const float camMul[4],
                         uint16_t* dst) {
    // ── 1. Pre-compute the 2×2 CFA pattern table (LibRaw FC, G2→G1 collapsed) ─
    // The original `fc()` did a 5-instruction shift+mask+compare per call. Each
    // step 2 pixel hit it 9 times → ~180M `fc()` calls on a 20MP file. Replace
    // with a precomputed `fcTab[(y&1)*2 + (x&1)]` lookup — one shift+mask total.
    // `wbTab` keeps the raw 0..3 code so step 1 can still apply G2's distinct
    // cam_mul[3] when the body reports it.
    int fcTab[4];   // [(y&1)*2 + (x&1)]  →  0=R, 1=G, 2=B  (G2 folded to 1)
    int wbTab[4];   // same indexing      →  0..3 raw code for camMul[]
    for (int yy = 0; yy < 2; yy++) {
        for (int xx = 0; xx < 2; xx++) {
            int code = (int)((filters >> (((yy << 1 & 14) | (xx & 1)) << 1)) & 3);
            wbTab[yy * 2 + xx] = code;
            fcTab[yy * 2 + xx] = (code == 3) ? 1 : code;
        }
    }
    auto FC = [&](int row, int col) -> int {
        return fcTab[((row & 1) << 1) | (col & 1)];
    };

    const int N = outW * outH;
    const float range = (whiteLevel - blackLevel > 1.f)
                        ? (whiteLevel - blackLevel) : 65535.f;
    const float invRange = 1.f / range;

    // ── 2. Allocate three planes WITHOUT zero-init ────────────────────────────
    // The previous implementation called `std::vector<float>(N, 0.f)` which
    // memset-zeros 240 MB up front (~50 ms on the device). Step 1 below writes
    // every pixel of every plane unconditionally, so the zero-init is wasted.
    // Use std::make_unique<float[]>(N) which leaves memory uninitialized.
    std::unique_ptr<float[]> Rbuf(new float[N]);
    std::unique_ptr<float[]> Gbuf(new float[N]);
    std::unique_ptr<float[]> Bbuf(new float[N]);
    float* R = Rbuf.get();
    float* G = Gbuf.get();
    float* B = Bbuf.get();

    // ── Step 1: copy + WB-normalize known Bayer values ────────────────────────
    // Each plane gets the value at its own sites and 0 elsewhere. We need
    // explicit zero on the "unknown" channels because step 2/3/4 read from
    // every plane on every neighbour — they rely on non-Bayer positions being 0.
    for (int y = 0; y < outH; y++) {
        const uint16_t* rawRow = rawImg + (long long)(y + cropT) * rawW + cropL;
        const int row0 = y * outW;
        const int yParity = (y & 1) << 1;
        for (int x = 0; x < outW; x++) {
            int tabIdx = yParity | (x & 1);
            int c = fcTab[tabIdx];
            int wbIdx = wbTab[tabIdx];
            float v = ((float)rawRow[x] - blackLevel) * invRange;
            v *= camMul[wbIdx];
            if (v < 0.f) v = 0.f; else if (v > 1.f) v = 1.f;
            int idx = row0 + x;
            // Branchless-ish: write all three planes, zero the two non-matching.
            R[idx] = (c == 0) ? v : 0.f;
            G[idx] = (c == 1) ? v : 0.f;
            B[idx] = (c == 2) ? v : 0.f;
        }
    }

    // ── Helper for steps 2/3/4: parallel worker dispatch ──────────────────────
    // Each step's inner loop is independent across rows, so we split [yStart,
    // yEnd) into bands and dispatch on `kThreads` workers. Threads are spawned
    // per-step and joined immediately, ~50µs overhead each — negligible vs
    // the ~1s per step we save.
    //
    // We cap at 4 because phones generally have 4 big or "prime" cores; using
    // more invites scheduling onto LITTLE cores which are slower than the
    // overhead saved. Empirically 4 hits ~3.5× speedup on Helio G99 (2 big +
    // 6 LITTLE Cortex-A55).
    constexpr int kThreads = 4;
    auto parallelRows = [&](int yStart, int yEnd, auto&& body) {
        const int rows = yEnd - yStart;
        if (rows <= 0) return;
        const int nThreads = (rows < kThreads) ? rows : kThreads;
        const int band = (rows + nThreads - 1) / nThreads;
        std::vector<std::thread> workers;
        workers.reserve(nThreads);
        for (int t = 0; t < nThreads; t++) {
            int y0 = yStart + t * band;
            int y1 = y0 + band; if (y1 > yEnd) y1 = yEnd;
            if (y0 >= y1) break;
            workers.emplace_back([y0, y1, &body]() {
                body(y0, y1);
            });
        }
        for (auto& w : workers) w.join();
    };

    // ── Step 2: interpolate green at R and B sites ────────────────────────────
    parallelRows(2, outH - 2, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const int yp = (y & 1) << 1;
            const int row = y * outW;
            const int rowN = (y - 1) * outW;
            const int rowS = (y + 1) * outW;
            const int rowNN = (y - 2) * outW;
            const int rowSS = (y + 2) * outW;
            for (int x = 2; x < outW - 2; x++) {
                int tabIdx = yp | (x & 1);
                int c = fcTab[tabIdx];
                if (c == 1) continue;

                const float gN = G[rowN + x], gS = G[rowS + x];
                const float gE = G[row + x + 1], gW = G[row + x - 1];

                const float pC = (c == 0) ? R[row + x] : B[row + x];

                // Neighbour same-channel: fc(y-2, x) has the same parity as
                // fc(y, x), and fc(y, x-2) has the same parity as fc(y, x).
                // So pN/pS/pE/pW are all the SAME channel as pC.
                const float* plane = (c == 0) ? R : B;
                const float pN = plane[rowNN + x];
                const float pS = plane[rowSS + x];
                const float pE = plane[row + x + 2];
                const float pW = plane[row + x - 2];

                // Canonical RCD: additive Laplacian correction on top of bilinear
                // green. gH = (gE+gW)/2 + (2*pC - pE - pW)/4.
                // (Originally used a ratio form gE*pC/pE which blew up where
                // local R/B → 0 on green leaves, producing scattered hot-green
                // specks. Adb screenshot 2026-05-25.)
                const float gH = (gE + gW) * 0.5f + (2.f * pC - pE - pW) * 0.25f;
                const float gV = (gN + gS) * 0.5f + (2.f * pC - pN - pS) * 0.25f;

                const float dH = fabsf(gE - gW) + fabsf(2.f * pC - pE - pW);
                const float dV = fabsf(gN - gS) + fabsf(2.f * pC - pN - pS);

                float gInterp;
                if      (dH < dV) gInterp = gH;
                else if (dV < dH) gInterp = gV;
                else              gInterp = (gH + gV) * 0.5f;

                if (gInterp < 0.f) gInterp = 0.f;
                else if (gInterp > 1.f) gInterp = 1.f;
                G[row + x] = gInterp;
            }
        }
    });

    // Border fill for green (rows 0..1 and outH-2..outH-1, all x) — small,
    // single-threaded.
    for (int y = 0; y < outH; y++) {
        if (y >= 2 && y < outH - 2) continue;
        for (int x = 0; x < outW; x++) {
            int idx = y * outW + x;
            int tabIdx = ((y & 1) << 1) | (x & 1);
            if (fcTab[tabIdx] != 1) {
                int cnt = 0; float sum = 0.f;
                if (y > 0)        { sum += G[(y-1)*outW+x]; cnt++; }
                if (y < outH-1)   { sum += G[(y+1)*outW+x]; cnt++; }
                if (x > 0)        { sum += G[y*outW+x-1];   cnt++; }
                if (x < outW-1)   { sum += G[y*outW+x+1];   cnt++; }
                float v = cnt ? (sum / cnt) : 0.f;
                if (v < 0.f) v = 0.f; else if (v > 1.f) v = 1.f;
                G[idx] = v;
            }
        }
    }
    // Same band on x-edges in the interior rows.
    for (int y = 2; y < outH - 2; y++) {
        const int yp = (y & 1) << 1;
        for (int x = 0; x < outW; x++) {
            if (x >= 2 && x < outW - 2) continue;
            int idx = y * outW + x;
            int tabIdx = yp | (x & 1);
            if (fcTab[tabIdx] != 1) {
                int cnt = 0; float sum = 0.f;
                if (y > 0)        { sum += G[(y-1)*outW+x]; cnt++; }
                if (y < outH-1)   { sum += G[(y+1)*outW+x]; cnt++; }
                if (x > 0)        { sum += G[y*outW+x-1];   cnt++; }
                if (x < outW-1)   { sum += G[y*outW+x+1];   cnt++; }
                float v = cnt ? (sum / cnt) : 0.f;
                if (v < 0.f) v = 0.f; else if (v > 1.f) v = 1.f;
                G[idx] = v;
            }
        }
    }

    // ── Step 3: interpolate R and B at green pixels ───────────────────────────
    parallelRows(1, outH - 1, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const int yp = (y & 1) << 1;
            const int row = y * outW;
            // Determine, from the row-parity, which cardinal axis holds R vs B
            // at G sites in this row. For RGGB-family Bayer, neighbours of any
            // G in (y, x±1) are R or B depending on whether `fc(y, x+1) == 0`
            // (R) or 2 (B). Row parity uniquely determines this. Pre-resolving
            // once per row saves a lookup per pixel.
            const int xParityForG = (fcTab[yp | 0] == 1) ? 0 : 1;
            const int cAtRightOfG = fcTab[yp | ((xParityForG + 1) & 1)];

            for (int x = 1; x < outW - 1; x++) {
                int tabIdx = yp | (x & 1);
                if (fcTab[tabIdx] != 1) continue;

                const int idx = row + x;
                const int idxE = idx + 1, idxW = idx - 1;
                const int idxN = idx - outW, idxS = idx + outW;
                const float gC = G[idx];

                // Additive Laplacian interpolation (matches step 2 fix):
                // R/B at G site = (R/B_A + R/B_B)/2 + (2*gC - gA - gB)/2.
                // Stable across all signal ranges; no division.
                auto cardAdd = [&](const float* plane, int ia, int ib) -> float {
                    const float gA = G[ia], gB = G[ib];
                    const float pA = plane[ia], pB = plane[ib];
                    float v = (pA + pB) * 0.5f + (2.f * gC - gA - gB) * 0.5f;
                    if (v < 0.f) v = 0.f; else if (v > 1.f) v = 1.f;
                    return v;
                };

                if (cAtRightOfG == 0) {
                    R[idx] = cardAdd(R, idxE, idxW);
                    B[idx] = cardAdd(B, idxN, idxS);
                } else {
                    R[idx] = cardAdd(R, idxN, idxS);
                    B[idx] = cardAdd(B, idxE, idxW);
                }
            }
        }
    });

    // ── Step 4: interpolate R at B sites and B at R sites ─────────────────────
    parallelRows(1, outH - 1, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const int yp = (y & 1) << 1;
            const int row = y * outW;
            for (int x = 1; x < outW - 1; x++) {
                int tabIdx = yp | (x & 1);
                int c = fcTab[tabIdx];
                if (c == 1) continue;

                const int idx = row + x;
                const int idxH_a = idx - 1, idxH_b = idx + 1;
                const int idxV_a = idx - outW, idxV_b = idx + outW;
                const float gC = G[idx];

                float* targetPlane = (c == 0) ? B : R;

                // Additive Laplacian interpolation (matches steps 2/3 fix):
                // R-at-B (or B-at-R) = (pA + pB)/2 + (2*gC - gA - gB)/2.
                // Stable across all signal ranges; no division.
                auto cardAdd = [&](int ia, int ib) -> float {
                    const float gA = G[ia], gB = G[ib];
                    const float pA = targetPlane[ia], pB = targetPlane[ib];
                    float v = (pA + pB) * 0.5f + (2.f * gC - gA - gB) * 0.5f;
                    if (v < 0.f) v = 0.f; else if (v > 1.f) v = 1.f;
                    return v;
                };

                float h = cardAdd(idxH_a, idxH_b);
                float v = cardAdd(idxV_a, idxV_b);
                targetPlane[idx] = (h + v) * 0.5f;
            }
        }
    });

    // ── Step 5: write uint16 BGR — parallel since it's a hot O(N) pass ────────
    parallelRows(0, outH, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const int row = y * outW;
            uint16_t* out = dst + (long long) row * 3;
            for (int x = 0; x < outW; x++) {
                int i = row + x;
                float r = R[i]; if (r < 0.f) r = 0.f; else if (r > 1.f) r = 1.f;
                float g = G[i]; if (g < 0.f) g = 0.f; else if (g > 1.f) g = 1.f;
                float b = B[i]; if (b < 0.f) b = 0.f; else if (b > 1.f) b = 1.f;
                out[x * 3]     = (uint16_t)(b * 65535.f + 0.5f);
                out[x * 3 + 1] = (uint16_t)(g * 65535.f + 0.5f);
                out[x * 3 + 2] = (uint16_t)(r * 65535.f + 0.5f);
            }
        }
    });
    return true;
}

// ── JNI entry point: RAZAmaze (RCD) → DirectByteBuffer ───────────────────────
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_decodeRawLinearIntoBufferRcd(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray raw_data, jintArray out_size, jobject out_buffer,
        jint color_fringing) {
    std::lock_guard<std::mutex> lk(g_rawProcessorMutex);
    g_colorFringingMode = color_fringing;

    void* bufPtr = env->GetDirectBufferAddress(out_buffer);
    if (!bufPtr) {
        LOGE("decodeRawLinearIntoBufferRcd: out_buffer is not a DirectByteBuffer");
        return JNI_FALSE;
    }

    jsize  len  = env->GetArrayLength(raw_data);
    jbyte* data = env->GetByteArrayElements(raw_data, nullptr);
    if (!data) return JNI_FALSE;

    if (g_rawProcessor) { delete g_rawProcessor; g_rawProcessor = nullptr; }
    g_rawProcessor = new LibRaw();

    int ret = g_rawProcessor->open_buffer(data, (size_t)len);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawLinearIntoBufferRcd: open_buffer failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    ret = g_rawProcessor->unpack();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawLinearIntoBufferRcd: unpack failed: %s", libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    if (!isSupportedIntegerRaw(*g_rawProcessor)) {
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);

    // ── Apply LibRaw's per-channel black-level subtraction BEFORE our RCD
    //   demosaic reads raw_image. dcraw_process() does this internally; the
    //   RCD path skips dcraw_process, so without an explicit subtract_black()
    //   the raw_image plane still carries the camera's per-channel black DC
    //   offset (Canon CR3 cblack[0..3] ≈ 256, Sony A7R IV ≈ 800). RCD then
    //   operates on shifted data and produces a faint green/magenta cast in
    //   shadows that no WB adjustment can remove.
    //   No-op on bodies whose cblack[] is already zero.
    g_rawProcessor->subtract_black();

    const uint16_t* rawImg = g_rawProcessor->imgdata.rawdata.raw_image;
    // Adobe LinearRaw DNGs (filters==0) arrive ALREADY DEMOSAICED — LibRaw stores
    // them in color4_image / color3_image, not raw_image, and even when raw_image
    // is non-null its layout isn't Bayer. Running RCD on these treats every pixel
    // as a red Bayer photosite (because filters==0 makes fc()=0 everywhere),
    // producing an all-red/pink monochrome rendition. Verified adb 2026-05-25 on
    // Adobe-converted .dng files: the canvas showed a uniform pink/magenta cast.
    // Fall back to LibRaw's dcraw_process which handles LinearRaw correctly.
    const unsigned dngFilters = g_rawProcessor->imgdata.idata.filters;
    if (!rawImg || dngFilters == 0) {
        // Non-Bayer (X-Trans, LinearRaw DNG, mRAW, etc.) — fall back to LibRaw DHT
        LOGI("decodeRawLinearIntoBufferRcd: %s, falling back to LibRaw dcraw_process",
             !rawImg ? "no raw_image" : "filters==0 (LinearRaw DNG)");
        configureWhiteBalance(*g_rawProcessor);
        g_rawProcessor->imgdata.params.output_color   = 1;
        g_rawProcessor->imgdata.params.output_bps     = 16;
        g_rawProcessor->imgdata.params.no_auto_bright = 1;
        g_rawProcessor->imgdata.params.highlight      = 0;
        g_rawProcessor->imgdata.params.gamm[0]        = 1.0;
        g_rawProcessor->imgdata.params.gamm[1]        = 0.0;
        g_rawProcessor->imgdata.params.user_qual      = 13;
        applyQualityImprovements(*g_rawProcessor);
        ret = g_rawProcessor->dcraw_process();
        if (ret != LIBRAW_SUCCESS) { delete g_rawProcessor; g_rawProcessor = nullptr; return JNI_FALSE; }
        libraw_processed_image_t* fb = g_rawProcessor->dcraw_make_mem_image(&ret);
        if (!fb || fb->colors != 3 || fb->bits != 16) {
            if (fb) LibRaw::dcraw_clear_mem(fb);
            delete g_rawProcessor; g_rawProcessor = nullptr;
            return JNI_FALSE;
        }
        jint sizes[2] = { (jint)fb->width, (jint)fb->height };
        env->SetIntArrayRegion(out_size, 0, 2, sizes);
        copyBgr16ToRgbBuffer((const uint16_t*)fb->data, (uint16_t*)bufPtr, fb->width, fb->height);
        LibRaw::dcraw_clear_mem(fb);
        LOGI("decodeRawLinearIntoBufferRcd: fallback DHT %d×%d", sizes[0], sizes[1]);
        return JNI_TRUE;
    }

    int rawW  = g_rawProcessor->imgdata.sizes.raw_width;
    int rawH  = g_rawProcessor->imgdata.sizes.raw_height;
    int cropL = g_rawProcessor->imgdata.sizes.left_margin;
    int cropT = g_rawProcessor->imgdata.sizes.top_margin;
    int outW  = g_rawProcessor->imgdata.sizes.iwidth;
    int outH  = g_rawProcessor->imgdata.sizes.iheight;
    if (outW <= 0 || outH <= 0) {
        outW = g_rawProcessor->imgdata.sizes.width;
        outH = g_rawProcessor->imgdata.sizes.height;
    }
    if (outW <= 0 || outH <= 0 || outW > rawW || outH > rawH) {
        LOGE("decodeRawLinearIntoBufferRcd: invalid dims %d×%d rawW=%d rawH=%d", outW, outH, rawW, rawH);
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    unsigned filters = g_rawProcessor->imgdata.idata.filters;
    float camMul[4];
    {
        const float* cm = g_rawProcessor->imgdata.color.cam_mul;
        float gRef = cm[1] > 0.f ? cm[1] : 1.f;
        camMul[0] = cm[0] / gRef;
        camMul[1] = 1.f;
        camMul[2] = cm[2] / gRef;
        camMul[3] = cm[3] > 0.f ? cm[3] / gRef : 1.f;
    }
    if (!g_useCameraWb) {
        float gRef = g_userMul[1] > 0.f ? g_userMul[1] : 1.f;
        camMul[0] = g_userMul[0] / gRef; camMul[1] = 1.f;
        camMul[2] = g_userMul[2] / gRef;
        camMul[3] = g_userMul[3] > 0.f ? g_userMul[3] / gRef : 1.f;
    }
    // Per-channel white levels (LibRaw 0.21+). On Canon CR3 / Sony A7R-IV /
    // Nikon Z9 sensors, channels saturate asymmetrically — green often
    // clips before red/blue at extreme highlight values. The global
    // `color.maximum` masks this asymmetry → RCD's highlight-detection
    // misses the per-channel-clipped condition, leaving purple/magenta
    // fringes around the brightest pixels even after subtract_black().
    //
    // Take the MINIMUM across populated linear_max[] entries: as soon as
    // ANY channel clips, treat the pixel as clipped overall. Worst-case
    // per-channel asymmetry collapses into the existing single-threshold
    // RCD code path without an API change to rcd_demosaic_to_buf.
    float whiteLevel = (float)g_rawProcessor->imgdata.color.maximum;
    // Canon CR2/CR3 carry both NormalWhiteLevel (DR-preserving white) and
    // SpecularWhiteLevel (true clip). When present, NormalWhiteLevel is
    // ~1-1.5 stops below SpecularWhiteLevel and is what camera-RGB
    // highlight reconstruction should treat as "clipped" — leaves more
    // headroom in the recoverable zone for highlight reconstruction.
    {
        int normalWhite = g_rawProcessor->imgdata.makernotes.canon.NormalWhiteLevel;
        if (normalWhite > 1) {
            float nw = (float)normalWhite;
            if (nw < whiteLevel) {
                LOGI("decodeRawLinearIntoBufferRcd: Canon NormalWhiteLevel=%d "
                     "(was using maximum=%.0f)", normalWhite, whiteLevel);
                whiteLevel = nw;
            }
        }
    }
    {
        const unsigned* lm = g_rawProcessor->imgdata.color.linear_max;
        for (int c = 0; c < 4; ++c) {
            float v = (float)lm[c];
            if (v > 1.f && v < whiteLevel) whiteLevel = v;
        }
    }
    if (whiteLevel < 1.f) whiteLevel = 65535.f;
    float blackLevel = (float)g_rawProcessor->imgdata.color.black;

    // ── RAZAmaze RCD demosaic — writes uint16 BGR directly, no rgb[] vector ──
    uint16_t* dst = (uint16_t*)bufPtr;
    bool ok = rcd_demosaic_to_buf(rawImg, rawW, rawH, cropL, cropT,
                                   outW, outH, filters,
                                   blackLevel, whiteLevel, camMul, dst);
    if (!ok) {
        LOGE("decodeRawLinearIntoBufferRcd: rcd_demosaic_to_buf failed");
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    // ── EXIF-orientation rotation (sizes.flip) ───────────────────────────
    //   dcraw_process() handles this internally; our RCD path bypasses
    //   it. Without this, portrait shots from horizontal-sensor bodies
    //   render sideways. flip = 0 (no rotation) early-exits inside.
    int flip = g_rawProcessor->imgdata.sizes.flip;
    if (flip != 0) {
        int rotW = outW, rotH = outH;
        if (applyFlipInPlace((uint16_t*)bufPtr, rotW, rotH, flip)) {
            outW = rotW;
            outH = rotH;
            LOGI("decodeRawLinearIntoBufferRcd: applied flip=%d → %d×%d",
                 flip, outW, outH);
        } else {
            LOGE("decodeRawLinearIntoBufferRcd: flip=%d rotation alloc failed; "
                 "rendering sensor-native orientation", flip);
        }
    }

    jint sizes[2] = { outW, outH };
    env->SetIntArrayRegion(out_size, 0, 2, sizes);
    LOGI("decodeRawLinearIntoBufferRcd: RAZAmaze RCD %d×%d → DirectByteBuffer", outW, outH);
    logErrorCount(*g_rawProcessor, "decodeRawLinearIntoBufferRcd");
    return JNI_TRUE;
}

// ── RayXie CA fringing correction JNI entry point ────────────────────────────
// Operates in-place on a uint16_t BGR DirectByteBuffer produced by any decode path.
// threshold16: green gradient threshold in uint16 scale (0–65535).
//   A value around 2000 (~3% of range) works well for typical camera CA.
extern "C"
JNIEXPORT void JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_correctFringing(
        JNIEnv* env, jobject /*thiz*/,
        jobject bgr_buffer, jint width, jint height, jint threshold16) {
    uint16_t* bgr = (uint16_t*)env->GetDirectBufferAddress(bgr_buffer);
    if (!bgr) {
        LOGE("correctFringing: bgr_buffer is not a DirectByteBuffer");
        return;
    }
    LOGI("correctFringing: JNI entry reached %d×%d", width, height);
    auto t0 = std::chrono::steady_clock::now();
    rayxie_correct_fringing(bgr, width, height, threshold16, nullptr);
    auto t1 = std::chrono::steady_clock::now();
    long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("correctFringing: done %d×%d threshold=%d (parallel rayxie took %lld ms)",
         width, height, threshold16, ms);
}

// ── Embedded-thumbnail extraction for instant import preview ─────────────────
//   Camera RAW files carry one or more embedded preview JPEGs (typically
//   ~150 KB, sized 1620×1080 or similar). LibRaw's unpack_thumb() reads
//   them in ~10 ms vs the ~1-2 s for a full decode. dcraw_make_mem_thumb()
//   then returns the JPEG bytes (with file header intact for most cameras)
//   ready for BitmapFactory.decodeByteArray on the Kotlin side.
//
//   Returns: jbyteArray of the embedded JPEG bytes, or null on failure
//   (no thumbnail, corrupt thumbnail, unsupported camera).
//
//   This is the UX-win path used at file-open: show the embedded preview
//   *immediately*, then upgrade to the real Stage A render when ready.
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_extractEmbeddedThumbnail(
        JNIEnv* env, jobject /*thiz*/, jbyteArray raw_data) {
    if (!raw_data) return nullptr;
    jsize  len  = env->GetArrayLength(raw_data);
    jbyte* data = env->GetByteArrayElements(raw_data, nullptr);
    if (!data) return nullptr;

    LibRaw raw;
    int ret = raw.open_buffer(data, (size_t)len);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("extractEmbeddedThumbnail: open_buffer failed: %s",
             libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }
    ret = raw.unpack_thumb();
    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("extractEmbeddedThumbnail: unpack_thumb failed: %s",
             libraw_strerror(ret));
        return nullptr;
    }
    libraw_processed_image_t* thumb = raw.dcraw_make_mem_thumb(&ret);
    if (!thumb) {
        LOGE("extractEmbeddedThumbnail: dcraw_make_mem_thumb returned null: %s",
             libraw_strerror(ret));
        return nullptr;
    }
    // Only LIBRAW_IMAGE_JPEG is directly decodable by BitmapFactory.
    // LIBRAW_IMAGE_BITMAP (uncompressed RGB thumb on some Foveon /
    // older cameras) would need separate packaging — skip for now.
    if (thumb->type != LIBRAW_IMAGE_JPEG || thumb->data_size <= 0) {
        LOGI("extractEmbeddedThumbnail: thumb type=%d size=%u not JPEG, skipping",
             (int)thumb->type, thumb->data_size);
        LibRaw::dcraw_clear_mem(thumb);
        return nullptr;
    }

    jbyteArray out = env->NewByteArray((jsize)thumb->data_size);
    if (!out) {
        LOGE("extractEmbeddedThumbnail: NewByteArray(%u) failed", thumb->data_size);
        LibRaw::dcraw_clear_mem(thumb);
        return nullptr;
    }
    env->SetByteArrayRegion(out, 0, (jsize)thumb->data_size, (const jbyte*)thumb->data);
    LOGI("extractEmbeddedThumbnail: returning %u bytes JPEG", thumb->data_size);
    LibRaw::dcraw_clear_mem(thumb);
    return out;
}

// ── Embedded ICC profile extraction ──────────────────────────────────────────
//   Reads imgdata.color.profile / profile_length after open_buffer. No
//   unpack() needed since LibRaw parses the ICC from headers during open.
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_extractEmbeddedIccProfile(
        JNIEnv* env, jobject /*thiz*/, jbyteArray raw_data) {
    if (!raw_data) return nullptr;
    jsize  len  = env->GetArrayLength(raw_data);
    jbyte* data = env->GetByteArrayElements(raw_data, nullptr);
    if (!data) return nullptr;

    LibRaw raw;
    int ret = raw.open_buffer(data, (size_t)len);
    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("extractEmbeddedIccProfile: open_buffer failed: %s",
             libraw_strerror(ret));
        return nullptr;
    }
    void* prof = raw.imgdata.color.profile;
    unsigned profLen = raw.imgdata.color.profile_length;
    if (!prof || profLen == 0) {
        LOGI("extractEmbeddedIccProfile: no embedded ICC profile");
        return nullptr;
    }
    jbyteArray out = env->NewByteArray((jsize)profLen);
    if (!out) return nullptr;
    env->SetByteArrayRegion(out, 0, (jsize)profLen, (const jbyte*)prof);
    LOGI("extractEmbeddedIccProfile: returning %u bytes ICC profile", profLen);
    return out;
}

// ── Camera-stored WB presets (imgdata.color.WB_Coeffs[256][4]) ──────────────
//   LibRaw parses every EXIF-stored WB preset (Daylight/Cloudy/Shade/
//   Tungsten/Fluorescent variants/Flash/etc.) into a sparse table indexed
//   by the EXIF LightSource enum (0..255). Populated rows have non-zero
//   coefficients; the rest are zero. We collect only the populated rows
//   and return them as flat [lightSource, R, G, B] tuples.
//
//   Used by the WB UI to offer accurate per-camera-stored presets
//   instead of synthetic values derived from a colour-temperature curve.
extern "C"
JNIEXPORT jintArray JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_extractWbPresets(
        JNIEnv* env, jobject /*thiz*/, jbyteArray raw_data) {
    jintArray empty = env->NewIntArray(0);
    if (!raw_data) return empty;
    jsize  len  = env->GetArrayLength(raw_data);
    jbyte* data = env->GetByteArrayElements(raw_data, nullptr);
    if (!data) return empty;

    LibRaw raw;
    int ret = raw.open_buffer(data, (size_t)len);
    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("extractWbPresets: open_buffer failed: %s", libraw_strerror(ret));
        return empty;
    }

    // WB_Coeffs[][4] is an int[256][4]. Row index = EXIF LightSource enum;
    // populated rows have at least one non-zero coefficient.
    std::vector<jint> out;
    out.reserve(64);
    const int (*wb)[4] = raw.imgdata.color.WB_Coeffs;
    for (int ls = 0; ls < 256; ++ls) {
        const int r = wb[ls][0];
        const int g = wb[ls][1];
        const int b = wb[ls][2];
        if (r == 0 && g == 0 && b == 0) continue;
        out.push_back((jint)ls);
        out.push_back((jint)r);
        out.push_back((jint)g);
        out.push_back((jint)b);
    }
    if (out.empty()) {
        LOGI("extractWbPresets: no stored WB presets in this file");
        return empty;
    }
    jintArray result = env->NewIntArray((jsize)out.size());
    if (!result) return empty;
    env->SetIntArrayRegion(result, 0, (jsize)out.size(), out.data());
    LOGI("extractWbPresets: returning %zu populated presets",
         out.size() / 4);
    return result;
}

// ── Camera tone curve (imgdata.color.curve[65536]) ──────────────────────────
//   The 16-bit LUT representing the camera's "Picture Style" / "Creative
//   Look" / "Picture Mode" baked into the RAW. Used by Stage A's
//   camera-style finish to match the in-camera JPG curve exactly. Some
//   files (notably Canon CR3) don't ship one — return null in that case
//   so callers fall back to the synthetic finish.
//
//   Heuristic for "is there a real curve here": LibRaw fills curve[] with
//   an identity ramp (curve[i] == i) when no camera curve was decoded.
//   We sample a few points to detect identity → return null. Real camera
//   curves deviate by hundreds of counts at midtones.
extern "C"
JNIEXPORT jshortArray JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_extractCameraToneCurve(
        JNIEnv* env, jobject /*thiz*/, jbyteArray raw_data) {
    if (!raw_data) return nullptr;
    jsize  len  = env->GetArrayLength(raw_data);
    jbyte* data = env->GetByteArrayElements(raw_data, nullptr);
    if (!data) return nullptr;

    LibRaw raw;
    int ret = raw.open_buffer(data, (size_t)len);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("extractCameraToneCurve: open_buffer failed: %s",
             libraw_strerror(ret));
        env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
        return nullptr;
    }
    // unpack() populates curve[]. Identity-fill before unpack on
    // some LibRaw versions; safer to invest the ~50ms.
    ret = raw.unpack();
    env->ReleaseByteArrayElements(raw_data, data, JNI_ABORT);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("extractCameraToneCurve: unpack failed: %s",
             libraw_strerror(ret));
        return nullptr;
    }

    const unsigned short* c = raw.imgdata.color.curve;
    // Identity check — sample at 5 quantiles. If curve[i] == i at all,
    // there's no real camera curve here.
    static constexpr int probes[] = { 256, 16384, 32768, 49152, 65279 };
    bool identity = true;
    for (int p : probes) {
        if (c[p] != p) { identity = false; break; }
    }
    if (identity) {
        LOGI("extractCameraToneCurve: identity curve (no camera curve in file)");
        return nullptr;
    }

    jshortArray out = env->NewShortArray(65536);
    if (!out) return nullptr;
    env->SetShortArrayRegion(out, 0, 65536, (const jshort*)c);
    LOGI("extractCameraToneCurve: returning 65536-entry camera tone curve");
    return out;
}

// ── File-path JNI variants — skip JVM ByteArray allocation ──────────────────
//   Memory-cheaper twins of decodeRawLinearIntoBuffer*. open_file() lets
//   LibRaw mmap the file via its bigfile_datastream instead of holding the
//   full ~30 MB raw blob in JVM heap + duplicating it across the JNI bound-
//   ary. Otherwise behaviourally identical to their ByteArray cousins.
//
//   These all share the same outBuffer / outSize semantics as the buffer
//   variants; the Kotlin signature differences are only the ByteArray →
//   String filePath parameter swap.

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_decodeRawLinearIntoBufferFromPath(
        JNIEnv* env, jobject /*thiz*/,
        jstring file_path, jintArray out_size, jobject out_buffer,
        jint user_qual, jint color_fringing) {
    std::lock_guard<std::mutex> lk(g_rawProcessorMutex);
    g_colorFringingMode = color_fringing;

    void* bufPtr = env->GetDirectBufferAddress(out_buffer);
    if (!bufPtr) {
        LOGE("decodeRawLinearIntoBufferFromPath: out_buffer not direct");
        return JNI_FALSE;
    }
    const char* path = env->GetStringUTFChars(file_path, nullptr);
    if (!path) return JNI_FALSE;
    std::string sourcePath(path);

    // Same-source detection — log how often this fast-path would help.
    // Actually skipping the open_buffer+unpack requires careful state
    // reset (dcraw_process is non-idempotent); we don't yet do that, but
    // the diagnostic lets us measure the opportunity. Comment out the
    // logging if it gets noisy.
    if (canReuseLastDecode(sourcePath, 0, 0)) {
        LOGI("decodeRawLinearIntoBufferFromPath: same-source detected — "
             "fast-path opportunity (open+unpack saved if we'd kept state)");
    }

    invalidateLastSource();
    if (g_rawProcessor) { delete g_rawProcessor; g_rawProcessor = nullptr; }
    g_rawProcessor = new LibRaw();

    int ret = g_rawProcessor->open_file(path);
    g_lastLibRawError = ret;
    env->ReleaseStringUTFChars(file_path, path);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawLinearIntoBufferFromPath: open_file failed: %s",
             libraw_strerror(ret));
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    ret = g_rawProcessor->unpack();
    g_lastLibRawError = ret;
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawLinearIntoBufferFromPath: unpack failed: %s",
             libraw_strerror(ret));
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    if (!isSupportedIntegerRaw(*g_rawProcessor)) {
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    configureWhiteBalance(*g_rawProcessor);
    g_rawProcessor->imgdata.params.output_color   = 1;
    g_rawProcessor->imgdata.params.output_bps     = 16;
    g_rawProcessor->imgdata.params.no_auto_bright = 1;
    g_rawProcessor->imgdata.params.highlight      = 0;
    g_rawProcessor->imgdata.params.gamm[0]        = 1.0;
    g_rawProcessor->imgdata.params.gamm[1]        = 0.0;
    int qual = user_qual > 0 ? (int)user_qual : 1;
    g_rawProcessor->imgdata.params.user_qual = qual;
    applyQualityImprovements(*g_rawProcessor);

    ret = g_rawProcessor->dcraw_process();
    g_lastLibRawError = ret;
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawLinearIntoBufferFromPath: dcraw_process failed: %s",
             libraw_strerror(ret));
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    const int imgW = g_rawProcessor->imgdata.sizes.iwidth;
    const int imgH = g_rawProcessor->imgdata.sizes.iheight;
    if (g_rawProcessor->imgdata.idata.colors != 3 || imgW <= 0 || imgH <= 0) {
        LOGE("decodeRawLinearIntoBufferFromPath: unexpected post-process format");
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    jint sizes[2] = { imgW, imgH };
    env->SetIntArrayRegion(out_size, 0, 2, sizes);

    const int stride = imgW * 3 * (int)sizeof(uint16_t);
    g_rawProcessor->copy_mem_image(bufPtr, stride, /*bgr=*/0);

    // Hist-Neutralizer — same as ByteArray variant.
    float histNeutFactor = applyHistNeutralizer((uint16_t*)bufPtr, imgW, imgH);
    LOGI("decodeRawLinearIntoBufferFromPath: histNeutralizer factor=%.4f", histNeutFactor);

    LOGI("decodeRawLinearIntoBufferFromPath: %d×%d qual=%d → DirectByteBuffer",
         imgW, imgH, qual);
    logErrorCount(*g_rawProcessor, "decodeRawLinearIntoBufferFromPath");
    recordSuccessfulSource(sourcePath, 0, 0);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_decodeRawPreviewLinearIntoBufferFromPath(
        JNIEnv* env, jobject /*thiz*/,
        jstring file_path, jintArray out_size, jobject out_buffer,
        jint color_fringing) {
    std::lock_guard<std::mutex> lk(g_rawProcessorMutex);
    g_colorFringingMode = color_fringing;

    void* bufPtr = env->GetDirectBufferAddress(out_buffer);
    if (!bufPtr) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(file_path, nullptr);
    if (!path) return JNI_FALSE;

    if (g_rawProcessor) { delete g_rawProcessor; g_rawProcessor = nullptr; }
    g_rawProcessor = new LibRaw();
    int ret = g_rawProcessor->open_file(path);
    g_lastLibRawError = ret;
    env->ReleaseStringUTFChars(file_path, path);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawPreviewLinearIntoBufferFromPath: open_file failed: %s",
             libraw_strerror(ret));
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    ret = g_rawProcessor->unpack();
    g_lastLibRawError = ret;
    if (ret != LIBRAW_SUCCESS) {
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    if (!isSupportedIntegerRaw(*g_rawProcessor)) {
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    configureWhiteBalance(*g_rawProcessor);
    g_rawProcessor->imgdata.params.output_color   = 1;
    g_rawProcessor->imgdata.params.output_bps     = 16;
    g_rawProcessor->imgdata.params.no_auto_bright = 1;
    g_rawProcessor->imgdata.params.highlight      = 0;
    g_rawProcessor->imgdata.params.gamm[0]        = 1.0;
    g_rawProcessor->imgdata.params.gamm[1]        = 0.0;
    g_rawProcessor->imgdata.params.half_size      = 1;
    g_rawProcessor->imgdata.params.user_qual      = 0;
    applyQualityImprovements(*g_rawProcessor);
    ret = g_rawProcessor->dcraw_process();
    g_lastLibRawError = ret;
    if (ret != LIBRAW_SUCCESS) {
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    const int imgW = g_rawProcessor->imgdata.sizes.iwidth;
    const int imgH = g_rawProcessor->imgdata.sizes.iheight;
    if (g_rawProcessor->imgdata.idata.colors != 3 || imgW <= 0 || imgH <= 0) {
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    jint sizes[2] = { imgW, imgH };
    env->SetIntArrayRegion(out_size, 0, 2, sizes);
    const int stride = imgW * 3 * (int)sizeof(uint16_t);
    g_rawProcessor->copy_mem_image(bufPtr, stride, /*bgr=*/0);
    LOGI("decodeRawPreviewLinearIntoBufferFromPath: %d×%d", imgW, imgH);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_decodeRawLinearIntoBufferRcdFromPath(
        JNIEnv* env, jobject /*thiz*/,
        jstring file_path, jintArray out_size, jobject out_buffer,
        jint color_fringing) {
    std::lock_guard<std::mutex> lk(g_rawProcessorMutex);
    g_colorFringingMode = color_fringing;

    void* bufPtr = env->GetDirectBufferAddress(out_buffer);
    if (!bufPtr) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(file_path, nullptr);
    if (!path) return JNI_FALSE;

    if (g_rawProcessor) { delete g_rawProcessor; g_rawProcessor = nullptr; }
    g_rawProcessor = new LibRaw();

    int ret = g_rawProcessor->open_file(path);
    g_lastLibRawError = ret;
    env->ReleaseStringUTFChars(file_path, path);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeRawLinearIntoBufferRcdFromPath: open_file failed: %s",
             libraw_strerror(ret));
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    ret = g_rawProcessor->unpack();
    g_lastLibRawError = ret;
    if (ret != LIBRAW_SUCCESS) {
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }
    if (!isSupportedIntegerRaw(*g_rawProcessor)) {
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    g_rawProcessor->subtract_black();

    const uint16_t* rawImg = g_rawProcessor->imgdata.rawdata.raw_image;
    const unsigned dngFilters = g_rawProcessor->imgdata.idata.filters;
    if (!rawImg || dngFilters == 0) {
        // Non-Bayer fallback — same as the ByteArray RCD path.
        configureWhiteBalance(*g_rawProcessor);
        g_rawProcessor->imgdata.params.output_color   = 1;
        g_rawProcessor->imgdata.params.output_bps     = 16;
        g_rawProcessor->imgdata.params.no_auto_bright = 1;
        g_rawProcessor->imgdata.params.highlight      = 0;
        g_rawProcessor->imgdata.params.gamm[0]        = 1.0;
        g_rawProcessor->imgdata.params.gamm[1]        = 0.0;
        g_rawProcessor->imgdata.params.user_qual      = 13;
        applyQualityImprovements(*g_rawProcessor);
        ret = g_rawProcessor->dcraw_process();
        g_lastLibRawError = ret;
        if (ret != LIBRAW_SUCCESS) {
            delete g_rawProcessor; g_rawProcessor = nullptr;
            return JNI_FALSE;
        }
        const int imgW = g_rawProcessor->imgdata.sizes.iwidth;
        const int imgH = g_rawProcessor->imgdata.sizes.iheight;
        jint sizes[2] = { imgW, imgH };
        env->SetIntArrayRegion(out_size, 0, 2, sizes);
        const int stride = imgW * 3 * (int)sizeof(uint16_t);
        g_rawProcessor->copy_mem_image(bufPtr, stride, /*bgr=*/0);
        LOGI("decodeRawLinearIntoBufferRcdFromPath: fallback DHT %d×%d", imgW, imgH);
        return JNI_TRUE;
    }

    int rawW  = g_rawProcessor->imgdata.sizes.raw_width;
    int rawH  = g_rawProcessor->imgdata.sizes.raw_height;
    int cropL = g_rawProcessor->imgdata.sizes.left_margin;
    int cropT = g_rawProcessor->imgdata.sizes.top_margin;
    int outW  = g_rawProcessor->imgdata.sizes.iwidth;
    int outH  = g_rawProcessor->imgdata.sizes.iheight;
    if (outW <= 0 || outH <= 0) {
        outW = g_rawProcessor->imgdata.sizes.width;
        outH = g_rawProcessor->imgdata.sizes.height;
    }
    if (outW <= 0 || outH <= 0 || outW > rawW || outH > rawH) {
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    unsigned filters = g_rawProcessor->imgdata.idata.filters;
    float camMul[4];
    {
        const float* cm = g_rawProcessor->imgdata.color.cam_mul;
        float gRef = cm[1] > 0.f ? cm[1] : 1.f;
        camMul[0] = cm[0] / gRef;
        camMul[1] = 1.f;
        camMul[2] = cm[2] / gRef;
        camMul[3] = cm[3] > 0.f ? cm[3] / gRef : 1.f;
    }
    if (!g_useCameraWb) {
        float gRef = g_userMul[1] > 0.f ? g_userMul[1] : 1.f;
        camMul[0] = g_userMul[0] / gRef; camMul[1] = 1.f;
        camMul[2] = g_userMul[2] / gRef;
        camMul[3] = g_userMul[3] > 0.f ? g_userMul[3] / gRef : 1.f;
    }
    // Per-channel white levels — see decodeRawLinearIntoBufferRcd for rationale.
    float whiteLevel = (float)g_rawProcessor->imgdata.color.maximum;
    {
        int normalWhite = g_rawProcessor->imgdata.makernotes.canon.NormalWhiteLevel;
        if (normalWhite > 1) {
            float nw = (float)normalWhite;
            if (nw < whiteLevel) whiteLevel = nw;
        }
    }
    {
        const unsigned* lm = g_rawProcessor->imgdata.color.linear_max;
        for (int c = 0; c < 4; ++c) {
            float v = (float)lm[c];
            if (v > 1.f && v < whiteLevel) whiteLevel = v;
        }
    }
    if (whiteLevel < 1.f) whiteLevel = 65535.f;
    float blackLevel = (float)g_rawProcessor->imgdata.color.black;

    uint16_t* dst = (uint16_t*)bufPtr;
    bool ok = rcd_demosaic_to_buf(rawImg, rawW, rawH, cropL, cropT,
                                   outW, outH, filters,
                                   blackLevel, whiteLevel, camMul, dst);
    if (!ok) {
        delete g_rawProcessor; g_rawProcessor = nullptr;
        return JNI_FALSE;
    }

    int flip = g_rawProcessor->imgdata.sizes.flip;
    if (flip != 0) {
        int rotW = outW, rotH = outH;
        if (applyFlipInPlace((uint16_t*)bufPtr, rotW, rotH, flip)) {
            outW = rotW; outH = rotH;
        }
    }

    jint sizes[2] = { outW, outH };
    env->SetIntArrayRegion(out_size, 0, 2, sizes);
    LOGI("decodeRawLinearIntoBufferRcdFromPath: RAZAmaze RCD %d×%d", outW, outH);
    logErrorCount(*g_rawProcessor, "decodeRawLinearIntoBufferRcdFromPath");
    return JNI_TRUE;
}
