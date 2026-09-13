/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Serializes OrtSession / TFLite Interpreter run vs close.
 *
 * Crash class (2026-09-07/08 logcat):
 *   • thread `3.CityscapesSeg` SIGABRT — FORTIFY pthread_mutex_lock on a
 *     destroyed mutex inside libonnxruntime / OrtSession.run
 *   • thread `raw-seg-u2net` SIGSEGV NPE in RawSegmentationProcessor.runU2Net
 *
 * Root cause: closeSession() / withTimeout finally / unloadAfterPass called
 * session.close() while a blocking native run() was still in flight on a
 * dedicated MIN_PRIORITY dispatcher. Cancellation does not interrupt
 * OrtSession.run — only the Kotlin coroutine is cancelled.
 *
 * Protocol:
 *   1. [run] increments in-flight; refuses to enter after [release]
 *   2. [release] marks closed and closes immediately only when idle; otherwise
 *      the last in-flight [run] performs the close
 *   3. Exceptions from a closed session are soft-failures (null), never crash
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class OrtSessionGate(private val tag: String) {

    private val lock = Any()
    private val released = AtomicBoolean(false)
    private val inFlight = AtomicInteger(0)
    @Volatile private var closeAction: (() -> Unit)? = null
    @Volatile private var didClose = false

    val isReleased: Boolean get() = released.get()

    /**
     * Run [block] against a live session. Returns null when already released,
     * when release races mid-entry, or when [block] throws after release /
     * with a closed-session signature.
     */
    fun <R> run(block: () -> R): R? {
        if (released.get()) return null
        inFlight.incrementAndGet()
        try {
            if (released.get()) return null
            return try {
                block()
            } catch (t: Throwable) {
                if (released.get() || isClosedSessionFailure(t)) {
                    Log.w(tag, "infer soft-fail after close/cancel: ${t.message}")
                    null
                } else {
                    throw t
                }
            }
        } finally {
            if (inFlight.decrementAndGet() == 0) maybeClose()
        }
    }

    /**
     * Request teardown. Safe from any thread, including while [run] is inside
     * a blocking native infer — close is deferred until in-flight hits zero.
     */
    fun release(close: () -> Unit) {
        synchronized(lock) {
            if (closeAction == null) closeAction = close
            released.set(true)
        }
        maybeClose()
    }

    private fun maybeClose() {
        if (!released.get()) return
        if (inFlight.get() != 0) return
        synchronized(lock) {
            if (didClose) return
            if (inFlight.get() != 0) return
            didClose = true
            val action = closeAction
            closeAction = null
            runCatching { action?.invoke() }
                .onFailure { Log.w(tag, "session close failed: ${it.message}") }
        }
    }

    companion object {
        /** Heuristic for ORT / TFLite "already closed" failures. */
        fun isClosedSessionFailure(t: Throwable): Boolean {
            val msg = t.message.orEmpty()
            if (t is IllegalStateException) return true
            if (t is NullPointerException) return true
            return msg.contains("closed", ignoreCase = true) ||
                msg.contains("has been closed", ignoreCase = true) ||
                msg.contains("SessionObject must be valid", ignoreCase = true)
        }
    }
}
