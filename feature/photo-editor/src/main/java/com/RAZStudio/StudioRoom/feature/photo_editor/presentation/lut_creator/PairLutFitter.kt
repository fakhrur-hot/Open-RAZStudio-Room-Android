/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * LUT Creator — fit a 3D .cube LUT from before/after photo pairs.
 *
 * On-device port of the validated Python pipeline (before/after → .cube):
 *   1. Sample corresponding pixels from each pair (after resized to before dims
 *      so pixel[i]↔pixel[i]; before/after must be the same framing/crop).
 *   2. Fit a degree-3 trivariate polynomial (20 terms) per output channel via
 *      ridge-regularised normal equations, two-pass with outlier rejection
 *      (drops overlay text / watermarks / minor mismatch and whole bad pairs).
 *   3. Shrink toward identity in under-sampled colour regions (support-weighted)
 *      so colours the pairs never exercised pass through unchanged — this is
 *      what prevents the "saturated colour → wild extrapolation / posterised
 *      artefact" failure.
 *   4. Light grid smoothing, clamp, serialise to a 33³ Adobe .cube (red-fastest,
 *      matching feature/filters CubeLutFilter's parser).
 *
 * No third-party numeric dependency: the only linear solve is a 20×20 system,
 * done with a self-contained ridge Gauss-Jordan elimination.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.lut_creator

import android.graphics.Bitmap
import kotlin.math.min

object PairLutFitter {

    private const val NTERMS = 20
    private const val WORK_LONG_SIDE = 384      // downscale pairs to this before sampling
    private const val MAX_SAMPLES_PER_PAIR = 40_000
    private const val RIDGE = 1e-4              // normal-equation regularisation
    /**
     * Identity-shrink strength, expressed as a FRACTION of the average sample
     * count per OCCUPIED cube node (measured before the support blur). A node
     * carrying the typical density keeps w = 1/(1+0.25) ≈ 0.8 of the fitted
     * grade; a node the pairs never exercised still falls back to identity.
     *
     * Was an ABSOLUTE pseudo-count (200.0), which made the generated LUT very
     * nearly a no-op: a 33³ cube has 35 937 nodes and a pair contributes only
     * ~55 k samples, so per-node counts are single digits (measured 3.4 for
     * full-gamut pairs, 45 for a photo-like one) against a 200 pseudo-count —
     * w ≈ 0.02, i.e. 98 % identity. It was also backwards: the MORE colour the
     * pairs covered, the fewer samples landed per node and the weaker the LUT
     * became. Measured recovery of a known grade (offline port of this file):
     * full-gamut pairs 1 % → 75 %, photo-like pairs 35 % → 83 %.
     */
    private const val SUPPORT_FRACTION = 0.25
    private const val PAIR_DROP_MEDIAN = 0.12   // drop a pair whose median residual exceeds this
    private const val INLIER_PERCENTILE = 0.90f  // keep the best 90% of pixels for the refit

    /** A fitted LUT: flat float grid + its cube size. Layout is red-fastest:
     *  grid[((b*size+g)*size+r)*3 + channel], values in [0,1]. */
    class FittedLut(val grid: FloatArray, val size: Int)

    // ── Public API ────────────────────────────────────────────────────────────

    /** Fit a LUT from before/after bitmap pairs. Returns null if no pair survives. */
    fun fit(pairs: List<Pair<Bitmap, Bitmap>>, cubeSize: Int = 33): FittedLut? {
        // ── collect corresponding pixel samples ──
        val sr = ArrayList<Float>(); val sg = ArrayList<Float>(); val sb = ArrayList<Float>()
        val yr = ArrayList<Float>(); val yg = ArrayList<Float>(); val yb = ArrayList<Float>()
        val pid = ArrayList<Int>()
        var pairIndex = 0
        for ((before, after) in pairs) {
            val b = scaleDown(before, WORK_LONG_SIDE)
            // resize after to before's working dims so pixel[i] ↔ pixel[i]
            val a = if (after.width == b.width && after.height == b.height) after
            else Bitmap.createScaledBitmap(after, b.width, b.height, true)
            val n = b.width * b.height
            val bp = IntArray(n); b.getPixels(bp, 0, b.width, 0, 0, b.width, b.height)
            val ap = IntArray(n); a.getPixels(ap, 0, a.width, 0, 0, a.width, a.height)
            val stride = maxOf(1, n / MAX_SAMPLES_PER_PAIR)
            var i = 0
            while (i < n) {
                val bc = bp[i]; val ac = ap[i]
                sr.add(((bc ushr 16) and 0xFF) / 255f); sg.add(((bc ushr 8) and 0xFF) / 255f); sb.add((bc and 0xFF) / 255f)
                yr.add(((ac ushr 16) and 0xFF) / 255f); yg.add(((ac ushr 8) and 0xFF) / 255f); yb.add((ac and 0xFF) / 255f)
                pid.add(pairIndex)
                i += stride
            }
            pairIndex++
        }
        val m = sr.size
        if (m < 100) return null

        val srr = FloatArray(m) { sr[it] }; val sgg = FloatArray(m) { sg[it] }; val sbb = FloatArray(m) { sb[it] }
        val yrr = FloatArray(m) { yr[it] }; val ygg = FloatArray(m) { yg[it] }; val ybb = FloatArray(m) { yb[it] }
        val pidd = IntArray(m) { pid[it] }

        // ── pass 1: fit over all samples ──
        var coef = solvePoly(srr, sgg, sbb, yrr, ygg, ybb, BooleanArray(m) { true })
            ?: return null

        // per-sample residual, per-pair median → drop bad pairs
        val resid = FloatArray(m)
        val feats = DoubleArray(NTERMS)
        for (k in 0 until m) {
            poly(srr[k].toDouble(), sgg[k].toDouble(), sbb[k].toDouble(), feats)
            val pr = predict(feats, coef, 0); val pg = predict(feats, coef, 1); val pb = predict(feats, coef, 2)
            val dr = pr - yrr[k]; val dg = pg - ygg[k]; val db = pb - ybb[k]
            resid[k] = kotlin.math.sqrt(dr * dr + dg * dg + db * db).toFloat()
        }
        val droppedPairs = HashSet<Int>()
        run {
            val byPair = HashMap<Int, ArrayList<Float>>()
            for (k in 0 until m) byPair.getOrPut(pidd[k]) { ArrayList() }.add(resid[k])
            for ((p, list) in byPair) if (median(list) > PAIR_DROP_MEDIAN) droppedPairs.add(p)
        }
        // if we'd drop everything, keep all (better a rough LUT than none)
        if (droppedPairs.size >= pairs.size) droppedPairs.clear()

        // inlier mask: surviving pairs AND best INLIER_PERCENTILE of residuals
        val survivingResid = ArrayList<Float>()
        for (k in 0 until m) if (pidd[k] !in droppedPairs) survivingResid.add(resid[k])
        val thresh = percentile(survivingResid, INLIER_PERCENTILE).coerceAtLeast(0.06f)
        val inlier = BooleanArray(m) { k -> pidd[k] !in droppedPairs && resid[k] <= thresh }

        // ── pass 2: refit on inliers ──
        coef = solvePoly(srr, sgg, sbb, yrr, ygg, ybb, inlier) ?: coef

        // ── support field for identity-shrink ──
        val N = cubeSize
        val support = DoubleArray(N * N * N)
        var inlierCount = 0
        for (k in 0 until m) {
            if (!inlier[k]) continue
            val ri = (srr[k] * (N - 1)).toInt().coerceIn(0, N - 1)
            val gi = (sgg[k] * (N - 1)).toInt().coerceIn(0, N - 1)
            val bi = (sbb[k] * (N - 1)).toInt().coerceIn(0, N - 1)
            support[(bi * N + gi) * N + ri] += 1.0
            inlierCount++
        }
        // Scale-invariant shrink reference: average samples per OCCUPIED node,
        // taken BEFORE the blur (the blur bleeds support into empty neighbours,
        // which would drag the reference toward zero). See SUPPORT_FRACTION.
        var occupiedNodes = 0
        for (v in support) if (v > 0.0) occupiedNodes++
        val supportRef = if (occupiedNodes > 0) inlierCount.toDouble() / occupiedNodes else 1.0
        val supportK = (SUPPORT_FRACTION * supportRef).coerceAtLeast(1e-6)
        blurScalar(support, N)  // 1 mild pass so isolated nodes aren't holes

        // ── evaluate grid: shrink toward identity where support is thin ──
        val grid = FloatArray(N * N * N * 3)
        val f = DoubleArray(NTERMS)
        for (bi in 0 until N) {
            val bv = bi.toDouble() / (N - 1)
            for (gi in 0 until N) {
                val gv = gi.toDouble() / (N - 1)
                for (ri in 0 until N) {
                    val rv = ri.toDouble() / (N - 1)
                    poly(rv, gv, bv, f)
                    val sup = support[(bi * N + gi) * N + ri]
                    val w = sup / (sup + supportK)           // 0..1
                    val or = predict(f, coef, 0); val og = predict(f, coef, 1); val ob = predict(f, coef, 2)
                    val idx = ((bi * N + gi) * N + ri) * 3
                    grid[idx]     = clamp01(w * or + (1 - w) * rv)
                    grid[idx + 1] = clamp01(w * og + (1 - w) * gv)
                    grid[idx + 2] = clamp01(w * ob + (1 - w) * bv)
                }
            }
        }
        blurGrid(grid, N)  // light smoothing to erase blend-boundary banding
        return FittedLut(grid, N)
    }

    /** Serialise a fitted LUT to Adobe .cube text (red-fastest), matching CubeLutFilter's parser. */
    fun serialize(lut: FittedLut, title: String): String {
        val N = lut.size
        val sb = StringBuilder(N * N * N * 22 + 128)
        sb.append("# Created in RAZStudio LUT Creator from before/after photo pairs\n")
        sb.append("TITLE \"").append(title.take(60)).append("\"\n")
        sb.append("LUT_3D_SIZE ").append(N).append('\n')
        sb.append("DOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n")
        for (bi in 0 until N) for (gi in 0 until N) for (ri in 0 until N) {
            val idx = ((bi * N + gi) * N + ri) * 3
            sb.append(fmt(lut.grid[idx])).append(' ')
                .append(fmt(lut.grid[idx + 1])).append(' ')
                .append(fmt(lut.grid[idx + 2])).append('\n')
        }
        return sb.toString()
    }

    /** Apply a fitted LUT to [src] (trilinear) → a new ARGB_8888 bitmap, for preview. */
    fun apply(src: Bitmap, lut: FittedLut, maxLongSide: Int = 720): Bitmap {
        val s = scaleDown(src, maxLongSide)
        val N = lut.size; val g = lut.grid
        val w = s.width; val h = s.height
        val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
        val fmax = (N - 1).toFloat()
        for (i in px.indices) {
            val c = px[i]
            val rp = ((c ushr 16) and 0xFF) / 255f * fmax
            val gp = ((c ushr 8) and 0xFF) / 255f * fmax
            val bp = (c and 0xFF) / 255f * fmax
            val r0 = rp.toInt().coerceIn(0, N - 1); val r1 = min(r0 + 1, N - 1); val fr = rp - r0
            val g0 = gp.toInt().coerceIn(0, N - 1); val g1 = min(g0 + 1, N - 1); val fg = gp - g0
            val b0 = bp.toInt().coerceIn(0, N - 1); val b1 = min(b0 + 1, N - 1); val fb = bp - b0
            var or = 0f; var og = 0f; var ob = 0f
            for (bi in 0..1) {
                val bb = if (bi == 0) b0 else b1; val wb = if (bi == 0) 1 - fb else fb
                for (gi in 0..1) {
                    val gg = if (gi == 0) g0 else g1; val wg = if (gi == 0) 1 - fg else fg
                    for (ri in 0..1) {
                        val rr = if (ri == 0) r0 else r1; val wr = if (ri == 0) 1 - fr else fr
                        val wgt = wb * wg * wr
                        val idx = ((bb * N + gg) * N + rr) * 3
                        or += g[idx] * wgt; og += g[idx + 1] * wgt; ob += g[idx + 2] * wgt
                    }
                }
            }
            val ri = (or * 255f + 0.5f).toInt().coerceIn(0, 255)
            val gi = (og * 255f + 0.5f).toInt().coerceIn(0, 255)
            val biv = (ob * 255f + 0.5f).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or biv
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ── polynomial + solve ─────────────────────────────────────────────────────

    private fun poly(r: Double, g: Double, b: Double, out: DoubleArray) {
        out[0] = 1.0; out[1] = r; out[2] = g; out[3] = b
        out[4] = r * r; out[5] = g * g; out[6] = b * b; out[7] = r * g; out[8] = r * b; out[9] = g * b
        out[10] = r * r * r; out[11] = g * g * g; out[12] = b * b * b
        out[13] = r * r * g; out[14] = r * r * b; out[15] = g * g * r; out[16] = g * g * b; out[17] = b * b * r; out[18] = b * b * g
        out[19] = r * g * b
    }

    private fun predict(f: DoubleArray, coef: Array<DoubleArray>, c: Int): Double {
        var s = 0.0
        val cc = coef[c]
        for (i in 0 until NTERMS) s += f[i] * cc[i]
        return s
    }

    /** Least-squares fit of the 20-term polynomial for all 3 channels over the masked samples.
     *  Returns coef[channel][20], or null if the system is degenerate. */
    private fun solvePoly(
        sr: FloatArray, sg: FloatArray, sb: FloatArray,
        yr: FloatArray, yg: FloatArray, yb: FloatArray,
        mask: BooleanArray,
    ): Array<DoubleArray>? {
        val ata = Array(NTERMS) { DoubleArray(NTERMS) }
        val atb = Array(NTERMS) { DoubleArray(3) }
        val f = DoubleArray(NTERMS)
        var count = 0
        for (k in sr.indices) {
            if (!mask[k]) continue
            poly(sr[k].toDouble(), sg[k].toDouble(), sb[k].toDouble(), f)
            for (i in 0 until NTERMS) {
                val fi = f[i]
                var j = i
                while (j < NTERMS) { ata[i][j] += fi * f[j]; j++ }
                atb[i][0] += fi * yr[k]; atb[i][1] += fi * yg[k]; atb[i][2] += fi * yb[k]
            }
            count++
        }
        if (count < NTERMS + 5) return null
        // symmetrise + ridge
        for (i in 0 until NTERMS) {
            for (j in i + 1 until NTERMS) ata[j][i] = ata[i][j]
            ata[i][i] += RIDGE * (ata[i][i] + 1.0)
        }
        return gaussSolve(ata, atb)
    }

    /** Solve ata (n×n) · X = atb (n×3) via Gauss-Jordan with partial pivoting. */
    private fun gaussSolve(ata: Array<DoubleArray>, atb: Array<DoubleArray>): Array<DoubleArray>? {
        val n = NTERMS
        val a = Array(n) { ata[it].copyOf() }
        val b = Array(n) { atb[it].copyOf() }
        for (col in 0 until n) {
            var piv = col; var best = kotlin.math.abs(a[col][col])
            for (r in col + 1 until n) { val v = kotlin.math.abs(a[r][col]); if (v > best) { best = v; piv = r } }
            if (best < 1e-12) return null
            if (piv != col) { val ta = a[piv]; a[piv] = a[col]; a[col] = ta; val tb = b[piv]; b[piv] = b[col]; b[col] = tb }
            val d = a[col][col]
            for (j in 0 until n) a[col][j] /= d
            for (c in 0 until 3) b[col][c] /= d
            for (r in 0 until n) {
                if (r == col) continue
                val fac = a[r][col]
                if (fac == 0.0) continue
                for (j in 0 until n) a[r][j] -= fac * a[col][j]
                for (c in 0 until 3) b[r][c] -= fac * b[col][c]
            }
        }
        // b now holds the solution per RHS: coef[channel][term]
        return Array(3) { c -> DoubleArray(n) { i -> b[i][c] } }
    }

    // ── grid / support smoothing ────────────────────────────────────────────────

    private fun blurScalar(v: DoubleArray, N: Int) {
        val tmp = DoubleArray(v.size)
        // R axis
        for (b in 0 until N) for (g in 0 until N) for (r in 0 until N) {
            val i = (b * N + g) * N + r
            val lo = if (r > 0) v[i - 1] else v[i]; val hi = if (r < N - 1) v[i + 1] else v[i]
            tmp[i] = (lo + v[i] + hi) / 3.0
        }
        System.arraycopy(tmp, 0, v, 0, v.size)
        // G axis
        for (b in 0 until N) for (g in 0 until N) for (r in 0 until N) {
            val i = (b * N + g) * N + r
            val lo = if (g > 0) v[i - N] else v[i]; val hi = if (g < N - 1) v[i + N] else v[i]
            tmp[i] = (lo + v[i] + hi) / 3.0
        }
        System.arraycopy(tmp, 0, v, 0, v.size)
        // B axis
        val nn = N * N
        for (b in 0 until N) for (g in 0 until N) for (r in 0 until N) {
            val i = (b * N + g) * N + r
            val lo = if (b > 0) v[i - nn] else v[i]; val hi = if (b < N - 1) v[i + nn] else v[i]
            tmp[i] = (lo + v[i] + hi) / 3.0
        }
        System.arraycopy(tmp, 0, v, 0, v.size)
    }

    private fun blurGrid(grid: FloatArray, N: Int) {
        val tmp = FloatArray(grid.size)
        val nn = N * N
        fun pass(stepNodes: Int, hasLo: (Int) -> Boolean, hasHi: (Int) -> Boolean) {
            for (b in 0 until N) for (g in 0 until N) for (r in 0 until N) {
                val node = (b * N + g) * N + r
                val i = node * 3
                val loNode = node - stepNodes; val hiNode = node + stepNodes
                for (c in 0 until 3) {
                    val cur = grid[i + c]
                    val lo = if (hasLo(node)) grid[loNode * 3 + c] else cur
                    val hi = if (hasHi(node)) grid[hiNode * 3 + c] else cur
                    tmp[i + c] = (lo + cur + hi) / 3f
                }
            }
            System.arraycopy(tmp, 0, grid, 0, grid.size)
        }
        pass(1, { (it % N) > 0 }, { (it % N) < N - 1 })
        pass(N, { ((it / N) % N) > 0 }, { ((it / N) % N) < N - 1 })
        pass(nn, { (it / nn) > 0 }, { (it / nn) < N - 1 })
    }

    // ── small helpers ───────────────────────────────────────────────────────────

    private fun scaleDown(src: Bitmap, maxLongSide: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= maxLongSide) return if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
        val scale = maxLongSide.toFloat() / long
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun clamp01(v: Double): Float = if (v < 0.0) 0f else if (v > 1.0) 1f else v.toFloat()

    private fun fmt(v: Float): String = "%.6f".format(v.coerceIn(0f, 1f))

    private fun median(list: ArrayList<Float>): Float {
        if (list.isEmpty()) return 0f
        val a = list.toFloatArray(); a.sort(); return a[a.size / 2]
    }

    private fun percentile(list: ArrayList<Float>, p: Float): Float {
        if (list.isEmpty()) return 0f
        val a = list.toFloatArray(); a.sort()
        return a[((a.size - 1) * p).toInt().coerceIn(0, a.size - 1)]
    }
}
