/*
 * StudioRoom — RAW Pipeline v3
 * lmmse_demosaic.cpp — LMMSE (Least Mean Square Error) demosaic.
 *
 * Adapted from RawTherapee rtengine/demosaic_algos.cc lmmse_interpolate_onescan.
 * Original authors: Emil Martinec, RawTherapee team. GPL-3.
 *
 * Substitutions vs RT:
 *   float array[H][W]         → flat float* with manual indexing
 *   OpenMP / SSE              → dropped, single-thread
 *   rgb[][]/image[][]         → outR/outG/outB planar
 *   LIM(x,lo,hi)              → clampf helper
 *   No librtprocess dependency — algorithm inlined directly.
 *
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  DO NOT MODIFY THIS FILE — LOCKED ALGORITHM                             ║
 * ║                                                                          ║
 * ║  This is the canonical RAZAMaZE+LMMSE demosaic, validated in            ║
 * ║  StudioRoom v3. The following invariants must not change:               ║
 * ║                                                                          ║
 * ║  • dR/dB interpolation uses CORRECT per-site Bayer offsets:             ║
 * ║    G1 (even row, odd col)  → R at dc=±1, dr=0  (horizontal)            ║
 * ║    G2 (odd  row, even col) → R at dr=±1, dc=0  (vertical)              ║
 * ║    B                       → R at dr=±1, dc=±1 (diagonal)              ║
 * ║    (mirrored for dB). Generic diagonal offsets hit G-pixels (NaN)       ║
 * ║    and caused systematic chroma errors in flat/low-contrast areas.      ║
 * ║                                                                          ║
 * ║  • camMul passed in MUST be G-normalised cam_mul (not pre_mul).         ║
 * ║    This is enforced by the caller (stage_a.cpp camMulDual).             ║
 * ║                                                                          ║
 * ║  • Bilateral smooth (Pass 4c) kThr=0.05, 2 passes — do not increase    ║
 * ║    passes or lower threshold without retesting on all photo sets.        ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */

#include "lmmse_demosaic.h"

#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <limits>

#define LOG_TAG "RawV3.LMMSE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw_v3 {

static inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// Bayer colour at raw (row,col) — raw coordinates, not crop.
// Returns 0=R, 1=G, 2=B  (G2 folded to 1).
static inline int bayerColor(int row, int col, unsigned filters) {
    int ch = int((filters >> (((row << 1 & 14) | (col & 1)) << 1)) & 3);
    return (ch == 3) ? 1 : ch;
}

