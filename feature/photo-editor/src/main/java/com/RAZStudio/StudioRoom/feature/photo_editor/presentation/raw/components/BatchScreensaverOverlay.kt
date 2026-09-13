/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full-screen AMOLED-burn-in protection overlay for batch processing.
 *
 * Background: a slowly-rotating two-layer rainbow drift so no pixel keeps the
 * same colour (burn-in prevention).
 *
 * Foreground: the app logo + a "N/total" progress badge that ricochets around
 * the screen DVD-bounce style — slow, constant velocity, reflecting off each
 * edge. The motion doubles as burn-in protection (the brightest element never
 * rests) and gives the user an at-a-glance batch progress without a static UI.
 *
 * Tapping anywhere fires [onDismiss]. While mounted it dims the window and
 * holds FLAG_KEEP_SCREEN_ON so the device can't sleep mid-batch.
 */
@Composable
fun BatchScreensaverOverlay(
    windowBrightness: Float,
    currentIndex: Int,
    totalCount: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val density = LocalDensity.current

    // Per-window brightness override + immersive (hide status/nav bars).
    // Both revert on dispose so the normal UI returns when the overlay closes.
    DisposableEffect(windowBrightness) {
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness ?: -1f
        window?.attributes = window?.attributes?.apply {
            screenBrightness = windowBrightness.coerceIn(0.01f, 0.5f)
            flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
        window?.let { it.attributes = it.attributes }  // re-apply
        // Hide the Android status + navigation bars while the screensaver is
        // up. Swipe-from-edge transiently reveals them (BEHAVIOR_SHOW_…SWIPE).
        val insetsController = window?.let { w ->
            WindowInsetsControllerCompat(w, w.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = originalBrightness
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                w.attributes = lp
            }
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // App launcher icon → bitmap, resolved at runtime so we don't need a
    // cross-module R-class import. Null on any failure → text-only badge.
    val logoBitmap = remember {
        runCatching {
            val pm = context.packageManager
            val icon: Drawable = pm.getApplicationIcon(context.packageName)
            (icon as? BitmapDrawable)?.bitmap
                ?: icon.toBitmap(192, 192, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    // Rotating rainbow drift background (burn-in prevention).
    val transition = rememberInfiniteTransition(label = "screensaver")
    val hue by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "hue",
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "drift",
    )
    val color = Color.hsv(hue % 360f, 0.85f, 1f)
    val color2 = Color.hsv((hue + 120f) % 360f, 0.85f, 1f)

    // ── Ricochet (DVD-bounce) position for the logo+progress badge ──────────
    // Two independent triangle waves (one per axis) at slightly different
    // periods make the badge trace a slow, non-repeating bounce path that
    // reflects off every edge. `phase01` ramps 0→1→0… via RepeatMode.Reverse,
    // which is exactly a wall-to-wall bounce on that axis.
    val bounceX by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 11_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bounceX",
    )
    val bounceY by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8_300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bounceY",
    )

    var boxW by remember { mutableStateOf(0) }
    var boxH by remember { mutableStateOf(0) }
    // Badge footprint in px (logo 96dp + text). Used to keep it fully on-screen.
    val badgePx = with(density) { 132.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxW = it.width; boxH = it.height }
            .background(color)
            .graphicsLayer { rotationZ = drift }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            },
    ) {
        // Second drift layer (opposite rotation) for burn-in coverage.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = -drift * 0.6f
                    alpha = 0.5f
                }
                .background(color2),
        )

        // Bouncing logo + progress badge. Counter-rotates the background drift
        // so it stays upright and readable while everything behind it spins.
        val travelX = (boxW - badgePx).coerceAtLeast(0f)
        val travelY = (boxH - badgePx).coerceAtLeast(0f)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .graphicsLayer {
                    translationX = bounceX * travelX
                    translationY = bounceY * travelY
                    rotationZ = -drift   // cancel the parent's rotation
                },
        ) {
            if (logoBitmap != null) {
                Image(
                    bitmap = logoBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                )
            }
            Text(
                text = "$currentIndex/$totalCount",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
