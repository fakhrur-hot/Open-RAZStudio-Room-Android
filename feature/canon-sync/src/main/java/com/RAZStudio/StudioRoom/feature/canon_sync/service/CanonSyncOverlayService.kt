/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.feature.canon_sync.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.RAZStudio.StudioRoom.core.domain.pip.PipStateHolder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Floating "Display over other apps" progress bubble for the Canon
 * Sync Download & Process pipeline. The user must grant
 * SYSTEM_ALERT_WINDOW via [Settings.canDrawOverlays] before this
 * service can show anything — we surface the prompt explicitly from
 * the batch screen.
 *
 * The bubble is a simple two-line text chip pinned to the top-right
 * of the screen, listening to [PipStateHolder.state]. It auto-stops
 * itself when state.active flips to false.
 */
@AndroidEntryPoint
class CanonSyncOverlayService : Service() {

    @Inject
    lateinit var pipState: PipStateHolder

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var bubble: View? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) {
            bubble = buildBubbleView()
            attachToWindow(bubble!!)
        }
        if (watcher == null) {
            watcher = scope.launch {
                pipState.state.collectLatest { snap ->
                    val title = bubble?.findViewById<TextView>(TITLE_ID)
                    val subtitle = bubble?.findViewById<TextView>(SUBTITLE_ID)
                    title?.text = snap.title.ifBlank { "Canon Sync" }
                    subtitle?.text = snap.subtitle.ifBlank { "Idle" }
                    if (!snap.active) {
                        // Pipeline finished — tear down so we don't
                        // linger as a dead bubble.
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        bubble?.let { v ->
            runCatching {
                getSystemService(WindowManager::class.java)?.removeView(v)
            }
        }
        bubble = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildBubbleView(): View {
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(220, 33, 33, 33))
            }
        }
        val title = TextView(ctx).apply {
            id = TITLE_ID
            text = "Canon Sync"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val subtitle = TextView(ctx).apply {
            id = SUBTITLE_ID
            text = "Starting…"
            setTextColor(Color.argb(220, 230, 230, 230))
            textSize = 11f
        }
        container.addView(title)
        container.addView(subtitle)
        return container
    }

    private fun attachToWindow(view: View) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(48)
        }
        runCatching {
            getSystemService(WindowManager::class.java)?.addView(view, params)
        }.onFailure {
            Log.w(TAG, "addView failed; SAW likely revoked", it)
            stopSelf()
        }
    }

    private fun Context.dp(value: Int): Int =
        (resources.displayMetrics.density * value).toInt()

    companion object {
        private const val TAG = "CanonSyncOverlay"
        private const val TITLE_ID = 0x10_00_01
        private const val SUBTITLE_ID = 0x10_00_02

        /** Convenience for "do we have the runtime grant right now?" */
        fun canShow(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(context)

        fun start(context: Context) {
            val intent = Intent(context, CanonSyncOverlayService::class.java)
            ContextCompat.startForegroundService(context, intent).also {
                // We intentionally start as a normal service, not FGS:
                // the bubble lifecycle is bounded by the pipeline lifecycle,
                // which is itself an FGS, so the overlay can ride that
                // session without claiming its own foreground slot.
            }
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CanonSyncOverlayService::class.java)
            runCatching { context.stopService(intent) }
        }
    }
}
