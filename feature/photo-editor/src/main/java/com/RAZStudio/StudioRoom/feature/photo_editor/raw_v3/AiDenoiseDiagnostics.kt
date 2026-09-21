package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

internal data class AiDenoiseDiagnostics(
    val modelId: String,
    val modelVersion: String,
    val provider: String,
    val tileWidth: Int,
    val tileHeight: Int,
    val coreWidth: Int,
    val coreHeight: Int,
    val tileCount: Int,
    val elapsedMs: Long,
    val freeMemoryBeforeBytes: Long,
    val freeMemoryAfterBytes: Long,
    val fallbackUsed: Boolean,
    val error: String? = null,
)
