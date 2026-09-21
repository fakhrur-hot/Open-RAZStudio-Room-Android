package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.AiDenoiseState
import java.security.MessageDigest

/** Cache identity for derived denoise output; canonical Stage A remains untouched. */
data class AiDenoiseCacheKey(
    val sourceImageHash: String,
    val state: AiDenoiseState,
    val inputSpaceVersion: Int = 1,
) {
    fun stableId(): String {
        val canonical = buildString {
            append(sourceImageHash).append('|')
            append(state.enabled).append('|')
            append(state.strength).append('|')
            append(state.luminance).append('|')
            append(state.color).append('|')
            append(state.detail).append('|')
            append(inputSpaceVersion)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
