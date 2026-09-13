/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Drop-in replacement for android.util.Log that respects [AppLogger.isEnabled].
 *
 * Usage — replace:
 *   import android.util.Log
 *   Log.d(TAG, "message")
 * with:
 *   import com.RAZStudio.StudioRoom.core.utils.AppLog
 *   AppLog.d(TAG, "message")
 *
 * All calls are no-ops when [AppLogger.isEnabled] is false (release builds or
 * when the in-app Debug Mode toggle is off). ProGuard/R8 constant-folds the
 * [BuildConfig.DEBUG] guard in [AppLogger] to `false` on release, eliminating
 * the call sites entirely from the DEX.
 */

package com.RAZStudio.StudioRoom.core.utils

import android.util.Log

object AppLog {
    @JvmStatic fun v(tag: String, msg: String)                    { if (AppLogger.isEnabled) Log.v(tag, msg) }
    @JvmStatic fun v(tag: String, msg: String, tr: Throwable)     { if (AppLogger.isEnabled) Log.v(tag, msg, tr) }
    @JvmStatic fun d(tag: String, msg: String)                    { if (AppLogger.isEnabled) Log.d(tag, msg) }
    @JvmStatic fun d(tag: String, msg: String, tr: Throwable)     { if (AppLogger.isEnabled) Log.d(tag, msg, tr) }
    @JvmStatic fun i(tag: String, msg: String)                    { if (AppLogger.isEnabled) Log.i(tag, msg) }
    @JvmStatic fun i(tag: String, msg: String, tr: Throwable)     { if (AppLogger.isEnabled) Log.i(tag, msg, tr) }
    @JvmStatic fun w(tag: String, msg: String)                    { if (AppLogger.isEnabled) Log.w(tag, msg) }
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable)     { if (AppLogger.isEnabled) Log.w(tag, msg, tr) }
    @JvmStatic fun e(tag: String, msg: String)                    { if (AppLogger.isEnabled) Log.e(tag, msg) }
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable)     { if (AppLogger.isEnabled) Log.e(tag, msg, tr) }
    @JvmStatic fun wtf(tag: String, msg: String)                  { if (AppLogger.isEnabled) Log.wtf(tag, msg) }
    @JvmStatic fun wtf(tag: String, msg: String, tr: Throwable)   { if (AppLogger.isEnabled) Log.wtf(tag, msg, tr) }
}
