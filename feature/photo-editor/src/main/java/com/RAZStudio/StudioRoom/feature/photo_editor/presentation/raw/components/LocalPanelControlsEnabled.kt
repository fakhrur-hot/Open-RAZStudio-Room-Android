package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.runtime.compositionLocalOf

/**
 * When false, all slider controls inside the adjustment panel are disabled
 * and touch input is intercepted. Set to false while a graded-AHB bake is
 * in progress so the user cannot push conflicting slider changes mid-bake.
 */
internal val LocalPanelControlsEnabled = compositionLocalOf { true }
