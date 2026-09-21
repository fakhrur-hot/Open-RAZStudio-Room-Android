package com.RAZStudio.StudioRoom.feature.photo_editor.raw.model

/** Export-level AI denoise controls. Kept outside ShaderParams until a model is approved. */
data class AiDenoiseState(
    val enabled: Boolean = false,
    val strength: Float = 25f,
    val luminance: Float = 0f,
    val color: Float = 0f,
    val detail: Float = 0f,
) {
    init {
        require(strength in 0f..100f) { "strength must be in 0..100" }
        require(luminance in 0f..100f) { "luminance must be in 0..100" }
        require(color in 0f..100f) { "color must be in 0..100" }
        require(detail in 0f..100f) { "detail must be in 0..100" }
    }

    companion object {
        val Default = AiDenoiseState()
    }
}

data class AiDenoiseEditSession(
    val committed: AiDenoiseState = AiDenoiseState.Default,
    val draft: AiDenoiseState = committed,
) {
    val isApplied: Boolean
        get() = committed.enabled

    fun update(next: AiDenoiseState): AiDenoiseEditSession = copy(draft = next)
    fun apply(): AiDenoiseEditSession = copy(committed = draft)
    fun cancel(): AiDenoiseEditSession = copy(draft = committed)
    fun reset(): AiDenoiseEditSession = copy(draft = AiDenoiseState.Default)
}
