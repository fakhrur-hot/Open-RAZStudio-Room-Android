package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import java.io.File

/** Open edition ABI stub: no LUT path is resolved or applied. */
object RawV3LutChainResolver {
    data class Result(val file: File?, val intensity: Float, val isChained: Boolean)
    fun resolveTopmost(
        context: Context,
        actions: List<RawAction>,
        cacheDir: File = File(context.cacheDir, "raw_v3_editor_luts"),
    ) = Result(null, 1f, false)
    fun resolveChain(
        context: Context,
        actions: List<RawAction>,
        cacheDir: File = File(context.cacheDir, "raw_v3_editor_luts"),
    ) = Result(null, 1f, false)
    fun resolveLutPath(context: Context, uri: String, cacheDir: File): String? = null
}