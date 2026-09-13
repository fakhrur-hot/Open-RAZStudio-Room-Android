/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Performance-hint helpers for the RAW export pipeline.
 *
 *  Single save  → PerformanceHintManager session targeted at the export
 *                 thread + parallelFor worker threads. The framework biases
 *                 the kernel CPU governor toward higher clocks for the
 *                 declared work duration. Best for short bursts (3-15 s).
 *
 *  Batch save   → setSustainedPerformanceMode(true) + screen-keep-on +
 *                 wake lock. Sustained mode caps the *peak* clock so the
 *                 device doesn't thermally throttle 30 s into the run —
 *                 trades short-term speed for long-term consistency. Pair
 *                 with the screen flag so the user can leave the app
 *                 visible while the queue drains without dimming.
 *
 *  Both helpers are no-ops on older OS versions or when the API isn't
 *  available. They MUST be released in a `finally` block — a leaked hint
 *  session keeps the device in boost state and drains battery, and a
 *  leaked wake lock is even worse.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.util.Log
import android.view.Window
import android.view.WindowManager

private const val TAG = "RawV3.PerfHints"

/**
 * Scoped performance boost for a single RAW export.
 *
 * Run [block] with a PerformanceHintManager session active. The session
 * targets [expectedDurationNs] (default 5 s) — the framework uses this as
 * a deadline hint, so undershoot is better than overshoot.
 *
 * Falls through unmodified on API < 33 or when the service is unavailable.
 * The session is closed in a finally so a thrown exception still releases
 * the boost.
 */
suspend fun <T> withSingleExportBoost(
    context: Context,
    expectedDurationNs: Long = 5_000_000_000L,
    block: suspend () -> T,
): T {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return block()
    val mgr = context.getSystemService(PerformanceHintManager::class.java) ?: return block()
    val session = runCatching {
        // Hint is keyed off the current thread; once kernel marks it
        // latency-sensitive, the scheduler scales sibling worker threads
        // (the native parallelFor pool) via normal load tracking.
        mgr.createHintSession(intArrayOf(android.os.Process.myTid()), expectedDurationNs)
    }.getOrNull()
    if (session == null) {
        Log.i(TAG, "withSingleExportBoost: PerformanceHintManager unavailable, running unboosted")
        return block()
    }
    return try {
        Log.i(TAG, "withSingleExportBoost: hint session active (target=${expectedDurationNs / 1_000_000} ms)")
        block()
    } finally {
        runCatching { session.close() }
        Log.i(TAG, "withSingleExportBoost: hint session released")
    }
}

/**
 * Scoped sustained-performance + wake-lock + keep-screen-on for a batch run.
 *
 * Call from the UI thread before kicking off the batch coroutine, then
 * invoke [BatchPerfScope.release] in a `finally` once the batch completes
 * (success, failure, or cancellation).
 */
class BatchPerfScope private constructor(
    private val window: Window?,
    private val wakeLock: PowerManager.WakeLock?,
    private val sustainedActive: Boolean,
) {
    fun release() {
        val w = window
        if (sustainedActive && w != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        ) {
            runCatching { w.setSustainedPerformanceMode(false) }
        }
        if (w != null) {
            runCatching { w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        val lock = wakeLock
        if (lock != null && lock.isHeld) {
            runCatching { lock.release() }
        }
        Log.i(TAG, "BatchPerfScope: released")
    }

    companion object {
        /**
         * @param activity  Optional — if null, only the wake lock is acquired
         *                  (sustained mode and screen-on require a Window).
         * @param tag       Wake-lock tag suffix for logs.
         */
        fun acquire(context: Context, activity: Activity?, tag: String = "RawBatch"): BatchPerfScope {
            val window = activity?.window
            var sustained = false
            if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching {
                    window.setSustainedPerformanceMode(true)
                    sustained = true
                }
            }
            if (window != null) {
                runCatching {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val lock = pm?.let {
                runCatching {
                    @Suppress("WakelockTimeout")  // explicit release in finally
                    it.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                   "RAZStudio:$tag").apply { acquire() }
                }.getOrNull()
            }
            Log.i(TAG, "BatchPerfScope: acquired (sustained=$sustained keepScreen=${window != null} wakeLock=${lock != null})")
            return BatchPerfScope(window, lock, sustained)
        }
    }
}
