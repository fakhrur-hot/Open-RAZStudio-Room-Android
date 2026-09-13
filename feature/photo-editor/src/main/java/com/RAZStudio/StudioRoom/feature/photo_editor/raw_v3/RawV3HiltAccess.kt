/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  Hilt accessor for v3 coordinator dependencies.
 *
 *  v3's [RawV3Coordinator] needs the project's Hilt-bound [FileController]
 *  and [ImageCompressor], but its callers (v2 [RawBatchProcessor], the
 *  debug smoke surface) aren't Hilt-injected themselves. This @EntryPoint
 *  bridges the gap — anyone with a [Context] can resolve both singletons
 *  from the application graph.
 *
 *  Same pattern the v3 debug entry point in :app already uses; this copy
 *  lives in :feature:photo-editor so the production batch processor can
 *  reach it without dragging in the app module.
 *
 *  At M12 (production cutover) this can collapse into the regular Hilt
 *  graph once [RawV3Coordinator] gets Hilt-injected directly.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.graphics.Bitmap
import com.RAZStudio.StudioRoom.core.domain.image.ImageCompressor
import com.RAZStudio.StudioRoom.core.domain.saving.FileController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RawV3HiltAccess {
    fun fileController(): FileController
    fun imageCompressor(): ImageCompressor<Bitmap>

    companion object {
        fun resolve(context: Context): RawV3HiltAccess =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                RawV3HiltAccess::class.java,
            )
    }
}
