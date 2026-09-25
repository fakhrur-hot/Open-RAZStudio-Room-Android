/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.core.resources.icons.Person
import com.RAZStudio.StudioRoom.core.resources.icons.WandStars
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.heal.HealSmartMasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Snapseed-style spot-heal sheet, upgraded to Lightroom-mobile Remove parity.
 * Lives on RawExportScreen's transform bar so it operates on the already-baked
 * export preview — the healed pixels survive into the saved file because
 * RawExportScreen threads the result into `cosmeticHealedPreview`.
 *
 * Interaction:
 *   • One-finger drag paints a stroke; the whole swept path is healed as ONE
 *     region on finger-up. A single tap heals one brush-sized disc.
 *   • Two fingers pinch-zoom / pan.
 *   • "Detect objects" — the stroke prompts MobileSAM and snaps to the object
 *     under it (stroke pixels always heal even if the model disagrees).
 *     Opt-in: the always-on object grow was removed from this tool before
 *     because it was slow and over-selected; as a labelled toggle it's escapable.
 *   • "Remove people" — one tap builds a DeepLab person mask and heals it.
 *   • "Remove subject" — one tap builds a BiRefNet/U2Net subject matte and heals it.
 *   • "Refine" — the next stroke EXTENDS the previous fill's mask and re-runs
 *     the inpaint from the pre-heal snapshot (no double-inpaint seams).
 *   • The progress row carries Cancel — the running heal is abandoned and its
 *     result discarded (the inference itself finishes in the background; only
 *     the commit is skipped).
 *   • Undo pops the last committed heal; Apply hands the bitmap to the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RawHealSheet(
    source: Bitmap,
    onDismiss: () -> Unit,
    onHealConfirmed: (Bitmap) -> Unit,
    protectBitmap: Bitmap? = null,
    segmentMasks: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3SegmentationMasks? = null,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current

    var workBitmap by remember { mutableStateOf(source) }
    val undoStack = remember { mutableListOf<Bitmap>() }
    var healCount by remember { mutableIntStateOf(0) }
    var isHealing by remember { mutableStateOf(false) }
    var radiusPx by remember { mutableFloatStateOf(60f) }
    var healStatus by remember { mutableStateOf("") }

    // Lightroom-parity tool state.
    var detectObjects by remember { mutableStateOf(false) }
    var refineMode by remember { mutableStateOf(false) }
    // The last committed heal's hole mask (source res) — Refine unions onto it.
    var lastMask by remember { mutableStateOf<Bitmap?>(null) }

    // Size-preview pointer (unchanged behaviour).
    var sizePreviewGen by remember { mutableIntStateOf(0) }
    var showSizePreview by remember { mutableStateOf(false) }
    LaunchedEffect(sizePreviewGen) {
        if (sizePreviewGen == 0) return@LaunchedEffect
        showSizePreview = true
        delay(1_500L)
        showSizePreview = false
    }

    // Brush stroke state (canvas space).
    var strokePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    // Heal requests. Bumping [healGen] restarts the LaunchedEffect — which is
    // also how Cancel works: null request + bump cancels the running coroutine.
    var healReq by remember { mutableStateOf<HealRequest?>(null) }
    var healGen by remember { mutableIntStateOf(0) }

    // Pan / pinch-zoom state (unchanged behaviour).
    var canvasScale by remember { mutableFloatStateOf(1f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }

    androidx.compose.material3.Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Heal") },
                actions = {
                    TextButton(
                        enabled = undoStack.isNotEmpty() && !isHealing,
                        onClick = {
                            val restored = undoStack.removeAt(undoStack.lastIndex)
                            val toRecycle = workBitmap
                            if (toRecycle !== source && toRecycle !== restored) runCatching { toRecycle.recycle() }
                            workBitmap = restored
                            if (healCount > 0) healCount -= 1
                            // The popped heal is what Refine was extending — its
                            // mask no longer matches any committed state.
                            lastMask?.let { runCatching { it.recycle() } }
                            lastMask = null
                            refineMode = false
                        },
                    ) { Text("Undo") }
                    Button(
                        enabled = !isHealing,
                        onClick = {
                            undoStack.forEach { b -> if (b !== source && b !== workBitmap) runCatching { b.recycle() } }
                            undoStack.clear()
                            lastMask?.let { runCatching { it.recycle() } }
                            lastMask = null
                            onHealConfirmed(workBitmap)
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("Apply") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp),
        ) {
            // ── Image canvas ──────────────────────────────────────────
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val srcW = workBitmap.width
                val srcH = workBitmap.height
                val srcAspect = srcW.toFloat() / srcH.toFloat()
                val containerW = constraints.maxWidth.toFloat()
                val containerH = constraints.maxHeight.toFloat()
                val canvasWpx: Float
                val canvasHpx: Float
                if (containerW / containerH > srcAspect) {
                    canvasHpx = containerH
                    canvasWpx = containerH * srcAspect
                } else {
                    canvasWpx = containerW
                    canvasHpx = containerW / srcAspect
                }
                Box(
                    modifier = Modifier
                        .aspectRatio(srcAspect)
                        .graphicsLayer(
                            scaleX = canvasScale,
                            scaleY = canvasScale,
                            translationX = canvasOffset.x,
                            translationY = canvasOffset.y,
                        )
                        .background(Color.Black)
                        // One finger = brush, two fingers = transform. See the
                        // pre-refactor comments for the gesture rationale.
                        .pointerInput(srcW, srcH, canvasWpx, canvasHpx, isHealing) {
                            awaitEachGesture {
                                val first = awaitFirstDown(requireUnconsumed = false)
                                if (isHealing) return@awaitEachGesture
                                val pts = mutableListOf(first.position)
                                strokePoints = listOf(first.position)
                                var transformMode = false
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val pressedCount = ev.changes.count { it.pressed }
                                    if (pressedCount >= 2) {
                                        if (!transformMode) {
                                            transformMode = true
                                            strokePoints = emptyList()
                                        }
                                        val zoom = ev.calculateZoom()
                                        val pan = ev.calculatePan()
                                        val centroid = ev.calculateCentroid(useCurrent = true)
                                        val oldScale = canvasScale
                                        val newScale = (oldScale * zoom).coerceIn(1f, 8f)
                                        canvasScale = newScale
                                        canvasOffset = if (newScale > 1f) {
                                            // Centroid-anchored zoom + clamped pan
                                            // (matches the Stage B preview feel).
                                            val cx = canvasWpx / 2f
                                            val cy = canvasHpx / 2f
                                            val dScale = newScale - oldScale
                                            val ox = canvasOffset.x - dScale * (centroid.x - cx) + pan.x * newScale
                                            val oy = canvasOffset.y - dScale * (centroid.y - cy) + pan.y * newScale
                                            val maxDx = canvasWpx * (newScale - 1f) / 2f
                                            val maxDy = canvasHpx * (newScale - 1f) / 2f
                                            Offset(ox.coerceIn(-maxDx, maxDx), oy.coerceIn(-maxDy, maxDy))
                                        } else {
                                            Offset.Zero
                                        }
                                        ev.changes.forEach { it.consume() }
                                    } else if (!transformMode) {
                                        val ch = ev.changes.firstOrNull { it.id == first.id }
                                            ?: ev.changes.firstOrNull()
                                        if (ch == null || !ch.pressed) break
                                        val p = ch.position
                                        if ((p - pts.last()).getDistance() > 1.5f) {
                                            pts.add(p)
                                            strokePoints = pts.toList()
                                        }
                                        ch.consume()
                                    } else {
                                        if (ev.changes.none { it.pressed }) break
                                    }
                                }
                                strokePoints = emptyList()
                                if (!transformMode && !isHealing) {
                                    healReq = HealRequest.Stroke(pts.toList())
                                    healGen += 1
                                }
                            }
                        },
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = workBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Refine mode: show the previous fill's mask so the user
                    // can see what the next stroke will extend. White = filled.
                    if (refineMode) {
                        lastMask?.let { lm ->
                            androidx.compose.foundation.Image(
                                bitmap = lm.asImageBitmap(),
                                contentDescription = null,
                                alpha = 0.4f,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    // Live stroke overlay (same coordinate space as the bitmap).
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val sp = strokePoints
                        if (sp.isNotEmpty()) {
                            val rCanvas = (radiusPx / canvasScale).coerceAtLeast(1f)
                            val fill = if (refineMode) Color(0x80_F48FB1) else Color(0x80_4FC3F7)
                            if (sp.size == 1) {
                                drawCircle(fill, rCanvas, sp[0])
                                drawCircle(Color.White, rCanvas, sp[0], style = Stroke(width = 2f))
                            } else {
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(sp[0].x, sp[0].y)
                                    for (i in 1 until sp.size) lineTo(sp[i].x, sp[i].y)
                                }
                                drawPath(
                                    path = path,
                                    color = fill,
                                    style = Stroke(
                                        width = 2f * rCanvas,
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                                    ),
                                )
                            }
                        }
                    }
                }

                // Screen-anchored size-preview crosshair (unchanged).
                if (showSizePreview) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width * 0.5f
                        val cy = size.height * 0.5f
                        val r = radiusPx
                        drawCircle(Color.White, r, Offset(cx, cy), style = Stroke(width = 2f))
                        drawCircle(Color.Black.copy(alpha = 0.5f), r + 1.5f, Offset(cx, cy), style = Stroke(width = 1f))
                        val tick = 8f
                        drawLine(Color.White, Offset(cx - r - tick, cy), Offset(cx - r + 2, cy), strokeWidth = 2f)
                        drawLine(Color.White, Offset(cx + r - 2, cy), Offset(cx + r + tick, cy), strokeWidth = 2f)
                        drawLine(Color.White, Offset(cx, cy - r - tick), Offset(cx, cy - r + 2), strokeWidth = 2f)
                        drawLine(Color.White, Offset(cx, cy + r - 2), Offset(cx, cy + r + tick), strokeWidth = 2f)
                    }
                }

                // ── The heal executor ─────────────────────────────────────
                // Keyed on healGen: bumping it restarts this effect, which
                // CANCELS any run in flight — that's the Cancel button.
                LaunchedEffect(healGen) {
                    val req = healReq ?: return@LaunchedEffect
                    if (isHealing) return@LaunchedEffect
                    isHealing = true
                    val refining = refineMode && lastMask != null && undoStack.isNotEmpty()
                    // Refine re-runs from the PRE-heal snapshot so overlapping
                    // regions aren't inpainted twice (double-fill seams).
                    val before = if (refining) undoStack.last() else workBitmap
                    var maskBmp: Bitmap? = null
                    try {
                        val healed = withContext(Dispatchers.Default) {
                            runCatching { org.opencv.android.OpenCVLoader.initLocal() }

                            // 1) Build the hole mask for this request.
                            val fit = if (srcW.toFloat() / srcH > canvasWpx / canvasHpx)
                                canvasWpx / srcW else canvasHpx / srcH
                            val rSrc = (radiusPx / fit).coerceAtLeast(2f)
                            maskBmp = when (req) {
                                is HealRequest.Stroke -> {
                                    val srcPts = req.points.map { p ->
                                        val nx = (p.x / canvasWpx).coerceIn(0f, 1f)
                                        val ny = (p.y / canvasHpx).coerceIn(0f, 1f)
                                        (nx * srcW).coerceIn(0f, (srcW - 1).toFloat()) to
                                            (ny * srcH).coerceIn(0f, (srcH - 1).toFloat())
                                    }
                                    var m = strokeMask(srcW, srcH, srcPts, rSrc)
                                    // Detect objects: snap the scribble to the
                                    // object under it; the literal stroke stays
                                    // unioned in (the model can only ADD).
                                    if (detectObjects) {
                                        healStatus = "Detecting object…"
                                        val snapped = HealSmartMasks.snapToObject(
                                            context, before, srcPts, rSrc,
                                        )
                                        coroutineContext.ensureActive()
                                        if (snapped != null) {
                                            m = unionMasks(m, snapped)
                                            snapped.recycle()
                                        }
                                    }
                                    m
                                }
                                HealRequest.People -> {
                                    healStatus = "Detecting people…"
                                    HealSmartMasks.peopleMask(context, before)
                                }
                                HealRequest.Subject -> {
                                    healStatus = "Detecting subject…"
                                    HealSmartMasks.subjectMask(context, before)
                                }
                            }
                            coroutineContext.ensureActive()
                            var mask = maskBmp ?: return@withContext null

                            // 2) Refine: union with the previous fill's mask.
                            if (refining) {
                                lastMask?.let { lm ->
                                    mask = unionMasks(mask, lm)
                                    maskBmp = mask
                                }
                            }

                            // Grazing a segment (≤10% of the hole) keeps that
                            // object locked. Painting onto it (>10%) heals
                            // as Snapseed / Lightroom Mobile: fill the stroke.
                            mask = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.heal
                                .HealMaskBuilder.applySegmentProtect(
                                    hole = mask,
                                    protectBitmap = protectBitmap,
                                    masks = segmentMasks,
                                )

                            // 3) Deep inpaint (RAZGAN / MI-GAN) → TELEA fallback.
                            healStatus = "Filling…"
                            val deep = com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3
                                .heal.InpaintEngine.heal(context, before, mask)
                            coroutineContext.ensureActive()
                            val res = deep ?: runCatching {
                                com.RAZStudio.opencv_tools.spot_heal.SpotHealer.heal(
                                    image = before,
                                    mask = mask,
                                    radius = 4f,
                                    type = com.RAZStudio.opencv_tools.spot_heal
                                        .model.HealType.TELEA,
                                )
                            }.getOrNull()
                            coroutineContext.ensureActive()
                            healStatus = when {
                                res == null -> ""
                                req is HealRequest.People -> "People removed"
                                req is HealRequest.Subject -> "Subject removed"
                                deep != null && refining -> "Fill refined"
                                deep != null -> "Deep inpaint"
                                else -> "Brush erase"
                            }
                            res
                        }

                        if (healed != null) {
                            if (refining) {
                                // Base snapshot stays on the undo stack; the
                                // superseded fill result is discarded.
                                val old = workBitmap
                                if (old !== source && old !== before) runCatching { old.recycle() }
                                workBitmap = healed
                            } else {
                                undoStack.add(before)
                                workBitmap = healed
                                healCount += 1
                            }
                            // Adopt this heal's mask as the Refine target.
                            lastMask?.let { if (it !== maskBmp) runCatching { it.recycle() } }
                            lastMask = maskBmp
                            maskBmp = null
                        } else if (req is HealRequest.People && maskBmp == null) {
                            healStatus = "No person detected (or model missing)"
                        } else if (req is HealRequest.Subject && maskBmp == null) {
                            healStatus = "No subject detected (or model missing)"
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        healStatus = "Cancelled"
                        throw ce
                    } catch (e: Exception) {
                        Log.e("RawHealSheet", "Healing failed: ${e.message}", e)
                        healStatus = "Healing error: ${e.message}"
                    } finally {
                        maskBmp?.let { runCatching { it.recycle() } }
                        isHealing = false
                        healReq = null
                    }
                }
            }

            // ── Progress + Cancel (Lightroom's inline non-blocking banner) ──
            if (isHealing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        // Restarting the effect with a null request cancels the
                        // in-flight coroutine; its result is discarded.
                        healReq = null
                        healGen += 1
                    }) { Text("Cancel") }
                }
            }

            // ── Smart tools ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = detectObjects,
                    onCheckedChange = { detectObjects = it },
                    enabled = !isHealing && HealSmartMasks.isObjectSnapAvailable(context),
                )
                Spacer(Modifier.width(6.dp))
                Text("Detect objects", fontSize = 12.sp, modifier = Modifier.weight(1f))
                FilterChip(
                    selected = refineMode,
                    enabled = healCount > 0 && lastMask != null && !isHealing,
                    onClick = { refineMode = !refineMode },
                    label = { Text("Refine", fontSize = 12.sp) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = !isHealing,
                    onClick = {
                        refineMode = false
                        healReq = HealRequest.Subject
                        healGen += 1
                    },
                ) {
                    Icon(
                        Icons.Rounded.WandStars,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 2.dp),
                    )
                    Text("Remove subject", fontSize = 12.sp)
                }
                TextButton(
                    enabled = !isHealing,
                    onClick = {
                        refineMode = false
                        healReq = HealRequest.People
                        healGen += 1
                    },
                ) {
                    Icon(
                        Icons.Rounded.Person,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 2.dp),
                    )
                    Text("Remove people", fontSize = 12.sp)
                }
            }

            // ── Radius slider ─────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Size", fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                Slider(
                    value = radiusPx,
                    onValueChange = {
                        radiusPx = it
                        sizePreviewGen += 1
                    },
                    valueRange = 8f..200f,
                    modifier = Modifier.weight(1f),
                )
                Text("${radiusPx.toInt()}", fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }

            Column {
                Text(
                    text = when {
                        refineMode -> "Refine: paint to extend the last fill — it re-runs as one region."
                        healCount == 0 -> "Tap on the photo to heal a spot."
                        else -> "$healCount heal${if (healCount == 1) "" else "s"} in this session."
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (healStatus.isNotEmpty()) {
                    Text(
                        text = "✓ $healStatus",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

        }
    }
}

/** What the heal executor should fill. */
private sealed interface HealRequest {
    /** A committed brush stroke, in CANVAS pixel space. */
    data class Stroke(val points: List<Offset>) : HealRequest

    /** One-tap remove-people — mask built by DeepLab human parsing. */
    data object People : HealRequest

    /** One-tap remove-subject — BiRefNet / U2Net general saliency matte. */
    data object Subject : HealRequest
}

/** Round-capped stroke → strict-binary white-on-black hole mask (source px). */
private fun strokeMask(
    w: Int,
    h: Int,
    pts: List<Pair<Float, Float>>,
    radius: Float,
): Bitmap {
    val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(maskBmp).apply {
        drawColor(android.graphics.Color.BLACK)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            isAntiAlias = false
        }
        if (pts.size == 1) {
            paint.style = android.graphics.Paint.Style.FILL
            drawCircle(pts[0].first, pts[0].second, radius, paint)
        } else {
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 2f * radius
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            paint.strokeJoin = android.graphics.Paint.Join.ROUND
            val path = android.graphics.Path().apply {
                moveTo(pts[0].first, pts[0].second)
                for (i in 1 until pts.size) lineTo(pts[i].first, pts[i].second)
            }
            drawPath(path, paint)
        }
    }
    return maskBmp
}

/**
 * Union of two white-on-black masks into a NEW mutable bitmap (max blend, so
 * white from either survives). Inputs are not recycled — callers own them.
 */
private fun unionMasks(a: Bitmap, b: Bitmap): Bitmap {
    val out = if (a.isMutable) a else a.copy(Bitmap.Config.ARGB_8888, true)
    val paint = android.graphics.Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.LIGHTEN)
    }
    android.graphics.Canvas(out).drawBitmap(
        b, null, android.graphics.Rect(0, 0, out.width, out.height), paint,
    )
    if (out !== a) runCatching { a.recycle() }
    return out
}
