/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.core.resources.icons.Rotate90Cw
import com.RAZStudio.StudioRoom.core.resources.icons.Flip
import com.RAZStudio.StudioRoom.core.resources.icons.FlipVertical
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedChip
import com.RAZStudio.StudioRoom.feature.crop.presentation.components.CropType
import com.RAZStudio.StudioRoom.feature.crop.presentation.components.Cropper
import com.RAZStudio.cropper.model.AspectRatio
import com.RAZStudio.cropper.model.OutlineType
import com.RAZStudio.cropper.model.RectCropShape
import com.RAZStudio.cropper.settings.CropDefaults
import com.RAZStudio.cropper.settings.CropOutlineProperty

private data class AspectChip(val label: String, val ratio: Float?)

private data class CropSource(
    val bitmap: Bitmap,
    val rotatedW: Int,
    val rotatedH: Int,
    val autoCrop: android.graphics.Rect,
)

private val SNAPSEED_RATIOS = listOf(
    AspectChip("Free",     null),
    AspectChip("Original", -1f),
    AspectChip("1:1",      1f),
    AspectChip("5:4",      5f / 4f),
    AspectChip("4:3",      4f / 3f),
    AspectChip("3:2",      3f / 2f),
    AspectChip("7:5",      7f / 5f),
    AspectChip("16:9",     16f / 9f),
    AspectChip("4:5",      4f / 5f),
    AspectChip("3:4",      3f / 4f),
    AspectChip("2:3",      2f / 3f),
    AspectChip("5:7",      5f / 7f),
    AspectChip("9:16",     9f / 16f),
    AspectChip("DIN",      1.4142f),
)

/**
 * Chip the sheet opens on — "Original" (the photo's own frame) rather than
 * "Free", per owner request 2026-09-07. Resolved by LABEL so reordering
 * [SNAPSEED_RATIOS] cannot silently point the default at another ratio.
 *
 * Note this is a selection change only: "Original" and "Free" both map to
 * `AspectRatio.Original` with `fixedAspectRatio = false` (see cropProps
 * below), so the crop box still drags freely — the chip row just now opens
 * reading "Original", which is what the untouched full-frame crop actually is.
 */
