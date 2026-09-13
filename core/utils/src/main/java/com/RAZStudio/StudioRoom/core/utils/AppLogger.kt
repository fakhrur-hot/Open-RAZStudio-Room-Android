/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Single runtime gate that controls whether ANY log output leaves the app.
 *
 * ── Debug builds ────────────────────────────────────────────────────────────
 *  [isEnabled] starts as `BuildConfig.DEBUG` (true). The in-app Debug Mode
 *  toggle in Settings drives [isEnabled] at runtime — turning it off silences
 *  ALL logcat output immediately without a restart.
 *
 * ── Release builds ──────────────────────────────────────────────────────────
 *  [BuildConfig.DEBUG] is `false` at compile time. ProGuard/R8 sees
 *  `if (false)` everywhere Logger.makeLog is called and strips the entire
 *  branch body, so ZERO log strings are ever constructed or emitted.
 *  [isEnabled] is also initialised to `false` so even if R8 somehow
 *  misses an edge case, the runtime check kills it.
 *
 * ── Usage ───────────────────────────────────────────────────────────────────
 *  • Logger.makeLog (and all makeLog extensions) check [isEnabled] before
 *    any string construction or RealLog call.
 *  • Call [AppLogger.setEnabled] from the settings observer in
 *    StudioRoomApplication whenever isDebugMode changes.
 *  • Never call this from feature modules — only the app module and Logger
 *    should touch it.
 */

package com.RAZStudio.StudioRoom.core.utils

import com.RAZStudio.StudioRoom.core.resources.BuildConfig

object AppLogger {

    /**
     * Runtime logging gate.
     *
     * - Debug build default: `true` (verbose from first launch, matches dev expectation)
     * - Release build default: `false` (ProGuard constant-folds all `if (isEnabled)` checks)
     *
     * `@Volatile` ensures visibility across threads without synchronisation cost —
     * the toggle path is rare and the read path (every log call) must be as cheap
     * as a memory load.
     */
    @Volatile
    @JvmField
    var isEnabled: Boolean = BuildConfig.DEBUG

    /**
     * Called from the settings observer whenever the Debug Mode toggle changes.
     * No-op on release builds because the pref key is never surfaced in the UI.
     */
    fun setEnabled(enabled: Boolean) {
        // On release BuildConfig.DEBUG is false — force off regardless of what
        // the caller passes so a stale pref can never re-enable logging.
        isEnabled = if (BuildConfig.DEBUG) enabled else false
    }
}
