/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.hardware.HardwareBuffer
import android.util.Log

/**
 * Kotlin wrapper for the native AhbMaskLoader. Manages zero-copy import
 * of NPU-generated AHardwareBuffers into GLES textures using EGLImageKHR.
 * Persists imported textures in a cache keyed by buffer identity to avoid
 * redundant EGLImage creation.
 */
class AhbMaskLoader : AutoCloseable {
    private var nativePtr: Long = RawV3Engine.createAhbMaskLoader()

    /**
     * Import [buffer] into a GL texture on the current thread's EGL context.
     * Returns the GL texture ID (unit 0..N), or 0 on failure.
     *
     * @param buffer      AHardwareBuffer to import (must have GPU_SAMPLED_IMAGE usage).
     * @param eglDisplay  Native EGLDisplay handle (from eglGetCurrentDisplay()).
     * @param generation  Monotonically increasing counter for the buffer's content.
     *                    Used to invalidate the cache if the SAME buffer instance
     *                    is updated with new NPU results.
     */
    fun importAhbMask(buffer: HardwareBuffer, eglDisplay: Long, generation: Long): Int {
        val ptr = nativePtr
        if (ptr == 0L) return 0
        return RawV3Engine.importAhbMask(ptr, buffer, eglDisplay, generation)
    }

    override fun close() {
        if (nativePtr != 0L) {
            RawV3Engine.destroyAhbMaskLoader(nativePtr)
            nativePtr = 0L
        }
    }

    protected fun finalize() {
        if (nativePtr != 0L) {
            Log.w("AhbMaskLoader", "AhbMaskLoader leaked! close() was not called.")
            close()
        }
    }
}
