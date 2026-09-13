/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import kotlin.math.roundToInt

/**
 * Expand [src]'s canvas by a solid border and return the LARGER bitmap. The
 * original photo pixels are copied in verbatim, insetted by the border width —
 * the border always lives OUTSIDE the photo frame, so nothing of the original
 * image is ever cropped or covered (product requirement, 2026-08-29).
 *
 * [thicknessFraction] is the border width as a fraction of the photo's LONG
 * side (so a square and a panorama get a visually-consistent frame). 0 → the
 * source is returned unchanged. [colorArgb] is the fill colour.
 */
fun applyBorderToBitmap(src: Bitmap, thicknessFraction: Float, colorArgb: Int): Bitmap {
    val longSide = maxOf(src.width, src.height)
    val b = (longSide * thicknessFraction).roundToInt().coerceAtLeast(0)
    if (b == 0) return src
    val outW = src.width + 2 * b
    val outH = src.height + 2 * b
    val cfg = src.config ?: Bitmap.Config.ARGB_8888
    val out = Bitmap.createBitmap(outW, outH, cfg)
    val c = Canvas(out)
    c.drawColor(colorArgb)
    // Photo copied in at (b, b) — centred, full-size, never scaled or cropped.
    c.drawBitmap(src, b.toFloat(), b.toFloat(), null)
    return out
}

/** The border palette shown in the sheet. */
private val BORDER_COLORS = listOf(
    Color.White,
    Color.Black,
    Color(0xFF808080), // mid grey
    Color(0xFFF5F0E6), // warm ivory
    Color(0xFF1C1C1E), // near-black charcoal
    Color(0xFFD32F2F), // red
    Color(0xFF1565C0), // blue
    Color(0xFFF9A825), // amber
)

/** Max border width = 15% of the long side — matches typical social frames. */
private const val MAX_BORDER_FRACTION = 0.15f

/**
 * Full-screen Border/Frame sheet for the RAW export transform bar. Live preview
 * draws the frame with Compose (no per-tick bitmap allocation — the heavy bake
 * happens once, on Apply, via [applyBorderToBitmap]). The border grows the
 * canvas outward, so the photo is never cropped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RawBorderSheet(
    source: Bitmap,
    initialThickness: Float,
    initialColorArgb: Int,
    onDismiss: () -> Unit,
    onBorderConfirmed: (bordered: Bitmap, thickness: Float, colorArgb: Int) -> Unit,
) {
    var thickness by remember { mutableFloatStateOf(initialThickness.coerceIn(0f, MAX_BORDER_FRACTION)) }
    var colorArgb by remember { mutableIntStateOf(initialColorArgb) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Border") },
                actions = {
                    Button(
                        onClick = {
                            val out = applyBorderToBitmap(source, thickness, colorArgb)
                            onBorderConfirmed(out, thickness, colorArgb)
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("Apply") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            // ── Live preview ─────────────────────────────────────────────
            // The frame is drawn as a background colour + padding around the
            // photo Image, so it visually expands OUTSIDE the photo exactly as
            // the baked bitmap will. Padding is expressed as a fraction of the
            // rendered long side so it tracks the slider 1:1 with the bake.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val srcAspect = source.width.toFloat() / source.height.toFloat()
                val img = remember(source) { source.asImageBitmap() }
                // Fit the whole framed result (photo + 2×border) in the viewport.
                val cW = constraints.maxWidth.toFloat()
                val cH = constraints.maxHeight.toFloat()
                Box(contentAlignment = Alignment.Center) {
                    // Outer frame box sized to photo * (1 + 2*fraction on long side).
                    val fitByW = cW / cH > srcAspect
                    val photoW: Float
                    val photoH: Float
                    // Reserve the border margin against the container.
                    val marginScale = 1f / (1f + 2f * thickness)
                    if (fitByW) {
                        photoH = cH * marginScale
                        photoW = photoH * srcAspect
                    } else {
                        photoW = cW * marginScale
                        photoH = photoW / srcAspect
                    }
                    val longPx = maxOf(photoW, photoH)
                    val borderPx = longPx * thickness
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    Box(
                        modifier = Modifier
                            .background(Color(colorArgb))
                            .padding(with(density) { borderPx.toDp() }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = img,
                            contentDescription = null,
                            modifier = Modifier
                                .width(with(density) { photoW.toDp() })
                                .height(with(density) { photoH.toDp() }),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }

            // ── Thickness slider ─────────────────────────────────────────
            Text(
                text = "Thickness  ${(thickness / MAX_BORDER_FRACTION * 100f).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = thickness,
                onValueChange = { thickness = it },
                valueRange = 0f..MAX_BORDER_FRACTION,
            )

            // ── Colour palette ───────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            Text(text = "Colour", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BORDER_COLORS.forEach { c ->
                    val selected = c.toArgb() == colorArgb
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            )
                            .clickable { colorArgb = c.toArgb() },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
