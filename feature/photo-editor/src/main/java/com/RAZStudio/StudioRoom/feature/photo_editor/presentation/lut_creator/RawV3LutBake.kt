/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * "Export current edit as a LUT." Renders the open photo twice through the
 * SAME Stage C kernel — once neutral (before), once with the current edit
 * (after) — then fits a .cube LUT from that single before/after pair with
 * PairLutFitter and saves it into the User's Lut library.
 *
 * A LUT can only carry a per-pixel colour transform, so this captures the
 * colour portion of the edit (tone / contrast / WB / curves / HSL / colour
 * grading / any applied LUT). Spatial/local ops (NR, clarity, sharpen,
 * bloom, vignette, gradients, masks) are inherently outside what a LUT can
 * represent and are not baked — the same limitation as any LUT export.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.lut_creator

import android.content.Context
import android.graphics.Bitmap
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.LocalLutRepository
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3LutStore
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ShaderParams
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object RawV3LutBake {

    /** Work resolution for the fit — full-res is unnecessary (PairLutFitter
     *  downsamples anyway) and 512 keeps memory + time modest. */
    private const val FIT_LONG_SIDE = 512

    /**
     * Bake the current edit into a LUT and save it to the User's Lut library.
     *
     * @param stageATifPath Stage A intermediate for the open photo.
     * @param fullW/fullH   Full-res dims stageCToBitmap requires for its target.
     * @param currentParams The composed [ShaderParams] blob currently rendering.
     * @param lutCubeFile   Active LUT .cube (or null) so the baked look includes it.
     * @param name          Display name for the new LUT.
     * @return the saved LUT's display name, or null on failure.
     */
    suspend fun bakeEditToLut(
        context: Context,
        stageATifPath: String,
        fullW: Int,
        fullH: Int,
        currentParams: ShaderParams,
        lutCubeFile: File?,
        name: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): String? = withContext(dispatcher) {
        if (fullW < 8 || fullH < 8 || !File(stageATifPath).exists()) return@withContext null

        val parsedLut = lutCubeFile?.takeIf { it.exists() }?.let { RawV3LutStore.parseCubeFile(it) }

        // Neutral params: identity edit, but keep the colour-space fields so the
        // before/after pair is encoded in the SAME space (else the fit would
        // bake a spurious colour-space conversion).
        val neutral = ShaderParams(
            gamutOut = currentParams.gamutOut,
            workspaceSpace = currentParams.workspaceSpace,
            lutAuthoredSpace = currentParams.lutAuthoredSpace,
        ).toFloatArray()

        // ── render "after" (current edit + active LUT), downscale, free full ──
        val after = renderScaled(stageATifPath, fullW, fullH, currentParams.toFloatArray(), parsedLut)
            ?: return@withContext null
        // ── render "before" (neutral, no LUT) at the SAME scaled dims ──
        val before = renderScaled(stageATifPath, fullW, fullH, neutral, null, matchTo = after)
            ?: run { after.recycle(); return@withContext null }

        val fitted = try {
            PairLutFitter.fit(listOf(before to after))
        } finally {
            before.recycle(); after.recycle()
        } ?: return@withContext null

        val safe = name.trim().ifBlank { "My Edit LUT" }
        val cube = PairLutFitter.serialize(fitted, safe)
        val pendingDir = File(context.cacheDir, "lut_pending").apply { mkdirs() }
        val temp = File(pendingDir, safe.replace(Regex("[^A-Za-z0-9._ -]"), "_") + "_" + hashCode().toString() + ".cube")
        runCatching { temp.writeText(cube) }.getOrElse { return@withContext null }

        val entry = LocalLutRepository(context).saveAsUserLut(temp.absolutePath, safe)
        runCatching { temp.delete() }
        entry?.name
    }

    /** Render Stage C into a full-res bitmap then downscale to the fit size.
     *  If [matchTo] is given, scales to exactly its dims (pixel correspondence). */
    private fun renderScaled(
        tif: String, fullW: Int, fullH: Int,
        params: FloatArray,
        lut: RawV3LutStore.ParsedCube?,
        matchTo: Bitmap? = null,
    ): Bitmap? {
        val full = runCatching { Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val res = RawV3Engine.stageCToBitmap(
            stageATifPath = tif,
            bitmap = full,
            actionParams = params,
            lutData = lut?.data,
            lutSize = lut?.size ?: 0,
            lutDomainMin = lut?.domainMin ?: floatArrayOf(0f, 0f, 0f),
            lutDomainMax = lut?.domainMax ?: floatArrayOf(1f, 1f, 1f),
        )
        if (!res.success) { full.recycle(); return null }
        val (tw, th) = if (matchTo != null) matchTo.width to matchTo.height else {
            val long = maxOf(fullW, fullH)
            val s = if (long <= FIT_LONG_SIDE) 1f else FIT_LONG_SIDE.toFloat() / long
            (fullW * s).toInt().coerceAtLeast(1) to (fullH * s).toInt().coerceAtLeast(1)
        }
        val small = runCatching { Bitmap.createScaledBitmap(full, tw, th, true) }.getOrNull()
        full.recycle()
        return small
    }
}