private val DEFAULT_CHIP_IDX =
    SNAPSEED_RATIOS.indexOfFirst { it.label == "Original" }.coerceAtLeast(0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RawCropSheet(
    source: Bitmap,
    onDismiss: () -> Unit,
    onCropConfirmed: (
        cropped: Bitmap,
        left: Float, top: Float, right: Float, bottom: Float,
        rotationDeg: Float,
        rotate90: Int, flipH: Boolean, flipV: Boolean,
    ) -> Unit,
) {
    BackHandler(onBack = onDismiss)

    var selectedChipIdx by remember { mutableStateOf(DEFAULT_CHIP_IDX) }
    var cropTrigger by remember { mutableStateOf(false) }
    val rotationState = remember { mutableFloatStateOf(0f) }
    var angleDeg by remember { mutableStateOf(0f) }
    var committedAngle by remember { mutableStateOf(0f) }
    // Discrete orientation (img.ly TRANSFORM parity): 90° rotate + flip H/V.
    // Applied to the source BEFORE straighten + crop; baked into cropSource so
    // the Cropper (and the exported rect) operate on the oriented image.
    var rotate90 by remember { mutableStateOf(0) }
    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }
    // Bake the rotated + auto-inscribed-cropped bitmap ONLY when the angle is
    // COMMITTED (slider released) — NOT on every live degree. Regenerating a
    // full-res rotated bitmap per tick on the main thread (and swapping it into
    // the Cropper) was the flicker/ghosting the user saw. During the drag the
    // rotation is shown smoothly via a GPU graphicsLayer on the Cropper box
    // (see below); this bake runs once on release.
    val cropSource = remember(source, committedAngle, rotate90, flipH, flipV) {
        // Step 1 — discrete orientation (flip then 90° rotate), lossless.
        val oriented = if (!flipH && !flipV && rotate90 == 0) source
        else runCatching {
            val m = android.graphics.Matrix()
            if (flipH || flipV) m.postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
            if (rotate90 != 0) m.postRotate(90f * (rotate90 % 4))
            android.graphics.Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
        }.getOrDefault(source)
        // Step 2 — continuous straighten on the oriented image.
        val a = committedAngle
        val rotated = if (kotlin.math.abs(a) < 0.01f) oriented
        else runCatching {
            val m = android.graphics.Matrix().apply { postRotate(a) }
            android.graphics.Bitmap.createBitmap(oriented, 0, 0, oriented.width, oriented.height, m, true)
        }.getOrDefault(oriented)
        val autoCrop = largestInscribedRect(
            srcW = oriented.width,
            srcH = oriented.height,
            rotatedW = rotated.width,
            rotatedH = rotated.height,
            angleDeg = a,
        )
        val display = if (kotlin.math.abs(a) < 0.01f) rotated
        else runCatching {
            android.graphics.Bitmap.createBitmap(rotated, autoCrop.left, autoCrop.top, autoCrop.width(), autoCrop.height())
        }.getOrDefault(rotated)
        CropSource(display, rotated.width, rotated.height, autoCrop)
    }
    val displaySource = cropSource.bitmap
    val autoCropNorm = remember(cropSource) {
        val rw = cropSource.rotatedW.toFloat()
        val rh = cropSource.rotatedH.toFloat()
        val r = cropSource.autoCrop
        floatArrayOf(
            r.left / rw,
            r.top / rh,
            r.right / rw,
            r.bottom / rh,
        )
    }
    var rectL by remember { mutableStateOf(0f) }
    var rectT by remember { mutableStateOf(0f) }
    var rectR by remember { mutableStateOf(1f) }
    var rectB by remember { mutableStateOf(1f) }
    LaunchedEffect(autoCropNorm) {
        rectL = autoCropNorm[0]
        rectT = autoCropNorm[1]
        rectR = autoCropNorm[2]
        rectB = autoCropNorm[3]
    }

    val defaultOutline = remember {
        CropOutlineProperty(outlineType = OutlineType.Rect, cropOutline = RectCropShape(id = 0, title = OutlineType.Rect.name))
    }
    val baseProps = remember(defaultOutline) { CropDefaults.properties(cropOutlineProperty = defaultOutline, fling = true) }
    val currentRatio = SNAPSEED_RATIOS[selectedChipIdx].ratio
    val cropProps = remember(baseProps, currentRatio) {
        baseProps.copy(
            aspectRatio = if (currentRatio == null || currentRatio == -1f) AspectRatio.Original else AspectRatio(currentRatio),
            fixedAspectRatio = currentRatio != null && currentRatio != -1f,
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Crop") },
                actions = {
                    TextButton(onClick = { cropTrigger = true }) { Text("Apply") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            // Aspect chip row
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(SNAPSEED_RATIOS.size) { idx ->
                    EnhancedChip(
                        selected = idx == selectedChipIdx,
                        onClick = { selectedChipIdx = idx },
                        label = { Text(SNAPSEED_RATIOS[idx].label, fontSize = 12.sp) },
                        selectedColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // Cropper canvas — takes remaining height
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .graphicsLayer {
                        // Smooth LIVE straighten: rotate the committed view by the drag
                        // DELTA on the GPU each frame (state read here happens in the draw
                        // phase → no recomposition/bitmap rebuild per tick → no flicker).
                        // On release, committedAngle catches up and cropSource rebakes the
                        // precise inscribed crop, so delta returns to 0 seamlessly.
                        val delta = angleDeg - committedAngle
                        rotationZ = delta
                        // Cover-scale so the rotation never exposes transparent corners.
                        val rad = Math.toRadians(delta.toDouble())
                        val c = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
                        val s = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
                        val ar = if (displaySource.height != 0)
                            displaySource.width.toFloat() / displaySource.height else 1f
                        val cover = c + s * maxOf(ar, 1f / ar)
                        scaleX = cover
                        scaleY = cover
                        clip = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.runtime.key(committedAngle) {
                    Cropper(
                        bitmap = displaySource,
                        crop = cropTrigger,
                        onImageCropStarted = {},
                        onImageCropFinished = { cropped ->
                            cropTrigger = false
                            if (cropped != null) onCropConfirmed(cropped, rectL, rectT, rectR, rectB, angleDeg, rotate90, flipH, flipV)
                        },
                        cropType = CropType.NoRotation,
                        coercePointsToImageArea = true,
                        rotationState = rotationState,
                        cropProperties = cropProps,
                        addVerticalInsets = false,
                        onCropRectChange = { l, t, r, b ->
                            // The Cropper works on the already auto-cropped display bitmap.
                            // Map its rect back onto the full rotated bitmap so the saved
                            // image also strips the transparent corners.
                            val ac = cropSource.autoCrop
                            val sx = ac.width().toFloat() / cropSource.rotatedW
                            val sy = ac.height().toFloat() / cropSource.rotatedH
                            rectL = autoCropNorm[0] + l * sx
                            rectT = autoCropNorm[1] + t * sy
                            rectR = autoCropNorm[0] + r * sx
                            rectB = autoCropNorm[1] + b * sy
                        },
                    )
                }
            }
            // Orientation row — 90° rotate + flip H/V (img.ly TRANSFORM parity).
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { rotate90 = (rotate90 + 1) % 4 }) {
                    Icon(Icons.Outlined.Rotate90Cw, contentDescription = "Rotate 90°")
                }
                IconButton(onClick = { flipH = !flipH }) {
                    Icon(
                        Icons.Outlined.Flip,
                        contentDescription = "Flip horizontal",
                        tint = if (flipH) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { flipV = !flipV }) {
                    Icon(
                        Icons.Outlined.FlipVertical,
                        contentDescription = "Flip vertical",
                        tint = if (flipV) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                if (rotate90 != 0 || flipH || flipV) {
                    TextButton(onClick = { rotate90 = 0; flipH = false; flipV = false }) {
                        Text("Reset orient.", fontSize = 12.sp)
                    }
                }
            }
            // Straighten row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Straighten", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                androidx.compose.material3.Slider(
                    value = angleDeg,
                    onValueChange = { angleDeg = it },
                    onValueChangeFinished = { committedAngle = angleDeg },
                    valueRange = -45f..45f,
                    modifier = Modifier.weight(1f),
                )
                Text("${angleDeg.toInt()}°", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                TextButton(onClick = { angleDeg = 0f; committedAngle = 0f }) { Text("Reset", fontSize = 12.sp) }
            }
        }
    }
}

/**
 * Largest centered axis-aligned rectangle inside a rectangle of size
 * [srcW] x [srcH] rotated by [angleDeg]. The result is in pixel coordinates
 * of the rotated bitmap and is snapped to integer pixels (ceil left/top,
 * floor right/bottom) so it can be used directly with Bitmap.createBitmap.
 */
private fun largestInscribedRect(
    srcW: Int,
    srcH: Int,
    rotatedW: Int,
    rotatedH: Int,
    angleDeg: Float,
): android.graphics.Rect {
    if (kotlin.math.abs(angleDeg) < 0.01f) {
        return android.graphics.Rect(0, 0, rotatedW, rotatedH)
    }
    val theta = Math.toRadians(kotlin.math.abs(angleDeg).toDouble())
    val w0 = srcW.toDouble()
    val h0 = srcH.toDouble()
    val sinA = kotlin.math.abs(kotlin.math.sin(theta))
    val cosA = kotlin.math.abs(kotlin.math.cos(theta))

    // Canonical largest-area axis-aligned rectangle inscribed in a w0×h0
    // rectangle rotated by theta (the standard "crop away the rotation's black
    // wedges" solution). Produces half-extents a (width) and b (height) of the
    // centered inscribed rect. The previous formula used the full rotated
    // bounding box for the height, so the rect spanned the black corners.
    val widthIsLonger = w0 >= h0
    val sideLong = if (widthIsLonger) w0 else h0
    val sideShort = if (widthIsLonger) h0 else w0
    val a: Double
    val b: Double
    if (sideShort <= 2.0 * sinA * cosA * sideLong || kotlin.math.abs(sinA - cosA) < 1e-10) {
        // The short side is the limiting constraint (large angle / near 45°).
        val x = 0.5 * sideShort
        val wr = if (widthIsLonger) x / sinA else x / cosA
        val hr = if (widthIsLonger) x / cosA else x / sinA
        a = wr / 2.0
        b = hr / 2.0
    } else {
        val cos2a = cosA * cosA - sinA * sinA
        a = (w0 * cosA - h0 * sinA) / cos2a / 2.0
        b = (h0 * cosA - w0 * sinA) / cos2a / 2.0
    }

    val cx = rotatedW / 2.0
    val cy = rotatedH / 2.0
    val left   = kotlin.math.ceil(cx - a).toInt().coerceIn(0, rotatedW - 1)
    val top    = kotlin.math.ceil(cy - b).toInt().coerceIn(0, rotatedH - 1)
    val right  = kotlin.math.floor(cx + a).toInt().coerceAtLeast(left + 1).coerceAtMost(rotatedW)
    val bottom = kotlin.math.floor(cy + b).toInt().coerceAtLeast(top + 1).coerceAtMost(rotatedH)
    return android.graphics.Rect(left, top, right, bottom)
}

private fun androidx.compose.foundation.lazy.LazyListScope.items(
    count: Int,
    itemContent: @Composable (Int) -> Unit,
) {
    items(count = count) { i -> itemContent(i) }
}
