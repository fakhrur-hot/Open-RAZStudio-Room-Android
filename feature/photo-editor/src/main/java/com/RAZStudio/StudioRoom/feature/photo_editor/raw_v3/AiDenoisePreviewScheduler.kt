package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import com.RAZStudio.StudioRoom.core.utils.AppLog
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.AiDenoiseState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

internal class AiDenoisePreviewScheduler<T>(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 500L,
) {
    private val generation = AtomicLong(0L)
    private var job: Job? = null
    private val _state = MutableStateFlow<AiDenoisePreviewState<T>>(AiDenoisePreviewState.Idle)
    val state: StateFlow<AiDenoisePreviewState<T>> = _state.asStateFlow()

    fun submit(
        params: AiDenoiseState,
        render: suspend (AiDenoiseState) -> T,
    ) {
        val request = generation.incrementAndGet()
        AppLog.i(TAG, "AI denoise preview submit: enabled=${params.enabled} strength=${params.strength} request=$request debounceMs=$debounceMs")
        job?.cancel()
        job = scope.launch {
            delay(debounceMs)
            _state.value = AiDenoisePreviewState.Rendering(params)
            runCatching { render(params) }
                .onSuccess { result ->
                    if (generation.get() == request) {
                        _state.value = AiDenoisePreviewState.Ready(params, result)
                        AppLog.i(TAG, "AI denoise preview ready: enabled=${params.enabled} strength=${params.strength} request=$request")
                    }
                }
                .onFailure { error ->
                    if (generation.get() == request) {
                        _state.value = AiDenoisePreviewState.Failed(params, error)
                        AppLog.w(TAG, "AI denoise preview failed: enabled=${params.enabled} strength=${params.strength} request=$request error=${error.message}")
                    }
                }
        }
    }

    fun cancel() {
        generation.incrementAndGet()
        job?.cancel()
        job = null
        _state.value = AiDenoisePreviewState.Idle
        AppLog.i(TAG, "AI denoise preview cancelled")
    }

    private companion object {
        const val TAG = "AiDenoise"
    }
}

internal sealed interface AiDenoisePreviewState<out T> {
    data object Idle : AiDenoisePreviewState<Nothing>
    data class Rendering(val params: AiDenoiseState) : AiDenoisePreviewState<Nothing>
    data class Ready<T>(val params: AiDenoiseState, val value: T) : AiDenoisePreviewState<T>
    data class Failed(val params: AiDenoiseState, val error: Throwable) : AiDenoisePreviewState<Nothing>
}
