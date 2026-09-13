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

package com.RAZStudio.StudioRoom.core.ui.utils.helper

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Performance-mode coordinator: signals the OS that the app is doing a long,
 * latency-sensitive workload so it should boost CPU/GPU governors and avoid
 * aggressive thermal throttling.
 *
 * Three layers, applied as available:
 *
 *  1. [Activity.getWindow].setSustainedPerformanceMode(true) — API 24+. The most
 *     reliable hint; designed for exactly this use case (long sustained work
 *     where we'd rather hold a lower peak frequency without thermal throttling
 *     than spike-and-crash). No permission required.
 *
 *  2. PARTIAL_WAKE_LOCK — keeps the CPU clocked even if the screen dims/off so
 *     a long export doesn't pause when the user looks away. Requires WAKE_LOCK
 *     permission (declared in the manifest).
 *
 *  3. GameManager.setGameState(isLoading=false, mode=MODE_CONTENT) — API 33+,
 *     opt-in to the OS game-mode performance scheduling. Reaches the same CPU
 *     governors that game devs use without declaring the app as a game. Safe
 *     no-op on devices that don't expose game mode.
 *
 * Refcounted: concurrent acquires (e.g. user starts batch, save fires from the
 * same screen) stack, and the OS hints only release when the last token closes.
 *
 * Usage:
 * ```
 * val token = perfMode.acquire(activity, reason = "raw-export")
 * try { /* heavy work */ } finally { token.release() }
 * ```
 *
 * Or via [withPerformanceMode] (recommended) which handles release in `finally`.
 */
class PerformanceModeManager(
    private val appContext: Context,
) {

    private val refCount = AtomicInteger(0)

    // Single wake lock; refcount guards acquire/release. Activity references are
    // weak because the activity may be destroyed while a token is still held by a
    // background coroutine — in that case we just skip the window-level hint and
    // keep the wake lock alive.
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var activeActivity: java.lang.ref.WeakReference<Activity>? = null
    @Volatile private var gameModeActive: Boolean = false

    /** A handle to release one perf-mode acquisition. Idempotent. */
    interface Token {
        fun release()
    }

    /**
     * Request performance mode. [activity], when provided, lets us set the
     * window-level sustained-performance hint. Reason is for telemetry/logging.
     * The returned token MUST be released — prefer [withPerformanceMode].
     */
    fun acquire(activity: Activity?, reason: String): Token {
        val newCount = refCount.incrementAndGet()
        Log.d(TAG, "acquire($reason) refCount=$newCount")
        if (newCount == 1) {
            startPerformanceMode(activity)
        } else if (activity != null && activeActivity?.get() !== activity) {
            // A new activity took over while perf mode was already active — apply
            // the sustained hint to it so the window-level boost follows the UI.
            applySustainedPerformance(activity, true)
            activeActivity = java.lang.ref.WeakReference(activity)
        }
        var released = false
        return object : Token {
            override fun release() {
                if (released) return
                released = true
                val n = refCount.decrementAndGet()
                Log.d(TAG, "release($reason) refCount=$n")
                if (n == 0) stopPerformanceMode()
                if (n < 0) {
                    Log.w(TAG, "release() underflow — token released twice or out of order")
                    refCount.set(0)
                }
            }
        }
    }

    private fun startPerformanceMode(activity: Activity?) {
        // 1. Sustained-performance window flag (API 24+)
        if (activity != null) {
            applySustainedPerformance(activity, true)
            activeActivity = java.lang.ref.WeakReference(activity)
        }

        // 2. CPU wake lock
        runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val lock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RAZStudio:PerfMode")
                ?.apply { setReferenceCounted(false) }
            lock?.acquire(10 * 60 * 1000L /* 10 min max */)
            wakeLock = lock
        }.onFailure { Log.w(TAG, "wake lock acquire failed: ${it.message}") }

        // 3. GameManager game-mode hint (API 33+).
        // Extracted to a separate method so the GameManager / GameState classes
        // aren't referenced from any method that runs on pre-33 devices — avoids
        // VerifyError on platforms that don't ship those classes.
        if (Build.VERSION.SDK_INT >= 33) {
            gameModeActive = applyGameModeHint(active = true)
        }
    }

    private fun stopPerformanceMode() {
        activeActivity?.get()?.let { applySustainedPerformance(it, false) }
        activeActivity = null

        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure { Log.w(TAG, "wake lock release failed: ${it.message}") }
        wakeLock = null

        if (Build.VERSION.SDK_INT >= 33 && gameModeActive) {
            applyGameModeHint(active = false)
            gameModeActive = false
        }
    }

    @RequiresApi(33)
    @SuppressLint("WrongConstant")
    private fun applyGameModeHint(active: Boolean): Boolean = runCatching {
        val gm = appContext.getSystemService(Context.GAME_SERVICE) as? android.app.GameManager
            ?: return@runCatching false
        val state = android.app.GameState(
            /* isLoading = */ false,
            /* mode      = */ if (active) android.app.GameState.MODE_CONTENT
                              else android.app.GameState.MODE_NONE,
        )
        gm.setGameState(state)
        active
    }.onFailure { Log.w(TAG, "GameManager hint(active=$active) failed: ${it.message}") }
        .getOrDefault(false)

    private fun applySustainedPerformance(activity: Activity, enable: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        // setSustainedPerformanceMode must be called on the UI thread because it
        // touches Window.
        activity.runOnUiThread {
            runCatching { activity.window?.setSustainedPerformanceMode(enable) }
                .onFailure { Log.w(TAG, "setSustainedPerformanceMode($enable) failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "RAZ.PerfMode"

        @Volatile private var INSTANCE: PerformanceModeManager? = null

        /** Process-wide instance. Wires once from any caller. */
        fun get(context: Context): PerformanceModeManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PerformanceModeManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}

/**
 * Run [block] with performance mode active. Always releases on exit, including
 * on exception. Use this from any suspend block — the perf mode is released
 * before the suspend frame returns, so background work that continues after
 * `block` returns won't keep the mode active.
 */
suspend inline fun <T> withPerformanceMode(
    context: Context,
    activity: Activity?,
    reason: String,
    block: () -> T,
): T {
    val token = PerformanceModeManager.get(context).acquire(activity, reason)
    return try {
        block()
    } finally {
        token.release()
    }
}

/**
 * Convenience: attach perf-mode lifecycle to a [ComponentActivity] for the
 * duration of a running flag (e.g. a `var isProcessing: Boolean` driving the UI).
 * Acquires when the flag flips to true, releases when it flips to false. Safe
 * across configuration changes — the token is held by the [LifecycleOwner] and
 * released on its `onDestroy`.
 */
fun ComponentActivity.bindPerformanceMode(
    owner: LifecycleOwner,
    reason: String,
    isActiveProvider: () -> Boolean,
): () -> Unit {
    val mgr = PerformanceModeManager.get(this)
    var token: PerformanceModeManager.Token? = null
    val sync = { active: Boolean ->
        if (active && token == null) {
            token = mgr.acquire(this, reason)
        } else if (!active && token != null) {
            token?.release(); token = null
        }
    }
    owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            token?.release(); token = null
        }
    })
    return { sync(isActiveProvider()) }
}