bool lmmse_demosaic_to_planes(
    const uint16_t* rawImg,
    int rawW, int rawH,
    int cropL, int cropT,
    int outW, int outH,
    unsigned filters,
    float blackLevel, float whiteLevel,
    const float camMul[4],
    float* outR, float* outG, float* outB)
{
    LOGI("lmmse_demosaic start: crop=(%d,%d) out=%dx%d", cropL, cropT, outW, outH);

    const int W = outW;
    const int H = outH;
    const size_t N = size_t(W) * H;

    const float scale = (whiteLevel > blackLevel + 1.f)
                        ? 1.f / (whiteLevel - blackLevel) : 1.f / 65535.f;

    // ── Scratch allocations ────────────────────────────────────────────────
    // mosaic : single-channel Bayer, WB-scaled [0..1]
    // gH/gV  : horizontal / vertical G estimates
    // vH/vV  : their local variances
    // dR/dB  : (G−R) and (G−B) difference planes
    std::unique_ptr<float[]> mosaic(new(std::nothrow) float[N]);
    std::unique_ptr<float[]> gH    (new(std::nothrow) float[N]);
    std::unique_ptr<float[]> gV    (new(std::nothrow) float[N]);
    std::unique_ptr<float[]> vH    (new(std::nothrow) float[N]);
    std::unique_ptr<float[]> vV    (new(std::nothrow) float[N]);
    std::unique_ptr<float[]> dR    (new(std::nothrow) float[N]);
    std::unique_ptr<float[]> dB    (new(std::nothrow) float[N]);
    if (!mosaic || !gH || !gV || !vH || !vV || !dR || !dB) {
        LOGE("OOM: %dx%d scratch", W, H);
        return false;
    }

    // ── Build mosaic ───────────────────────────────────────────────────────
    // Apply camMul (= LibRaw pre_mul[], passed from stage_a) to match
    // LibRaw's scale_colors step that AMaZE goes through.  This ensures
    // LMMSE and AMaZE outputs are in the same exposure-normalised scale
    // before rgb_cam is applied to both.
    for (int r = 0; r < H; ++r) {
        const int sr = r + cropT;
        for (int c = 0; c < W; ++c) {
            const int sc = c + cropL;
            if (sr < 0 || sr >= rawH || sc < 0 || sc >= rawW) {
                mosaic[r * W + c] = 0.f;
                continue;
            }
            const int rawCh = int((filters >> (((sr << 1 & 14) | (sc & 1)) << 1)) & 3);
            const float wb  = camMul[rawCh];
            float v = (float(rawImg[sr * rawW + sc]) - blackLevel) * scale * wb;
            mosaic[r * W + c] = clampf(v, 0.f, 1.f);
        }
    }
    LOGI("mosaic built");
    // Per-Bayer-channel means of the raw input and the WB'd mosaic. For a
    // scene neutral under the as-shot WB, the four mosaic means should come
    // out roughly EQUAL — that is what white balance does. A per-channel skew
    // here means the inputs (raw data / black level / multipliers) are wrong
    // before any demosaic math runs; a clean result here exonerates them.
    {
        double rawSum[4] = {0, 0, 0, 0}, mosSum[4] = {0, 0, 0, 0};
        long long cnt[4] = {0, 0, 0, 0};
        for (int r = 0; r < H; ++r) {
            const int sr = r + cropT;
            for (int c = 0; c < W; ++c) {
                const int sc = c + cropL;
                if (sr < 0 || sr >= rawH || sc < 0 || sc >= rawW) continue;
                const int ch4 = int((filters >> (((sr << 1 & 14) | (sc & 1)) << 1)) & 3);
                rawSum[ch4] += rawImg[size_t(sr) * rawW + sc];
                mosSum[ch4] += mosaic[size_t(r) * W + c];
                cnt[ch4]++;
            }
        }
        auto m = [&](double* s, int k) { return cnt[k] ? s[k] / double(cnt[k]) : 0.0; };
        LOGI("mosaic RAW means   R=%.1f G1=%.1f B=%.1f G2=%.1f (black=%.1f white=%.1f)",
             m(rawSum,0), m(rawSum,1), m(rawSum,2), m(rawSum,3), blackLevel, whiteLevel);
        LOGI("mosaic WB'd means  R=%.5f G1=%.5f B=%.5f G2=%.5f (camMul=[%.3f,%.3f,%.3f,%.3f])",
             m(mosSum,0), m(mosSum,1), m(mosSum,2), m(mosSum,3),
             camMul[0], camMul[1], camMul[2], camMul[3]);
    }

    // Border clamp helper (crop-relative coords)
#define PX(arr, r_, c_) ((arr)[std::max(0,std::min(H-1,(r_)))*W + std::max(0,std::min(W-1,(c_)))])

    // ── Pass 1: directional G estimates ───────────────────────────────────
    // At G pixels: gH = gV = mosaic value.
    // At R/B pixels: gH = avg of left+right G neighbours; gV = top+bottom.
    for (int r = 0; r < H; ++r) {
        for (int c = 0; c < W; ++c) {
            const int sr = r + cropT, sc = c + cropL;
            const int ch = bayerColor(sr, sc, filters);
            const size_t i = size_t(r) * W + c;
            if (ch == 1) {
                gH[i] = gV[i] = mosaic[i];
            } else {
                gH[i] = 0.5f * (PX(mosaic, r, c - 1) + PX(mosaic, r, c + 1));
                gV[i] = 0.5f * (PX(mosaic, r - 1, c) + PX(mosaic, r + 1, c));
            }
        }
    }

    // ── Pass 2: 5-tap local variance of gH (horizontal) and gV (vertical) ─
    for (int r = 0; r < H; ++r) {
        for (int c = 0; c < W; ++c) {
            const size_t i = size_t(r) * W + c;
            const float hc = gH[i];
            float dh = 0.f;
            for (int k = -2; k <= 2; ++k) {
                float d = PX(gH, r, c + k) - hc;
                dh += d * d;
            }
            vH[i] = dh;

            const float vc = gV[i];
            float dv = 0.f;
            for (int k = -2; k <= 2; ++k) {
                float d = PX(gV, r + k, c) - vc;
                dv += d * d;
            }
            vV[i] = dv;
        }
    }

    // ── Pass 3: Wiener blend → outG ───────────────────────────────────────
    // Low-variance direction is trusted more (inverse-variance weighting).
    // Known-G sites keep the exact mosaic value.
    for (int r = 0; r < H; ++r) {
        for (int c = 0; c < W; ++c) {
            const int sr = r + cropT, sc = c + cropL;
            const int ch = bayerColor(sr, sc, filters);
            const size_t i = size_t(r) * W + c;
            if (ch == 1) {
                outG[i] = mosaic[i];
            } else {
                const float vh = vH[i], vv = vV[i];
                const float den = vh + vv;
                outG[i] = clampf(den < 1e-12f
                    ? 0.5f * (gH[i] + gV[i])
                    : (vv * gH[i] + vh * gV[i]) / den,
                    0.f, 1.f);
            }
        }
    }
    LOGI("green channel done");

    // ── Pass 4a: fill G−R and G−B at known R and B sites ─────────────────
    const float kNaN = std::numeric_limits<float>::quiet_NaN();
    for (size_t i = 0; i < N; ++i) { dR[i] = dB[i] = kNaN; }

    for (int r = 0; r < H; ++r) {
        for (int c = 0; c < W; ++c) {
            const int sr = r + cropT, sc = c + cropL;
            const int ch = bayerColor(sr, sc, filters);
            const size_t i = size_t(r) * W + c;
            if (ch == 0) dR[i] = outG[i] - mosaic[i];
            else if (ch == 2) dB[i] = outG[i] - mosaic[i];
        }
    }

    // ── Pass 4b: interpolate dR/dB at non-R/non-B sites ──────────────────
    // In a standard RGGB Bayer pattern the nearest same-colour neighbours
    // depend on the site type:
    //
    //   R site  (even row, even col): already filled in Pass 4a — skip.
    //   B site  (odd  row, odd  col): already filled in Pass 4a — skip.
    //   G1 site (even row, odd  col): nearest R neighbours are horizontal
    //       (dr=0, dc=±1) → (even,even) = R.
    //       nearest B neighbours are vertical (dr=±1, dc=0) → (odd,odd) = B.
    //   G2 site (odd  row, even col): nearest R neighbours are vertical
    //       (dr=±1, dc=0) → (even,even) = R.
    //       nearest B neighbours are horizontal (dr=0, dc=±1) → (odd,odd) = B.
    //
    // Using the correct per-site offsets avoids averaging across-edge values
    // (the previous diagonal scheme hit G/G2 sites, not R/B, producing NaN
    // fallbacks that propagated bad chroma into flat regions).
    for (int r = 0; r < H; ++r) {
        for (int c = 0; c < W; ++c) {
            const size_t i = size_t(r) * W + c;
            if (!std::isnan(dR[i]) && !std::isnan(dB[i])) continue;

            const int sr = r + cropT, sc = c + cropL;
            const int ch = bayerColor(sr, sc, filters);

            // dR at this site
            if (std::isnan(dR[i])) {
                float s = 0.f; int n = 0;
                // G1 (even row, odd col): R neighbours at dc=±1, dr=0
                // G2 (odd  row, even col): R neighbours at dr=±1, dc=0
                // B  (odd  row, odd  col): R neighbours diagonal dc=±1, dr=±1
                if (ch == 1) {
                    // G site — axis depends on row parity
                    if ((sr & 1) == 0) {
                        // G1: horizontal
                        float v;
                        v = PX(dR, r, c - 1); if (!std::isnan(v)) { s += v; ++n; }
                        v = PX(dR, r, c + 1); if (!std::isnan(v)) { s += v; ++n; }
                    } else {
                        // G2: vertical
                        float v;
                        v = PX(dR, r - 1, c); if (!std::isnan(v)) { s += v; ++n; }
                        v = PX(dR, r + 1, c); if (!std::isnan(v)) { s += v; ++n; }
                    }
                } else {
                    // B site: diagonal R neighbours
                    float v;
                    v = PX(dR, r - 1, c - 1); if (!std::isnan(v)) { s += v; ++n; }
                    v = PX(dR, r - 1, c + 1); if (!std::isnan(v)) { s += v; ++n; }
                    v = PX(dR, r + 1, c - 1); if (!std::isnan(v)) { s += v; ++n; }
                    v = PX(dR, r + 1, c + 1); if (!std::isnan(v)) { s += v; ++n; }
                }
                dR[i] = (n > 0) ? s / float(n) : 0.f;
            }

            // dB at this site
            if (std::isnan(dB[i])) {
                float s = 0.f; int n = 0;
                if (ch == 1) {
                    // G site — opposite axis from dR
                    if ((sr & 1) == 0) {
                        // G1: B neighbours vertical
                        float v;
                        v = PX(dB, r - 1, c); if (!std::isnan(v)) { s += v; ++n; }
                        v = PX(dB, r + 1, c); if (!std::isnan(v)) { s += v; ++n; }
                    } else {
                        // G2: B neighbours horizontal
                        float v;
                        v = PX(dB, r, c - 1); if (!std::isnan(v)) { s += v; ++n; }
                        v = PX(dB, r, c + 1); if (!std::isnan(v)) { s += v; ++n; }
                    }
                } else {
                    // R site: diagonal B neighbours
                    float v;
                    v = PX(dB, r - 1, c - 1); if (!std::isnan(v)) { s += v; ++n; }
                    v = PX(dB, r - 1, c + 1); if (!std::isnan(v)) { s += v; ++n; }
                    v = PX(dB, r + 1, c - 1); if (!std::isnan(v)) { s += v; ++n; }
                    v = PX(dB, r + 1, c + 1); if (!std::isnan(v)) { s += v; ++n; }
                }
                dB[i] = (n > 0) ? s / float(n) : 0.f;
            }
        }
    }

    // ── Pass 4c: edge-aware smooth on dR/dB difference planes ────────────
    // Diagonal interpolation leaves chroma errors at high-contrast edges.
    // A simple box average spreads those errors into visible streaks.
    // Instead: bilateral-style smooth — only accept neighbours whose dR/dB
    // value is within a threshold of the current pixel, so outlier spikes
    // from edge interpolation don't contaminate the flat regions around them.
    // Run 2 passes to spread values, keep threshold tight (0.05 in [0,1]).
    {
        std::unique_ptr<float[]> tmpR(new(std::nothrow) float[N]);
        std::unique_ptr<float[]> tmpB(new(std::nothrow) float[N]);
        if (tmpR && tmpB) {
            constexpr float kThr = 0.05f;   // max chroma difference to accept neighbour
            for (int pass = 0; pass < 2; ++pass) {
                for (int r = 0; r < H; ++r) {
                    for (int c = 0; c < W; ++c) {
                        const size_t i = size_t(r) * W + c;
                        const float cr = dR[i], cb = dB[i];
                        float sr = std::isnan(cr) ? 0.f : cr;
                        float sb = std::isnan(cb) ? 0.f : cb;
                        int nr = std::isnan(cr) ? 0 : 1;
                        int nb = std::isnan(cb) ? 0 : 1;
                        for (int dr = -1; dr <= 1; ++dr) {
                            for (int dc = -1; dc <= 1; ++dc) {
                                if (dr == 0 && dc == 0) continue;
                                float vr = PX(dR, r + dr, c + dc);
                                float vb = PX(dB, r + dr, c + dc);
                                if (!std::isnan(vr) && (std::isnan(cr) || std::abs(vr - cr) < kThr))
                                    { sr += vr; ++nr; }
                                if (!std::isnan(vb) && (std::isnan(cb) || std::abs(vb - cb) < kThr))
                                    { sb += vb; ++nb; }
                            }
                        }
                        tmpR[i] = (nr > 0) ? sr / float(nr) : (std::isnan(cr) ? 0.f : cr);
                        tmpB[i] = (nb > 0) ? sb / float(nb) : (std::isnan(cb) ? 0.f : cb);
                    }
                }
                memcpy(dR.get(), tmpR.get(), N * sizeof(float));
                memcpy(dB.get(), tmpB.get(), N * sizeof(float));
            }
        }
    }

    // ── Pass 5: recover R and B ────────────────────────────────────────────
    for (size_t i = 0; i < N; ++i) {
        outR[i] = clampf(outG[i] - dR[i], 0.f, 1.f);
        outB[i] = clampf(outG[i] - dB[i], 0.f, 1.f);
    }
    // Restore exact raw values at known R/B sites (overrides the estimate).
    for (int r = 0; r < H; ++r) {
        for (int c = 0; c < W; ++c) {
            const int sr = r + cropT, sc = c + cropL;
            const int ch = bayerColor(sr, sc, filters);
            const size_t i = size_t(r) * W + c;
            if (ch == 0) outR[i] = mosaic[i];
            else if (ch == 2) outB[i] = mosaic[i];
        }
    }

    LOGI("lmmse_demosaic complete: %dx%d", W, H);
    return true;
}

} // namespace raw_v3
