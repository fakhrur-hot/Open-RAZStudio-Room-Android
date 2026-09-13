/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.RAZStudio.StudioRoom.core.domain.pip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped flag that any long-running pipeline can flip ON so the
 * host Activity will auto-enter Picture-in-Picture mode on
 * `onUserLeaveHint`. Currently fed by Canon Sync's Download & Process
 * coordinator; future surfaces (RAW Editor batch, manifest pull) can
 * write to it without touching the activity glue.
 *
 * Also carries optional user-facing status strings so the PiP window
 * can render `current filename • N/total` directly from this flow
 * without holding a back-reference to the producer.
 */
@Singleton
class PipStateHolder @Inject constructor() {

    data class Snapshot(
        val active: Boolean,
        val title: String,
        val subtitle: String,
        /** 0..1 progress, or null when indeterminate. */
        val progress: Float?,
    ) {
        companion object {
            val Idle = Snapshot(active = false, title = "", subtitle = "", progress = null)
        }
    }

    private val _state = MutableStateFlow(Snapshot.Idle)
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun set(snapshot: Snapshot) { _state.value = snapshot }
    fun setActive(active: Boolean) {
        _state.value = _state.value.copy(active = active)
    }
    fun clear() { _state.value = Snapshot.Idle }
}
