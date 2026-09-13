/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * GradingPipelineShim — no-op compatibility shim that keeps callers in
 * RawEditorComponent and RawEditorContent compiling.
 *
 * HISTORY: this was scaffolding for an abandoned "Unified Stage B Renderer"
 * migration (RendererCore + FrameScheduler, tasks 5.x/8.x). That half-built
 * renderer was DELETED 2026-08-27 — it was never instantiated, and a race audit
 * found it inherited none of the live renderer's thread-safety fixes
 * (unsynchronized params, cross-thread GL init, JNI mask uploads with no length
 * guards, a no-op commitProcessedBuffer). The real GPU preview path is
 * RawV3GlSurfaceView + GlesRenderer; do not resurrect the deleted classes.
 *
 * All methods here are intentional no-ops. Do not wire any logic in.
 * This file can be deleted outright once the ~4 call sites in
 * RawEditorContent (requestBake / suppressBake / resumeBake) are removed.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.graphics.Bitmap
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro

enum class SuppressReason {
    MASK_TAB,
    CPU_ACTION,
    EXPORT,
}

class GradingPipelineShim {

    fun requestBake(
        params: ShaderParams,
        macro: UserMacro,
        stageATifPath: String,
        lutCubePath: String?,
        brushMaskLayers: List<Bitmap?>,
        toneCurveLut: ByteArray?,
        knownSrcWidth: Int,
        knownSrcHeight: Int,
    ) = Unit

    fun suppressBake(reason: SuppressReason) = Unit

    fun resumeBake(
        params: ShaderParams,
        macro: UserMacro,
        stageATifPath: String,
        lutCubePath: String?,
        brushMaskLayers: List<Bitmap?>,
        toneCurveLut: ByteArray?,
        knownSrcWidth: Int,
        knownSrcHeight: Int,
    ) = Unit

    fun dropToUngraded() = Unit
}
