/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Post-Stage-C FX bitmap pass — intentionally a no-op.
 *
 * History: this used to re-apply mist / vintage / glow / dust / blur in
 * Kotlin after Stage C so export could match a GL-only preview. Those
 * effects are now in apply_macro.cpp (mirroring shader_sources.cpp), so
 * running this pass again double-applied them. Filmic Pro-Mist Glow was
 * the worst case: Stage C did highlight-gated screen + Karis bloom, then
 * this pass piled on the old additive Gaussian glow → MAD ~9 and a
 * flatter / less cinematic save vs the single-pass canvas preview.
 *
 * Do not reintroduce bitmap FX without first removing the matching Stage C
 * block (hard rule #1: preview = export).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.graphics.Bitmap

/**
 * Identity. Call sites keep this hook so a future GL-only effect can land
 * here without touching the export coordinator again — but only for effects
 * that Stage C does **not** already run.
 */
@Suppress("UNUSED_PARAMETER")
fun applyFxBitmapPass(bitmap: Bitmap, params: ShaderParams): Bitmap = bitmap
