/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Color-grading scopes for the Color tab (OpenShot 4.0 Color View parity):
 * a **vectorscope** (chroma scatter, Rec.601 U/V) and a **luma waveform**.
 *
 * Both are computed from the already-downscaled [previewBitmap] the editor
 * hands the adjustment panel, so there's no extra decode. The heavy pixel
 * scan is memoised on the bitmap instance (and a generation counter the caller
 * can bump), so scopes recompute only when the preview actually changes.
 *
 * Collapsed by default — scopes are a pro aid, not something to spend layout
 * height on for every edit.
 */
@Composable
internal fun ColorScopesSection(
    previewBitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    var show by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Scopes",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(checked = show, onCheckedChange = { show = it })
        }

        if (show) {
            if (previewBitmap == null || previewBitmap.isRecycled) {
                Text(
                    text = "Scopes appear once the preview is ready.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                return@Column
            }
            // One pixel scan → both scope textures. Keyed on the bitmap instance
            // (the editor swaps in a fresh preview Bitmap on each graded update).
            val scopes = remember(previewBitmap) { computeScopes(previewBitmap) }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ScopePanel("Vectorscope", scopes.vectorscope, Modifier.weight(1f))
                ScopePanel("Waveform (luma)", scopes.waveform, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ScopePanel(title: String, bmp: Bitmap, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = title,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentScale = ContentScale.Fit,
        )
    }
}

private class Scopes(val vectorscope: Bitmap, val waveform: Bitmap)

private const val SCOPE = 256          // output texture side
private const val MAX_SAMPLES = 30_000 // cap the pixel scan for responsiveness

/**
 * Single scan of [src] producing a colored vectorscope and a luma waveform,
 * both [SCOPE]×[SCOPE] ARGB_8888. Runs off the UI thread implicitly via the
 * `remember { }` calculation on first composition after a preview swap.
 */
private fun computeScopes(src: Bitmap): Scopes {
    val w = src.width
    val h = src.height
    val total = w.toLong() * h.toLong()
    // Uniform stride so we sample ~MAX_SAMPLES pixels regardless of preview size.
    val stride = maxOf(1, sqrt(total.toDouble() / MAX_SAMPLES).roundToInt())

    // Accumulators.
    val vCount = IntArray(SCOPE * SCOPE)
    val vR = LongArray(SCOPE * SCOPE)
    val vG = LongArray(SCOPE * SCOPE)
    val vB = LongArray(SCOPE * SCOPE)
    val wf = IntArray(SCOPE * SCOPE)   // waveform density [x, luma]

    // Vectorscope scale: U∈[-0.436,0.436], V∈[-0.615,0.615]. Normalise the
    // larger (V) to the radius so the graticule fits.
    val vScale = (SCOPE / 2f) / 0.63f

    val rowPixels = IntArray(w)
    var y = 0
    while (y < h) {
        src.getPixels(rowPixels, 0, w, 0, y, w, 1)
        var x = 0
        while (x < w) {
            val c = rowPixels[x]
            val r = ((c shr 16) and 0xFF)
            val g = ((c shr 8) and 0xFF)
            val b = (c and 0xFF)
            val rf = r / 255f; val gf = g / 255f; val bf = b / 255f

            // Rec.601 luma + chroma.
            val luma = 0.299f * rf + 0.587f * gf + 0.114f * bf
            val u = -0.14713f * rf - 0.28886f * gf + 0.436f * bf
            val v = 0.615f * rf - 0.51499f * gf - 0.10001f * bf

            // Vectorscope point (V up).
            val vx = (SCOPE / 2f + u * vScale).toInt()
            val vy = (SCOPE / 2f - v * vScale).toInt()
            if (vx in 0 until SCOPE && vy in 0 until SCOPE) {
                val idx = vy * SCOPE + vx
                vCount[idx]++
                vR[idx] += r; vG[idx] += g; vB[idx] += b
            }

            // Waveform: column = image x mapped to SCOPE, row = luma (bottom=0).
            val wx = (x.toLong() * (SCOPE - 1) / (w - 1).coerceAtLeast(1)).toInt()
            val wy = (SCOPE - 1) - (luma * (SCOPE - 1)).toInt().coerceIn(0, SCOPE - 1)
            wf[wy * SCOPE + wx]++

            x += stride
        }
        y += stride
    }

    return Scopes(
        vectorscope = renderVectorscope(vCount, vR, vG, vB),
        waveform = renderWaveform(wf),
    )
}

private fun renderVectorscope(
    count: IntArray, sumR: LongArray, sumG: LongArray, sumB: LongArray,
): Bitmap {
    var peak = 1
    for (n in count) if (n > peak) peak = n
    val out = IntArray(SCOPE * SCOPE)
    val cx = SCOPE / 2; val cy = SCOPE / 2
    val ringR = (SCOPE / 2f) - 2f
    for (yy in 0 until SCOPE) {
        for (xx in 0 until SCOPE) {
            val i = yy * SCOPE + xx
            val dx = xx - cx; val dy = yy - cy
            val dist = sqrt((dx * dx + dy * dy).toFloat())
            var a = 0; var r = 0; var g = 0; var b = 0
            // Graticule ring + crosshair (dim).
            if (dist in (ringR - 1f)..(ringR + 1f) || (xx == cx && dy in -6..6) || (yy == cy && dx in -6..6)) {
                a = 255; r = 40; g = 46; b = 54
            }
            val n = count[i]
            if (n > 0) {
                // Log-scaled brightness so sparse chroma is still visible.
                val bright = (kotlin.math.ln(1.0 + n) / kotlin.math.ln(1.0 + peak)).toFloat()
                val ar = (sumR[i] / n).toInt()
                val ag = (sumG[i] / n).toInt()
                val ab = (sumB[i] / n).toInt()
                r = (ar * (0.5f + 0.5f * bright)).toInt().coerceIn(0, 255)
                g = (ag * (0.5f + 0.5f * bright)).toInt().coerceIn(0, 255)
                b = (ab * (0.5f + 0.5f * bright)).toInt().coerceIn(0, 255)
                a = 255
            }
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    val bmp = Bitmap.createBitmap(SCOPE, SCOPE, Bitmap.Config.ARGB_8888)
    bmp.setPixels(out, 0, SCOPE, 0, 0, SCOPE, SCOPE)
    return bmp
}

private fun renderWaveform(density: IntArray): Bitmap {
    var peak = 1
    for (n in density) if (n > peak) peak = n
    val out = IntArray(SCOPE * SCOPE)
    for (i in out.indices) {
        val n = density[i]
        if (n > 0) {
            val bright = (kotlin.math.ln(1.0 + n) / kotlin.math.ln(1.0 + peak)).toFloat()
            val g = (min(1f, 0.25f + bright) * 255f).toInt().coerceIn(0, 255)
            out[i] = (255 shl 24) or (0 shl 16) or (g shl 8) or (g / 3)
        } else {
            out[i] = (255 shl 24)   // opaque black background
        }
    }
    val bmp = Bitmap.createBitmap(SCOPE, SCOPE, Bitmap.Config.ARGB_8888)
    bmp.setPixels(out, 0, SCOPE, 0, 0, SCOPE, SCOPE)
    return bmp
}
