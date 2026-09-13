/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0.
 *
 * Direct port of v2's `rcd_demosaic_to_buf` (raw_decoder.cpp:1267-1549).
 * Comments preserved verbatim where they document non-obvious design
 * decisions; do not edit them without revisiting the underlying issue.
 */

#include "rcd_demosaic.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <thread>
#include <vector>

namespace raw_v3 {

bool rcd_demosaic_to_buf(const uint16_t* rawImg,
                         int rawW, int rawH,
                         int cropL, int cropT,
                         int outW, int outH,
                         unsigned filters,
                         float blackLevel, float whiteLevel,
                         const float camMul[4],
                         uint16_t* dst,
                         float clipThreshold) {
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

    // Highlight reconstruction (dcraw highlight=2 style). A sensel at/near the
    // raw white point is BLOWN — its value is truncated. Green saturates first,
    // so after the per-channel WB multiply (R×1.0, G×0.45, B×0.68) a clipped
    // cell demosaics to a corrupt ratio → MAGENTA. The pipeline:
    //   1. detect clip on the RAW value (pre-WB) — green's clip is hidden once
    //      its small WB gain is applied, so we must look before WB;
    //   2. dilate the clip mask to cover the RCD interpolation footprint;
    //   3. feather it into a smooth 0..1 weight (no hard edge);
    //   4. (step 5) reconstruct CHROMA only — lift the clipped channels toward
    //      the per-pixel max, weighted by the feather, leaving luminance
    //      untouched. Blown areas lose the false colour but keep their natural
    //      brightness gradient → smooth neutral highlights, no halo, no step.
    // Clip detection threshold is user-tunable now — was hardcoded at 0.97.
    // Lower values (0.85..0.95) catch near-clip sensels that aren't quite
    // truncated but still drift toward a colour cast from the brightest
    // unclipped channel; useful when the highlights have a slight tint
    // before reaching the hard sensor saturation point.
    const float clipFrac = (clipThreshold < 0.80f) ? 0.80f
                         : (clipThreshold > 1.00f) ? 1.00f : clipThreshold;
    const float clipRaw = blackLevel + clipFrac * range;   // sensel clip level
    std::unique_ptr<uint8_t[]> clipBin(new uint8_t[N]());   // hard 0/1 detect
    std::unique_ptr<float[]>   clipSoft(new float[N]());    // feathered 0..1 weight

    // ── Step 0: hot/stuck pixel pass (darktable-style adaptive threshold) ────
    // Canon 6D at long exposure / high ISO produces ~dozens of hot pixels.
    // Test: a pixel is "hot" if its value exceeds `med + k * sigma_local`,
    // where `med` is the median of its 4 same-color Bayer neighbours (±2 along
    // each axis so neighbours are the same channel) and `sigma_local` is
    // estimated from those same neighbours' Median Absolute Deviation (MAD).
    //
    // Why adaptive: a fixed `2.5 × med` ratio either misses dim hot pixels at
    // low ISO (where the noise floor is small and a 1.2× over-bright pixel is
    // already wildly out of family) or false-triggers on legitimate bright
    // detail at high ISO (where local noise σ is naturally large and a true-
    // signal speckle can easily reach 2.5× its neighbours). MAD-driven
    // threshold scales with the actual measured noise so both regimes work.
    //
    //   σ ≈ 1.4826 · MAD   (Gaussian-noise consistency factor)
    //   threshold = med + k · σ
    //   k = 7.0  → ~6σ outlier rejection (very confident hot-pixel call)
    //
    // The 6σ choice keeps false-positives near zero on noisy ISO 12800 / 25600
    // shots while still catching the persistent ~5–15 stuck pixels that
    // dominate the Canon 6D hot-pixel population. Replacement is the
    // same-color median, preserving Bayer structure for the demosaic that
    // follows. Cheap (~12 ms on 20MP).
    std::unique_ptr<uint16_t[]> rawWork(new uint16_t[size_t(rawW) * rawH]);
    std::memcpy(rawWork.get(), rawImg, size_t(rawW) * rawH * sizeof(uint16_t));
    {
        const float hotThresh = blackLevel + 0.05f * range;   // skip near-black
        const float kSigma    = 7.0f;                          // ~6σ rejection
        const float madToSig  = 1.4826f;                       // MAD → σ
        for (int y = 2; y < rawH - 2; ++y) {
            uint16_t* row = rawWork.get() + size_t(y) * rawW;
            const uint16_t* row_m2 = rawWork.get() + size_t(y - 2) * rawW;
            const uint16_t* row_p2 = rawWork.get() + size_t(y + 2) * rawW;
            for (int x = 2; x < rawW - 2; ++x) {
                const float v = float(row[x]);
                if (v < hotThresh) continue;
                // 4 same-color neighbours at ±2 along each axis.
                float n[4] = { float(row_m2[x]), float(row_p2[x]),
                               float(row[x - 2]), float(row[x + 2]) };
                // Insertion sort (4 elements) → median = mean of n[1], n[2].
                for (int i = 1; i < 4; ++i) {
                    float t = n[i]; int j = i - 1;
                    while (j >= 0 && n[j] > t) { n[j + 1] = n[j]; --j; }
                    n[j + 1] = t;
                }
                const float med = 0.5f * (n[1] + n[2]);
                if (med <= blackLevel) continue;
                // MAD = median(|n_i - med|). With 4 samples and the median
                // already computed, |n_i - med| is just |n_i - med| over the
                // sorted set; median of 4 abs-deviations = mean of the two
                // middle ones after re-sorting.
                float dev[4] = {
                    std::fabs(n[0] - med), std::fabs(n[1] - med),
                    std::fabs(n[2] - med), std::fabs(n[3] - med),
                };
                for (int i = 1; i < 4; ++i) {
                    float t = dev[i]; int j = i - 1;
                    while (j >= 0 && dev[j] > t) { dev[j + 1] = dev[j]; --j; }
                    dev[j + 1] = t;
                }
                const float mad   = 0.5f * (dev[1] + dev[2]);
                // Read-noise floor — keep a small minimum σ so dead-flat
                // patches (mad ≈ 0) still allow detection of stuck-bright
                // pixels. ~3 DN floor matches typical 14-bit CMOS read noise.
                const float sigma = std::max(madToSig * mad, 3.0f);
                if (v > med + kSigma * sigma) {
                    row[x] = uint16_t(med + 0.5f);
                }
            }
        }
    }
    const uint16_t* rawSrc = rawWork.get();

    // ── Step 1: copy + WB-normalize known Bayer values + build clip mask ──────
    // Each plane gets the value at its own sites and 0 elsewhere. We need
    // explicit zero on the "unknown" channels because step 2/3/4 read from
    // every plane on every neighbour — they rely on non-Bayer positions being 0.
    for (int y = 0; y < outH; y++) {
        const uint16_t* rawRow = rawSrc + (long long)(y + cropT) * rawW + cropL;
        const int row0 = y * outW;
        const int yParity = (y & 1) << 1;
        for (int x = 0; x < outW; x++) {
            int tabIdx = yParity | (x & 1);
            int c = fcTab[tabIdx];
            int wbIdx = wbTab[tabIdx];
            const float raw = (float)rawRow[x];
            float v = (raw - blackLevel) * invRange;
            v *= camMul[wbIdx];
            if (v < 0.f) v = 0.f; else if (v > 1.f) v = 1.f;
            int idx = row0 + x;
            // Branchless-ish: write all three planes, zero the two non-matching.
            R[idx] = (c == 0) ? v : 0.f;
            G[idx] = (c == 1) ? v : 0.f;
            B[idx] = (c == 2) ? v : 0.f;
            if (raw >= clipRaw) clipBin[idx] = 1;
        }
    }

    // ── Step 1b: dilate, then FEATHER the clip mask into a 0..1 weight ─────────
    // Binary mask → reconstruction has a hard luminosity step at its edge.
    // We dilate to cover the RCD interpolation footprint, then box-blur the
    // mask so it ramps smoothly 0→1 across the boundary. Step 5 blends the
    // reconstruction (lift clipped channels toward the max) by this weight, so
    // blown areas roll off SMOOTHLY into the surroundings — no instant clip.
    {
        const int kDilate = 3;
        std::unique_ptr<uint8_t[]> dil(new uint8_t[N]());
        std::unique_ptr<uint8_t[]> tmp(new uint8_t[N]());
        for (int y = 0; y < outH; y++) {
            const int row = y * outW;
            for (int x = 0; x < outW; x++) {
                uint8_t hit = 0;
                const int x0 = x - kDilate < 0 ? 0 : x - kDilate;
                const int x1 = x + kDilate >= outW ? outW - 1 : x + kDilate;
                for (int xx = x0; xx <= x1; xx++) { if (clipBin[row + xx]) { hit = 1; break; } }
                tmp[row + x] = hit;
            }
        }
        for (int x = 0; x < outW; x++) {
            for (int y = 0; y < outH; y++) {
                uint8_t hit = 0;
                const int y0 = y - kDilate < 0 ? 0 : y - kDilate;
                const int y1 = y + kDilate >= outH ? outH - 1 : y + kDilate;
                for (int yy = y0; yy <= y1; yy++) { if (tmp[yy * outW + x]) { hit = 1; break; } }
                dil[y * outW + x] = hit;
            }
        }
        // Feather: separable box blur of the dilated 0/1 mask → smooth 0..1.
        const int kFeather = 16;
        const float fInv = 1.f / float(2 * kFeather + 1);
        std::unique_ptr<float[]> ftmp(new float[N]());
        for (int y = 0; y < outH; y++) {
            const int row = y * outW;
            for (int x = 0; x < outW; x++) {
                float acc = 0.f;
                const int x0 = x - kFeather, x1 = x + kFeather;
                for (int xx = x0; xx <= x1; xx++) {
                    const int cx = xx < 0 ? 0 : (xx >= outW ? outW - 1 : xx);
                    acc += dil[row + cx];
                }
                ftmp[row + x] = acc * fInv;
            }
        }
        for (int x = 0; x < outW; x++) {
            for (int y = 0; y < outH; y++) {
                float acc = 0.f;
                const int y0 = y - kFeather, y1 = y + kFeather;
                for (int yy = y0; yy <= y1; yy++) {
                    const int cy = yy < 0 ? 0 : (yy >= outH ? outH - 1 : yy);
                    acc += ftmp[cy * outW + x];
                }
                clipSoft[y * outW + x] = acc * fInv;
            }
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
                //
                // Highlight-halo fix: at point-source bright spots (string
                // lights, sun glints) the centre R/B `pC` is at sensor
                // saturation while same-channel neighbours `pE/pW/pN/pS` are
                // dark. The Laplacian term `(2*pC - pE - pW) * 0.25` then
                // injects a large value (~0.5 at full clip) into a green
                // interpolation whose neighbours `gE/gW` are also near zero —
                // producing a bright artificial green halo around the bulb.
                // Other demosaicers (AHD, VNG) don't show it because they
                // don't use this exact Laplacian-correction form at clip.
                //
                // Fix: clamp the correction term to ±(max neighbour green).
                // The intent of the correction is to recover a high-frequency
                // luma component that's plausibly present in green too — so
                // its magnitude can't exceed what green could plausibly
                // contain locally. Capping at max-neighbour-green prevents
                // the runaway clip-injection without affecting normal edges
                // (where pC and pE/W are within green's local range anyway).
                // Highlight clip gate: if pC OR any G neighbour is near clip,
                // the Laplacian (2*pC - pE - pW) becomes meaningless (the
                // plateau hides what the true signal was). Adding it injects
                // a colour error that propagates through steps 3/4 as a
                // pink/magenta halo. Fade the correction term toward 0 as the
                // local max approaches clip; below 0.85 use full RCD.
                constexpr float kStep2ClipLo = 0.85f, kStep2ClipHi = 0.97f;
                const float locMax = std::max({pC, gN, gS, gE, gW});
                float clipT = (kStep2ClipHi - locMax) / (kStep2ClipHi - kStep2ClipLo);
                if (clipT < 0.f) clipT = 0.f; else if (clipT > 1.f) clipT = 1.f;
                const float corrH = (2.f * pC - pE - pW) * 0.25f * clipT;
                const float corrV = (2.f * pC - pN - pS) * 0.25f * clipT;
                const float gMaxH = (gE > gW ? gE : gW);
                const float gMaxV = (gN > gS ? gN : gS);
                const float gMinH = (gE < gW ? gE : gW);
                const float gMinV = (gN < gS ? gN : gS);
                // Symmetric clamp around 0 by the local green range.
                const float corrCapH = gMaxH - gMinH + 0.05f;  // small floor
                const float corrCapV = gMaxV - gMinV + 0.05f;
                const float corrHc = corrH >  corrCapH ?  corrCapH :
                                     corrH < -corrCapH ? -corrCapH : corrH;
                const float corrVc = corrV >  corrCapV ?  corrCapV :
                                     corrV < -corrCapV ? -corrCapV : corrV;
                const float gH = (gE + gW) * 0.5f + corrHc;
                const float gV = (gN + gS) * 0.5f + corrVc;

                const float dH = fabsf(gE - gW) + fabsf(2.f * pC - pE - pW);
                const float dV = fabsf(gN - gS) + fabsf(2.f * pC - pN - pS);

                // Directional pick with a SELF-GATING soft selector. A hard
                // dH<dV branch structures sensor noise into sharp colour grit
                // in out-of-focus areas ("nervous" bokeh), because in flat
                // regions the "winner" is just noise. Instead of a binary
                // switch we blend the directional estimate with the isotropic
                // 50/50 average by a continuous factor `alpha`:
                //   gInterp = alpha*gDir + (1-alpha)*gIso
                // alpha is the product of two attenuations, so it falls to 0
                // (→ isotropic, melts noise) when EITHER condition holds:
                //   • magnitude gate Ω_mag = m²/(m²+ε²), m=max(dH,dV): kills
                //     the directional term below the ε noise floor (sigmoidal,
                //     leaves true edges at 1).
                //   • certainty gate Ω_dir = ((dH-dV)/(dH+dV+δ))²: 0 when the
                //     two directions are ambiguous (flat), 1 when one clearly
                //     dominates (a real edge).
                // On a sharp edge both gates → 1 ⇒ full directional RCD. In
                // OOF noise either gate → 0 ⇒ clean isotropic low-pass. The
                // blend is continuous so no threshold cliff / zipper.
                const float gDir = (dH < dV) ? gH : gV;
                const float gIso = (gH + gV) * 0.5f;
                constexpr float EPS2  = 0.0009f; // (ε≈0.03)² noise floor
                constexpr float DELTA = 1e-5f;   // div-by-zero safeguard
                const float md  = dH > dV ? dH : dV;
                const float md2 = md * md;
                const float omegaMag = md2 / (md2 + EPS2);
                const float diffD = fabsf(dH - dV);
                const float sumD  = dH + dV + DELTA;
                const float ratio = diffD / sumD;
                const float omegaDir = ratio * ratio;
                const float alpha = omegaMag * omegaDir;
                float gInterp = alpha * gDir + (1.f - alpha) * gIso;

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
                    // Near-clip gate: when any G sample is near saturation, the
                    // Laplacian correction (2*gC - gA - gB) is unreliable —
                    // clipped G plateaus look like flat regions while the
                    // *true* signal kept rising. Adding the bogus Laplacian
                    // shifts R/B asymmetrically, producing pink/magenta halos
                    // in the highlight rolloff. Fade to plain bilinear there.
                    constexpr float kClipLo = 0.90f, kClipHi = 0.98f;
                    const float gMax = gA > gB ? (gA > gC ? gA : gC)
                                               : (gB > gC ? gB : gC);
                    float t = (kClipHi - gMax) / (kClipHi - kClipLo);
                    if (t < 0.f) t = 0.f; else if (t > 1.f) t = 1.f;
                    const float corr = (2.f * gC - gA - gB) * 0.5f * t;
                    float v = (pA + pB) * 0.5f + corr;
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
                    // Same near-clip gate as Step 3 — see comment there.
                    constexpr float kClipLo = 0.90f, kClipHi = 0.98f;
                    const float gMax = gA > gB ? (gA > gC ? gA : gC)
                                               : (gB > gC ? gB : gC);
                    float t = (kClipHi - gMax) / (kClipHi - kClipLo);
                    if (t < 0.f) t = 0.f; else if (t > 1.f) t = 1.f;
                    const float corr = (2.f * gC - gA - gB) * 0.5f * t;
                    float v = (pA + pB) * 0.5f + corr;
                    if (v < 0.f) v = 0.f; else if (v > 1.f) v = 1.f;
                    return v;
                };

                float h = cardAdd(idxH_a, idxH_b);
                float v = cardAdd(idxV_a, idxV_b);
                targetPlane[idx] = (h + v) * 0.5f;
            }
        }
    });

    // ── Step 4b: R/B border replication ───────────────────────────────────────
    // Steps 3 & 4 iterate [1 .. outH-2] × [1 .. outW-2], so the outermost row
    // (outH-1), outermost column (outW-1), top row and left column are NEVER
    // written for R/B — and the planes are deliberately allocated uninitialised
    // (make_unique<float[]>). On Canon CR2 this showed as a garbage green strip
    // in the bottom 1–3 rows of the decoded image. Replicate the nearest valid
    // interior pixel into all four borders so the edges carry real colour.
    if (outW >= 2 && outH >= 2) {
        // Top + bottom rows ← adjacent interior row. ALL THREE planes (not
        // just R/B) — G is interpolated by step 2 over [1..outH-2] only, so
        // the outermost rows have whatever the step-1 Bayer write left there
        // (zero for non-G positions). Replicate G as well to kill the 1-px
        // edge banding that shows up on Canon 6D shots as a green or magenta
        // hairline along the top/bottom edge.
        for (int x = 0; x < outW; x++) {
            R[x]                       = R[outW + x];
            G[x]                       = G[outW + x];
            B[x]                       = B[outW + x];
            R[(outH - 1) * outW + x]   = R[(outH - 2) * outW + x];
            G[(outH - 1) * outW + x]   = G[(outH - 2) * outW + x];
            B[(outH - 1) * outW + x]   = B[(outH - 2) * outW + x];
        }
        // Left + right columns ← adjacent interior column. Same fix for the
        // VERTICAL edge — step 2 also iterates [1..outW-2], so column 0 and
        // column outW-1 have raw Bayer-position values (0 for the "wrong"
        // channels) instead of demosaiced values. The user-reported "1-px
        // color shift on left/right edges of some 6D shots" was exactly this.
        for (int y = 0; y < outH; y++) {
            const int r = y * outW;
            R[r]            = R[r + 1];
            G[r]            = G[r + 1];
            B[r]            = B[r + 1];
            R[r + outW - 1] = R[r + outW - 2];
            G[r + outW - 1] = G[r + outW - 2];
            B[r + outW - 1] = B[r + outW - 2];
        }
    }

    // ── Step 4c: LCh-style highlight chroma inpainting (Ansel/darktable) ──────
    //   The desaturate-toward-max recon in step 5 makes blown patches neutral,
    //   which works but flattens any colour gradient the unclipped neighbours
    //   imply (e.g. orange sunset sky → the blown sun core becomes white
    //   instead of saturated orange). The Ansel "Color reconstruction" module
    //   instead diffuses chrominance from the unclipped surround INTO the
    //   clipped patch — equivalent to solving the Poisson equation
    //       ∇²(Cr) = 0,  ∇²(Cb) = 0
    //   with Dirichlet boundary = unclipped pixels' chrominance. Iteratively
    //   solved via Jacobi (simple, easy to multithread, converges fast on the
    //   tiny clipped regions typical of a photograph).
    //
    //   Chrominance basis: (Cr, Cb) = (r - y, b - y) with y = 0.299r+0.587g+0.114b.
    //   This is exactly the YCbCr separation; it sidesteps the cost of going
    //   through Lab inside the loop and gives the same hue-preserving behavior
    //   for the gentle chroma we're reconstructing here.
    //
    //   Step 5 will compose the recovered chroma back onto the luma the
    //   demosaicer produced, replacing the desaturate-to-max path for cells
    //   where the inpaint result is more saturated than neutral.
    std::unique_ptr<float[]> CrBuf(new float[N]);
    std::unique_ptr<float[]> CbBuf(new float[N]);
    float* Cr = CrBuf.get();
    float* Cb = CbBuf.get();
    {
        // Seed from current demosaic. Cells where clipBin==1 will be
        // overwritten by Jacobi; the rest stay as Dirichlet boundary.
        for (int i = 0; i < N; ++i) {
            const float r = R[i], g = G[i], b = B[i];
            const float y = 0.299f * r + 0.587f * g + 0.114f * b;
            Cr[i] = r - y;
            Cb[i] = b - y;
        }
        // Inside the clipped core the seed chrominance is wrong (one or more
        // channels truncated), so wipe it to zero before the solve so it
        // doesn't bias the average. Boundary cells (clipBin==0) keep their
        // measured chrominance — these drive the diffusion.
        for (int i = 0; i < N; ++i) {
            if (clipBin[i]) { Cr[i] = 0.f; Cb[i] = 0.f; }
        }
        // Jacobi iterations. 30 iters covers a ~30-pixel diffusion radius —
        // larger than any typical clipped blob (sun, specular, sky hotspot).
        // Allocate two scratch planes so we don't read+write the same buffer.
        std::unique_ptr<float[]> Cr2Buf(new float[N]);
        std::unique_ptr<float[]> Cb2Buf(new float[N]);
        float* Cr2 = Cr2Buf.get();
        float* Cb2 = Cb2Buf.get();
        const int kIters = 30;
        for (int it = 0; it < kIters; ++it) {
            // Copy current state — Dirichlet cells stay fixed; clipped cells
            // get overwritten with the 4-neighbour mean.
            std::memcpy(Cr2, Cr, size_t(N) * sizeof(float));
            std::memcpy(Cb2, Cb, size_t(N) * sizeof(float));
            // Interior loop only — borders are already replicated and
            // clipBin is unlikely to be set on the 1-px frame.
            for (int y = 1; y < outH - 1; ++y) {
                const int row = y * outW;
                for (int x = 1; x < outW - 1; ++x) {
                    const int i = row + x;
                    if (!clipBin[i]) continue;
                    Cr2[i] = 0.25f * (Cr[i - 1] + Cr[i + 1] + Cr[i - outW] + Cr[i + outW]);
                    Cb2[i] = 0.25f * (Cb[i - 1] + Cb[i + 1] + Cb[i - outW] + Cb[i + outW]);
                }
            }
            std::swap(Cr, Cr2);
            std::swap(Cb, Cb2);
            // After the swap Cr/Cb point into Cr2Buf/Cb2Buf (and vice versa);
            // the unique_ptrs still own the same memory regardless. We need
            // the final state in CrBuf/CbBuf — the post-loop swap below
            // handles the odd-iter-count case.
        }
        // If kIters is odd, the latest result lives in the *2Buf. Detect by
        // address and copy back so CrBuf/CbBuf always hold the final solution.
        if (Cr != CrBuf.get()) std::memcpy(CrBuf.get(), Cr, size_t(N) * sizeof(float));
        if (Cb != CbBuf.get()) std::memcpy(CbBuf.get(), Cb, size_t(N) * sizeof(float));
        Cr = CrBuf.get();
        Cb = CbBuf.get();
    }

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
                // Highlight reconstruction — continuous blend, no boundary
                // discontinuity.
                //
                // Previously this branched on clipBin: inside the clipped
                // core (clipBin==1) the pixel used LCh chroma inpaint, and
                // in the feather zone (clipBin==0, clipSoft>0) it used the
                // luma-preserving desat-toward-max. Those two paths produce
                // DIFFERENT chromas at the boundary — the dilated-clip ring
                // (16-px feather) was a sharp chroma switch and read as a
                // soft halo around bright objects against dark surroundings.
                //
                // Fix: compute both candidates and blend BOTH by clipSoft.
                //   • lchR/G/B  — LCh recon (chroma from Poisson Cr/Cb)
                //   • desR/G/B  — luma-preserving desat-toward-max
                // Final = lerp(orig, lerp(des, lch, clipBin), clipSoft).
                //
                // Result: in the core, full LCh recon (matches old behaviour);
                // at the feather midpoint, smooth blend between recon and
                // desat-toward-max; outside the feather, untouched. No
                // chroma jump anywhere.
                const float w = clipSoft[i];
                if (w > 0.001f) {
                    // ── Candidate A: LCh chroma inpaint ──
                    const float y   = 0.299f * r + 0.587f * g + 0.114f * b;
                    // Pink/magenta-rim fix for white point-source highlights.
                    // The Poisson solver diffuses surround chrominance into
                    // the clipped core — perfect for orange sunsets but
                    // wrong for a white string-light bulb whose warm-tinted
                    // incandescent glow surrounds it. The solver pulls that
                    // warm tint INTO the white core, producing the pink
                    // halo rim the user reported.
                    //
                    // Fix: attenuate the inpainted chroma proportional to
                    // (1 - luma)². At luma=1.0 (pure white core) chroma is
                    // fully muted regardless of surround. At luma=0.7
                    // (sunset-zone clip) chroma stays at 9% mute — minimal
                    // impact on the orange-sky use case. Quadratic so the
                    // transition is smooth and the cutoff is concentrated
                    // near pure white.
                    const float chromaGate = (1.f - y) * (1.f - y) * 4.f;
                    const float chromaScale = chromaGate < 0.f ? 0.f :
                                              (chromaGate > 1.f ? 1.f : chromaGate);
                    const float crI = Cr[i] * chromaScale;
                    const float cbI = Cb[i] * chromaScale;
                    const float lchR_unclamped = y + crI;
                    const float lchB_unclamped = y + cbI;
                    const float lchG_unclamped = (y - 0.299f * lchR_unclamped - 0.114f * lchB_unclamped) / 0.587f;
                    const float lchR = lchR_unclamped < 0.f ? 0.f : (lchR_unclamped > 1.f ? 1.f : lchR_unclamped);
                    const float lchG = lchG_unclamped < 0.f ? 0.f : (lchG_unclamped > 1.f ? 1.f : lchG_unclamped);
                    const float lchB = lchB_unclamped < 0.f ? 0.f : (lchB_unclamped > 1.f ? 1.f : lchB_unclamped);
                    // ── Candidate B: luma-preserving desat toward LUMA ──
                    // Was desat-toward-max which preserved the dominant-
                    // channel bias — a magenta-tinted near-clip pixel
                    // (R=0.95, G=0.70, B=0.85) would have all channels
                    // pulled toward max=R, AMPLIFYING the magenta hue
                    // instead of removing it. The pink/magenta band the
                    // user saw OUTSIDE the white core was this path
                    // entrenching the demosaic's near-clip chroma error.
                    //
                    // Correct form: pull each channel toward the pixel's
                    // OWN luma (BT.601 weights). That moves the pixel
                    // along the chroma axis toward neutral gray without
                    // changing luminance (luma is invariant under this
                    // transform because Σwᵢ·(yᵢ + (y - yᵢ)·w) = y).
                    const float lumaBefore = y;
                    float desR = r + (lumaBefore - r) * w;
                    float desG = g + (lumaBefore - g) * w;
                    float desB = b + (lumaBefore - b) * w;
                    // ── Blend the two candidates by clipBin softly ──
                    // clipBin is hard 0/1 — read directly to weight LCh vs
                    // desat. The OUTER blend (by clipSoft) eats any sharpness
                    // that introduces at the dilated-mask edge.
                    const float bw = clipBin[i] ? 1.f : 0.f;
                    const float candR = desR + (lchR - desR) * bw;
                    const float candG = desG + (lchG - desG) * bw;
                    const float candB = desB + (lchB - desB) * bw;
                    // Outer blend by clipSoft — w=1 in deep core (full recon),
                    // w→0 at feather edge (full pass-through).
                    r = r + (candR - r) * w;
                    g = g + (candG - g) * w;
                    b = b + (candB - b) * w;
                }
                out[x * 3]     = (uint16_t)(b * 65535.f + 0.5f);
                out[x * 3 + 1] = (uint16_t)(g * 65535.f + 0.5f);
                out[x * 3 + 2] = (uint16_t)(r * 65535.f + 0.5f);
            }
        }
    });
    return true;
}

}  // namespace raw_v3
