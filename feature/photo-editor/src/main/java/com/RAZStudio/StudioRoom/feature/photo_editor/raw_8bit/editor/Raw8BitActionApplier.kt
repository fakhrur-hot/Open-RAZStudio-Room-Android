/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.editor

import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import org.opencv.core.Mat

/**
 * Replays a [RawAction] stack onto an 8-bit BGR Mat (CV_8UC3). The
 * apply semantics mirror — at field level — the 16-bit shader-based
 * replay in `RawV3ActionReplay`, but the math runs entirely on CPU
 * via OpenCV so the same UserMacro fields can produce an editable
 * 8-bit working buffer without an OpenGL surface.
 *
 * The 16-bit editor flattens the action stack into shader uniforms
 * and lets the GPU composite. We can't do that for the 8-bit buffer
 * — every adjustment touches pixels directly. To stay coherent we
 * walk the stack newest-first and apply each action's macro in turn.
 * Empty fields are no-ops; only fields the user actually changed
 * incur cost.
 *
 * Phase 4.A status
 * ----------------
 * This is the foundation. Each `apply<Field>` method is currently a
 * pass-through (or TODO marker). Subsequent Phase 4.A sub-steps
 * implement them tab-by-tab:
 *   - Light:   exposure / highlights / shadows / whites / blacks
 *   - Color:   WB / tint / saturation / vibrance / clarity / sharpness / NR
 *   - Tonemap: tonemapExposure / tonemapHighlights / tonemapShadows
 *   - Curves:  toneCurvePoints
 *   - Details: smartSharpness / luminanceNR / colorNR / filmGrain*
 *   - LUT:     lutCubeUri / lutIntensity / lutStack
 *
 * Local-mask + gradient + vignette tabs land in Phase 4.B.
 *
 * Threading: not thread-safe — callers must serialise calls on one Mat.
 */
internal class Raw8BitActionApplier {

    /**
     * Apply [actions] in reverse order (oldest → newest, matching v3
     * replay semantics) onto a clone of [src]. Returns a NEW Mat;
     * caller releases.
     */
    fun apply(src: Mat, actions: List<RawAction>): Mat {
        val out = src.clone()
        // The stack stores newest-first (index 0 = most recent). Replay
        // oldest-to-newest so each action sees the prior actions'
        // results — same order the 16-bit editor flattens to shader
        // uniforms (where later layers stack on earlier).
        val ordered = actions
            .asReversed()
            .filter { it.isVisible && it.id != RawAction.ORIGINAL_ID }
        for (a in ordered) {
            applyMacro(out, a.macro)
        }
        return out
    }

    /**
     * Apply a single [UserMacro] onto [mat] in place. Used by both
     * [apply] and by the live-preview slider-drag path, which passes
     * the user's *current* macro (not yet committed to an action) so
     * the preview reflects the in-flight slider position.
     */
    fun applyMacro(mat: Mat, macro: UserMacro) {
        // Light tab
        applyExposure(mat, macro.exposure)
        applyHighlights(mat, macro.highlights)
        applyShadows(mat, macro.shadows)
        applyWhites(mat, macro.whites)
        applyBlacks(mat, macro.blacks)

        // Color tab
        applyWhiteBalance(mat, macro.whiteBalance, macro.tint)
        applySaturation(mat, macro.saturation)
        applyVibrance(mat, macro.vibrance)
        applyClarity(mat, macro.clarity)
        applySharpness(mat, macro.sharpness)
        applyNoiseReduction(mat, macro.noiseReduction)

        // Tonemap tab (additive on top of Light tab)
        applyExposure(mat, macro.tonemapExposure)
        applyHighlights(mat, macro.tonemapHighlights)
        applyShadows(mat, macro.tonemapShadows)

        // Curves tab
        applyToneCurves(mat, macro.toneCurvePoints)

        // Details tab
        applySmartSharpness(mat, macro.smartSharpness)
        applyLuminanceNR(mat, macro.luminanceNR)
        applyColorNR(mat, macro.colorNR)
        applyFilmGrain(
            mat,
            amount = macro.filmGrain,
            size = macro.filmGrainSize,
            uniformity = 0f,
            washOut = macro.filmGrainWashOut,
        )

        // LUT tab — currently active edit
        if (macro.lutCubeUri.isNotEmpty()) {
            applyLut(mat, macro.lutCubeUri, macro.lutIntensity)
        }
        // LUT tab — committed stack from earlier actions
        for (layer in macro.lutStack) {
            applyLut(mat, layer.cubeUri, layer.intensity)
        }
    }

    // ── Light tab ──────────────────────────────────────────────────
    private fun applyExposure(mat: Mat, ev: Float) {
        if (ev == 0f) return
        // TODO Phase 4.A.1 — Light tab: out = in · 2^ev, clamped to [0,255].
        Log.v(TAG, "applyExposure: ev=$ev (stub)")
    }

