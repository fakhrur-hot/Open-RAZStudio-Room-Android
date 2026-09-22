package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.lut_creator

import android.content.Context
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ShaderParams
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Open edition ABI stub. Export-edit-as-LUT is private. */
object RawV3LutBake {
    suspend fun bakeEditToLut(
        context: Context,
        stageATifPath: String,
        fullW: Int,
        fullH: Int,
        currentParams: ShaderParams,
        lutCubeFile: File?,
        name: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): String? = null
}