    private fun applyHighlights(mat: Mat, v: Float) {
        if (v == 0f) return
        // TODO Phase 4.A.1 — masked toward bright values; positive = recover.
        Log.v(TAG, "applyHighlights: v=$v (stub)")
    }

    private fun applyShadows(mat: Mat, v: Float) {
        if (v == 0f) return
        // TODO Phase 4.A.1 — masked toward dark values; positive = lift.
        Log.v(TAG, "applyShadows: v=$v (stub)")
    }

    private fun applyWhites(mat: Mat, v: Float) {
        if (v == 0f) return
        // TODO Phase 4.A.1 — re-anchors the white point.
        Log.v(TAG, "applyWhites: v=$v (stub)")
    }

    private fun applyBlacks(mat: Mat, v: Float) {
        if (v == 0f) return
        // TODO Phase 4.A.1 — re-anchors the black point.
        Log.v(TAG, "applyBlacks: v=$v (stub)")
    }

    // ── Color tab ──────────────────────────────────────────────────
    private fun applyWhiteBalance(mat: Mat, kelvin: Int, tint: Float) {
        if (kelvin == 0 && tint == 0f) return
        // TODO Phase 4.A.2 — Kelvin delta → R/B channel multiplier;
        // tint → G channel multiplier.
        Log.v(TAG, "applyWhiteBalance: kelvin=$kelvin tint=$tint (stub)")
    }

    private fun applySaturation(mat: Mat, v: Float) {
        if (v == 0f) return
        // TODO Phase 4.A.2 — HSV S channel multiplier centred at 1.0.
        Log.v(TAG, "applySaturation: v=$v (stub)")
    }

    private fun applyVibrance(mat: Mat, v: Float) {
        if (v == 0f) return
        // TODO Phase 4.A.2 — saturation boost weighted by 1 − S
        // (already-saturated pixels boosted less).
        Log.v(TAG, "applyVibrance: v=$v (stub)")
    }

    private fun applyClarity(mat: Mat, v: Float) {
        if (v == 0f) return
        // TODO Phase 4.A.2 — local-contrast unsharp mask on luma only.
        Log.v(TAG, "applyClarity: v=$v (stub)")
    }

    private fun applySharpness(mat: Mat, v: Float) {
        if (v == 0f) return
        // TODO Phase 4.A.2 — small-radius unsharp mask.
        Log.v(TAG, "applySharpness: v=$v (stub)")
    }

    private fun applyNoiseReduction(mat: Mat, v: Float) {
        if (v == 0f) return
        // TODO Phase 4.A.2 — bilateral filter on luma, gentler on chroma.
        Log.v(TAG, "applyNoiseReduction: v=$v (stub)")
    }

    // ── Curves tab ─────────────────────────────────────────────────
    private fun applyToneCurves(mat: Mat, curvePoints: List<List<Float>>) {
        if (curvePoints.isEmpty() || isIdentityCurve(curvePoints)) return
        // TODO Phase 4.A.4 — 4-channel LUT (L,R,G,B) built from spline
        // fitted to 5 control points each, then applied via Core.LUT.
        Log.v(TAG, "applyToneCurves: ${curvePoints.size} channels (stub)")
    }

    private fun isIdentityCurve(curvePoints: List<List<Float>>): Boolean {
        val identity = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        return curvePoints.all { it == identity }
    }

    // ── Details tab ────────────────────────────────────────────────
    private fun applySmartSharpness(mat: Mat, v: Float) {
        if (v == 0f) return
        // TODO Phase 4.A.5 — edge-aware sharpening, foreground-mask gated.
        Log.v(TAG, "applySmartSharpness: v=$v (stub)")
    }

    private fun applyLuminanceNR(mat: Mat, v: Float) {
        if (v == 0f) return
        // TODO Phase 4.A.5 — non-local-means on luma.
        Log.v(TAG, "applyLuminanceNR: v=$v (stub)")
    }

    private fun applyColorNR(mat: Mat, v: Float) {
        if (v == 0f) return
        // TODO Phase 4.A.5 — non-local-means on chroma only.
        Log.v(TAG, "applyColorNR: v=$v (stub)")
    }

    private fun applyFilmGrain(
        mat: Mat,
        amount: Float,
        size: Float,
        uniformity: Float,
        washOut: Float,
    ) {
        if (amount == 0f) return
        // TODO Phase 4.A.5 — additive monochrome noise, params tune the
        // grain frequency + intensity falloff.
        Log.v(TAG, "applyFilmGrain: a=$amount s=$size u=$uniformity w=$washOut (stub)")
    }

    // ── LUT tab ────────────────────────────────────────────────────
    private fun applyLut(mat: Mat, cubeUri: String, intensity: Float) {
        if (intensity == 0f || cubeUri.isEmpty()) return
        // TODO Phase 4.A.6 — load 3D cube LUT from URI, sample per
        // pixel, blend with original by intensity.
        Log.v(TAG, "applyLut: uri=$cubeUri intensity=$intensity (stub)")
    }

    private companion object {
        private const val TAG = "Raw8Bit.Applier"
    }
}
